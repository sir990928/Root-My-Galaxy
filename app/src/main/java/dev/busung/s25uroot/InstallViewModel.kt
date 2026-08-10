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
data class InstallUiState(val phase: InstallPhase = InstallPhase.Checking, val message: String = "", val probeOutput: String = "", val log: String = "") {
    val busy: Boolean get() = phase in setOf(InstallPhase.Checking, InstallPhase.Downloading, InstallPhase.Exploiting, InstallPhase.LoadingKernelSu)
}
data class TargetCatalogUiState(val loading: Boolean = false, val profiles: List<TargetProfile> = emptyList(), val error: String? = null)

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

    fun deleteHistoryEntries(ids: Collection<String>) {
        ids.filterNot { it == activeHistoryEntry?.id }.forEach(historyStore::delete)
        mutableHistory.value = mutableHistory.value.filterNot { it.id in ids }
    }

    fun loadTargetCatalog() {
        if (mutableTargetCatalog.value.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            mutableTargetCatalog.value = TargetCatalogUiState(loading = true)
            mutableTargetCatalog.value = try {
                TargetCatalogUiState(profiles = repository.loadTargets().sortedWith(compareBy(TargetProfile::displayName, TargetProfile::profileId)))
            } catch (error: Throwable) {
                TargetCatalogUiState(error = error.message)
            }
        }
    }

    fun install() {
        if (installJob?.isActive == true || mutableState.value.phase == InstallPhase.Installed) return
        discoveryJob?.cancel()
        installJob = viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = InstallUiState(phase = InstallPhase.Checking, probeOutput = mutableState.value.probeOutput)
            startHistory()
            try {
                val mgr = AppPreferences.rootManager(app)
                appendLog("[*] Selected: $mgr")
                val isSukisu = mgr == "SukiSU-Ultra"
                val managerKey = if (isSukisu) "sukisu" else "kernelsu"

                setPhase(InstallPhase.Downloading, "Downloading...")
                val exploit = repository.downloadExploit { appendLog("[*] $it") }
                val mf = repository.downloadManager(managerKey) { appendLog("[*] $it") }

                setPhase(InstallPhase.Exploiting, "Running exploit...")
                executeExploit(exploit)

                setPhase(InstallPhase.LoadingKernelSu, "Loading $mgr...")
                if (isSukisu) installSukisu(mf.ksud.absolutePath, mf.ko!!.absolutePath)
                else installKernelSu(mf.ksud.absolutePath)

                setPhase(InstallPhase.Installed, "$mgr active")
                finishHistory(InstallRunResult.Succeeded)
            } catch (error: Throwable) {
                appendLog("[-] ${error.message}")
                setPhase(InstallPhase.Failed, "Failed")
                finishHistory(InstallRunResult.Failed)
            }
        }
    }

    private suspend fun executeExploit(exploit: File) {
        appendLog("[*] Running exploit...")
        val p = ProcessBuilder("sh", "-c", "cp ${exploit.absolutePath} /data/local/tmp/cve-2026-43499-app.so && chmod 755 /data/local/tmp/cve-2026-43499-root && cd /data/local/tmp && EXPLOIT_ATTEMPTS=24 LD_PRELOAD=/data/local/tmp/cve-2026-43499-app.so /system/bin/true").redirectErrorStream(true).start()
        val start = SystemClock.elapsedRealtime()
        while (p.isAlive) { delay(500); require(SystemClock.elapsedRealtime() - start < 300_000) { "Timeout" } }
        require(p.waitFor() == 0) { "Exploit failed" }
        appendLog("[+] Root obtained!")
    }

    private fun runRoot(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }

    // KernelSU: 只需 late-load
    private fun installKernelSu(ksudPath: String) {
        appendLog("[*] Installing KernelSU...")
        runRoot("cp $ksudPath /data/local/tmp/ksud && chmod 755 /data/local/tmp/ksud")
        runRoot("ln -sf /data/local/tmp/ksud /data/local/tmp/ksud-selected && mount --bind /data/local/tmp/ksud-selected /system/bin/logcat")
        runRoot("logcat late-load --ephemeral")
        storeInstallReceipt()
        appendLog("[+] KernelSU installed!")
    }

    // SuKiSU: 需要加载 .ko
    private fun installSukisu(ksudPath: String, koPath: String) {
        appendLog("[*] Installing SuKiSU...")
        runRoot("cp $ksudPath /data/local/tmp/ksud && chmod 755 /data/local/tmp/ksud")
        runRoot("ln -sf /data/local/tmp/ksud /data/local/tmp/ksud-selected && mount --bind /data/local/tmp/ksud-selected /system/bin/logcat")
        appendLog("[*] Loading kernel module...")
        runRoot("cat $koPath > /dev/sukisu.ko && logcat insmod /dev/sukisu.ko")
        runRoot("logcat late-load --ephemeral")
        storeInstallReceipt()
        appendLog("[+] SuKiSU installed!")
    }

    private fun detectInstalled() = NativeProbe.isKernelSuActive()
    private fun storeInstallReceipt() {}
    private fun setPhase(phase: InstallPhase, message: String) { mutableState.value = mutableState.value.copy(phase = phase, message = message); appendLog("[*] $message") }
    private fun appendLog(line: String) {
        val clean = line.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "").replace("\r", "").trim()
        if (clean.isBlank()) return
        mutableState.value = mutableState.value.copy(log = (mutableState.value.log + "\n" + clean).trim())
    }
    private fun startHistory() { val e = historyStore.create(); activeHistoryEntry = e; publishHistory(e) }
    private fun updateHistory(t: (InstallHistoryEntry) -> InstallHistoryEntry) { activeHistoryEntry?.let { val u = t(it); activeHistoryEntry = u; historyStore.save(u); publishHistory(u) } }
    private fun updateHistoryLog() = updateHistory { it.copy(log = mutableState.value.log) }
    private fun finishHistory(r: InstallRunResult) { updateHistory { it.copy(completedAtMillis = System.currentTimeMillis(), result = r, log = mutableState.value.log) }; activeHistoryEntry = null }
    private fun publishHistory(e: InstallHistoryEntry) { mutableHistory.value = (mutableHistory.value.filterNot { it.id == e.id } + e).sortedByDescending { it.startedAtMillis } }
}
