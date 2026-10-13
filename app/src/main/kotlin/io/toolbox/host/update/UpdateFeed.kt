package io.toolbox.host.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Stable follows published GitHub releases; test follows the rolling `dev` prerelease CI refreshes from `main`. */
internal enum class UpdateChannel(val label: String) { STABLE("正式版"), TEST("测试版") }

internal const val TestReleaseTag = "dev"

internal data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

internal data class ParsedRelease(
    val tag: String,
    val publishedAt: String?,
    val notes: String,
    val assets: List<ReleaseAsset>,
)

internal data class BuildReceipt(val versionName: String?, val versionCode: Int?, val commit: String?, val runId: Long?)

internal data class AppPackage(
    val versionName: String,
    val versionCode: Int?,
    val commit: String?,
    val runId: Long?,
    val publishedAt: String?,
    val notes: String,
    val asset: ReleaseAsset,
)

internal data class ToolPackage(val slug: String, val version: String, val asset: ReleaseAsset) {
    /** Release files are named after the example directory; tool ids are `io.toolbox.<directory without dashes>`. */
    fun matches(toolId: String): Boolean = toolId.substringAfterLast('.') == slug.replace("-", "")
}

internal data class ChannelFeed(val channel: UpdateChannel, val app: AppPackage?, val tools: List<ToolPackage>)

internal data class InstalledBuild(val versionName: String, val versionCode: Int, val runId: Long)

internal enum class AppUpdateState { NEWER, CURRENT, OTHER_BUILD, DOWNGRADE }

internal object UpdateFeedParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val apkName = Regex("""^toolbox-v(.+?)-(release|dev)\.apk$""")
    private val tbxName = Regex("""^([a-z0-9][a-z0-9-]*?)-v([0-9][0-9A-Za-z.+-]*)\.tbx$""")
    private val sha256 = Regex("[0-9a-f]{64}")

    fun release(body: String): ParsedRelease {
        val root = json.parseToJsonElement(body).jsonObject
        val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
            val asset = element as? JsonObject ?: return@mapNotNull null
            val name = asset.string("name") ?: return@mapNotNull null
            val url = asset.string("browser_download_url") ?: return@mapNotNull null
            val digest = asset.string("digest")?.removePrefix("sha256:")?.lowercase()?.takeIf(sha256::matches)
            ReleaseAsset(name, url, (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0L, digest)
        }
        return ParsedRelease(
            tag = root.string("tag_name").orEmpty(),
            publishedAt = root.string("published_at"),
            notes = root.string("body").orEmpty(),
            assets = assets,
        )
    }

    /** Fills missing digests from a `SHA256SUMS.txt` asset. */
    fun withChecksums(release: ParsedRelease, sums: String): ParsedRelease {
        val known = sums.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && sha256.matches(parts[0].lowercase())) parts[1].removePrefix("*") to parts[0].lowercase() else null
        }.toMap()
        return release.copy(assets = release.assets.map { it.copy(sha256 = it.sha256 ?: known[it.name]) })
    }

    fun receipt(text: String): BuildReceipt {
        val values = text.lineSequence().mapNotNull { line ->
            line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() }
        }.toMap()
        return BuildReceipt(
            versionName = values["VERSION_NAME"],
            versionCode = values["VERSION_CODE"]?.toIntOrNull(),
            commit = values["BUILD_COMMIT"],
            runId = values["WORKFLOW_RUN"]?.toLongOrNull(),
        )
    }

    fun app(release: ParsedRelease, receipt: BuildReceipt?): AppPackage? {
        val asset = release.assets.firstOrNull { apkName.matches(it.name) } ?: return null
        val versionName = apkName.find(asset.name)!!.groupValues[1]
        return AppPackage(
            versionName = receipt?.versionName ?: versionName,
            versionCode = receipt?.versionCode,
            commit = receipt?.commit,
            runId = receipt?.runId,
            publishedAt = release.publishedAt,
            notes = release.notes,
            asset = asset,
        )
    }

    fun tools(release: ParsedRelease): List<ToolPackage> = release.assets.mapNotNull { asset ->
        tbxName.find(asset.name)?.let { ToolPackage(it.groupValues[1], it.groupValues[2], asset) }
    }.groupBy { it.slug }.map { (_, versions) -> versions.maxWith { a, b -> compareVersions(a.version, b.version) } }

    /** The test channel offers whichever of the two is newer for each tool, since CI only republishes changed tools. */
    fun mergeTools(stable: List<ToolPackage>, test: List<ToolPackage>): List<ToolPackage> =
        (stable + test).groupBy { it.slug }.map { (_, candidates) ->
            candidates.maxWith { a, b -> compareVersions(a.version, b.version) }
        }.sortedBy { it.slug }

    /**
     * Decided by version alone: CI requires a higher versionCode and versionName for every change that
     * ships, and never publishes one version twice, so a newer test build always shows a new version
     * number. versionCode decides; versionName only when a feed has no build receipt. The CI run id
     * only tells this exact build apart from another build of the same version (e.g. a stable and a
     * test build of one commit); it never makes a same-version build "newer".
     */
    fun appState(candidate: AppPackage, installed: InstalledBuild): AppUpdateState {
        val order = compareAppVersions(candidate.versionCode, candidate.versionName, installed.versionCode, installed.versionName)
        return when {
            order > 0 -> AppUpdateState.NEWER
            order < 0 -> AppUpdateState.DOWNGRADE
            candidate.runId == null && installed.runId == 0L -> AppUpdateState.CURRENT
            candidate.runId != null && candidate.runId == installed.runId -> AppUpdateState.CURRENT
            else -> AppUpdateState.OTHER_BUILD
        }
    }

    /**
     * Whether [replacement], read again after the test asset was swapped mid-download, may stand in for
     * [original]: not a lower version, and for the same version (a rerun before CI stopped republishing
     * versions) not an older CI run.
     */
    fun isNotOlder(replacement: AppPackage, original: AppPackage): Boolean {
        val order = compareAppVersions(replacement.versionCode, replacement.versionName, original.versionCode, original.versionName)
        return order > 0 || (order == 0 && (original.runId == null || (replacement.runId ?: 0L) >= original.runId))
    }

    private fun compareAppVersions(leftCode: Int?, leftName: String, rightCode: Int?, rightName: String): Int =
        if (leftCode != null && rightCode != null) leftCode.compareTo(rightCode) else compareVersions(leftName, rightName)
}

/** Numeric dotted comparison; a pre-release suffix sorts before the same release. */
internal fun compareVersions(left: String, right: String): Int {
    fun parse(value: String): Pair<List<Long>, String> {
        val core = value.substringBefore('-').substringBefore('+')
        return core.split('.').map { part -> part.takeWhile(Char::isDigit).toLongOrNull() ?: 0L } to
            value.removePrefix(core).removePrefix("-")
    }
    val (a, aSuffix) = parse(left)
    val (b, bSuffix) = parse(right)
    for (index in 0 until maxOf(a.size, b.size)) {
        val difference = a.getOrElse(index) { 0L }.compareTo(b.getOrElse(index) { 0L })
        if (difference != 0) return difference
    }
    return when {
        aSuffix == bSuffix -> 0
        aSuffix.isEmpty() -> 1
        bSuffix.isEmpty() -> -1
        else -> aSuffix.compareTo(bSuffix)
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
