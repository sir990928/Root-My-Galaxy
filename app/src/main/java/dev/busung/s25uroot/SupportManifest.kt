package dev.busung.s25uroot

import org.json.JSONArray
import org.json.JSONObject

data class RemoteArtifact(val url: String, val size: Long = -1)

data class ManagerInfo(
    val name: String, val ksudUrl: String, val koUrl: String? = null,
    val needsKo: Boolean = false, val managerPackage: String,
)

data class TargetProfile(
    val profileId: String, val displayName: String,
    val models: Set<String>, val kernelVersions: Set<String>,
    val exploit: RemoteArtifact, val managers: Map<String, ManagerInfo>,
) {
    fun matches(snapshot: DeviceSnapshot) = models.any { it.equals(snapshot.model, ignoreCase = true) } && snapshot.kernelVersion in kernelVersions
    fun matchesDevice(snapshot: DeviceSnapshot) = models.any { it.equals(snapshot.model, ignoreCase = true) }
    fun matchesKernelVersion(snapshot: DeviceSnapshot) = snapshot.kernelVersion in kernelVersions
    val supportedModels get() = models.joinToString()
    val supportedKernelVersions get() = kernelVersions.joinToString()
}

data class SupportManifest(val targets: List<TargetProfile>) {
    companion object {
        fun parse(bytes: ByteArray): SupportManifest {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val payloads = root.getJSONArray("payloads")
            val list = buildList {
                for (i in 0 until payloads.length()) {
                    val p = payloads.getJSONObject(i)
                    val exploit = p.getJSONObject("exploit")
                    val mgrJson = p.getJSONObject("managers")
                    val mgrMap = buildMap {
                        for (key in mgrJson.keys()) {
                            val m = mgrJson.getJSONObject(key)
                            put(key, ManagerInfo(m.getString("name"), m.getString("ksudUrl"),
                                m.optString("koUrl", null), m.optBoolean("needsKo", false), m.getString("managerPackage")))
                        }
                    }
                    add(TargetProfile(p.getString("payloadId"), p.getString("displayName"),
                        p.getJSONArray("models").strings(), p.getJSONArray("kernelVersions").strings(),
                        RemoteArtifact(exploit.getString("url")), mgrMap))
                }
            }
            return SupportManifest(list)
        }
        private fun JSONArray.strings() = buildSet { for (i in 0 until length()) add(getString(i)) }
    }
}
