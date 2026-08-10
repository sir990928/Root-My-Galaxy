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

enum class InstallPhase { Checking, Ready, Downloading, Exploiting, LoadingKernelSu, Installed, Failed }

data class InstallUiState(
    val phase: InstallPhase = InstallPhase.Checking,
    val message: String = "", val probeOutput: String = "", val log: String = ""
) { val busy: Boolean get() = phase in setOf(InstallPhase.Checking, InstallPhase.Downloading, InstallPhase.Exploiting, InstallPhase.LoadingKernelSu) }

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
                mutableState.value = InstallUiState(phase = InstallPhase.Installed, message = "Active", probeOutput = probe, log = probe)
                return@launch
            }
            try {
                mutableState.value = InstallUiState(phase = InstallPhase.Ready, message = "Ready", probeOutput = probe, log = probe)
            } catch (error: Throwable) {
                mutableState.value = InstallUiState(phase = InstallPhase.Failed, message = "Failed", probeOutput = probe, log = "$probe\n${error.message}")
            }
        }
    }

    fun loadTargetCatalog() {
        if (mutableTargetCatalog.value.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            mutableTargetCatalog.value = TargetCatalogUiState(loading = true)
            mutableTargetCatalog.value = try {
                TargetCatalogUiState(profiles = repository.loadTargets().sortedBy { it.displayName })
            } catch (error: Throwable) {
                TargetCatalogUiState(error = error.message)
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
                setPhase(InstallPhase.Downloading, "Downloading...")
                val mgr = AppPreferences.rootManager(app)
                val profile = if (profileId == null) repository.resolveTarget(DeviceSnapshot.current()) else repository.resolveTarget(profileId)
                val payloads = repository.download(profile) { appendLog("[*] $it") }

                setPhase(InstallPhase.Exploiting, "Running exploit...")
                executeExploit(payloads.exploit)

                setPhase(InstallPhase.LoadingKernelSu, "Loading $mgr...")
                installKernelSu(payloads, mgr)

                setPhase(InstallPhase.Installed, "$mgr active")
                finishHistory(InstallRunResult.Succeeded)
            } catch (error: Throwable) {
                appendLog("[-] ${error.message}")
                setPhase(InstallPhase.Failed, "Failed")
                finishHistory(InstallRunResult.Failed)
            }
        }
    }

    private suspend fun executeExploit(payload: File) {
        val logFile = File(app.filesDir, "exploit.log"); logFile.delete()
        val helper = helperFile()
        val process = ProcessBuilder(helper.absolutePath, "--run-payload", payload.absolutePath, helper.absolutePath, logFile.absolutePath).redirectErrorStream(true).start()
        val start = SystemClock.elapsedRealtime()
        while (process.isAlive) { delay(500); require(SystemClock.elapsedRealtime() - start < 300_000) { "Timeout" } }
        require(process.waitFor() == 0) { "Exploit failed" }
    }

    private fun installKernelSu(payloads: VerifiedPayloads, mgr: String) {
        val isSukisu = mgr == "SukiSU-Ultra"
        val src = shellQuote(payloads.kernelSu.absolutePath)
        runHelper("-c", "cp $src /data/local/tmp/ksud && chmod 755 /data/local/tmp/ksud")
        runHelper("-c", "ln -sf /data/local/tmp/ksud /data/local/tmp/ksud-selected && mount --bind /data/local/tmp/ksud-selected /system/bin/logcat")
        if (isSukisu) { File(payloads.kernelSu.parentFile, "kernelsu.ko").let { if (it.exists()) runHelper("-c", "cat ${shellQuote(it.absolutePath)} > /dev/sukisu.ko && logcat insmod /dev/sukisu.ko") } }
        runHelper("-c", "logcat late-load" + if (isSukisu) "" else " --ephemeral")
        storeInstallReceipt()
        appendLog("[+] $mgr installed!")
    }

    private fun detectInstalled() = NativeProbe.isKernelSuActive()
    private fun storeInstallReceipt() {}
    private fun helperFile() = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
    private fun runHelper(vararg a: String) = ProcessBuilder(listOf(helperFile().absolutePath) + a).redirectErrorStream(true).start().let { CommandResult(it.waitFor(), it.inputStream.bufferedReader().readText()) }
    private fun shellQuote(v: String) = "'${v.replace("'", "'\\''")}'"
    private fun setPhase(phase: InstallPhase, msg: String) { mutableState.value = mutableState.value.copy(phase = phase, message = msg) }
    private fun appendLog(line: String) { val c = line.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "").trim(); if (c.isNotBlank()) { mutableState.value = mutableState.value.copy(log = (mutableState.value.log + "\n" + c).trim()) } }
    private fun startHistory() { val e = historyStore.create(); activeHistoryEntry = e; publishHistory(e) }
    private fun finishHistory(r: InstallRunResult) { activeHistoryEntry?.let { val u = it.copy(completedAtMillis = System.currentTimeMillis(), result = r); activeHistoryEntry = null; historyStore.save(u); publishHistory(u) } }
    private fun publishHistory(e: InstallHistoryEntry) { mutableHistory.value = (mutableHistory.value.filterNot { it.id == e.id } + e).sortedByDescending { it.startedAtMillis } }
}
