package io.toolbox.host.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.widget.Toast
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.host.BuildConfig
import io.toolbox.host.HostInstalledManifestReader
import io.toolbox.host.HostInstalledManifestResult
import io.toolbox.host.HostRuntimeM2HandlerFactory
import io.toolbox.tool.runtime.RuntimeSessionCleanupHandler
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxApiV1
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.runtime.AndroidRuntimeSystemPermissionChecker
import io.toolbox.tool.runtime.DefaultRuntimeAuthorizationPolicy
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeBasicDeviceInfo
import io.toolbox.tool.runtime.RuntimeBridgeConfiguration
import io.toolbox.tool.runtime.RuntimeBridgeProvider
import io.toolbox.tool.runtime.RuntimeClipboardWriteHandler
import io.toolbox.tool.runtime.RuntimeDeviceBasicHandler
import io.toolbox.tool.runtime.RuntimeGrantStateSource
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeHapticsHandler
import io.toolbox.tool.runtime.RuntimeM1Handlers
import io.toolbox.tool.runtime.RuntimeM2Handlers
import io.toolbox.tool.runtime.RuntimePolicyDecision
import io.toolbox.tool.runtime.RuntimeQuotaChecker
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import io.toolbox.tool.runtime.RuntimeSessionIdentity
import io.toolbox.tool.runtime.RuntimeBatchStorageHandler
import io.toolbox.tool.runtime.RuntimeStorageMutation
import io.toolbox.tool.runtime.RuntimeStorageSet
import io.toolbox.tool.runtime.RuntimeStorageHandler
import io.toolbox.tool.runtime.RuntimeToastHandler
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

internal class HostRuntimeBridgeProvider(
    context: Context,
    repositories: CoreDataRepositories,
    private val m2HandlerFactory: HostRuntimeM2HandlerFactory,
    private val continuityHandlerFactory: (PreparedToolRuntime) -> HostRuntimeContinuityHandlers,
    private val hostVersion: String = BuildConfig.VERSION_NAME,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RuntimeBridgeProvider {
    private val applicationContext = context.applicationContext
    private val grantState = RepositoryRuntimeGrantStateSource(repositories.catalog, repositories.grants)
    private val systemPermissions = AndroidRuntimeSystemPermissionChecker(applicationContext)
    private val keyValues = repositories.keyValues
    private val networkGrants = repositories.grants
    private val installedManifests = HostInstalledManifestReader(applicationContext.filesDir, repositories.catalog)

    override fun create(runtime: PreparedToolRuntime): RuntimeBridgeConfiguration {
        val continuity = continuityHandlerFactory(runtime)
        val m2Handlers = m2HandlerFactory.createHandlers(runtime).copy(
            continuousBackground = continuity.background,
            alarms = continuity.alarms,
        )
        val foregroundHandlers = ForegroundCapabilityBroker.activeHandlers(
            context = applicationContext,
            toolId = runtime.toolId,
            toolName = runtime.installedManifest.name,
            authorizeBrowserLaunch = {
                val current = (installedManifests.read(runtime.toolId) as? HostInstalledManifestResult.Found)?.manifest
                if (current?.versionCode != runtime.versionCode) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
                }
                if (current.permissions.none { it.capability == "browser" }) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_DECLARED, "Browser permission is not declared by this tool")
                }
                if (!grantState.isGranted(runtime.toolId, ToolBoxCapabilityId.BROWSER)) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Enable browser access in this tool's permissions")
                }
            },
        )
        val networkDomains = HostNetworkDomainHandler {
            val current = (installedManifests.read(runtime.toolId) as? HostInstalledManifestResult.Found)?.manifest
            val grant = networkGrants.observeGrants(runtime.toolId).first().firstOrNull { it.capability == "network" }
            if (current?.versionCode != runtime.versionCode ||
                current.permissions.none { it.capability == "network" } || grant?.granted != true) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "工具版本、网络声明或权限已变更。")
            }
            grant.updatedAt
        }
        val m3Handlers = foregroundHandlers.copy(
            locationWatch = continuity.locationWatch,
            networkDomains = networkDomains,
            sessionCleanup = RuntimeSessionCleanupHandler {
                networkDomains.close()
                foregroundHandlers.sessionCleanup?.close()
            },
        )
        val authorization = DefaultRuntimeAuthorizationPolicy(
            state = grantState,
            systemPermissions = systemPermissions,
            quota = HostRuntimeQuotaChecker(runtime.maxBridgePayloadBytes),
            clockMillis = SystemClock::elapsedRealtime,
        )
        return RuntimeBridgeConfiguration(
            authorization = authorization,
            handlers = createM1Handlers(runtime),
            m2Handlers = m2Handlers,
            m3Handlers = m3Handlers,
            hostVersion = hostVersion,
            generation = "${runtime.toolId}:${runtime.versionCode}:${UUID.randomUUID()}",
            maxPayloadBytes = runtime.maxBridgePayloadBytes,
            browserLaunchGuard = continuity.requireForegroundRuntime,
        )
    }

    private fun createM1Handlers(runtime: PreparedToolRuntime): RuntimeM1Handlers = RuntimeM1Handlers(
        toast = AndroidToastHandler(applicationContext),
        storage = StandardToolKvStorageHandler(
            toolId = runtime.toolId,
            repository = keyValues,
            nowMillis = nowMillis,
            canAccess = {
                grantState.currentVersionCode(runtime.toolId) == runtime.versionCode &&
                    grantState.isGranted(runtime.toolId, ToolBoxCapabilityId.STORAGE)
            },
            maxBatchResponseBytes = runtime.maxBridgePayloadBytes,
        ),
        secureStorage = AndroidKeyStoreCipher.isAvailable().takeIf { it }?.let {
            createRuntimeSecureStorageHandler(
                toolId = runtime.toolId,
                repository = keyValues,
                nowMillis = nowMillis,
                canAccess = { grantState.isGranted(runtime.toolId, ToolBoxCapabilityId.STORAGE_SECURE) },
            )
        },
        deviceBasic = AndroidBasicDeviceHandler(applicationContext),
        haptics = AndroidHapticsHandler(applicationContext),
        clipboardWrite = AndroidClipboardWriteHandler(applicationContext),
    )
}

