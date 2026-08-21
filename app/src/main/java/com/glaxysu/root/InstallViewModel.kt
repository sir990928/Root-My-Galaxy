package com.glaxysu.root

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
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
    private var wirelessExecution = false
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
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Installed,
                    message = app.getString(
                        R.string.status_root_active_format,
                        app.getString(manager.labelRes),
                    ),
                    probeOutput = probe,
                    log = probe,
                )
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
                selectExecutionMode()
                setPhase(InstallPhase.Checking, app.getString(R.string.status_checking_github))
                val profile = if (profileId == null) repository.resolveTarget(DeviceSnapshot.current()) else repository.resolveTarget(profileId)
                appendLog(app.getString(R.string.log_profile, profile.profileId))
                updateHistoryProfile(profile.profileId)

                setPhase(InstallPhase.Downloading, app.getString(R.string.status_downloading_payload))
                val payloads = repository.download(profile) { appendLog("[*] $it") }
                appendLog(app.getString(R.string.log_download_verified))
                stageBootstrapHelper()
                val bootstrapRootReady = checkBootstrapRootOnBothRoutes()

                if (bootstrapRootReady) {
                    appendLog("[+] helper -c id 检测到 uid=0，跳过 exploit")
                } else {
                    setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit_running))
                    executeExploit(payloads.exploit)
                }

                setPhase(InstallPhase.LoadingKernelSu, app.getString(R.string.status_ksu_loading))
                installKernelSu(payloads)

                val manager = AppPreferences.rootManager(app)
                AppPreferences.markInstalled(app, manager)
                setPhase(
                    InstallPhase.Installed,
                    app.getString(
                        R.string.status_root_active_format,
                        app.getString(manager.labelRes),
                    ),
                )
                appendLog(app.getString(R.string.log_install_complete))
                finishHistory(InstallRunResult.Succeeded)
            } catch (error: Throwable) {
                appendLog("[-] ${error.message ?: error.javaClass.simpleName}")
                setPhase(InstallPhase.Failed, app.getString(R.string.status_install_failed))
                finishHistory(InstallRunResult.Failed)
            }
        }
    }

    private suspend fun selectExecutionMode() {
    val wirelessExpected = isWirelessDebuggingEnabled() ||
        AppPreferences.wirelessAdbPaired(app)
    
    if (!wirelessExpected) {
        wirelessExecution = false
        appendLog("[*] 无线开关关闭，强制本地模式")
        return
    }

    try {
        WirelessAdbManager.ensureConnected(app)
        wirelessExecution = true
        appendLog("[*] 无线开关已开，强制无线模式")
    } catch (e: Exception) {
        wirelessExecution = false
        appendLog("[-] 无线连接失败，降级本地: ${e.message}")
    }
}

    private fun isWirelessDebuggingEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)
    }

    private fun stageBootstrapHelper() {
        val helper = helperFile()
        require(helper.isFile && helper.canRead() && (wirelessExecution || helper.canExecute())) {
            app.getString(R.string.error_helper_unavailable)
        }
        if (!wirelessExecution) return
        val result = WirelessAdbManager.stageFile(app, helper, WirelessAdbManager.REMOTE_HELPER_PATH)
        require(result.code == 0) {
            app.getString(R.string.error_wireless_adb_stage, helper.name, result.output)
        }
        appendLog(app.getString(R.string.log_wireless_adb_helper_staged))
    }

    private suspend fun executeExploit(payload: File) {
    if (isWirelessDebuggingEnabled() || AppPreferences.wirelessAdbPaired(app)) {
        try {
            WirelessAdbManager.ensureConnected(app)
            wirelessExecution = true
        } catch (e: Exception) {
            wirelessExecution = false
        }
    } else {
        wirelessExecution = false
    }
    
    if (!wirelessExecution) {
        executeLocalExploit(payload)
        return
    }

    appendLog(app.getString(R.string.log_execution_path_wireless))
        val remotePayload = "$TMP_PATH/${payload.name}"
        val stagedPayload = stageToTmp(payload, payload.name)
        require(stagedPayload.code == 0) {
            app.getString(R.string.error_wireless_adb_stage, payload.name, stagedPayload.output)
        }

        val logPrefix = mutableState.value.log
        val exploitCommand = buildString {
            append("cd ${shellQuote(TMP_PATH)} && ")
            append("EXPLOIT_ATTEMPTS=${shellQuote(EXPLOIT_ATTEMPTS)} ")
            append("LD_PRELOAD=${shellQuote(remotePayload)} ")
            append("/system/bin/true")
        }

        val process = WirelessAdbManager.openProcess(app, exploitCommand)
        val startedAt = SystemClock.elapsedRealtime()
        var lastProgressAt = startedAt
        var lastRawLog = ""
        var exploitSucceeded = false
        try {
            while (process.isAlive()) {
                process.drainAvailable()
                val rawLog = process.cleanOutput()
                if (rawLog != lastRawLog) {
                    publishExploitLog(logPrefix, rawLog)
                    lastRawLog = rawLog
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                if (rawLog.contains("root=1")) {
                    exploitSucceeded = true
                    break
                }

                val now = SystemClock.elapsedRealtime()
                require(now - lastProgressAt < EXPLOIT_STALL_MILLIS) {
                    app.getString(R.string.error_exploit_stalled)
                }
                require(now - startedAt < EXPLOIT_TOTAL_MILLIS) {
                    app.getString(R.string.error_exploit_timeout)
                }
                delay(LOG_POLL_INTERVAL)
            }

            process.drainAvailable()
            val finalLog = process.cleanOutput()
            if (finalLog != lastRawLog) {
                publishExploitLog(logPrefix, finalLog)
            }
            val exitCode = process.exitCode()
            require(exploitSucceeded || hasBootstrapRoot()) {
                app.getString(
                    R.string.error_payload_exit,
                    exitCode ?: -1,
                    " (helper -c id 未返回 uid=0)",
                )
            }
        } finally {
            process.close()
        }
        appendLog(app.getString(R.string.log_bootstrap_root))
    }

    private suspend fun executeLocalExploit(payload: File) {
        appendLog(app.getString(R.string.log_execution_path_local))
        val logFile = File(app.filesDir, "exploit.log")
        logFile.delete()
        val helper = helperFile()
        require(helper.isFile && helper.canExecute()) {
            app.getString(R.string.error_helper_unavailable)
        }

        val process = ProcessBuilder(
            helper.absolutePath,
            "--run-payload",
            payload.absolutePath,
            helper.absolutePath,
            logFile.absolutePath,
        ).redirectErrorStream(true).apply {
            environment().apply {
                put("EXPLOIT_ATTEMPTS", EXPLOIT_ATTEMPTS)
                put("PSELECT_DELAY_USEC", "1000")
                put("P0_ATTEMPT_TIMEOUT_SEC", "45")
                put("EXPLOIT_ATTEMPT_TIMEOUT_SEC", "120")
            }
        }.start()

        val logPrefix = mutableState.value.log
        val startedAt = SystemClock.elapsedRealtime()
        var lastProgressAt = startedAt
        var lastRawLog = ""
        var exploitSucceeded = false
        try {
            while (process.isAlive) {
                val rawLog = logFile.readTextIfPresent()
                if (rawLog != lastRawLog) {
                    publishExploitLog(logPrefix, rawLog)
                    lastRawLog = rawLog
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                if (rawLog.contains("root=1")) {
                    exploitSucceeded = true
                    break
                }

                val now = SystemClock.elapsedRealtime()
                require(now - lastProgressAt < EXPLOIT_STALL_MILLIS) {
                    app.getString(R.string.error_exploit_stalled)
                }
                require(now - startedAt < EXPLOIT_TOTAL_MILLIS) {
                    app.getString(R.string.error_exploit_timeout)
                }
                delay(LOG_POLL_INTERVAL)
            }

            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
            val finalLog = logFile.readTextIfPresent()
            if (finalLog != lastRawLog) {
                publishExploitLog(logPrefix, finalLog)
                lastRawLog = finalLog
            }
            val exitCode = process.waitFor()
            val earlyOutput = readProcessOutput(process).trim()
            require(exploitSucceeded || hasBootstrapRoot()) {
                app.getString(
                    R.string.error_payload_exit,
                    exitCode,
                    earlyOutput.takeIf(String::isNotBlank)?.let { " ($it); helper -c id 未返回 uid=0" }
                        ?: " (helper -c id 未返回 uid=0)",
                )
            }
        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
        }
        appendLog(app.getString(R.string.log_bootstrap_root))
    }

    private fun readProcessOutput(process: Process): String {
        return runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    private fun publishExploitLog(prefix: String, rawLog: String) {
        mutableState.value = mutableState.value.copy(log = listOf(prefix, stripAnsi(rawLog)).filter(String::isNotBlank).joinToString("\n"))
        updateHistoryLog()
    }

    private fun installKernelSu(payloads: VerifiedPayloads) {
        val manager = AppPreferences.rootManager(app)
        val isSukisu = manager == RootManager.SukiSU
        appendLog("[*] Root implementation: ${manager.storedValue}")
        val ksudName = payloads.kernelSu.name
        val ksudPath = "$TMP_PATH/$ksudName"
        val stagedKsud = stageToTmp(payloads.kernelSu, ksudName)
        require(stagedKsud.code == 0) {
            app.getString(R.string.error_ksu_stage, stagedKsud.output)
        }
        appendLog("[+] ksud staged: $ksudPath")

        if (isSukisu) {
            val koFile = payloads.kernelModule
                ?: error("SukiSU kernel module is unavailable")
            val koName = koFile.name
            val koPath = "$TMP_PATH/$koName"
            val stagedKo = stageToTmp(koFile, koName)
            require(stagedKo.code == 0) {
                app.getString(R.string.error_ksu_stage, stagedKo.output)
            }
            appendLog("[+] SukiSU module staged: $koPath")

            val lateLoad = runHelper(
                "-c",
                lateLoadCommand(ksudPath, ephemeral = false),
            )
            require(lateLoad.code == 0) {
                app.getString(R.string.error_ksu_verify, lateLoad.code, lateLoad.output)
            }
            appendLog("[+] SukiSU ksud late-load 已执行")
            if (lateLoad.output.isNotBlank()) appendLog(lateLoad.output)

            val insmodResult = runHelper("-c", "cat ${shellQuote(koPath)} > /dev/sukisu.ko")
            require(insmodResult.code == 0) {
                app.getString(R.string.error_ksu_stage, insmodResult.output)
            }
            if (insmodResult.output.isNotBlank()) {
                appendLog("[*] SukiSU KO 写入输出: ${insmodResult.output}")
            }
            appendLog("[+] SukiSU module copied to /dev/sukisu.ko")

            val loadResult = runHelper("-c", "logcat insmod /dev/sukisu.ko")
            require(loadResult.code == 0) {
                app.getString(R.string.error_ksu_verify, loadResult.code, loadResult.output)
            }
            if (loadResult.output.isNotBlank()) appendLog(loadResult.output)
            appendLog("[+] SukiSU KO insmod 已执行")

            val moduleState = runCatching {
                runHelper(
                    "-c",
                    "if [ -e /sys/module/sukisu ] || " +
                        "[ -e /sys/module/sukisu_ultra ] || " +
                        "[ -e /sys/module/ksu ] || " +
                        "grep -qE '^(sukisu|sukisu_ultra|ksu) ' /proc/modules 2>/dev/null; " +
                        "then echo 'SukiSU module active'; " +
                        "else echo 'SukiSU module state is not visible'; exit 1; fi",
                )
            }.getOrElse { error ->
                if (isStreamClosed(error)) {
                    appendLog("[*] SukiSU insmod 已关闭无线 shell 流，按加载成功处理")
                    null
                } else {
                    throw error
                }
            }
            if (moduleState != null) {
                if (moduleState.output.isNotBlank()) appendLog("[*] ${moduleState.output}")
                require(moduleState.code == 0) {
                    app.getString(R.string.error_ksu_verify, moduleState.code, moduleState.output)
                }
            }
        } else {
            val lateLoad = runHelper(
                "-c",
                lateLoadCommand(ksudPath, ephemeral = true),
            )
            require(lateLoad.code == 0) {
                app.getString(R.string.error_ksu_verify, lateLoad.code, lateLoad.output)
            }
            appendLog("[+] KernelSU ksud late-load 已执行")
            if (lateLoad.output.isNotBlank()) appendLog(lateLoad.output)
        }

        appendLog(app.getString(R.string.log_ksu_control_verified))
    }

    private fun stageToTmp(source: File, name: String): CommandResult {
        val target = "$TMP_PATH/$name"
        if (!wirelessExecution) {
            val mode = if (name.endsWith(".ko")) "644" else "755"
            return runHelper(
                "-c",
                "cat ${shellQuote(source.absolutePath)} > ${shellQuote(target)} && chmod $mode ${shellQuote(target)}",
            )
        }
        val result = WirelessAdbManager.stageFile(app, source, target)
        return CommandResult(result.code, result.output)
    }

    private fun lateLoadCommand(ksudPath: String, ephemeral: Boolean): String {
        val ephemeralArg = if (ephemeral) " --ephemeral" else ""
        val selectedPath = "$TMP_PATH/ksud-selected"
        return "umount /system/bin/logcat 2>/dev/null || true; " +
            "rm -f ${shellQuote(selectedPath)} && " +
            "ln -sf ${shellQuote(ksudPath)} ${shellQuote(selectedPath)} && " +
            "mount --bind ${shellQuote(selectedPath)} /system/bin/logcat && " +
            "logcat late-load$ephemeralArg"
    }

    private fun detectInstalled(): Boolean {
        val manager = AppPreferences.rootManager(app)
        if (AppPreferences.isInstalled(app, manager)) return true
        val bootStartedAtMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (historyStore.latestSuccessfulManagerSinceBoot(bootStartedAtMillis) == manager) {
            AppPreferences.markInstalled(app, manager)
            return true
        }
        if (NativeProbe.isKernelSuActive()) return true

        val preferredWireless = wirelessExecution
        val localDetected = runCatching {
            wirelessExecution = false
            runHelper("-c", KERNEL_SU_STATE_COMMAND)
        }.getOrNull()
        if (localDetected?.code == 0) {
            wirelessExecution = false
            return true
        }

        val wirelessDetected = runCatching {
            if (!WirelessAdbManager.refreshConnection(app)) return@runCatching null
            wirelessExecution = true
            stageBootstrapHelper()
            runHelper("-c", KERNEL_SU_STATE_COMMAND)
        }.getOrNull()
        wirelessExecution = preferredWireless
        return wirelessDetected?.code == 0
    }

    private fun hasBootstrapRoot(): Boolean {
        if (wirelessExecution && !remoteHelperExists()) return false
        val result = runCatching {
            runHelper("-c", "id")
        }.getOrNull() ?: return false
        appendLog(
            "[*] helper -c id: ${
                result.output.ifBlank { "无输出 (rc=${result.code})" }
            }",
        )
        return result.code == 0 && ROOT_ID_PATTERN.containsMatchIn(result.output)
    }

    private fun checkBootstrapRootOnBothRoutes(): Boolean {
        val preferredWireless = wirelessExecution

        val wirelessResult = runCatching {
            wirelessExecution = true
            runHelper("-c", "id")
        }.getOrNull()
        if (wirelessResult != null) {
            appendLog(
                "[*] 无线 Helper -c id: ${
                    wirelessResult.output.ifBlank { "无输出 (rc=${wirelessResult.code})" }
                }",
            )
            if (wirelessResult.code == 0 && ROOT_ID_PATTERN.containsMatchIn(wirelessResult.output)) {
                wirelessExecution = true
                return true
            }
        }

        val localResult = runCatching {
            wirelessExecution = false
            runHelper("-c", "id")
        }.getOrNull()
        if (localResult != null) {
            appendLog(
                "[*] 本地备用 Helper -c id: ${
                    localResult.output.ifBlank { "无输出 (rc=${localResult.code})" }
                }",
            )
            if (localResult.code == 0 && ROOT_ID_PATTERN.containsMatchIn(localResult.output)) {
                wirelessExecution = false
                return true
            }
        }

        wirelessExecution = preferredWireless
        return false
    }

    private fun remoteHelperExists(): Boolean {
        val result = runCatching {
            WirelessAdbManager.runCommand(
                app,
                "test -f ${shellQuote(WirelessAdbManager.REMOTE_HELPER_PATH)}",
            )
        }.getOrNull() ?: return false
        return result.code == 0
    }

    private fun helperFile(): File = nativeHelperFile()
    private fun nativeHelperFile() = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
    private fun runHelper(vararg arguments: String): CommandResult {
    val wirelessExpected = isWirelessDebuggingEnabled() || AppPreferences.wirelessAdbPaired(app)
    
    val actuallyWireless = if (wirelessExpected) {
        try {
            WirelessAdbManager.refreshConnection(app, forceReconnect = false)
        } catch (e: Exception) {
            false
        }
    } else {
        false
    }
    
    wirelessExecution = actuallyWireless
    
    if (!actuallyWireless) {
        val helper = helperFile()
        require(helper.isFile && helper.canExecute()) {
            app.getString(R.string.error_helper_unavailable)
        }
        val process = ProcessBuilder(listOf(helper.absolutePath) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = readProcessOutput(process)
        return CommandResult(process.waitFor(), stripAnsi(output.trim()))
    }

    val command = buildString {
        append(shellQuote(WirelessAdbManager.REMOTE_HELPER_PATH))
        arguments.forEach {
            append(' ')
            append(shellQuote(it))
        }
    }
    val result = WirelessAdbManager.runCommand(
        app,
        command,
        allowStreamClose = arguments.any {
            it == "id" ||
                it.contains("late-load") ||
                it.contains("/dev/sukisu.ko") ||
                it.contains("logcat insmod /dev/sukisu.ko")
        },
    )
    return CommandResult(result.code, stripAnsi(result.output.trim()))
}
    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"
    private fun isStreamClosed(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .any { it.contains("stream closed", ignoreCase = true) || it == "closed" }
    private fun setPhase(phase: InstallPhase, message: String) { mutableState.value = mutableState.value.copy(phase = phase, message = message); appendLog("[*] $message") }
    private fun appendLog(line: String) { val cleanLine = stripAnsi(line).trim(); if (cleanLine.isBlank()) return; mutableState.value = mutableState.value.copy(log = (mutableState.value.log + "\n" + cleanLine).trim()); updateHistoryLog() }
    private fun startHistory() { val entry = historyStore.create(); activeHistoryEntry = entry; publishHistory(entry) }
    private fun updateHistoryLog() { val entry = activeHistoryEntry ?: return; val updated = entry.copy(log = mutableState.value.log); activeHistoryEntry = updated; historyStore.save(updated); publishHistory(updated) }
    private fun updateHistoryProfile(profileId: String) { val entry = activeHistoryEntry ?: return; val updated = entry.copy(profileId = profileId); activeHistoryEntry = updated; historyStore.save(updated); publishHistory(updated) }
    private fun finishHistory(result: InstallRunResult) {
        val entry = activeHistoryEntry ?: return
        val completed = entry.copy(completedAtMillis = System.currentTimeMillis(), result = result, log = mutableState.value.log)
        activeHistoryEntry = null; historyStore.save(completed); publishHistory(completed)
    }
    private fun publishHistory(entry: InstallHistoryEntry) { mutableHistory.value = (mutableHistory.value.filterNot { it.id == entry.id } + entry).sortedByDescending(InstallHistoryEntry::startedAtMillis) }
    private fun File.readTextIfPresent(): String = if (exists()) readText() else ""

    companion object {
        private const val EXPLOIT_ATTEMPTS = "24"
        private const val WIRELESS_CONNECT_ATTEMPTS = 8
        private const val WIRELESS_CONNECT_RETRY_DELAY_MILLIS = 1_500L
        private const val EXPLOIT_STALL_MILLIS = 90_000L
        private const val EXPLOIT_TOTAL_MILLIS = 900_000L
        private const val TMP_PATH = "/data/local/tmp"
        private const val KERNEL_SU_STATE_COMMAND =
            "if [ -e /sys/module/kernelsu ] || " +
                "[ -e /sys/module/sukisu ] || " +
                "[ -e /sys/module/sukisu_ultra ] || " +
                "[ -e /sys/module/ksu ] || " +
                "grep -qE '^(kernelsu|sukisu|sukisu_ultra|ksu) ' /proc/modules 2>/dev/null; " +
                "then exit 0; else exit 1; fi"
        private val LOG_POLL_INTERVAL = 250.milliseconds
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val ROOT_ID_PATTERN = Regex("(^|\\s)uid=0(?:\\(|\\s|$)")
        private fun stripAnsi(value: String): String = ANSI_ESCAPE.replace(value, "").replace("\r", "")
    }
}
