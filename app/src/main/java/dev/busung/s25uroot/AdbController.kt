package dev.busung.s25uroot

import java.net.Inet4Address
import java.net.NetworkInterface

object AdbController {
    private var connected = false
    private const val exploitPath = "/data/local/tmp/cve-2026-43499-root"

    private fun execRoot(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(exploitPath, "-c", cmd))
            p.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            ""
        }
    }

    fun enableWifiDebug(port: Int = 5555): Boolean {
        return try {
            execRoot("setprop service.adb.tcp.port $port && stop adbd && start adbd")
            connected = true
            true
        } catch (e: Exception) {
            false
        }
    }

    fun disableWifiDebug(): Boolean {
        return try {
            execRoot("setprop service.adb.tcp.port -1 && stop adbd && start adbd")
            connected = false
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getWifiIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress ?: "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    fun isConnected(): Boolean = connected

    fun exec(cmd: String): String = execRoot(cmd)

    fun disconnect() {
        disableWifiDebug()
    }
}