internal class RepositoryRuntimeGrantStateSource(
    private val catalog: CatalogRepository,
    private val grants: PermissionGrantRepository,
) : RuntimeGrantStateSource {
    override suspend fun currentVersionCode(toolId: String): Int? =
        catalog.observeTool(toolId).first()?.currentVersion?.versionCode

    override suspend fun isGranted(toolId: String, capability: ToolBoxCapabilityId): Boolean =
        grants.observeGrants(toolId)
            .first()
            .firstOrNull { it.capability == ToolBoxApiV1.capability(capability).wireName }
            ?.granted == true
}

/** Revocation denies queued callers and drains the already admitted ordinary operation. */
internal suspend fun awaitRuntimeStandardStorageIdle(toolId: String) {
    ToolRuntimeStorageLocks.mutexFor(toolId, ToolStorageNamespace.Standard).withLock { }
}

internal suspend fun clearRuntimeSecureStorage(
    toolId: String,
    repository: ToolKvRepository,
    deleteKey: () -> Boolean = { AndroidKeyStoreCipher.deleteForTool(toolId) },
): Boolean = withContext(Dispatchers.IO) {
    ToolRuntimeStorageLocks.mutexFor(toolId, ToolStorageNamespace.Secure).withLock {
        try {
            when (RuntimeSecureEnvelopeStorage(toolId, repository).clear()) {
                is DataResult.Success,
                is DataResult.Failure.NotFound,
                -> Unit

                is DataResult.Failure -> error("secure storage cleanup failed")
            }
            check(deleteKey())
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}

private class HostRuntimeQuotaChecker(
    private val maxRpcBytes: Int,
) : RuntimeQuotaChecker {
    override suspend fun admit(
        identity: RuntimeSessionIdentity,
        method: MethodDescriptor,
        encodedBytes: Int,
    ): RuntimePolicyDecision = if (encodedBytes <= maxRpcBytes) {
        RuntimePolicyDecision.Allowed
    } else {
        RuntimePolicyDecision.Denied(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "ToolBox request is too large")
    }
}

private class AndroidToastHandler(
    private val context: Context,
) : RuntimeToastHandler {
    override suspend fun show(message: String) {
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

private class AndroidBasicDeviceHandler(
    private val context: Context,
) : RuntimeDeviceBasicHandler {
    override suspend fun getBasicInfo(): RuntimeBasicDeviceInfo {
        val configuration = context.resources.configuration
        val screenWidth = configuration.smallestScreenWidthDp
        val screenClass = when {
            screenWidth >= 840 -> "expanded"
            screenWidth >= 600 -> "medium"
            else -> "compact"
        }
        return RuntimeBasicDeviceInfo(
            apiLevel = Build.VERSION.SDK_INT,
            locale = configuration.locales[0].toLanguageTag(),
            timeZone = TimeZone.getDefault().id,
            screenClass = screenClass,
        )
    }
}

private class AndroidHapticsHandler(
    private val context: Context,
) : RuntimeHapticsHandler {
    override suspend fun perform(effect: String) {
        val effectId = when (effect) {
            "click" -> VibrationEffect.EFFECT_CLICK
            "confirm" -> VibrationEffect.EFFECT_DOUBLE_CLICK
            "reject" -> VibrationEffect.EFFECT_HEAVY_CLICK
            else -> throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "Unsupported haptic effect")
        }
        withContext(Dispatchers.Main.immediate) {
            context.getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?.vibrate(VibrationEffect.createPredefined(effectId))
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Haptics are unavailable")
        }
    }
}

private class AndroidClipboardWriteHandler(
    private val context: Context,
) : RuntimeClipboardWriteHandler {
    override suspend fun writeText(text: String) {
        withContext(Dispatchers.Main.immediate) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Clipboard is unavailable")
            clipboard.setPrimaryClip(ClipData.newPlainText("ToolBox", text))
        }
    }
}

internal enum class ToolStorageNamespace(val documentKey: String) {
    Standard("toolbox.runtime.v1.standard.values"),
    Secure("toolbox.runtime.v1.secure.values"),
}

/** Ordinary values use bounded rows; secure values keep their encrypted document. */
internal class StandardToolKvStorageHandler(
    private val toolId: String,
    private val repository: ToolKvRepository,
    private val nowMillis: () -> Long,
    private val canAccess: suspend () -> Boolean = { true },
    private val maxBatchResponseBytes: Int = 8 * 1024 * 1024,
) : RuntimeBatchStorageHandler {
    override suspend fun get(key: String): RpcValue? = withAccess {
        val physicalKey = physicalKey(key)
        readValue(physicalKey) ?: loadLegacyDocument()?.get(key)
    }

    override suspend fun getMany(keys: List<String>): List<RpcValue?> = withAccess {
        if (keys.size > MAX_BATCH_KEYS) invalidBatch()
        val requested = keys.map { it to physicalKey(it) }
        repository.readSnapshot(toolId) { snapshot ->
            val decoded = mutableMapOf<String, Pair<RpcValue?, Int>>()
            val result = mutableListOf<RpcValue?>()
            var resultBytes = 2L // Array delimiters; the bridge checks the full envelope too.
            var legacyLoaded = false
            var legacy: Map<String, RpcValue>? = null
            // Bound temporary row materialization even if every requested value is large.
            for (batch in requested.chunked(READ_WINDOW_KEYS)) {
                val needed = batch.map { it.second }.toSet() - decoded.keys
                val headers = if (needed.isEmpty()) emptyMap() else snapshot.getMany(needed)
                for ((logical, physical) in batch) {
                    val (value, bytes) = decoded[physical] ?: run {
                        val record = headers[physical]?.valueJson?.let { RuntimeValueJson.decodeObject(it) ?: unreadable() }
                        val value = when {
                            record == null -> {
                                if (!legacyLoaded) {
                                    val rows = snapshot.getMany(setOf(ToolStorageNamespace.Standard.documentKey))
                                    legacy = rows[ToolStorageNamespace.Standard.documentKey]?.valueJson?.let(::decodeLegacyDocument)
                                    legacyLoaded = true
                                }
                                legacy?.get(logical)
                            }
                            record.keys == setOf("value") -> record.getValue("value")
                            else -> {
                                val count = chunkCount(record)
                                if (count > maxBatchResponseBytes / (CHUNK_CHARS - 1) + 1) batchResponseTooLarge()
                                var valueBytes = 0L
                                val encoded = buildString {
                                    for (parts in (0 until count).toList().chunked(READ_WINDOW_KEYS)) {
                                        val chunks = snapshot.getMany(parts.map { "$physical.$it" }.toSet())
                                        for (part in parts) {
                                            val row = chunks["$physical.$part"] ?: unreadable()
                                            val text = (RuntimeValueJson.decode(row.valueJson) as? RpcValue.StringValue)?.value ?: unreadable()
                                            if (text.length > CHUNK_CHARS) unreadable()
                                            valueBytes += text.toByteArray(Charsets.UTF_8).size
                                            if (resultBytes + valueBytes > maxBatchResponseBytes) batchResponseTooLarge()
                                            append(text)
                                        }
                                    }
                                }
                                RuntimeValueJson.decode(encoded) ?: unreadable()
                            }
                        }
                        val bytes = RuntimeValueJson.encode(value ?: RpcValue.Null).toByteArray(Charsets.UTF_8).size
                        (value to bytes).also { decoded[physical] = it }
                    }
                    resultBytes += bytes + if (result.isEmpty()) 0 else 1
                    if (resultBytes > maxBatchResponseBytes) batchResponseTooLarge()
                    result += value
                }
            }
            result
        }
    }

    override suspend fun set(key: String, value: RpcValue) = apply(RuntimeStorageMutation(set = listOf(RuntimeStorageSet(key, value))))

    override suspend fun remove(key: String) = apply(RuntimeStorageMutation(remove = listOf(key)))

    override suspend fun apply(mutation: RuntimeStorageMutation) = withAccess {
        val changedKeys = mutation.set.map { it.key } + mutation.remove
        if (changedKeys.size > MAX_BATCH_KEYS || changedKeys.distinct().size != changedKeys.size) invalidBatch()
        val changedPhysicalKeys = changedKeys.map(::physicalKey).toSet()
        // Encode every requested value before reading or mutating persisted rows.
        val rows = linkedMapOf<String, String>()
        for ((key, value) in mutation.set) rows.putAll(encodeRows(physicalKey(key), value))
        if (changedKeys.isEmpty()) return@withAccess
        val removeRows = repository.readSnapshot(toolId) { snapshot ->
            val existing = snapshot.getMany(changedPhysicalKeys + ToolStorageNamespace.Standard.documentKey)
            val legacyRow = existing[ToolStorageNamespace.Standard.documentKey]
            val legacy = legacyRow?.valueJson?.let(::decodeLegacyDocument).orEmpty()
            val headers = existing + snapshot.getMany(legacy.keys.map(::physicalKey).toSet() - existing.keys)
            val removed = linkedSetOf<String>()
            for (key in changedPhysicalKeys) {
                val record = headers[key]?.valueJson?.let { RuntimeValueJson.decodeObject(it) ?: unreadable() } ?: continue
                removed += key
                if (record.keys != setOf("value")) repeat(chunkCount(record)) { removed += "$key.$it" }
            }
            if (legacyRow != null) {
                // Existing v2 rows win over legacy values, matching get(). Mutations and
                // migration share one commit, so failure leaves the legacy document intact.
                for ((key, value) in legacy) {
                    val physical = physicalKey(key)
                    if (physical !in changedPhysicalKeys && physical !in headers) rows.putAll(encodeRows(physical, value))
                }
                removed += ToolStorageNamespace.Standard.documentKey
            }
            removed
        }
        if (!canAccess()) throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Storage permission is disabled")
        checkResult(repository.replace(toolId, removeRows, rows, nowMillis()))
    }

    override suspend fun keys(): List<String> = withAccess {
        val rows = repository.keys(toolId).filter { it.startsWith(ROW_PREFIX) && '.' !in it.removePrefix(ROW_PREFIX) }
            .map { decodeKey(it.removePrefix(ROW_PREFIX)) }
        (rows + loadLegacyDocument().orEmpty().keys).distinct()
    }

    override suspend fun clear() = withAccess {
        val rows = repository.keys(toolId).filterTo(linkedSetOf()) {
            it.startsWith(ROW_PREFIX) || it == ToolStorageNamespace.Standard.documentKey
        }
        if (!canAccess()) throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Storage permission is disabled")
        checkResult(repository.replace(toolId, rows, emptyMap(), nowMillis()))
    }

    private suspend fun <T> withAccess(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        withRuntimeStorageAccess(toolId, ToolStorageNamespace.Standard, canAccess, action)
    }

    private suspend fun loadLegacyDocument(): Map<String, RpcValue>? {
        val encoded = repository.observe(toolId, ToolStorageNamespace.Standard.documentKey).first()?.valueJson ?: return null
        return decodeLegacyDocument(encoded)
    }

    private fun decodeLegacyDocument(encoded: String): Map<String, RpcValue> {
        val document = RuntimeValueJson.decodeObject(encoded) ?: unreadable()
        for (key in document.keys) physicalKey(key)
        return document
    }

    private suspend fun readRecord(key: String): Map<String, RpcValue>? {
        val encoded = repository.observe(toolId, key).first()?.valueJson ?: return null
        return RuntimeValueJson.decodeObject(encoded) ?: unreadable()
    }

    private suspend fun readValue(key: String): RpcValue? {
        val record = readRecord(key) ?: return null
        if (record.keys == setOf("value")) return record.getValue("value")
        val count = chunkCount(record)
        val encoded = buildString {
            repeat(count) { index ->
                val chunk = repository.observe(toolId, "$key.$index").first()?.valueJson ?: unreadable()
                val text = (RuntimeValueJson.decode(chunk) as? RpcValue.StringValue)?.value ?: unreadable()
                if (text.length > CHUNK_CHARS) unreadable()
                append(text)
            }
        }
        return RuntimeValueJson.decode(encoded) ?: unreadable()
    }

    private fun chunkCount(record: Map<String, RpcValue>): Int {
        val count = (record["chunks"] as? RpcValue.Number)?.value ?: unreadable()
        if (record.keys != setOf("chunks") || count < 1 || count > Int.MAX_VALUE || !count.isFinite() || count != count.toInt().toDouble()) unreadable()
        return count.toInt()
    }

    private fun encodeRows(key: String, value: RpcValue): Map<String, String> {
        val encoded = RuntimeValueJson.encode(value)
        if (encoded.length <= CHUNK_CHARS) return mapOf(key to RuntimeValueJson.encodeObject(mapOf("value" to value)))
        val rows = linkedMapOf<String, String>()
        var offset = 0
        var index = 0
        while (offset < encoded.length) {
            var end = offset + minOf(CHUNK_CHARS, encoded.length - offset)
            if (end < encoded.length && encoded[end - 1].isHighSurrogate() && encoded[end].isLowSurrogate()) end--
            rows["$key.${index++}"] = RuntimeValueJson.encode(RpcValue.StringValue(encoded.substring(offset, end)))
            offset = end
        }
        rows[key] = RuntimeValueJson.encodeObject(mapOf("chunks" to RpcValue.Number(index.toDouble())))
        return rows
    }

    private fun physicalKey(key: String): String {
        if (key.isBlank() || key.length > 128 || key.any(Char::isISOControl)) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "Invalid storage key")
        }
        // UTF-16 code units preserve every accepted JS key, including lone surrogates.
        return ROW_PREFIX + key.map { it.code.toString(16).padStart(4, '0') }.joinToString("")
    }

    private fun decodeKey(encoded: String): String {
        if (encoded.isEmpty() || encoded.length % 4 != 0 || encoded.length > 512) unreadable()
        val key = encoded.chunked(4).map { (it.toIntOrNull(16) ?: unreadable()).toChar() }.joinToString("")
        if (physicalKey(key) != ROW_PREFIX + encoded) unreadable()
        return key
    }

    private fun checkResult(result: DataResult<Unit>) {
        when (result) {
            is DataResult.Success -> Unit
            is DataResult.Failure.NotFound -> throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Tool was removed")
            is DataResult.Failure -> throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "无法写入工具数据，请检查手机剩余空间后重试")
        }
    }

    private fun batchResponseTooLarge(): Nothing = throw RuntimeHandlerException(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Storage batch response is too large; request fewer keys")

    private fun invalidBatch(): Nothing = throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "Storage batches allow at most 256 keys, without duplicate mutations")

    private fun unreadable(): Nothing = throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "Stored values cannot be read")

    private companion object {
        const val READ_WINDOW_KEYS = 16
        const val MAX_BATCH_KEYS = 256
        const val ROW_PREFIX = "toolbox.runtime.v2.standard.key."
        // Even worst-case JSON escaping stays below a 2 MiB Android CursorWindow.
        const val CHUNK_CHARS = 128 * 1024
    }
}

