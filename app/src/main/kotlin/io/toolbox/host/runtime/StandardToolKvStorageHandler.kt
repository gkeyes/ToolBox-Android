package io.toolbox.host.runtime

import io.toolbox.core.data.DataResult
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeBatchStorageHandler
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import io.toolbox.tool.runtime.RuntimeStorageMutation
import io.toolbox.tool.runtime.RuntimeStorageSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Ordinary values use bounded rows; secure values use encrypted per-key rows (RuntimeSecureStorage.kt). */
internal class StandardToolKvStorageHandler(
    private val toolId: String,
    private val repository: ToolKvRepository,
    private val nowMillis: () -> Long,
    private val canAccess: suspend () -> Boolean = { true },
) : RuntimeBatchStorageHandler {
    private val maxBatchResponseBytes: Int
        get() = (io.toolbox.core.data.ResourceCapacity.availableHeapBytes() / Char.SIZE_BYTES)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    override suspend fun get(key: String): RpcValue? = withAccess {
        val physicalKey = physicalKey(key)
        readValue(physicalKey) ?: loadLegacyDocument()?.get(key)
    }

    override suspend fun getMany(keys: List<String>): List<RpcValue?> = withAccess {
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
        if (changedKeys.distinct().size != changedKeys.size) invalidBatch()
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
        if (key.isBlank() || key.any(Char::isISOControl)) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "Invalid storage key")
        }
        return ROW_PREFIX + encodeRuntimeStorageKey(key)
    }

    private fun decodeKey(encoded: String): String {
        val key = decodeRuntimeStorageKey(encoded) ?: unreadable()
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

    private fun invalidBatch(): Nothing = throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "Storage mutations must not contain duplicate keys")

    private fun unreadable(): Nothing = throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "Stored values cannot be read")

    private companion object {
        const val READ_WINDOW_KEYS = 16
        const val ROW_PREFIX = "toolbox.runtime.v2.standard.key."
        // Even worst-case JSON escaping stays below a 2 MiB Android CursorWindow.
        const val CHUNK_CHARS = 128 * 1024
    }
}

internal object RuntimeValueJson {
    fun encode(value: RpcValue): String = io.toolbox.tool.runtime.RuntimeRpcJson.encodeValue(value)
    fun decode(encoded: String): RpcValue? = runCatching {
        io.toolbox.tool.runtime.RuntimeRpcJson.parseValue(encoded)
    }.getOrNull()
    fun encodeObject(values: Map<String, RpcValue>): String = encode(RpcValue.ObjectValue(values))
    fun decodeObject(encoded: String): Map<String, RpcValue>? =
        (decode(encoded) as? RpcValue.ObjectValue)?.value
}
