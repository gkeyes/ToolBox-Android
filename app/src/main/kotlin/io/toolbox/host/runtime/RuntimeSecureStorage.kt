package io.toolbox.host.runtime

import io.toolbox.core.data.DataResult
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import io.toolbox.tool.runtime.RuntimeStorageHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal fun createRuntimeSecureStorageHandler(
    toolId: String,
    repository: ToolKvRepository,
    nowMillis: () -> Long,
    canAccess: suspend () -> Boolean,
): RuntimeStorageHandler = SecureToolKvStorageHandler(
    toolId, RuntimeSecureStore(toolId, repository, AndroidKeyStoreCipher(toolId)), nowMillis, canAccess,
)

/** Each secure key is its own encrypted row, so get/set touch only that key. */
internal class SecureToolKvStorageHandler(
    private val toolId: String,
    private val store: RuntimeSecureStore,
    private val nowMillis: () -> Long,
    private val canAccess: suspend () -> Boolean = { true },
) : RuntimeStorageHandler {
    @Volatile private var legacyMigrated = false

    override suspend fun get(key: String): RpcValue? = withMigratedAccess { store.get(key) }

    override suspend fun set(key: String, value: RpcValue) = withMigratedAccess { store.set(key, value, nowMillis()) }

    override suspend fun remove(key: String) = withMigratedAccess { store.remove(key, nowMillis()) }

    override suspend fun keys(): List<String> = withMigratedAccess { store.logicalKeys() }

    // Clearing never needs the old document to be readable.
    override suspend fun clear() = withAccess { store.clear() }

    private suspend fun <T> withMigratedAccess(action: suspend () -> T): T = withAccess {
        if (!legacyMigrated) {
            store.migrateLegacyDocument(nowMillis())
            legacyMigrated = true
        }
        action()
    }

    private suspend fun <T> withAccess(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        withRuntimeStorageAccess(toolId, ToolStorageNamespace.Secure, canAccess, action)
    }
}

/**
 * Secure rows: `toolbox.runtime.v2.secure.key.<utf16 hex>` holds an AES-GCM envelope whose
 * associated data is the row key, so rows cannot be swapped between keys. Envelopes larger
 * than one CursorWindow-safe row are split into `<row>.<index>` parts behind a header.
 * The pre-v2 single encrypted document is migrated on first access in one atomic replace.
 * Callers hold the secure namespace lock.
 */
