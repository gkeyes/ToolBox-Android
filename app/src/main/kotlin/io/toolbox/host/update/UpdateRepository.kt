package io.toolbox.host.update

import android.content.Context
import io.toolbox.host.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal open class UpdateCheckException(message: String) : IOException(message)

/** CI replaced the asset after the feed was read: it is gone or has new content, so read the feed again. */
internal class StaleAssetException(message: String) : UpdateCheckException(message)

/**
 * Reads the public GitHub releases of the host repository: the latest release is the stable
 * channel and the rolling [TestReleaseTag] prerelease is the test channel. No token is needed
 * for a public repository; all network and preference I/O runs on [Dispatchers.IO].
 */
internal class UpdateRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
    private val repository: String = BuildConfig.UPDATE_REPOSITORY,
) {
    private val preferences by lazy { context.getSharedPreferences("toolbox_updates", Context.MODE_PRIVATE) }

    val installedBuild = InstalledBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_RUN_ID)

    suspend fun channel(): UpdateChannel = withContext(Dispatchers.IO) {
        preferences.getString(KeyChannel, null)?.let { saved -> UpdateChannel.entries.firstOrNull { it.name == saved } }
            ?: UpdateChannel.STABLE
    }

    suspend fun setChannel(channel: UpdateChannel) = withContext(Dispatchers.IO) {
        preferences.edit().putString(KeyChannel, channel.name).apply()
    }

    suspend fun checkOnLaunch(): Boolean = withContext(Dispatchers.IO) { preferences.getBoolean(KeyCheckOnLaunch, true) }

    suspend fun setCheckOnLaunch(enabled: Boolean) = withContext(Dispatchers.IO) {
        preferences.edit().putBoolean(KeyCheckOnLaunch, enabled).apply()
    }

    /** Returns the feed when a launch check is due (at most every [LaunchCheckIntervalMs]), otherwise null. */
    suspend fun launchCheck(now: Long = System.currentTimeMillis()): ChannelFeed? = withContext(Dispatchers.IO) {
        if (!preferences.getBoolean(KeyCheckOnLaunch, true)) return@withContext null
        if (now - preferences.getLong(KeyLastLaunchCheck, 0L) < LaunchCheckIntervalMs) return@withContext null
        preferences.edit().putLong(KeyLastLaunchCheck, now).apply()
        fetch(channel())
    }

    suspend fun fetch(channel: UpdateChannel): ChannelFeed = withContext(Dispatchers.IO) {
        val stable = release("releases/latest")
        val test = if (channel == UpdateChannel.TEST) release("releases/tags/$TestReleaseTag") else null
        if (stable == null && test == null) throw UpdateCheckException("没有找到可用的发布版本。")
        val stableApp = stable?.let { UpdateFeedParser.app(it, receipt(it)) }
        val testApp = test?.let { UpdateFeedParser.app(it, receipt(it)) }
        val stableTools = stable?.let(UpdateFeedParser::tools).orEmpty()
        ChannelFeed(
            channel = channel,
            app = if (channel == UpdateChannel.TEST) testApp ?: stableApp else stableApp,
            tools = if (channel == UpdateChannel.TEST) {
                UpdateFeedParser.mergeTools(stableTools, test?.let(UpdateFeedParser::tools).orEmpty())
            } else {
                stableTools.sortedBy { it.slug }
            },
        )
    }

    /** Downloads [asset] into the private update cache and verifies its published SHA-256 when one is known. */
    suspend fun download(asset: ReleaseAsset, onProgress: (Float?) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, UpdateCacheDirectory).apply { mkdirs() }
        val target = File(directory, asset.name)
        val partial = File(directory, "${asset.name}.part")
        partial.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(asset.url).build()).execute().use { response ->
            if (response.code == 404) throw StaleAssetException("文件已被新构建替换，请重新检查更新。")
            if (!response.isSuccessful) throw UpdateCheckException("下载失败（HTTP ${response.code}）。")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: asset.size.takeIf { it > 0 }
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        received += read
                        onProgress(total?.let { (received.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (asset.sha256 != null && asset.sha256 != actual) {
            partial.delete()
            throw StaleAssetException("文件校验失败，请重新检查更新后再试。")
        }
        if (!partial.renameTo(target)) {
            target.delete()
            if (!partial.renameTo(target)) throw UpdateCheckException("无法保存下载的文件。")
        }
        target
    }

    /** Removes downloaded files that are no longer needed. */
    suspend fun clearDownloads() = withContext(Dispatchers.IO) {
        File(context.cacheDir, UpdateCacheDirectory).listFiles()?.forEach { it.delete() }
    }

    private fun release(path: String): ParsedRelease? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/$path")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val parsed = client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> return null
                response.code == 403 || response.code == 429 -> throw UpdateCheckException("GitHub 请求过于频繁，请稍后再试。")
                !response.isSuccessful -> throw UpdateCheckException("检查更新失败（HTTP ${response.code}）。")
                else -> UpdateFeedParser.release(response.body.string())
            }
        }
        if (parsed.assets.all { it.sha256 != null }) return parsed
        val sums = parsed.assets.firstOrNull { it.name == "SHA256SUMS.txt" } ?: return parsed
        return text(sums.url)?.let { UpdateFeedParser.withChecksums(parsed, it) } ?: parsed
    }

    private fun receipt(release: ParsedRelease): BuildReceipt? =
        release.assets.firstOrNull { it.name == "BUILD_RECEIPT.txt" }?.let { text(it.url) }?.let(UpdateFeedParser::receipt)

    private fun text(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    }.getOrNull()

    private companion object {
        const val KeyChannel = "channel"
        const val KeyCheckOnLaunch = "check_on_launch"
        const val KeyLastLaunchCheck = "last_launch_check"
        const val LaunchCheckIntervalMs = 6L * 60 * 60 * 1000
        const val UpdateCacheDirectory = "updates"
    }
}

/** Startup hint when a due launch check finds updates; never throws, so startup is unaffected by network state. */
internal suspend fun UpdateRepository.launchUpdateHint(catalog: io.toolbox.core.data.CatalogRepository): String? = try {
    launchCheck()?.let { feed ->
        val appNewer = feed.app?.let { UpdateFeedParser.appState(it, installedBuild) } == AppUpdateState.NEWER
        val tools = toolRows(feed, catalog.observeTools().first())
            .count { it.state == ToolRowState.UPDATE }
        when {
            appNewer && tools > 0 -> "ToolBox 和 $tools 个小工具有更新，可在 设置 › 软件更新 中安装"
            appNewer -> "ToolBox 有新${feed.channel.label}，可在 设置 › 软件更新 中安装"
            tools > 0 -> "$tools 个小工具有更新，可在 设置 › 软件更新 中安装"
            else -> null
        }
    }
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
