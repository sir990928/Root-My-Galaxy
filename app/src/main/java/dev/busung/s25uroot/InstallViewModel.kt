package dev.busung.s25uroot

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

enum class InstallPhase {
    Checking, Ready, Downloading, Exploiting, LoadingKernelSu, Installed, Failed,
}

data class InstallUiState(
    val phase: InstallPhase = InstallPhase.Checking,
    val message: String = "",
    val probeOutput: String = "",
    val log: String = "",
) {
    val busy: Boolean get() = phase in setOf(InstallPhase.Checking, InstallPhase.Downloading, InstallPhase.Exploiting, InstallPhase.LoadingKernelSu)
}

data class TargetCatalogUiState(
    val loading: Boolean = false,
    val profiles: List<TargetProfile> = emptyList(),
    val error: String? = null,
)

private data class CommandResult(val code: Int, val output: String)

class InstallViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repository = PayloadRepository(application)
    private val historyStore = InstallHistoryStore(application)
    private val mutableState = MutableStateFlow(InstallUiState())
    private val mutableHistory = MutableStateFlow(historyStore.closeInterruptedRuns())
    private val mutableTargetCatalog = MutableStateFlow(TargetCatalogUiState())
    private var discoveryJob: Job? = null
    private var installJob: Job? = null
    private var activeHistoryEntry: InstallHistoryEntry? = null
    val state: StateFlow<InstallUiState> = mutableState.asStateFlow()
    val history: StateFlow<List<InstallHistoryEntry>> = mutableHistory.asStateFlow()
    val targetCatalog: StateFlow<TargetCatalogUiState> = mutableTargetCatalog.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (installJob?.isActive == true) return
        mutableHistory.value = historyStore.load()
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            val probe = NativeProbe.run()
            if (detectInstalled()) {
                mutableState.value = InstallUiState(phase = InstallPhase.Installed, message = app.getString(R.string.status_ksu_active), probeOutput = probe, log = probe)
                return@launch
            }
            try {
                val profile = repository.resolveTarget(DeviceSnapshot.current())
                mutableState.value = InstallUiState(phase = InstallPhase.Ready, message = app.getString(R.string.status_not_installed), probeOutput = probe, log = "$probe\n${app.getString(R.string.log_profile, profile.profileId)}")
            } catch (error: Throwable) {
                mutableState.value = InstallUiState(phase = InstallPhase.Failed, message = app.getString(R.string.status_support_failed), probeOutput = probe, log = "$probe\n[-] ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun loadTargetCatalog() {
        if (mutableTargetCatalog.value.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            mutableTargetCatalog.value = TargetCatalogUiState(loading = true)
            mutableTargetCatalog.value = try {
                TargetCatalogUiState(profiles = repository.loadTargets().sortedWith(compareBy(TargetProfile::kernelRelease, TargetProfile::kernelBuildVersion, TargetProfile::model, TargetProfile::buildDisplay)))
            } catch (error: Throwable) {
                TargetCatalogUiState(error = error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun install(profileId: String? = null) {
        if (installJob?.isActive == true || mutableState.value.phase == InstallPhase.Installed) return
        discoveryJob?.cancel()
        installJob = viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = InstallUiState(phase = InstallPhase.Checking, probeOutput = mutableState.value.probeOutput)
            startHistory()
            try {
                setPhase(InstallPhase.Checking, app.getString(R.string.status_checking_github))
                val profile = if (profileId == null) repository.resolveTarget(DeviceSnapshot.current()) else repository.resolveTarget(profileId)
                appendLog(app.getString(R.string.log_profile, profile.profileId))

                setPhase(InstallPhase.Downloading, app.getString(R.string.status_downloading_payload))
                val payloads = repository.download(profile) { appendLog("[*] $it") }
                appendLog(app.getString(R.string.log_download_verified))

                setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit_running))
                executeExploit(payloads.exploit)

                setPhase(InstallPhase.LoadingKernelSu, app.getString(R.string.status_ksu_loading))
                installKernelSu(payloads)

                setPhase(InstallPhase.Installed, app.getString(R.string.status_ksu_active))
                appendLog(app.getString(R.string.log_install_complete))
                finishHistory(InstallRunResult.Succeeded)
            } catch (error: Throwable) {
                appendLog("[-] ${error.message ?: error.javaClass.simpleName}")
                setPhase(InstallPhase.Failed, app.getString(R.string.status_install_failed))
                finishHistory(InstallRunResult.Failed)
            }
        }
    }

    private suspend fun executeExploit(payload: File) {
        val logFile = File(app.filesDir, "exploit.log")
        logFile.delete()
        val helper = helperFile()
        require(helper.canExecute()) { app.getString(R.string.error_helper_unavailable) }
        val logPrefix = mutableState.value.log
        val bootToken = currentBootToken()
        val processBuilder = ProcessBuilder(helper.absolutePath, "--run-payload", payload.absolutePath, helper.absolutePath, logFile.absolutePath).redirectErrorStream(true)
        cachedP0Offset(bootToken)?.let { processBuilder.environment()[P0_OFFSET_ENV] = it }
        val process = processBuilder.start()
        try {
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastRawLog = ""
            while (process.isAlive) {
                val rawLog = logFile.readTextIfPresent()
                if (rawLog != lastRawLog) {
                    cacheP0Offset(bootToken, rawLog)
                    publishExploitLog(logPrefix, rawLog)
                    lastRawLog = rawLog
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                require(SystemClock.elapsedRealtime() - lastProgressAt < EXPLOIT_STALL_MILLIS) { app.getString(R.string.error_exploit_stalled) }
                require(SystemClock.elapsedRealtime() - startedAt < EXPLOIT_TOTAL_MILLIS) { app.getString(R.string.error_exploit_timeout) }
                delay(LOG_POLL_INTERVAL)
            }
            val exitCode = process.waitFor()
            val rawLog = logFile.readTextIfPresent()
            cacheP0Offset(bootToken, rawLog)
            publishExploitLog(logPrefix, rawLog)
            require(exitCode == 0) { app.getString(R.string.error_payload_exit, exitCode, "") }
            require(rawLog.contains("exploit completed") && rawLog.contains("done=1 root=1")) { app.getString(R.string.error_success_marker) }
        } finally {
            if (process.isAlive) { process.destroy(); delay(500.milliseconds); if (process.isAlive) process.destroyForcibly() }
        }
        appendLog(app.getString(R.string.log_bootstrap_root))
    }

    private fun publishExploitLog(prefix: String, rawLog: String) {
        mutableState.value = mutableState.value.copy(log = listOf(prefix, stripAnsi(rawLog)).filter(String::isNotBlank).joinToString("\n"))
        updateHistoryLog()
    }

    private fun installKernelSu(payloads: VerifiedPayloads) {
        val mgr = AppPreferences.rootManager(app)
        val isSukisu = mgr == "SukiSU-Ultra"
        val source = shellQuote(payloads.kernelSu.absolutePath)

        // Stage ksud
        val stageCmd = "/system/bin/cp $source /data/local/tmp/ksud && /system/bin/chmod 755 /data/local/tmp/ksud"
        val stage = runHelper("-c", stageCmd)
        require(stage.code == 0) { app.getString(R.string.error_ksu_stage, stage.output) }
        appendLog("[*] ksud staged")

        // Link and mount
        runHelper("-c", "ln -sf /data/local/tmp/ksud /data/local/tmp/ksud-selected && mount --bind /data/local/tmp/ksud-selected /system/bin/logcat")

        // Load KO only for SuKiSU
        if (isSukisu) {
            val koFile = File(payloads.kernelSu.parentFile, "kernelsu.ko")
            if (koFile.exists()) {
                appendLog("[*] Loading kernel module...")
                runHelper("-c", "cat ${shellQuote(koFile.absolutePath)} > /dev/sukisu.ko && logcat insmod /dev/sukisu.ko")
                appendLog("[*] KO loaded")
            }
        }

        // Late load
        val lateArg = if (isSukisu) "late-load" else "late-load --ephemeral"
        val lateLoad = runHelper("-c", "logcat $lateArg")
        require(lateLoad.code == 0) { "Late load failed: ${lateLoad.output}" }
        if (lateLoad.output.isNotBlank()) appendLog(lateLoad.output)
        storeInstallReceipt()
        appendLog("[+] $mgr installed!")
    }

    private fun detectInstalled(): Boolean {
        if (NativeProbe.isKernelSuActive()) return true
        val bootToken = currentBootToken() ?: return false
        val receipt = app.getSharedPreferences(INSTALL_RECEIPT, Application.MODE_PRIVATE)
        return receipt.getString(RECEIPT_BOOT_TOKEN, null) == bootToken && receipt.getBoolean(RECEIPT_VERIFIED, false)
    }

    private fun storeInstallReceipt() {
        val bootToken = currentBootToken() ?: error(app.getString(R.string.error_boot_id))
        app.getSharedPreferences(INSTALL_RECEIPT, Application.MODE_PRIVATE).edit().putString(RECEIPT_BOOT_TOKEN, bootToken).putBoolean(RECEIPT_VERIFIED, true).apply()
    }

    private fun currentBootToken() = runCatching { File("/proc/sys/kernel/random/boot_id").readText(Charsets.US_ASCII).trim().takeIf(String::isNotBlank) }.getOrNull()
    private fun cachedP0Offset(bootToken: String?): String? {
        if (bootToken == null) return null
        val stored = app.getSharedPreferences(P0_CACHE, Application.MODE_PRIVATE)
        return if (stored.getString(P0_CACHE_BOOT_TOKEN, null) != bootToken) null else stored.getString(P0_CACHE_OFFSET, null)
    }
    private fun cacheP0Offset(bootToken: String?, log: String) {
        if (bootToken == null) return
        val match = P0_OFFSET_PATTERN.findAll(log).lastOrNull() ?: return
        val offset = match.groupValues[1].toLongOrNull(16) ?: return
        if (offset !in 0..P0_OFFSET_MAX || offset and P0_OFFSET_MASK != 0L) return
        app.getSharedPreferences(P0_CACHE, Application.MODE_PRIVATE).edit().putString(P0_CACHE_BOOT_TOKEN, bootToken).putString(P0_CACHE_OFFSET, "0x${offset.toString(16)}").apply()
    }
    private fun helperFile() = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
    private fun runHelper(vararg arguments: String): CommandResult {
        val process = ProcessBuilder(listOf(helperFile().absolutePath) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return CommandResult(process.waitFor(), stripAnsi(output.trim()))
    }
    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"
    private fun setPhase(phase: InstallPhase, message: String) { mutableState.value = mutableState.value.copy(phase = phase, message = message); appendLog("[*] $message") }
    private fun appendLog(line: String) { val cleanLine = stripAnsi(line).trim(); if (cleanLine.isNotBlank()) { mutableState.value = mutableState.value.copy(log = (mutableState.value.log + "\n" + cleanLine).trim()); updateHistoryLog() } }
    private fun startHistory() { val entry = historyStore.create(); activeHistoryEntry = entry; publishHistory(entry) }
    private fun updateHistoryLog() { activeHistoryEntry?.let { val u = it.copy(log = mutableState.value.log); activeHistoryEntry = u; historyStore.save(u); publishHistory(u) } }
    private fun finishHistory(result: InstallRunResult) { activeHistoryEntry?.let { val u = it.copy(completedAtMillis = System.currentTimeMillis(), result = result, log = mutableState.value.log); activeHistoryEntry = null; historyStore.save(u); publishHistory(u) } }
    private fun publishHistory(entry: InstallHistoryEntry) { mutableHistory.value = (mutableHistory.value.filterNot { it.id == entry.id } + entry).sortedByDescending(InstallHistoryEntry::startedAtMillis) }
    private fun File.readTextIfPresent(): String = if (exists()) readText() else ""

    companion object {
        private const val EXPLOIT_STALL_MILLIS = 90_000L
        private const val EXPLOIT_TOTAL_MILLIS = 900_000L
        private const val INSTALL_RECEIPT = "install_receipt"
        private const val RECEIPT_BOOT_TOKEN = "kernel_boot_id"
        private const val RECEIPT_VERIFIED = "verified"
        private const val P0_CACHE = "p0_cache"
        private const val P0_CACHE_BOOT_TOKEN = "kernel_boot_id"
        private const val P0_CACHE_OFFSET = "offset"
        private const val P0_OFFSET_ENV = "SLIDE_P0_OFFSET"
        private const val P0_OFFSET_MAX = 0x1f0000L
        private const val P0_OFFSET_MASK = 0xffffL
        private val LOG_POLL_INTERVAL = 250.milliseconds
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val P0_OFFSET_PATTERN = Regex("slide-kaslr-ok[^\\n]*slide=([0-9a-fA-F]{16})")
        private fun stripAnsi(value: String): String = ANSI_ESCAPE.replace(value, "").replace("\r", "")
    }
}