internal class RuntimeSecureStore(
    private val toolId: String,
    private val repository: ToolKvRepository,
    private val cipher: SecureValueCipher,
) {
    suspend fun get(key: String): RpcValue? {
        val physical = physicalKey(key)
        val envelope = readEnvelope(physical) ?: return null
        return decodeValue(cipher.decrypt(envelope, physical.toByteArray(Charsets.UTF_8)))
    }

    suspend fun set(key: String, value: RpcValue, updatedAt: Long) {
        val physical = physicalKey(key)
        val rows = encryptRows(mapOf(key to value))
        checkWriteResult(repository.replace(toolId, existingRows(physical), rows, updatedAt))
    }

    suspend fun remove(key: String, updatedAt: Long) {
        val physical = physicalKey(key)
        val existing = existingRows(physical)
        if (existing.isNotEmpty()) checkWriteResult(repository.replace(toolId, existing, emptyMap(), updatedAt))
    }

    suspend fun logicalKeys(): List<String> = repository.keys(toolId)
        .filter { it.startsWith(ROW_PREFIX) && '.' !in it.removePrefix(ROW_PREFIX) }
        .map { decodeKey(it.removePrefix(ROW_PREFIX)) }

    suspend fun clear() = checkWriteResult(clearRows())

    /** Removes legacy and per-key rows; the result reports a removed tool instead of throwing. */
    suspend fun clearRows(): DataResult<Unit> = repository.replace(toolId, allRows(), emptyMap(), 0)

    /** Keystore work only; no repository access. Backup restore calls this outside its transaction. */
    fun encryptRows(values: Map<String, RpcValue>): Map<String, String> {
        val rows = linkedMapOf<String, String>()
        for ((key, value) in values) {
            val physical = physicalKey(key)
            val envelope = cipher.encrypt(RuntimeValueJson.encode(value), physical.toByteArray(Charsets.UTF_8))
            if (envelope.length <= CHUNK_CHARS) {
                rows[physical] = envelope
            } else {
                val parts = envelope.chunked(CHUNK_CHARS)
                parts.forEachIndexed { index, part -> rows["$physical.$index"] = part }
                rows[physical] = RuntimeValueJson.encodeObject(mapOf(
                    "chunks" to RpcValue.Number(parts.size.toDouble()),
                    "length" to RpcValue.Number(envelope.length.toDouble()),
                ))
            }
        }
        return rows
    }

    /** Replaces every secure row (legacy and v2) with prepared rows in one repository commit. */
    suspend fun replaceAll(rows: Map<String, String>, updatedAt: Long): DataResult<Unit> =
        repository.replace(toolId, allRows(), rows, updatedAt)

    /** All values, including a not yet migrated legacy document; null when nothing is stored. */
    suspend fun readAll(): Map<String, RpcValue>? {
        val legacy = readLegacyDocument()
        val keys = logicalKeys()
        if (legacy == null && keys.isEmpty()) return null
        val result = LinkedHashMap(legacy.orEmpty())
        for (key in keys) result[key] = get(key) ?: continue
        return result
    }

    suspend fun migrateLegacyDocument(updatedAt: Long) {
        val legacy = readLegacyDocument() ?: return
        val existing = logicalKeys().toSet()
        // Rows written by this format win, matching ordinary storage migration.
        val rows = encryptRows(legacy.filterKeys { it !in existing })
        checkWriteResult(repository.replace(toolId, legacyRows(), rows, updatedAt))
    }

    private suspend fun readLegacyDocument(): Map<String, RpcValue>? {
        val envelope = RuntimeSecureEnvelopeStorage(toolId, repository).read() ?: return null
        val document = RuntimeValueJson.decodeObject(cipher.decrypt(envelope, null)) ?: unreadable()
        if (document.keys.any { !isValidLogicalKey(it) }) unreadable()
        return document
    }

    private suspend fun readEnvelope(physical: String): String? {
        val header = repository.observe(toolId, physical).first()?.valueJson ?: return null
        val count = chunkCount(header) ?: return header
        return buildString {
            repeat(count.first) { index ->
                append(repository.observe(toolId, "$physical.$index").first()?.valueJson ?: unreadable())
            }
        }.also { if (it.length != count.second) unreadable() }
    }

    private suspend fun existingRows(physical: String): Set<String> {
        val header = repository.observe(toolId, physical).first()?.valueJson ?: return emptySet()
        // A damaged header must still be replaceable or removable.
        val count = runCatching { chunkCount(header)?.first }.getOrNull() ?: 0
        return buildSet {
            add(physical)
            repeat(count) { add("$physical.$it") }
        }
    }

    /** (parts, total length) for a split envelope, or null for an inline envelope. */
    private fun chunkCount(header: String): Pair<Int, Int>? {
        val record = RuntimeValueJson.decodeObject(header) ?: unreadable()
        if (record.keys == ENVELOPE_KEYS) return null
        if (record.keys != setOf("chunks", "length")) unreadable()
        val count = positiveInt(record["chunks"])
        val length = positiveInt(record["length"])
        if (count != (length - 1) / CHUNK_CHARS + 1) unreadable()
        return count to length
    }

    private suspend fun allRows(): Set<String> = repository.keys(toolId).filterTo(linkedSetOf()) {
        it.startsWith(ROW_PREFIX) || it == LEGACY_ROOT || it.startsWith(LEGACY_CHUNK_PREFIX)
    }

    private suspend fun legacyRows(): Set<String> = repository.keys(toolId).filterTo(linkedSetOf(LEGACY_ROOT)) {
        it.startsWith(LEGACY_CHUNK_PREFIX)
    }

    private fun decodeValue(json: String): RpcValue = RuntimeValueJson.decode(json) ?: unreadable()

    private fun physicalKey(key: String): String {
        if (!isValidLogicalKey(key)) throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "Invalid storage key")
        return ROW_PREFIX + encodeRuntimeStorageKey(key)
    }

    private fun decodeKey(encoded: String): String =
        decodeRuntimeStorageKey(encoded)?.takeIf(::isValidLogicalKey) ?: unreadable()

    private fun isValidLogicalKey(key: String): Boolean = key.isNotBlank() && key.none(Char::isISOControl)

    private fun positiveInt(value: RpcValue?): Int {
        val number = (value as? RpcValue.Number)?.value ?: unreadable()
        if (!number.isFinite() || number < 1 || number > Int.MAX_VALUE || number != number.toInt().toDouble()) unreadable()
        return number.toInt()
    }

    private fun checkWriteResult(result: DataResult<Unit>) {
        when (result) {
            is DataResult.Success -> Unit
            is DataResult.Failure.NotFound -> throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Tool was removed")
            is DataResult.Failure -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.INTERNAL_ERROR,
                "无法写入工具数据，请检查手机剩余空间后重试",
            )
        }
    }

    private fun unreadable(): Nothing = throw RuntimeHandlerException(
        RuntimeRpcErrorCode.INTERNAL_ERROR, "Secure storage data cannot be read",
    )

    companion object {
        const val ROW_PREFIX = "toolbox.runtime.v2.secure.key."
        val LEGACY_ROOT = ToolStorageNamespace.Secure.documentKey
        val LEGACY_CHUNK_PREFIX = "$LEGACY_ROOT.chunk."
        // Even worst-case JSON escaping stays below a 2 MiB Android CursorWindow.
        const val CHUNK_CHARS = 128 * 1024
        private val ENVELOPE_KEYS = setOf("v", "iv", "ciphertext")
    }
}

/** Encrypts UTF-8 text; [associatedData] is null only for the legacy single-document format. */
internal interface SecureValueCipher {
    fun encrypt(plaintext: String, associatedData: ByteArray?): String
    fun decrypt(encoded: String, associatedData: ByteArray?): String
}

/** UTF-16 code units preserve every accepted JS key, including lone surrogates. */
internal fun encodeRuntimeStorageKey(key: String): String =
    key.map { it.code.toString(16).padStart(4, '0') }.joinToString("")

internal fun decodeRuntimeStorageKey(encoded: String): String? {
    if (encoded.isEmpty() || encoded.length % 4 != 0) return null
    val key = encoded.chunked(4).map { (it.toIntOrNull(16) ?: return null).toChar() }.joinToString("")
    return key.takeIf { encodeRuntimeStorageKey(it) == encoded }
}
