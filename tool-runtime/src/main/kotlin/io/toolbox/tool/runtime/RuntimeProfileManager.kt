package io.toolbox.tool.runtime

import android.system.Os
import android.system.OsConstants
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.annotation.UiThread
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.IdentityHashMap
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

interface RuntimeDataCleaner {
    suspend fun <T> clearThenRun(
        toolId: String,
        action: suspend () -> T,
    ): RuntimeDataCleanupExecution<T>
}

sealed interface RuntimeDataCleanupExecution<out T> {
    data class Completed<T>(
        val cleanupResult: RuntimeDataCleanupResult,
        val value: T,
    ) : RuntimeDataCleanupExecution<T>

    data class Rejected(val reason: RuntimeDataCleanupResult) : RuntimeDataCleanupExecution<Nothing>
}

enum class RuntimeDataCleanupResult { Cleared, AlreadyAbsent, RecoveryDeferred, InUse, ProviderUnsupported, Failed }

enum class RuntimeIsolationMode { DEDICATED_PROFILE, ORIGIN_ONLY_STATELESS }

data class RuntimeProviderCapabilities(
    val multiProfile: Boolean,
    val deleteBrowsingData: Boolean,
    val documentStartScript: Boolean,
    val serviceWorkerBasicUsage: Boolean,
    val serviceWorkerShouldInterceptRequest: Boolean,
) {
    val preferredIsolationMode: RuntimeIsolationMode
        get() = if (multiProfile && deleteBrowsingData) {
            RuntimeIsolationMode.DEDICATED_PROFILE
        } else {
            RuntimeIsolationMode.ORIGIN_ONLY_STATELESS
        }
}

sealed interface RuntimeCreationPermitResult {
    data class Ready(val permit: RuntimeCreationPermit) : RuntimeCreationPermitResult
    data class Rejected(val reason: RuntimeDataCleanupResult) : RuntimeCreationPermitResult
}

fun interface RuntimePermitProvider {
    suspend fun acquireRuntimePermit(
        toolId: String,
        awaitExistingRuntimeRelease: Boolean,
    ): RuntimeCreationPermitResult
}

interface RuntimeCreationPermit {
    val isolationMode: RuntimeIsolationMode

    @UiThread
    fun attach(webView: WebView)

    @UiThread
    fun close()
}

internal class ManagedRuntimeCreationPermit(internal val toolId: String) : RuntimeCreationPermit {
    private var selectedIsolationMode: RuntimeIsolationMode? = null

    override val isolationMode: RuntimeIsolationMode
        get() = checkNotNull(selectedIsolationMode) { "Runtime isolation mode has not been selected" }

    internal fun bindIsolationMode(mode: RuntimeIsolationMode) {
        check(selectedIsolationMode == null) { "Runtime isolation mode is already selected" }
        selectedIsolationMode = mode
    }

    @UiThread
    override fun attach(webView: WebView) = RuntimeWebViewLifecycle.attach(this, webView)

    @UiThread
    override fun close() = RuntimeWebViewLifecycle.cancel(this)
}

