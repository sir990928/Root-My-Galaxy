package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class ManagerFiles(val ksud: File, val ko: File?)

class PayloadRepository(private val context: Context) {
    private val baseUrl = "https://gitee.com/lin0928/samsung-root/raw/main"
    private val manifestUrl = "$baseUrl/Root-My-Galaxy-Payloads/support/targets-v3.json"

    private val cachedManifest: JSONObject by lazy {
        val bytes = URL(manifestUrl).openConnection().apply {
            connectTimeout = 15_000; readTimeout = 30_000; connect()
            require((this as HttpURLConnection).responseCode == 200)
        }.inputStream.use { it.readBytes() }
        JSONObject(String(bytes, Charsets.UTF_8))
    }

    fun downloadExploit(onProgress: (String) -> Unit): File {
        val p = cachedManifest.getJSONArray("payloads").getJSONObject(0)
        val url = p.getJSONObject("exploit").getString("url")
        val dir = File(context.filesDir, "exploit").apply { mkdirs() }
        val dest = File(dir, "cve-2026-43499-app.so")
        val file = downloadFile(url, dest, "exploit", onProgress)
        Os.chmod(file.absolutePath, 0b100100100)
        return file
    }

    fun downloadManager(managerKey: String, onProgress: (String) -> Unit): ManagerFiles {
        val p = cachedManifest.getJSONArray("payloads").getJSONObject(0)
        val m = p.getJSONObject("managers").getJSONObject(managerKey) // "kernelsu" or "sukisu"
        val dir = File(context.filesDir, "managers/$managerKey").apply { mkdirs() }
        
        val ksud = downloadFile(m.getString("ksudUrl"), File(dir, "ksud"), "ksud", onProgress)
        Os.chmod(ksud.absolutePath, 0b100100100)
        
        val ko = if (m.optBoolean("needsKo", false)) {
            val koFile = downloadFile(m.getString("koUrl"), File(dir, "kernelsu.ko"), "ko", onProgress)
            Os.chmod(koFile.absolutePath, 0b100100100)
            koFile
        } else null
        
        return ManagerFiles(ksud, ko)
    }

    private fun downloadFile(url: String, dest: File, label: String, onProgress: (String) -> Unit): File {
        onProgress("Downloading $label...")
        val tmp = File(dest.parentFile, "${dest.name}.part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 120_000; conn.connect()
        require(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
        conn.inputStream.use { FileOutputStream(tmp).use { it.copyTo(it, 8192) } }
        conn.disconnect()
        tmp.renameTo(dest)
        onProgress("$label downloaded (${dest.length()} bytes)")
        return dest
    }
}
