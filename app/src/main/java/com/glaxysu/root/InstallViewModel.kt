package com.glaxysu.root

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

enum class InstallPhase { Checking, Ready, Downloading, Exploiting, LoadingKernelSu, Installed, Failed }
data class InstallUiState(val phase: InstallPhase = InstallPhase.Checking, val message: String = "", val probeOutput: String = "", val log: String = "") {
    val busy: Boolean get() = phase in setOf(InstallPhase.Checking, InstallPhase.Downloading, InstallPhase.Exploiting, InstallPhase.LoadingKernelSu)
}
data class TargetCatalogUiState(val loading: Boolean = false, val profiles: List<TargetProfile> = emptyList(), val error: String? = null)
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
                val manager = AppPreferences.rootManager(app)
                mutableState.value = InstallUiState(phase = InstallPhase.Installed, message = app.getString(R.string.status_root_active_format, app.getString(manager.labelRes)), probeOutput = probe, log = probe)
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
                TargetCatalogUiState(profiles = repository.loadTargets().sortedWith(compareBy(TargetProfile::displayName, TargetProfile::profileId)))
            } catch (error: Throwable) { TargetCatalogUiState(error = error.message ?: error.javaClass.simpleName) }
        }
    }

    fun install(profileId: String? = null) {
        if (installJob?.isActive == true || mutableState.value.phase == InstallPhase.Installed) return
        discoveryJob?.cancel()
        installJob = viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = InstallUiState(phase = InstallPhase.Checking, probeOutput = mutableState.value.probeOutput)
            startHistory()
            try {
                val useWireless = try {
                   WirelessAdbManager.ensureConnected(app)
                   appendLog("[*] Wireless connected")
                   true
               } catch (e: Exception) {
                  appendLog("[*] Wireless failed: ${e.message}")
                   false
                }
                setPhase(InstallPhase.Checking, app.getString(R.string.status_checking_github))
                val profile = if (profileId == null) repository.resolveTarget(DeviceSnapshot.current()) else repository.resolveTarget(profileId)
                appendLog(app.getString(R.string.log_profile, profile.profileId))
                updateHistoryProfile(profile.profileId)

                setPhase(InstallPhase.Downloading, app.getString(R.string.status_downloading_payload))
                val payloads = repository.download(profile) { appendLog("[*] $it") }
                appendLog(app.getString(R.string.log_download_verified))
                
                if (useWireless) stageBootstrapHelper()

                setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit_running))
                executeExploit(payloads.exploit, useWireless)

                setPhase(InstallPhase.LoadingKernelSu, app.getString(R.string.status_ksu_loading))
                installKernelSu(payloads, useWireless)

                val manager = AppPreferences.rootManager(app)
                setPhase(InstallPhase.Installed, app.getString(R.string.status_root_active_format, app.getString(manager.labelRes)))
                appendLog(app.getString(R.string.log_install_complete))
                finishHistory(InstallRunResult.Succeeded)
            } catch (error: Throwable) {
                appendLog("[-] ${error.message ?: error.javaClass.simpleName}")
                setPhase(InstallPhase.Failed, app.getString(R.string.status_install_failed))
                finishHistory(InstallRunResult.Failed)
            }
        }
    }

    private fun stageBootstrapHelper() {
        val helper = helperFile()
        require(helper.isFile && helper.canRead()) { app.getString(R.string.error_helper_unavailable) }
        val result = WirelessAdbManager.stageFile(app, helper, WirelessAdbManager.REMOTE_HELPER_PATH)
        require(result.code == 0) { app.getString(R.string.error_wireless_adb_stage, helper.name, result.output) }
        appendLog(app.getString(R.string.log_wireless_adb_helper_staged))
    }

    private suspend fun executeExploit(payload: File, useWireless: Boolean) {
        if (!useWireless) {
            val logFile = File(app.filesDir, "exploit.log"); logFile.delete()
            val helper = helperFile()
            require(helper.canExecute()) { app.getString(R.string.error_helper_unavailable) }
            val logPrefix = mutableState.value.log
            val bootToken = currentBootToken()
            val pb = ProcessBuilder(helper.absolutePath, "--run-payload", payload.absolutePath, helper.absolutePath, logFile.absolutePath).redirectErrorStream(true)
            pb.environment().apply {
                put("EXPLOIT_ATTEMPTS", EXPLOIT_ATTEMPTS)
                put("P0_ATTEMPT_TIMEOUT_SEC", "45")
                put("EXPLOIT_ATTEMPT_TIMEOUT_SEC", "120")
                cachedP0Offset(bootToken)?.let { put(P0_OFFSET_ENV, it) }
            }
            val process = pb.start()
            try {
                val startedAt = SystemClock.elapsedRealtime()
                var lastProgressAt = startedAt
                var lastRawLog = ""
                while (process.isAlive) {
                    val rawLog = logFile.readTextIfPresent()
                    if (rawLog != lastRawLog) { cacheP0Offset(bootToken, rawLog); publishExploitLog(logPrefix, rawLog); lastRawLog = rawLog; lastProgressAt = SystemClock.elapsedRealtime() }
                    require(SystemClock.elapsedRealtime() - lastProgressAt < EXPLOIT_STALL_MILLIS) { app.getString(R.string.error_exploit_stalled) }
                    require(SystemClock.elapsedRealtime() - startedAt < EXPLOIT_TOTAL_MILLIS) { app.getString(R.string.error_exploit_timeout) }
                    delay(LOG_POLL_INTERVAL)
                }
                val exitCode = process.waitFor()
                val rawLog = logFile.readTextIfPresent()
                cacheP0Offset(bootToken, rawLog); publishExploitLog(logPrefix, rawLog)
                require(exitCode == 0) { app.getString(R.string.error_payload_exit, exitCode, "") }
                require(rawLog.contains("exploit completed") && rawLog.contains("done=1 root=1")) { app.getString(R.string.error_success_marker) }
            } finally { if (process.isAlive) { process.destroy(); delay(500); if (process.isAlive) process.destroyForcibly() } }
            appendLog(app.getString(R.string.log_bootstrap_root))
            return
        }
        val remotePayload = "$TMP_PATH/${payload.name}"
        val stagedPayload = stageToTmp(payload, payload.name)
        require(stagedPayload.code == 0) { app.getString(R.string.error_wireless_adb_stage, payload.name, stagedPayload.output) }
        val logPrefix = mutableState.value.log
        val bootToken = currentBootToken()
        val exploitCommand = buildString {
            append("cd ${shellQuote(TMP_PATH)} && ")
            append("EXPLOIT_ATTEMPTS=${shellQuote(EXPLOIT_ATTEMPTS)} ")
            append("PSELECT_DELAY_USEC=1000 ")
            append("P0_ATTEMPT_TIMEOUT_SEC=45 ")
            append("EXPLOIT_ATTEMPT_TIMEOUT_SEC=120 ")
            cachedP0Offset(bootToken)?.let { append(" $P0_OFFSET_ENV=${shellQuote(it)}") }
            append("CVE43499_ROOT_HELPER=${shellQuote(WirelessAdbManager.REMOTE_HELPER_PATH)} ")
            append("LD_PRELOAD=${shellQuote(remotePayload)} ")
            append("/system/bin/true")
        }
        val process = WirelessAdbManager.openProcess(app, exploitCommand)
        val startedAt = SystemClock.elapsedRealtime()
        var lastRawLog = ""
        var exploitSucceeded = false
        try {
            while (process.isAlive()) {
                process.drainAvailable()
                val rawLog = process.cleanOutput()
                if (rawLog != lastRawLog) { cacheP0Offset(bootToken, rawLog); publishExploitLog(logPrefix, rawLog); lastRawLog = rawLog }
                if (rawLog.contains("root=1")) { exploitSucceeded = true; break }
                require(SystemClock.elapsedRealtime() - startedAt < EXPLOIT_TOTAL_MILLIS) { app.getString(R.string.error_exploit_timeout) }
                delay(LOG_POLL_INTERVAL)
            }
            process.drainAvailable()
            val finalLog = process.cleanOutput()
            if (finalLog != lastRawLog) { cacheP0Offset(bootToken, finalLog); publishExploitLog(logPrefix, finalLog) }
            val exitCode = process.exitCode() ?: if (exploitSucceeded) 0 else -1
            require(exitCode == 0) { app.getString(R.string.error_payload_exit, exitCode, "") }
            require(exploitSucceeded || finalLog.contains("root=1")) { app.getString(R.string.error_success_marker) }
        } finally { process.close() }
        appendLog(app.getString(R.string.log_bootstrap_root))
    }

    private fun publishExploitLog(prefix: String, rawLog: String) {
        mutableState.value = mutableState.value.copy(log = listOf(prefix, stripAnsi(rawLog)).filter(String::isNotBlank).joinToString("\n"))
        updateHistoryLog()
    }

    private fun installKernelSu(payloads: VerifiedPayloads, useWireless: Boolean) {
        val manager = AppPreferences.rootManager(app)
        val isSukisu = manager == RootManager.SukiSU
        appendLog("[*] Root implementation: ${manager.storedValue}")
        val ksudName = payloads.kernelSu.name
        val ksudPath = "$TMP_PATH/$ksudName"

        // 推送 ksud 到设备
        if (useWireless) {
            val stagedKsud = stageToTmp(payloads.kernelSu, ksudName)
            require(stagedKsud.code == 0) { app.getString(R.string.error_ksu_stage, stagedKsud.output) }
        } else {
            val result = runLocal("cp ${shellQuote(payloads.kernelSu.absolutePath)} $ksudPath && chmod 755 $ksudPath")
            require(result.code == 0) { "Failed to stage ksud: ${result.output}" }
        }
        appendLog("[+] ksud staged: $ksudPath")

        if (isSukisu) {
            val koFile = payloads.kernelModule ?: error("SukiSU kernel module is unavailable")
            val koName = koFile.name; val koPath = "$TMP_PATH/$koName"
            
            // 推送 ko 到设备
            if (useWireless) {
                val stagedKo = stageToTmp(koFile, koName)
                require(stagedKo.code == 0) { app.getString(R.string.error_ksu_stage, stagedKo.output) }
            } else {
                val result = runLocal("cp ${shellQuote(koFile.absolutePath)} $koPath && chmod 755 $koPath")
                require(result.code == 0) { "Failed to stage ko: ${result.output}" }
            }
            appendLog("[+] SukiSU module staged: $koPath")
            
            // exp 过后统一用本地 runLocal
            // 1. late-load + mount bind
            val lateResult = runLocal(lateLoadCommand(ksudName, false))
            require(lateResult.code == 0) { "late-load failed: ${lateResult.output}" }
            appendLog("[+] late-load completed")
            
            // 验证 mount bind
            val mountCheck = runLocal("mount | grep /system/bin/logcat")
            require(mountCheck.code == 0 && mountCheck.output.contains("/system/bin/logcat")) {
                "Mount bind failed! logcat not replaced. Output: ${mountCheck.output}"
            }
            appendLog("[+] mount bind verified")
            
            // 2. 复制 .ko 到 /dev
            val catResult = runLocal("cat ${shellQuote(koPath)} > /dev/sukisu.ko")
            require(catResult.code == 0) { "Failed to write ko: ${catResult.output}" }
            appendLog("[+] ko written to /dev/sukisu.ko")
            
            // 3. insmod 加载
            val insmodResult = runLocal("logcat insmod /dev/sukisu.ko")
            require(insmodResult.code == 0) { "insmod failed: ${insmodResult.output}" }
            
            // 验证模块加载
            val moduleCheck = runLocal("cat /proc/modules | grep sukisu")
            require(moduleCheck.code == 0 && moduleCheck.output.contains("sukisu")) {
                "Module not loaded in kernel! ${moduleCheck.output}"
            }
            appendLog("[+] SukiSU loaded and verified in kernel")
            
        } else {
            // 普通 KernelSU
            val result = runLocal(lateLoadCommand(ksudName, true))
            require(result.code == 0) { "late-load failed: ${result.output}" }
            
            // 验证 mount bind
            val mountCheck = runLocal("mount | grep /system/bin/logcat")
            require(mountCheck.code == 0 && mountCheck.output.contains("/system/bin/logcat")) {
                "Mount bind failed! Output: ${mountCheck.output}"
            }
            appendLog("[+] KernelSU late-load completed")
        }
        
        storeInstallReceipt()
        appendLog(app.getString(R.string.log_ksu_control_verified))
    }

    private fun stageToTmp(source: File, name: String): CommandResult {
        val result = WirelessAdbManager.stageFile(app, source, "$TMP_PATH/$name")
        return CommandResult(result.code, result.output)
    }

    private fun lateLoadCommand(ksudName: String, ephemeral: Boolean): String {
        val ep = if (ephemeral) " --ephemeral" else ""
        return "ln -sf $TMP_PATH/$ksudName $TMP_PATH/ksud-selected && mount --bind $TMP_PATH/ksud-selected /system/bin/logcat && logcat late-load$ep"
    }
    
    private fun runLocal(cmd: String): CommandResult {
        val helper = helperFile()
        val process = ProcessBuilder(helper.absolutePath, "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        
        if (exitCode != 0) {
            appendLog("[-] Local cmd failed (exit=$exitCode): $cmd")
            if (output.isNotBlank()) appendLog("[-] ${output.take(500)}")
        }
        
        return CommandResult(exitCode, stripAnsi(output.trim()))
    }

    private fun detectInstalled(): Boolean {
        if (NativeProbe.isKernelSuActive()) return true
        val bootToken = currentBootToken() ?: return false
        val receipt = app.getSharedPreferences(INSTALL_RECEIPT, Application.MODE_PRIVATE)
        return receipt.getString(RECEIPT_BOOT_TOKEN, null) == bootToken && receipt.getBoolean(RECEIPT_VERIFIED, false)
    }
    private fun storeInstallReceipt() {
        val bootToken = currentBootToken() ?: error(app.getString(R.string.error_boot_id))
        app.getSharedPreferences(INSTALL_RECEIPT, Application.MODE_PRIVATE).edit().putString(RECEIPT_BOOT_TOKEN, bootToken).putBoolean(RECEIPT_VERIFIED, true).commit()
    }
    private fun currentBootToken(): String? = runCatching { File("/proc/sys/kernel/random/boot_id").readText(Charsets.US_ASCII).trim().takeIf(String::isNotBlank) }.getOrNull()
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
    private fun helperFile(): File = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"
    private fun setPhase(phase: InstallPhase, msg: String) { mutableState.value = mutableState.value.copy(phase = phase, message = msg) }
    private fun appendLog(line: String) { val c = stripAnsi(line).trim(); if (c.isNotBlank()) mutableState.value = mutableState.value.copy(log = (mutableState.value.log + "\n" + c).trim()); updateHistoryLog() }
    private fun startHistory() { val e = historyStore.create(); activeHistoryEntry = e; publishHistory(e) }
    private fun updateHistoryLog() { val e = activeHistoryEntry ?: return; val u = e.copy(log = mutableState.value.log); activeHistoryEntry = u; historyStore.save(u); publishHistory(u) }
    private fun updateHistoryProfile(pid: String) { val e = activeHistoryEntry ?: return; val u = e.copy(profileId = pid); activeHistoryEntry = u; historyStore.save(u); publishHistory(u) }
    private fun finishHistory(r: InstallRunResult) { val e = activeHistoryEntry ?: return; val u = e.copy(completedAtMillis = System.currentTimeMillis(), result = r, log = mutableState.value.log); activeHistoryEntry = null; historyStore.save(u); publishHistory(u) }
    private fun publishHistory(e: InstallHistoryEntry) { mutableHistory.value = (mutableHistory.value.filterNot { it.id == e.id } + e).sortedByDescending { it.startedAtMillis } }
    private fun File.readTextIfPresent(): String = if (exists()) readText() else ""

    companion object {
        private const val EXPLOIT_ATTEMPTS = "24"
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
        private const val TMP_PATH = "/data/local/tmp"
        private val LOG_POLL_INTERVAL = 250.milliseconds
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val P0_OFFSET_PATTERN = Regex("slide-kaslr-ok[^\\n]*slide=([0-9a-fA-F]{16})")
        private fun stripAnsi(value: String): String = ANSI_ESCAPE.replace(value, "").replace("\r", "")
    }
}