internal fun createRuntimeSecureStorageHandler(
    toolId: String,
    repository: ToolKvRepository,
    nowMillis: () -> Long,
    canAccess: suspend () -> Boolean,
): RuntimeStorageHandler = JsonToolKvStorageHandler(
    toolId, repository, ToolStorageNamespace.Secure, nowMillis, AndroidKeyStoreCipher(toolId), canAccess,
)

/** Stores only the encrypted envelope. Callers hold the secure namespace lock. */
internal class RuntimeSecureEnvelopeStorage(
    private val toolId: String,
    private val repository: ToolKvRepository,
) {
    suspend fun read(): String? {
        val encoded = repository.observe(toolId, ROOT_KEY).first()?.valueJson ?: return null
        val header = RuntimeValueJson.decodeObject(encoded) ?: unreadable()
        // Existing AES-GCM envelopes stay readable until the next successful write.
        if (header.keys == setOf("v", "iv", "ciphertext")) return encoded
        if (header.keys != setOf("format", "v", "chunks", "length") ||
            header["format"] != RpcValue.StringValue(FORMAT) || header["v"] != RpcValue.Number(1.0)) unreadable()
        val length = positiveInt(header["length"])
        val count = positiveInt(header["chunks"])
        if (count != (length - 1) / CHUNK_CHARS + 1) unreadable()
        return buildString {
            repeat(count) { index ->
                val chunk = repository.observe(toolId, "$CHUNK_PREFIX$index").first()?.valueJson ?: unreadable()
                val expected = if (index == count - 1) length - index * CHUNK_CHARS else CHUNK_CHARS
                if (chunk.length != expected) unreadable()
                append(chunk)
            }
        }
    }

    suspend fun write(encryptedEnvelope: String, updatedAt: Long): DataResult<Unit> {
        require(encryptedEnvelope.isNotEmpty())
        val chunks = encryptedEnvelope.chunked(CHUNK_CHARS)
        val rows = linkedMapOf(
            ROOT_KEY to RuntimeValueJson.encodeObject(mapOf(
                "format" to RpcValue.StringValue(FORMAT),
                "v" to RpcValue.Number(1.0),
                "chunks" to RpcValue.Number(chunks.size.toDouble()),
                "length" to RpcValue.Number(encryptedEnvelope.length.toDouble()),
            )),
        )
        chunks.forEachIndexed { index, chunk -> rows["$CHUNK_PREFIX$index"] = chunk }
        return repository.replace(toolId, physicalRows(), rows, updatedAt)
    }

    suspend fun clear(): DataResult<Unit> = repository.replace(toolId, physicalRows(), emptyMap(), 0)

    private suspend fun physicalRows(): Set<String> = repository.keys(toolId).filterTo(linkedSetOf(ROOT_KEY)) {
        it.startsWith(CHUNK_PREFIX)
    }

    private fun positiveInt(value: RpcValue?): Int {
        val number = (value as? RpcValue.Number)?.value ?: unreadable()
        if (!number.isFinite() || number < 1 || number > Int.MAX_VALUE || number != number.toInt().toDouble()) unreadable()
        return number.toInt()
    }

    private fun unreadable(): Nothing = throw RuntimeHandlerException(
        RuntimeRpcErrorCode.INTERNAL_ERROR, "Secure storage data cannot be read",
    )

    private companion object {
        val ROOT_KEY = ToolStorageNamespace.Secure.documentKey
        val CHUNK_PREFIX = "$ROOT_KEY.chunk."
        const val FORMAT = "toolbox.secure.chunks"
        const val CHUNK_CHARS = 128 * 1024
    }
}

