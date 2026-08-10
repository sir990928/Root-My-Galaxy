cat > ~/Root-My-Galaxy/app/src/main/java/dev/busung/s25uroot/SupportManifest.kt << 'ENDOFFILE'
package dev.busung.s25uroot

import org.json.JSONArray
import org.json.JSONObject

data class RemoteArtifact(val url: String, val size: Long = -1)

data class ManagerInfo(
    val name: String,
    val ksudUrl: String,
    val koUrl: String? = null,
    val needsKo: Boolean = false,
    val managerPackage: String,
)

data class TargetProfile(
    val profileId: String,
    val displayName: String,
    val models: Set<String>,
    val kernelVersions: Set<String>,
    val exploit: RemoteArtifact,
    val managers: Map<String, ManagerInfo>,
) {
    fun matches(snapshot: DeviceSnapshot) =
        models.any { it.equals(snapshot.model, ignoreCase = true) } &&
        snapshot.kernelVersion in kernelVersions
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
                    val mgrJson = p.optJSONObject("managers") ?: JSONObject()
                    val mgrMap = buildMap {
                        for (key in mgrJson.keys()) {
                            val m = mgrJson.getJSONObject(key)
                            put(key, ManagerInfo(
                                name = m.getString("name"),
                                ksudUrl = m.getString("ksudUrl"),
                                koUrl = m.optString("koUrl", null),
                                needsKo = m.optBoolean("needsKo", false),
                                managerPackage = m.getString("managerPackage"),
                            ))
                        }
                    }
                    add(TargetProfile(
                        profileId = p.getString("payloadId"),
                        displayName = p.getString("displayName"),
                        models = p.getJSONArray("models").strings(),
                        kernelVersions = p.getJSONArray("kernelVersions").strings(),
                        exploit = RemoteArtifact(exploit.getString("url")),
                        managers = mgrMap,
                    ))
                }
            }
            return SupportManifest(list)
        }
        private fun JSONArray.strings() = buildSet { for (i in 0 until length()) add(getString(i)) }
    }
}
ENDOFFILE