package io.github.duddmeister.lumen.update

import org.json.JSONObject

/** A version of Lumen published as a GitHub release. */
class Release(
    val versionCode: Int,
    val versionName: String,
    /** What changed, a few short lines from the release notes. */
    val changes: List<String>,
    val apkUrl: String,
    val apkSize: Long,
)

object Releases {
    /**
     * Reads GitHub's "latest release" answer. Null when the release has no APK or its
     * tag (v1.0.<version code>) carries no version code.
     */
    fun parse(json: String): Release? {
        val root = JSONObject(json)
        val tag = root.optString("tag_name")
        val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val url = asset.optString("browser_download_url")
            if (asset.optString("name").endsWith(".apk") && url.startsWith("https://")) {
                return Release(code, tag.removePrefix("v"), changes(root.optString("body")), url, asset.optLong("size"))
            }
        }
        return null
    }

    /** The bullet points of GitHub's generated notes, without their "by @someone in <link>". */
    internal fun changes(notes: String): List<String> = notes.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("* ") || it.startsWith("- ") }
        .map { it.drop(2).replace(Regex("\\s+by @\\S+ in \\S+$"), "").trim() }
        .filter { it.isNotEmpty() }
        .take(MAX_CHANGES)
        .toList()

    private const val MAX_CHANGES = 3
}
