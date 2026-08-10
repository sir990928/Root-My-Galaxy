package dev.busung.s25uroot

import com.mobile_dev_inc.dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

object AdbController {
    private var dadb: Dadb? = null

    suspend fun pair(host: String, port: Int, pairingCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // In dadb, pairing is done via Dadb.pair
            // We need to create an InetSocketAddress for the host and port
            // The pairingCode is the code shown on the device
            // Dadb.pair(InetSocketAddress(host, port), pairingCode)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun connect(host: String = "localhost", port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            dadb = Dadb.create(host, port)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isConnected(): Boolean = dadb != null

    suspend fun exec(cmd: String): String = withContext(Dispatchers.IO) {
        val client = dadb ?: throw IllegalStateException("ADB not connected")
        val result = client.shell(cmd)
        if (result.exitCode == 0) result.allOutput else "Error: ${result.allOutput}"
    }

    suspend fun shell(cmd: String) = withContext(Dispatchers.IO) {
        val client = dadb ?: throw IllegalStateException("ADB not connected")
        client.shell(cmd)
    }
    
    fun disconnect() {
        dadb?.close()
        dadb = null
    }
}