private class JsonToolKvStorageHandler(
    private val toolId: String,
    private val repository: ToolKvRepository,
    private val namespace: ToolStorageNamespace,
    private val nowMillis: () -> Long,
    private val cipher: AndroidKeyStoreCipher,
    private val canAccess: suspend () -> Boolean = { true },
) : RuntimeStorageHandler {
    private val encryptedStorage = RuntimeSecureEnvelopeStorage(toolId, repository)
    override suspend fun get(key: String): RpcValue? = withAccess {
        validateLogicalKey(key)
        loadDocument()[key]
    }

    override suspend fun set(key: String, value: RpcValue) = withAccess {
        validateLogicalKey(key)
        val document = loadDocument()
        document[key] = value
        saveDocument(document)
    }

    override suspend fun remove(key: String) = withAccess {
        validateLogicalKey(key)
        val document = loadDocument()
        if (document.remove(key) != null) saveDocument(document)
    }

    override suspend fun keys(): List<String> = withAccess { loadDocument().keys.toList() }

    override suspend fun clear() = withAccess {
        checkWriteResult(encryptedStorage.clear())
    }

    private suspend fun <T> withAccess(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        withRuntimeStorageAccess(toolId, namespace, canAccess, action)
    }

    private suspend fun loadDocument(): LinkedHashMap<String, RpcValue> {
        val encoded = encryptedStorage.read() ?: return linkedMapOf()
        val json = cipher.decrypt(encoded)
        val document = RuntimeValueJson.decodeObject(json)
            ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "Stored values cannot be read")
        if (document.keys.any { !isValidLogicalKey(it) }) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "Stored values are invalid")
        }
        return LinkedHashMap(document)
    }

    private suspend fun saveDocument(document: Map<String, RpcValue>) {
        if (document.keys.any { !isValidLogicalKey(it) }) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "Storage index is invalid")
        }
        val json = RuntimeValueJson.encodeObject(document)
        checkWriteResult(encryptedStorage.write(cipher.encrypt(json), nowMillis()))
    }

    private fun checkWriteResult(result: DataResult<Unit>) {
        when (result) {
            is DataResult.Success -> Unit
            is DataResult.Failure.NotFound -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.NOT_FOUND,
                "Tool was removed",
            )

            is DataResult.Failure -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.INTERNAL_ERROR,
                "无法写入工具数据，请检查手机剩余空间后重试",
            )
        }
    }

    private fun validateLogicalKey(key: String) {
        if (!isValidLogicalKey(key)) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "Invalid storage key")
        }
    }

    private fun isValidLogicalKey(key: String): Boolean =
        key.isNotBlank() && key.length <= MAX_LOGICAL_KEY_CHARS && key.none(Char::isISOControl)

    private companion object {
        const val MAX_LOGICAL_KEY_CHARS = 128
    }
}

