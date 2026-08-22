package com.glaxysu.root

import android.content.Context
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class VerifiedPayloads(
    val profile: TargetProfile,
    val exploit: File,
    val kernelSu: File,
    val kernelModule: File? = null,
)

class PayloadRepository(private val context: Context) {
    fun loadTargets(): List<TargetProfile> {
        val manifestBytes = downloadBytes(TARGETS_MANIFEST_URL, MAX_MANIFEST_BYTES)
        return SupportManifest.parse(manifestBytes).targets
    }

    fun resolveTarget(snapshot: DeviceSnapshot): TargetProfile = loadTargets()
        .firstOrNull { it.matches(snapshot) }
        ?: error(context.getString(R.string.repo_no_profile))

    fun resolveTarget(profileId: String): TargetProfile = loadTargets()
        .firstOrNull { it.profileId == profileId }
        ?: error(context.getString(R.string.repo_profile_missing, profileId))

    fun download(
        profile: TargetProfile,
        adbMode: Boolean,
        onProgress: (String) -> Unit,
    ): VerifiedPayloads {
        val directory = File(context.filesDir, "payloads/${profile.profileId}").apply { mkdirs() }

        val exploitArtifact = if (adbMode) {
            profile.exploitAdb ?: profile.exploit
        } else {
            profile.exploit
        }

        val exploit = downloadArtifact(
            exploitArtifact,
            File(directory, "cve-2026-43499-app.so"),
            context.getString(R.string.artifact_exploit),
            onProgress,
        )

        val manager = AppPreferences.rootManager(context)
        val managerInfo = profile.managers[manager.manifestKey]
            ?: error("Missing ${manager.manifestKey} manager in support manifest")
        require(managerInfo.ksudUrl.isNotBlank()) {
            "Missing ${manager.manifestKey} ksudUrl in support manifest"
        }
        val ksudName = artifactFileName(managerInfo.ksudUrl, "${manager.manifestKey}-ksud")

        val kernelSu = downloadArtifact(
            RemoteArtifact(managerInfo.ksudUrl),
            File(directory, ksudName),
            managerInfo.name,
            onProgress,
        )

        var kernelModule: File? = null
        if (managerInfo.needsKo) {
            val koUrl = managerInfo.koUrl
                ?: error("Missing ${manager.manifestKey} koUrl in support manifest")
            val koFile = File(directory, artifactFileName(koUrl, "sukisu.ko"))
            kernelModule = downloadArtifact(
                RemoteArtifact(koUrl),
                koFile,
                "kernel module",
                onProgress,
            ).also { Os.chmod(it.absolutePath, 0b100100100) }
        }

        Os.chmod(exploit.absolutePath, 0b100100100)
        Os.chmod(kernelSu.absolutePath, 0b100100100)
        return VerifiedPayloads(profile, exploit, kernelSu, kernelModule)
    }

    private fun downloadArtifact(artifact: RemoteArtifact, destination: File, label: String, onProgress: (String) -> Unit): File {
        onProgress(context.getString(R.string.repo_downloading, label))
        val temporary = File(destination.parentFile, "${destination.name}.part")
        val connection = open(artifact.url)
        var total = 0L
        connection.inputStream.use { input ->
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        connection.disconnect()
        if (destination.exists()) destination.delete()
        require(temporary.renameTo(destination)) { context.getString(R.string.repo_finalize_failed, label) }
        onProgress(context.getString(R.string.repo_verified, label))
        return destination
    }

    private fun artifactFileName(url: String, fallback: String): String =
        URL(url).path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: fallback

    private fun downloadBytes(url: String, maximum: Int): ByteArray {
        val connection = open(url)
        val bytes = connection.inputStream.use { it.readBytes() }
        connection.disconnect()
        require(bytes.size <= maximum) { context.getString(R.string.repo_response_too_large) }
        return bytes
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "S25URoot/${BuildConfig.VERSION_NAME}")
            connect()
            require(responseCode == HttpURLConnection.HTTP_OK) { "HTTP $responseCode" }
        }

    companion object {
        private const val TARGETS_MANIFEST_URL =
            "https://gitee.com/lin0928/samsung-root/raw/main/Root-My-Galaxy-Payloads/support/targets-v3.json"
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
    }
}