class RuntimeProfileManager internal constructor(
    privateFilesDirectory: File,
    private val afterCleanupProofWritten: suspend () -> Unit,
    private val onRuntimeReleaseWait: suspend () -> Unit,
    private val capabilityOverride: RuntimeProviderCapabilities? = null,
    private val physicalDeleteOverride: ((String) -> PhysicalDeleteResult)? = null,
) : RuntimeDataCleaner, RuntimePermitProvider {
    constructor(privateFilesDirectory: File) : this(privateFilesDirectory, {}, {}, null, null)

    internal constructor(
        privateFilesDirectory: File,
        afterCleanupProofWritten: suspend () -> Unit,
    ) : this(privateFilesDirectory, afterCleanupProofWritten, {}, null, null)

    internal constructor(
        privateFilesDirectory: File,
        capabilities: RuntimeProviderCapabilities,
        physicalProfileDeletion: (String) -> PhysicalDeleteResult,
    ) : this(privateFilesDirectory, {}, {}, capabilities, physicalProfileDeletion)

    private val markerRoot = privateFilesDirectory.toPath().toAbsolutePath().normalize()
        .resolve(MARKER_DIRECTORY)
    private val modeRoot = privateFilesDirectory.toPath().toAbsolutePath().normalize()
        .resolve(MODE_DIRECTORY)

    suspend fun providerCapabilities(): RuntimeProviderCapabilities = capabilityOverride ?: withContext(Dispatchers.Main.immediate) {
        RuntimeProviderCapabilities(
            multiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
            deleteBrowsingData = WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA),
            documentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
            serviceWorkerBasicUsage = WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE),
            serviceWorkerShouldInterceptRequest = WebViewFeature.isFeatureSupported(
                WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST,
            ),
        )
    }

    override suspend fun acquireRuntimePermit(
        toolId: String,
        awaitExistingRuntimeRelease: Boolean,
    ): RuntimeCreationPermitResult {
        val permit = withContext(Dispatchers.Main.immediate) {
            if (awaitExistingRuntimeRelease) {
                RuntimeWebViewLifecycle.reserveAfterRuntimeRelease(toolId, onRuntimeReleaseWait)
            } else {
                RuntimeWebViewLifecycle.reserve(toolId)
            }
        } ?: return RuntimeCreationPermitResult.Rejected(RuntimeDataCleanupResult.InUse)
        var delivered = false
        try {
            val mode = when (val selection = selectIsolationMode(toolId)) {
                is IsolationModeSelection.Ready -> selection.mode
                is IsolationModeSelection.Rejected -> return RuntimeCreationPermitResult.Rejected(selection.reason)
            }
            permit.bindIsolationMode(mode)
            val markerConsumed = withContext(NonCancellable + Dispatchers.IO) {
                runCatching { consumeMarker(toolId) }.getOrDefault(false)
            }
            if (!markerConsumed) return RuntimeCreationPermitResult.Rejected(RuntimeDataCleanupResult.Failed)
            coroutineContext.ensureActive()
            delivered = true
            return RuntimeCreationPermitResult.Ready(permit)
        } finally {
            if (!delivered) {
                withContext(NonCancellable + Dispatchers.Main.immediate) { permit.close() }
            }
        }
    }

    override suspend fun <T> clearThenRun(
        toolId: String,
        action: suspend () -> T,
    ): RuntimeDataCleanupExecution<T> {
        val leaseAcquired = withContext(Dispatchers.Main.immediate) {
            RuntimeWebViewLifecycle.beginCleanup(toolId)
        }
        if (!leaseAcquired) return RuntimeDataCleanupExecution.Rejected(RuntimeDataCleanupResult.InUse)
        try {
            val recordedMode = withContext(Dispatchers.IO) { readModeRecord(toolId) }
            if (recordedMode is ModeRecordRead.Invalid) {
                return RuntimeDataCleanupExecution.Rejected(RuntimeDataCleanupResult.Failed)
            }
            val mode = (recordedMode as? ModeRecordRead.Valid)?.record?.mode
            val cleanupResult = when (mode) {
                null -> RuntimeDataCleanupResult.AlreadyAbsent
                RuntimeIsolationMode.ORIGIN_ONLY_STATELESS -> {
                    val removed = withContext(NonCancellable + Dispatchers.IO) { removeModeRecord(toolId) }
                    if (!removed) return RuntimeDataCleanupExecution.Rejected(RuntimeDataCleanupResult.Failed)
                    RuntimeDataCleanupResult.AlreadyAbsent
                }
                RuntimeIsolationMode.DEDICATED_PROFILE -> {
                    val capabilities = providerCapabilities()
                    if (!capabilities.multiProfile) {
                        return RuntimeDataCleanupExecution.Rejected(RuntimeDataCleanupResult.ProviderUnsupported)
                    }
                    val physical = withContext(Dispatchers.Main.immediate) { deleteProfile(toolId) }
                    val result = when (physical) {
                        PhysicalDeleteResult.Deleted -> RuntimeDataCleanupResult.Cleared
                        PhysicalDeleteResult.Absent -> RuntimeDataCleanupResult.AlreadyAbsent
                        is PhysicalDeleteResult.Loaded -> {
                            if (!capabilities.deleteBrowsingData) {
                                return RuntimeDataCleanupExecution.Rejected(RuntimeDataCleanupResult.ProviderUnsupported)
                            }
                            val cleared = withContext(NonCancellable + Dispatchers.Main.immediate) {
                                val profile = physical.profile ?: return@withContext false
                                awaitBrowsingDataDeletion(profile.webStorage)
                            }
                            if (!cleared) return RuntimeDataCleanupExecution.Rejected(RuntimeDataCleanupResult.Failed)
                            RuntimeDataCleanupResult.Cleared
                        }
                        PhysicalDeleteResult.Failed -> {
                            return RuntimeDataCleanupExecution.Rejected(RuntimeDataCleanupResult.Failed)
                        }
                    }
                    val markerWritten = withContext(NonCancellable + Dispatchers.IO) { writeMarker(toolId) }
                    if (!markerWritten) return RuntimeDataCleanupExecution.Rejected(RuntimeDataCleanupResult.Failed)
                    result
                }
            }
            afterCleanupProofWritten()
            coroutineContext.ensureActive()
            val value = action()
            return RuntimeDataCleanupExecution.Completed(cleanupResult, value)
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                RuntimeWebViewLifecycle.finishCleanup(toolId)
            }
        }
    }

    suspend fun reapMarkedOrphanProfiles(installedToolIds: Set<String>): RuntimeDataCleanupResult {
        val recoveryState = withContext(Dispatchers.IO) {
            val markers = readAllMarkers() ?: return@withContext null
            val records = readAllModeRecords() ?: return@withContext null
            RecoveryState(markers, records)
        } ?: return RuntimeDataCleanupResult.Failed
        val markers = recoveryState.markers
        val records = recoveryState.records
        if (markers.isEmpty() && records.isEmpty()) return RuntimeDataCleanupResult.AlreadyAbsent
        val orphanMarkers = markers.filter { it.toolId !in installedToolIds }
        val orphanRecords = records.filter { it.toolId !in installedToolIds }
        if (orphanMarkers.isEmpty() && orphanRecords.isEmpty()) return RuntimeDataCleanupResult.AlreadyAbsent
        val orphanToolIds = buildSet {
            orphanMarkers.forEach { add(it.toolId) }
            orphanRecords.forEach { add(it.toolId) }
        }
        val dedicatedToolIds = buildSet {
            orphanMarkers.forEach { add(it.toolId) }
            orphanRecords.filter { it.mode == RuntimeIsolationMode.DEDICATED_PROFILE }.forEach { add(it.toolId) }
        }
        val capabilities = providerCapabilities()
        if (dedicatedToolIds.isNotEmpty() && !capabilities.multiProfile) {
            return RuntimeDataCleanupResult.RecoveryDeferred
        }
        val cleanupLeases: List<String>? = withContext(Dispatchers.Main.immediate) {
            if (!RuntimeWebViewLifecycle.isQuiescent()) return@withContext null
            val acquired = mutableListOf<String>()
            for (toolId in orphanToolIds) {
                if (!RuntimeWebViewLifecycle.beginCleanup(toolId)) {
                    acquired.forEach(RuntimeWebViewLifecycle::finishCleanup)
                    return@withContext null
                }
                acquired += toolId
            }
            acquired
        }
        if (cleanupLeases == null) return RuntimeDataCleanupResult.Failed
        try {
            val deleted = withContext(Dispatchers.Main.immediate) {
                dedicatedToolIds.all { toolId ->
                    when (deleteProfile(toolId)) {
                        PhysicalDeleteResult.Deleted,
                        PhysicalDeleteResult.Absent,
                        -> true
                        is PhysicalDeleteResult.Loaded,
                        PhysicalDeleteResult.Failed,
                        -> false
                    }
                }
            }
            if (!deleted) return RuntimeDataCleanupResult.Failed
            val recoveryProofsRemoved = withContext(NonCancellable + Dispatchers.IO) {
                removeRecoveryProofs(orphanMarkers, orphanRecords)
            }
            return if (recoveryProofsRemoved) RuntimeDataCleanupResult.Cleared else RuntimeDataCleanupResult.Failed
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                cleanupLeases.forEach(RuntimeWebViewLifecycle::finishCleanup)
            }
        }
    }

    private suspend fun selectIsolationMode(toolId: String): IsolationModeSelection {
        val capabilities = providerCapabilities()
        val desiredMode = capabilities.preferredIsolationMode
        if (
            desiredMode == RuntimeIsolationMode.ORIGIN_ONLY_STATELESS &&
            (!capabilities.documentStartScript ||
                (capabilities.serviceWorkerBasicUsage && !capabilities.serviceWorkerShouldInterceptRequest))
        ) {
            return IsolationModeSelection.Rejected(RuntimeDataCleanupResult.ProviderUnsupported)
        }
        val recordedMode = withContext(Dispatchers.IO) { readModeRecord(toolId) }
        val priorMode = when (recordedMode) {
            ModeRecordRead.Absent -> null
            is ModeRecordRead.Valid -> recordedMode.record.mode
            ModeRecordRead.Invalid -> return IsolationModeSelection.Rejected(RuntimeDataCleanupResult.Failed)
        }
        if (
            priorMode == RuntimeIsolationMode.DEDICATED_PROFILE &&
            desiredMode == RuntimeIsolationMode.ORIGIN_ONLY_STATELESS
        ) {
            if (!capabilities.multiProfile) {
                return IsolationModeSelection.Rejected(RuntimeDataCleanupResult.ProviderUnsupported)
            }
            when (withContext(Dispatchers.Main.immediate) { deleteProfile(toolId) }) {
                PhysicalDeleteResult.Deleted,
                PhysicalDeleteResult.Absent,
                -> Unit
                is PhysicalDeleteResult.Loaded -> {
                    return IsolationModeSelection.Rejected(RuntimeDataCleanupResult.ProviderUnsupported)
                }
                PhysicalDeleteResult.Failed -> {
                    return IsolationModeSelection.Rejected(RuntimeDataCleanupResult.Failed)
                }
            }
        }
        if (priorMode != desiredMode) {
            val recorded = withContext(NonCancellable + Dispatchers.IO) {
                writeModeRecord(toolId, desiredMode)
            }
            if (!recorded) return IsolationModeSelection.Rejected(RuntimeDataCleanupResult.Failed)
        }
        return IsolationModeSelection.Ready(desiredMode)
    }

    @UiThread
    private fun deleteProfile(toolId: String): PhysicalDeleteResult =
        physicalDeleteOverride?.invoke(toolId) ?: tryPhysicalDelete(toolId)

    @UiThread
    private fun tryPhysicalDelete(toolId: String): PhysicalDeleteResult {
        val profileName = RuntimeIdentity.profileName(toolId)
        return try {
            val store = ProfileStore.getInstance()
            val present = profileName in store.allProfileNames
            when {
                !present -> PhysicalDeleteResult.Absent
                store.deleteProfile(profileName) -> PhysicalDeleteResult.Deleted
                else -> PhysicalDeleteResult.Failed
            }
        } catch (_: IllegalStateException) {
            try {
                PhysicalDeleteResult.Loaded(
                    requireNotNull(ProfileStore.getInstance().getProfile(profileName)) {
                        "The loaded WebView profile is unavailable"
                    },
                )
            } catch (_: RuntimeException) {
                PhysicalDeleteResult.Failed
            }
        } catch (_: RuntimeException) {
            PhysicalDeleteResult.Failed
        }
    }

    @UiThread
    private suspend fun awaitBrowsingDataDeletion(webStorage: WebStorage): Boolean =
        suspendCoroutine { continuation ->
            try {
                WebStorageCompat.deleteBrowsingData(webStorage) { continuation.resume(true) }
            } catch (_: RuntimeException) {
                continuation.resume(false)
            }
        }

    private fun writeMarker(toolId: String): Boolean {
        var temporary: Path? = null
        return try {
            ensureMarkerDirectory()
            val marker = CleanupMarker(toolId, RuntimeIdentity.profileName(toolId))
            val target = markerPath(marker.profileName)
            val temporaryPath = markerRoot.resolve(".${marker.profileName}.${UUID.randomUUID()}.tmp")
            temporary = temporaryPath
            FileOutputStream(temporaryPath.toFile()).use { output ->
                output.write(marker.encode().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporaryPath,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            fsyncDirectory(markerRoot)
            true
        } catch (_: Exception) {
            false
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun writeModeRecord(toolId: String, mode: RuntimeIsolationMode): Boolean {
        var temporary: Path? = null
        return try {
            ensureModeDirectory()
            val record = IsolationModeRecord(toolId, RuntimeIdentity.profileName(toolId), mode)
            val target = modePath(record.profileName)
            val temporaryPath = modeRoot.resolve(".${record.profileName}.${UUID.randomUUID()}.tmp")
            temporary = temporaryPath
            FileOutputStream(temporaryPath.toFile()).use { output ->
                output.write(record.encode().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporaryPath,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            fsyncDirectory(modeRoot)
            true
        } catch (_: Exception) {
            false
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun consumeMarker(toolId: String): Boolean {
        if (!Files.exists(markerRoot, LinkOption.NOFOLLOW_LINKS)) return true
        val rootAttributes = Files.readAttributes(
            markerRoot,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink) return false
        val profileName = RuntimeIdentity.profileName(toolId)
        val path = markerPath(profileName)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        val marker = readMarker(path) ?: return false
        if (marker.toolId != toolId || marker.profileName != profileName) return false
        Files.delete(path)
        fsyncDirectory(markerRoot)
        return true
    }

    private fun readAllMarkers(): List<CleanupMarker>? = runCatching {
        if (!Files.exists(markerRoot, LinkOption.NOFOLLOW_LINKS)) return@runCatching emptyList()
        val directoryAttributes = Files.readAttributes(
            markerRoot,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        check(directoryAttributes.isDirectory && !directoryAttributes.isSymbolicLink)
        Files.list(markerRoot).use { paths ->
            buildList {
                paths.forEach { path ->
                    if (path.fileName.toString().endsWith(MARKER_SUFFIX)) {
                        add(readMarker(path) ?: error("Invalid runtime profile cleanup marker"))
                    }
                }
            }
        }
    }.getOrNull()

    private fun readModeRecord(toolId: String): ModeRecordRead {
        if (!Files.exists(modeRoot, LinkOption.NOFOLLOW_LINKS)) return ModeRecordRead.Absent
        val rootAttributes = runCatching {
            Files.readAttributes(modeRoot, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrElse { return ModeRecordRead.Invalid }
        if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink) return ModeRecordRead.Invalid
        val path = modePath(RuntimeIdentity.profileName(toolId))
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return ModeRecordRead.Absent
        val record = readModeRecord(path) ?: return ModeRecordRead.Invalid
        return if (record.toolId == toolId) ModeRecordRead.Valid(record) else ModeRecordRead.Invalid
    }

    private fun readAllModeRecords(): List<IsolationModeRecord>? = runCatching {
        if (!Files.exists(modeRoot, LinkOption.NOFOLLOW_LINKS)) return@runCatching emptyList()
        val directoryAttributes = Files.readAttributes(
            modeRoot,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        check(directoryAttributes.isDirectory && !directoryAttributes.isSymbolicLink)
        Files.list(modeRoot).use { paths ->
            buildList {
                paths.forEach { path ->
                    if (path.fileName.toString().endsWith(MODE_SUFFIX)) {
                        add(readModeRecord(path) ?: error("Invalid runtime isolation mode record"))
                    }
                }
            }
        }
    }.getOrNull()

    private fun readModeRecord(path: Path): IsolationModeRecord? = runCatching {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() !in 1..MAX_MODE_BYTES) {
            return@runCatching null
        }
        val lines = Files.readAllLines(path, Charsets.UTF_8)
        if (lines.size != 4 || lines[0] != "version=1") return@runCatching null
        val toolId = lines[1].removePrefix("toolId=").takeIf { lines[1] == "toolId=$it" }
            ?: return@runCatching null
        val profileName = lines[2].removePrefix("profile=").takeIf { lines[2] == "profile=$it" }
            ?: return@runCatching null
        val modeName = lines[3].removePrefix("mode=").takeIf { lines[3] == "mode=$it" }
            ?: return@runCatching null
        val mode = RuntimeIsolationMode.entries.singleOrNull { it.name == modeName }
            ?: return@runCatching null
        if (!TOOL_ID_PATTERN.matches(toolId) || toolId.length > 120) return@runCatching null
        if (!PROFILE_PATTERN.matches(profileName) || profileName != RuntimeIdentity.profileName(toolId)) {
            return@runCatching null
        }
        if (path.fileName.toString() != "$profileName$MODE_SUFFIX") return@runCatching null
        IsolationModeRecord(toolId, profileName, mode)
    }.getOrNull()

    private fun readMarker(path: Path): CleanupMarker? = runCatching {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() !in 1..MAX_MARKER_BYTES) {
            return@runCatching null
        }
        val lines = Files.readAllLines(path, Charsets.UTF_8)
        if (lines.size != 4 || lines[0] != "version=1" || lines[3] != "status=$MARKER_STATUS") {
            return@runCatching null
        }
        val toolId = lines[1].removePrefix("toolId=").takeIf { lines[1] == "toolId=$it" }
            ?: return@runCatching null
        val profileName = lines[2].removePrefix("profile=").takeIf { lines[2] == "profile=$it" }
            ?: return@runCatching null
        if (!TOOL_ID_PATTERN.matches(toolId) || toolId.length > 120) return@runCatching null
        if (!PROFILE_PATTERN.matches(profileName) || profileName != RuntimeIdentity.profileName(toolId)) {
            return@runCatching null
        }
        if (path.fileName.toString() != "$profileName$MARKER_SUFFIX") return@runCatching null
        CleanupMarker(toolId, profileName)
    }.getOrNull()

    private fun removeRecoveryProofs(
        markers: List<CleanupMarker>,
        records: List<IsolationModeRecord>,
    ): Boolean = runCatching {
        if (markers.isNotEmpty()) {
            markers.forEach { Files.delete(markerPath(it.profileName)) }
            fsyncDirectory(markerRoot)
        }
        if (records.isNotEmpty()) {
            records.forEach { Files.delete(modePath(it.profileName)) }
            fsyncDirectory(modeRoot)
        }
    }.isSuccess

    private fun removeModeRecord(toolId: String): Boolean = runCatching {
        if (!Files.exists(modeRoot, LinkOption.NOFOLLOW_LINKS)) return@runCatching
        val path = modePath(RuntimeIdentity.profileName(toolId))
        if (Files.deleteIfExists(path)) fsyncDirectory(modeRoot)
    }.isSuccess

    private fun ensureMarkerDirectory() {
        Files.createDirectories(markerRoot)
        val attributes = Files.readAttributes(
            markerRoot,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        check(attributes.isDirectory && !attributes.isSymbolicLink)
    }

    private fun ensureModeDirectory() {
        Files.createDirectories(modeRoot)
        val attributes = Files.readAttributes(
            modeRoot,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        check(attributes.isDirectory && !attributes.isSymbolicLink)
    }

    private fun markerPath(profileName: String): Path = markerRoot.resolve("$profileName$MARKER_SUFFIX")

    private fun modePath(profileName: String): Path = modeRoot.resolve("$profileName$MODE_SUFFIX")

    private fun fsyncDirectory(directory: Path) {
        val descriptor = Os.open(
            directory.toString(),
            OsConstants.O_RDONLY,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private data class CleanupMarker(val toolId: String, val profileName: String) {
        fun encode(): String = buildString {
            appendLine("version=1")
            appendLine("toolId=$toolId")
            appendLine("profile=$profileName")
            appendLine("status=$MARKER_STATUS")
        }
    }

    private data class IsolationModeRecord(
        val toolId: String,
        val profileName: String,
        val mode: RuntimeIsolationMode,
    ) {
        fun encode(): String = buildString {
            appendLine("version=1")
            appendLine("toolId=$toolId")
            appendLine("profile=$profileName")
            appendLine("mode=${mode.name}")
        }
    }

    private sealed interface ModeRecordRead {
        data object Absent : ModeRecordRead
        data class Valid(val record: IsolationModeRecord) : ModeRecordRead
        data object Invalid : ModeRecordRead
    }

    private sealed interface IsolationModeSelection {
        data class Ready(val mode: RuntimeIsolationMode) : IsolationModeSelection
        data class Rejected(val reason: RuntimeDataCleanupResult) : IsolationModeSelection
    }

    private data class RecoveryState(
        val markers: List<CleanupMarker>,
        val records: List<IsolationModeRecord>,
    )

    internal sealed interface PhysicalDeleteResult {
        data object Deleted : PhysicalDeleteResult
        data object Absent : PhysicalDeleteResult
        data class Loaded(val profile: Profile? = null) : PhysicalDeleteResult
        data object Failed : PhysicalDeleteResult
    }

    private companion object {
        const val MARKER_DIRECTORY = "runtime-profile-cleanup"
        const val MARKER_SUFFIX = ".pending"
        const val MARKER_STATUS = "CONTENT_CLEARED_PENDING_PROFILE_DELETE"
        const val MODE_DIRECTORY = "runtime-isolation-mode"
        const val MODE_SUFFIX = ".mode"
        const val MAX_MARKER_BYTES = 512L
        const val MAX_MODE_BYTES = 512L
        val TOOL_ID_PATTERN = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*){2,}$")
        val PROFILE_PATTERN = Regex("^tbx_[0-9a-f]{24}$")
    }
}

object RuntimeWebViewLifecycle {
    private val toolByWebView = IdentityHashMap<WebView, String>()
    private val destroyedWebViews = Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    private val reservations = IdentityHashMap<ManagedRuntimeCreationPermit, String>()
    private val clearingToolIds = hashSetOf<String>()
    private val lifecycleGeneration = MutableStateFlow(0L)

    @UiThread
    internal fun reserve(toolId: String): ManagedRuntimeCreationPermit? {
        if (
            toolId in clearingToolIds ||
            reservations.containsValue(toolId) ||
            toolByWebView.containsValue(toolId)
        ) {
            return null
        }
        return ManagedRuntimeCreationPermit(toolId).also { reservations[it] = toolId }
    }

    @UiThread
    internal fun attach(permit: ManagedRuntimeCreationPermit, webView: WebView) {
        val toolId = reservations.remove(permit)
        check(toolId == permit.toolId && toolId !in clearingToolIds) { "Runtime creation permit is invalid" }
        toolByWebView[webView] = toolId
    }

    @UiThread
    internal fun cancel(permit: ManagedRuntimeCreationPermit) {
        if (reservations.remove(permit) != null) notifyLifecycleChanged()
    }

    @UiThread
    fun destroyAndUnregister(webView: WebView) {
        if (!destroyedWebViews.add(webView)) return
        if (toolByWebView.remove(webView) != null) notifyLifecycleChanged()
        runCatching { webView.stopLoading() }
        runCatching { webView.destroy() }
    }

    @UiThread
    internal fun beginCleanup(toolId: String): Boolean {
        if (
            toolByWebView.containsValue(toolId) ||
            reservations.containsValue(toolId) ||
            !clearingToolIds.add(toolId)
        ) {
            return false
        }
        return true
    }

    @UiThread
    internal fun finishCleanup(toolId: String) {
        if (clearingToolIds.remove(toolId)) notifyLifecycleChanged()
    }

    @UiThread
    internal suspend fun reserveAfterRuntimeRelease(
        toolId: String,
        onWaiting: suspend () -> Unit,
    ): ManagedRuntimeCreationPermit? {
        while (true) {
            if (toolId in clearingToolIds) return null
            reserve(toolId)?.let { return it }
            val observedGeneration = lifecycleGeneration.value
            if (
                toolId !in clearingToolIds &&
                !reservations.containsValue(toolId) &&
                !toolByWebView.containsValue(toolId)
            ) {
                continue
            }
            onWaiting()
            lifecycleGeneration.first { it != observedGeneration }
        }
    }

    @UiThread
    internal fun isQuiescent(): Boolean =
        toolByWebView.isEmpty() && reservations.isEmpty() && clearingToolIds.isEmpty()

    private fun notifyLifecycleChanged() {
        lifecycleGeneration.value += 1L
    }
}