internal suspend fun <T> withRuntimeStorageAccess(
    toolId: String,
    namespace: ToolStorageNamespace,
    canAccess: suspend () -> Boolean,
    action: suspend () -> T,
): T = ToolRuntimeStorageLocks.mutexFor(toolId, namespace).withLock {
    // Recheck under the same lock used by revocation, not only before RPC dispatch.
    if (!canAccess()) throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Storage permission is disabled")
    action()
}

internal object ToolRuntimeStorageLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun mutexFor(toolId: String, namespace: ToolStorageNamespace): Mutex =
        locks.getOrPut("${namespace.name}:$toolId") { Mutex() }
}

private class AndroidKeyStoreCipher(
    private val toolId: String,
) {
    private val alias = aliasFor(toolId)

    fun encrypt(plaintext: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        JSONObject()
            .put("v", FORMAT_VERSION)
            .put("iv", encoder.encodeToString(cipher.iv))
            .put("ciphertext", encoder.encodeToString(ciphertext))
            .toString()
    } catch (failure: RuntimeHandlerException) {
        throw failure
    } catch (_: Exception) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Secure storage is unavailable")
    }

    fun decrypt(encoded: String): String = try {
        val value = JSONTokener(encoded).nextValue() as? JSONObject
            ?: throw IllegalArgumentException("ciphertext")
        require(value.optInt("v", -1) == FORMAT_VERSION)
        val iv = decoder.decode(value.getString("iv"))
        val ciphertext = decoder.decode(value.getString("ciphertext"))
        require(iv.size == GCM_IV_BYTES && ciphertext.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (failure: RuntimeHandlerException) {
        throw failure
    } catch (_: Exception) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "Secure storage data cannot be read")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun isAvailable(): Boolean = runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        }.isSuccess

        fun deleteForTool(toolId: String): Boolean = runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                load(null)
                if (containsAlias(aliasFor(toolId))) deleteEntry(aliasFor(toolId))
            }
        }.isSuccess

        private fun aliasFor(toolId: String): String = "toolbox.runtime.secure.v1.${toolId.sha256Hex()}"
    }
}

