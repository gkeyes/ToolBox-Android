package io.toolbox.host.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.host.HostImportConfirmationKind
import io.toolbox.host.HostImportResult
import io.toolbox.host.HostPackageOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal enum class ToolRowState { UPDATE, CURRENT, NOT_INSTALLED }

internal data class ToolUpdateRow(
    val available: ToolPackage,
    val toolId: String?,
    val name: String,
    val installedVersion: String?,
    val state: ToolRowState,
)

internal const val AppProgressKey = "toolbox-app"

/** CI swaps a replaced test asset within seconds; read the feed this many times, this far apart. */
private const val StaleRetryAttempts = 3
private const val StaleRetryDelayMs = 5_000L

internal data class UpdateUiState(
    val installed: InstalledBuild,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val checkOnLaunch: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val feed: ChannelFeed? = null,
    val tools: List<ToolUpdateRow> = emptyList(),
    /** In-flight downloads/installs by tool slug or [AppProgressKey]; a null value means indeterminate. */
    val progress: Map<String, Float?> = emptyMap(),
    val message: String? = null,
    val pendingApk: File? = null,
) {
    val appState: AppUpdateState? get() = feed?.app?.let { UpdateFeedParser.appState(it, installed) }
    val updatableTools: List<ToolUpdateRow> get() = tools.filter { it.state == ToolRowState.UPDATE }
    val busy: Boolean get() = progress.isNotEmpty()
}

internal fun toolRows(feed: ChannelFeed?, installed: List<InstalledTool>): List<ToolUpdateRow> =
    feed?.tools.orEmpty().map { candidate ->
        val tool = installed.firstOrNull { candidate.matches(it.metadata.id) }
        ToolUpdateRow(
            available = candidate,
            toolId = tool?.metadata?.id,
            name = tool?.metadata?.name ?: candidate.slug,
            installedVersion = tool?.currentVersion?.version,
            state = when {
                tool == null -> ToolRowState.NOT_INSTALLED
                compareVersions(candidate.version, tool.currentVersion.version) > 0 -> ToolRowState.UPDATE
                else -> ToolRowState.CURRENT
            },
        )
    }.sortedWith(compareBy<ToolUpdateRow> { it.state.ordinal }.thenBy { it.name })

