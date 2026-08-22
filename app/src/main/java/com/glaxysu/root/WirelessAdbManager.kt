package com.glaxysu.root

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.LocalServices
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class WirelessAdbCommandResult(
    val code: Int,
    val output: String,
)

private const val EXIT_MARKER = "__GLAXYSU_EXIT__"

class WirelessAdbProcess internal constructor(
    private val stream: AdbStream,
) : AutoCloseable {
    private val captured = ByteArrayOutputStream()

    fun isAlive(): Boolean = !stream.isClosed

    fun drainAvailable() {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val available = runCatching { stream.available() }.getOrDefault(0)
            if (available <= 0) return
            val count = runCatching {
                stream.read(buffer, 0, minOf(buffer.size, available))
            }.getOrDefault(-1)
            if (count <= 0) return
            captured.write(buffer, 0, count)
        }
    }

    fun output(): String = captured.toString(Charsets.UTF_8.name())

    fun cleanOutput(): String {
        val output = output()
        val markerIndex = output.lastIndexOf(EXIT_MARKER)
        return if (markerIndex < 0) output else output.substring(0, markerIndex)
    }

    fun exitCode(): Int? {
        val output = output()
        val markerIndex = output.lastIndexOf(EXIT_MARKER)
        if (markerIndex < 0) return null
        return output.substring(markerIndex + EXIT_MARKER.length)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.toIntOrNull()
    }

    override fun close() {
        runCatching { stream.close() }
    }
}

class WirelessAdbPairingRequiredException(cause: Throwable? = null) :
    IOException("Wireless debugging needs to be paired once in Settings", cause)

object WirelessAdbManager {
    const val REMOTE_HELPER_PATH = "/data/local/tmp/cve-2026-43499-root"

    private const val CONNECTION_TIMEOUT_MILLIS = 8_000L
    private const val PAIRING_DISCOVERY_TIMEOUT_MILLIS = 15_000L
    private const val SYNC_DATA_CHUNK_SIZE = 64 * 1024
    private const val EXIT_MARKER = "__GLAXYSU_EXIT__"
    private val lock = Any()

    fun isConnected(context: Context): Boolean = runCatching {
        WirelessAdbConnectionManager.getInstance(context).isConnected
    }.getOrDefault(false)

    fun refreshConnection(
        context: Context,
        forceReconnect: Boolean = false,
        allowUnpaired: Boolean = false,
    ): Boolean = runCatching {
        if (forceReconnect) {
            synchronized(lock) {
                runCatching {
                    WirelessAdbConnectionManager.getInstance(context).disconnect()
                }
            }
        }
        val connected = if (isConnected(context)) {
            true
        } else if (allowUnpaired || AppPreferences.wirelessAdbPaired(context)) {
            ensureConnected(context)
        } else {
            false
        }
        if (!connected) return@runCatching false

        if (probeShell(context)) {
            AppPreferences.setWirelessAdbPaired(context, true)
            return@runCatching true
        }

        synchronized(lock) {
            runCatching {
                WirelessAdbConnectionManager.getInstance(context).disconnect()
            }
        }
        val reconnected = if (allowUnpaired || AppPreferences.wirelessAdbPaired(context)) {
            ensureConnected(context)
        } else {
            false
        }
        reconnected && probeShell(context)
    }.getOrDefault(false)

    @Throws(Exception::class)
    fun ensureConnected(context: Context): Boolean {
        val manager = WirelessAdbConnectionManager.getInstance(context)
        synchronized(lock) {
            if (manager.isConnected) {
                AppPreferences.setWirelessAdbPaired(context, true)
                return true
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return false
            }
            runCatching { manager.disconnect() }
            val connected = try {
                manager.autoConnect(context.applicationContext, CONNECTION_TIMEOUT_MILLIS)
            } catch (error: AdbPairingRequiredException) {
                throw WirelessAdbPairingRequiredException(error)
            }
            if (connected) AppPreferences.setWirelessAdbPaired(context, true)
            return connected
        }
    }

    @Throws(Exception::class)
    fun pair(context: Context, pairingCode: String): Boolean {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Wireless debugging requires Android 11 or newer"
        }
        require(pairingCode.matches(Regex("\\d{6}"))) {
            "Pairing code must contain six digits"
        }