private object RuntimeValueJson {
    fun encode(value: RpcValue): String = toJson(value).toString()

    fun decode(encoded: String): RpcValue? = runCatching {
        fromJson(kotlinx.serialization.json.Json.parseToJsonElement(encoded))
    }.getOrNull()

    fun encodeObject(values: Map<String, RpcValue>): String = encode(RpcValue.ObjectValue(values))

    fun decodeObject(encoded: String): Map<String, RpcValue>? =
        (decode(encoded) as? RpcValue.ObjectValue)?.value

    private fun toJson(value: RpcValue): kotlinx.serialization.json.JsonElement = when (value) {
        RpcValue.Null -> kotlinx.serialization.json.JsonNull
        is RpcValue.Bool -> kotlinx.serialization.json.JsonPrimitive(value.value)
        is RpcValue.Number -> kotlinx.serialization.json.JsonPrimitive(value.value.also { require(it.isFinite()) })
        is RpcValue.StringValue -> kotlinx.serialization.json.JsonPrimitive(value.value)
        is RpcValue.ArrayValue -> kotlinx.serialization.json.JsonArray(value.value.map(::toJson))
        is RpcValue.ObjectValue -> kotlinx.serialization.json.JsonObject(value.value.mapValues { toJson(it.value) })
    }

    private fun fromJson(value: kotlinx.serialization.json.JsonElement): RpcValue = when (value) {
        kotlinx.serialization.json.JsonNull -> RpcValue.Null
        is kotlinx.serialization.json.JsonObject -> RpcValue.ObjectValue(value.mapValues { fromJson(it.value) })
        is kotlinx.serialization.json.JsonArray -> RpcValue.ArrayValue(value.map(::fromJson))
        is kotlinx.serialization.json.JsonPrimitive -> when {
            value.isString -> RpcValue.StringValue(value.content)
            value.content == "true" -> RpcValue.Bool(true)
            value.content == "false" -> RpcValue.Bool(false)
            else -> RpcValue.Number(value.content.toDouble().also { require(it.isFinite()) })
        }
    }
}

private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