internal class UpdateViewModel(
    private val repository: UpdateRepository,
    private val catalog: CatalogRepository,
    private val packages: HostPackageOperations,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UpdateUiState(installed = repository.installedBuild))
    val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()
    private var installedTools: List<InstalledTool> = emptyList()
    private val toolMutations = Mutex()

    init {
        viewModelScope.launch {
            val channel = repository.channel()
            mutableState.update { it.copy(channel = channel, checkOnLaunch = repository.checkOnLaunch()) }
            refresh()
        }
        viewModelScope.launch {
            catalog.observeTools().collect { tools ->
                installedTools = tools
                mutableState.update { it.copy(tools = toolRows(it.feed, tools)) }
            }
        }
    }

    fun refresh() {
        if (mutableState.value.loading) return
        val channel = mutableState.value.channel
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = try {
                Result.success(repository.fetch(channel))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            mutableState.update { current ->
                if (current.channel != channel) return@update current.copy(loading = false)
                result.fold(
                    onSuccess = { feed -> current.copy(loading = false, feed = feed, tools = toolRows(feed, installedTools)) },
                    onFailure = { error -> current.copy(loading = false, error = error.userMessage("检查更新失败，请检查网络后重试。")) },
                )
            }
            if (mutableState.value.channel != channel) refresh()
        }
    }

    fun selectChannel(channel: UpdateChannel) {
        if (channel == mutableState.value.channel) return
        mutableState.update { it.copy(channel = channel, feed = null, tools = emptyList(), error = null) }
        viewModelScope.launch { repository.setChannel(channel) }
        refresh()
    }

    fun setCheckOnLaunch(enabled: Boolean) {
        mutableState.update { it.copy(checkOnLaunch = enabled) }
        viewModelScope.launch { repository.setCheckOnLaunch(enabled) }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    /** Downloads the channel's APK; the screen then hands [UpdateUiState.pendingApk] to the system installer. */
    fun downloadApp() {
        val feed = mutableState.value.feed ?: return
        val app = feed.app ?: return
        if (AppProgressKey in mutableState.value.progress) return
        setProgress(AppProgressKey, 0f)
        viewModelScope.launch {
            try {
                // During CI's swap the test feed falls back to the stable APK, and an older overlapping
                // run can publish last; take neither as the replacement.
                val file = downloadCurrent(feed.channel, app.asset, AppProgressKey) { fresh ->
                    fresh.app?.takeIf { replacement ->
                        (fresh.channel != UpdateChannel.TEST || replacement.asset.name.endsWith("-dev.apk")) &&
                            UpdateFeedParser.isNotOlder(replacement, app)
                    }?.asset
                }
                mutableState.update { it.copy(pendingApk = file) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(message = error.userMessage("下载失败，请重试。")) }
            } finally {
                clearProgress(AppProgressKey)
            }
        }
    }

    fun apkHandedOff() = mutableState.update { it.copy(pendingApk = null) }

    fun installTool(slug: String) {
        val row = mutableState.value.tools.firstOrNull { it.available.slug == slug } ?: return
        val channel = mutableState.value.feed?.channel ?: return
        if (slug in mutableState.value.progress) return
        setProgress(slug, null)
        viewModelScope.launch {
            val message = installOne(channel, row)
            if (message != null) mutableState.update { it.copy(message = message) }
        }
    }

    fun updateAllTools() {
        val rows = mutableState.value.updatableTools.filter { it.available.slug !in mutableState.value.progress }
        val channel = mutableState.value.feed?.channel ?: return
        if (rows.isEmpty()) return
        rows.forEach { setProgress(it.available.slug, null) }
        viewModelScope.launch {
            val failures = rows.mapNotNull { row -> installOne(channel, row)?.let { "${row.name}：$it" } }
            mutableState.update {
                it.copy(message = if (failures.isEmpty()) "已更新 ${rows.size} 个小工具" else failures.joinToString("\n"))
            }
        }
    }

    /** Returns an error message, or null on success. */
    private suspend fun installOne(channel: UpdateChannel, row: ToolUpdateRow): String? = try {
        toolMutations.withLock {
            val slug = row.available.slug
            val file = downloadCurrent(channel, row.available.asset, slug) { feed ->
                feed.tools.firstOrNull { it.slug == slug && compareVersions(it.version, row.available.version) >= 0 }?.asset
            }
            setProgress(slug, null)
            try {
                when (val result = packages.importPackage(FilePackageInput(file))) {
                    is HostImportResult.Installed -> null
                    HostImportResult.Cancelled -> "已取消"
                    is HostImportResult.Failed -> result.message
                    is HostImportResult.ConfirmationRequired -> {
                        val confirmation = result.confirmation
                        if (confirmation.kind == HostImportConfirmationKind.UPDATE) {
                            when (val confirmed = packages.confirmImport(confirmation.id)) {
                                is HostImportResult.Installed -> null
                                is HostImportResult.Failed -> confirmed.message
                                else -> "更新未完成"
                            }
                        } else {
                            packages.cancelImport(confirmation.id)
                            "已安装版本不低于 ${confirmation.incomingVersionName}"
                        }
                    }
                }
            } finally {
                file.delete()
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        error.userMessage("更新失败，请重试。")
    } finally {
        clearProgress(row.available.slug)
    }

    /**
     * The test channel is republished on every merge to main, so an asset can be replaced while it
     * downloads. Then read [channel]'s feed again and download what it offers now, up to
     * [StaleRetryAttempts] feed reads in total. [pick] returns null while the replacement is not published yet.
     */
    private suspend fun downloadCurrent(
        channel: UpdateChannel,
        asset: ReleaseAsset,
        key: String,
        pick: (ChannelFeed) -> ReleaseAsset?,
    ): File {
        var current = asset
        var reads = 0
        while (true) {
            try {
                return repository.download(current) { setProgress(key, it) }
            } catch (stale: StaleAssetException) {
                var fresh: ReleaseAsset? = null
                while (fresh == null && reads < StaleRetryAttempts) {
                    if (reads > 0) delay(StaleRetryDelayMs)
                    reads++
                    val feed = repository.fetch(channel)
                    mutableState.update {
                        if (it.channel == channel) it.copy(feed = feed, tools = toolRows(feed, installedTools)) else it
                    }
                    // The same asset may be back after the swap (a rerun publishes identical bytes), so retry it too.
                    fresh = pick(feed)
                }
                current = fresh ?: throw stale
                setProgress(key, 0f)
            }
        }
    }

    private fun setProgress(key: String, value: Float?) = mutableState.update { it.copy(progress = it.progress + (key to value)) }

    private fun clearProgress(key: String) = mutableState.update { it.copy(progress = it.progress - key) }

    private fun Throwable.userMessage(fallback: String): String =
        (this as? UpdateCheckException)?.message ?: fallback
}