        val endpoint = discoverPairingEndpoint(context)
        val manager = WirelessAdbConnectionManager.getInstance(context)
        synchronized(lock) {
            runCatching { manager.disconnect() }
            manager.pair(endpoint.host.hostAddress ?: error("Wireless debugging host unavailable"), endpoint.port, pairingCode)
            manager.autoConnect(context.applicationContext, CONNECTION_TIMEOUT_MILLIS)
        }
        val connected = manager.isConnected
        if (connected) AppPreferences.setWirelessAdbPaired(context, true)
        return connected
    }

    @Throws(Exception::class)
    fun runCommand(
        context: Context,
        command: String,
        allowStreamClose: Boolean = false,
    ): WirelessAdbCommandResult {
        require(ensureConnected(context)) {
            "Wireless debugging is not connected"
        }
        val script = commandScript(command)
        val stream = WirelessAdbConnectionManager.getInstance(context).openStream("shell:sh -c ${shellQuote(script)}")
        return readCommandResult(stream, allowStreamClose)
    }

    @Throws(Exception::class)
    fun openProcess(context: Context, command: String): WirelessAdbProcess {
        require(ensureConnected(context)) {
            "Wireless debugging is not connected"
        }
        val script = commandScript(command)
        val stream = WirelessAdbConnectionManager.getInstance(context)
            .openStream("shell:sh -c ${shellQuote(script)}")
        return WirelessAdbProcess(stream)
    }

    @Throws(Exception::class)
    fun stageFile(
        context: Context,
        source: File,
        remotePath: String,
        mode: String = "755",
    ): WirelessAdbCommandResult {
        require(source.isFile) { "Unable to stage missing file: ${source.name}" }
        require(mode.matches(Regex("[0-7]{3,4}"))) { "Invalid remote mode" }
        require(ensureConnected(context)) {
            "Wireless debugging is not connected"
        }

        return syncUpload(context, source, remotePath, mode)
    }

    fun close(context: Context) {
        synchronized(lock) {
            runCatching {
                WirelessAdbConnectionManager.getInstance(context).close()
            }
        }
    }

    private fun probeShell(context: Context): Boolean = runCatching {
        val result = runCommand(
            context,
            "printf GLAXYSU_ADB_PROBE",
            allowStreamClose = true,
        )
        result.code == 0 && result.output.contains("GLAXYSU_ADB_PROBE")
    }.getOrDefault(false)

    private fun discoverPairingEndpoint(context: Context): PairingEndpoint {
        val latch = CountDownLatch(1)
        var endpoint: PairingEndpoint? = null
        val mdns = AdbMdns(
            context.applicationContext,
            AdbMdns.SERVICE_TYPE_TLS_PAIRING,
        ) { hostAddress: InetAddress?, port: Int ->
            if (hostAddress != null && port > 0) {
                endpoint = PairingEndpoint(hostAddress, port)
                latch.countDown()
            }
        }
        mdns.start()
        return try {
            if (!latch.await(PAIRING_DISCOVERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                error("Could not find the Wireless debugging pairing service")
            }
            endpoint ?: error("Wireless debugging pairing service unavailable")
        } finally {
            mdns.stop()
        }
    }

    private fun readCommandResult(
        stream: AdbStream,
        allowStreamClose: Boolean,
    ): WirelessAdbCommandResult {
        val captured = ByteArrayOutputStream()
        return try {
            val input = stream.openInputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) captured.write(buffer, 0, count)
            }
            val output = captured.toString(Charsets.UTF_8.name())
            val markerIndex = output.lastIndexOf(EXIT_MARKER)
            if (markerIndex < 0) {
                WirelessAdbCommandResult(0, output.trim())
            } else {
                val codeText = output.substring(markerIndex + EXIT_MARKER.length)
                    .lineSequence()
                    .firstOrNull()
                    ?.trim()
                    .orEmpty()
                val code = codeText.toIntOrNull() ?: 1
                WirelessAdbCommandResult(
                    code = code,
                    output = output.substring(0, markerIndex).trim(),
                )
            }
        } catch (error: IOException) {
            if (allowStreamClose && error.message?.contains("closed", ignoreCase = true) == true) {
                val output = captured.toString(Charsets.UTF_8.name())
                val markerIndex = output.lastIndexOf(EXIT_MARKER)
                WirelessAdbCommandResult(
                    code = if (markerIndex < 0) 0 else output.substring(markerIndex + EXIT_MARKER.length)
                        .lineSequence()
                        .firstOrNull()
                        ?.trim()
                        ?.toIntOrNull()
                        ?: 0,
                    output = if (markerIndex < 0) output.trim() else output.substring(0, markerIndex).trim(),
                )
            } else {
                throw error
            }
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun commandScript(command: String): String {
        return "$command\nstatus=${'$'}?\nprintf '$EXIT_MARKER%s\\n' \"${'$'}status\""
    }

    private fun syncUpload(
        context: Context,
        source: File,
        remotePath: String,
        mode: String,
    ): WirelessAdbCommandResult {
        val stream = WirelessAdbConnectionManager.getInstance(context).openStream(LocalServices.SYNC)
        val input = stream.openInputStream()
        val output = stream.openOutputStream()
        return try {
            val modeValue = 0x8000 or mode.toInt(8)
            val pathAndMode = "$remotePath,$modeValue".toByteArray(Charsets.UTF_8)
            sendSyncHeader(output, "SEND", pathAndMode.size)
            output.write(pathAndMode)

            source.inputStream().use { sourceInput ->
                val buffer = ByteArray(SYNC_DATA_CHUNK_SIZE)
                while (true) {
                    val count = sourceInput.read(buffer)
                    if (count < 0) break
                    sendSyncHeader(output, "DATA", count)
                    output.write(buffer, 0, count)
                }
            }

            val modifiedSeconds = (source.lastModified() / 1_000L).toInt()
            sendSyncHeader(output, "DONE", modifiedSeconds)
            output.flush()
            readSyncResponse(input)
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun sendSyncHeader(output: java.io.OutputStream, id: String, value: Int) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.put(id.toByteArray(Charsets.UTF_8))
        header.putInt(value)
        output.write(header.array())
    }

    private fun readSyncResponse(input: java.io.InputStream): WirelessAdbCommandResult {
        val header = readExactly(input, 8)
        val id = header.copyOfRange(0, 4).toString(Charsets.UTF_8)
        val length = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return when (id) {
            "OKAY" -> WirelessAdbCommandResult(0, "")
            "FAIL" -> {
                val error = readExactly(input, length).toString(Charsets.UTF_8)
                WirelessAdbCommandResult(1, error)
            }
            else -> WirelessAdbCommandResult(1, "Unexpected ADB sync response: $id")
        }
    }

    private fun readExactly(input: java.io.InputStream, length: Int): ByteArray {
        require(length >= 0) { "Invalid ADB sync response length" }
        val output = ByteArrayOutputStream(length)
        val buffer = ByteArray(minOf(length, SYNC_DATA_CHUNK_SIZE))
        while (output.size() < length) {
            val remaining = length - output.size()
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) error("ADB sync response ended unexpectedly")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"

    private data class PairingEndpoint(
        val host: InetAddress,
        val port: Int,
    )
}
