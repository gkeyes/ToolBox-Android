package io.toolbox.host.runtime

import io.toolbox.core.data.DataResult
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.core.data.ToolKvSnapshot
import io.toolbox.core.data.ToolKvValue
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcJson
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeSecureStoreTest {
    private val toolId = "io.example.secure"

    @Test
    fun legacyDocumentMigratesToPerKeyRowsWithoutLoss() = runBlocking {
        val repository = MemoryKv()
        val cipher = FakeCipher()
        val legacy = RuntimeValueJson.encodeObject(mapOf(
            "token" to RpcValue.StringValue("secret"),
            "profile" to RpcValue.ObjectValue(mapOf("id" to RpcValue.Number(7.0))),
        ))
        repository.rows[RuntimeSecureStore.LEGACY_ROOT] = cipher.encrypt(legacy, null)
        val handler = SecureToolKvStorageHandler(toolId, RuntimeSecureStore(toolId, repository, cipher), { 5L })

        assertEquals(RpcValue.StringValue("secret"), handler.get("token"))
        assertEquals(setOf("token", "profile"), handler.keys().toSet())
        assertFalse(RuntimeSecureStore.LEGACY_ROOT in repository.rows)
        assertTrue(repository.rows.keys.all { it.startsWith(RuntimeSecureStore.ROW_PREFIX) })
        assertEquals(
            RpcValue.ObjectValue(mapOf("id" to RpcValue.Number(7.0))),
            handler.get("profile"),
        )
        assertEquals("Migration must be one atomic replace", 1, repository.replaceCalls)
    }

    @Test
    fun setTouchesOnlyItsOwnRowAndLargeValuesRoundTrip() = runBlocking {
        val repository = MemoryKv()
        val cipher = FakeCipher()
        val handler = SecureToolKvStorageHandler(toolId, RuntimeSecureStore(toolId, repository, cipher), { 1L })
        handler.set("small", RpcValue.StringValue("a"))
        val decryptsBefore = cipher.decrypts
        val large = "x".repeat(RuntimeSecureStore.CHUNK_CHARS * 2)
        handler.set("large", RpcValue.StringValue(large))
        assertEquals("Writing one key must not decrypt the others", decryptsBefore, cipher.decrypts)
        assertTrue(repository.rows.keys.count { it.startsWith(RuntimeSecureStore.ROW_PREFIX) } > 3)

        assertEquals(RpcValue.StringValue(large), handler.get("large"))
        assertEquals(RpcValue.StringValue("a"), handler.get("small"))
        handler.remove("large")
        assertEquals(listOf("small"), handler.keys())
        assertEquals(1, repository.rows.size)
    }

    @Test
    fun rowCopiedToAnotherKeyIsRejected() = runBlocking {
        val repository = MemoryKv()
        val store = RuntimeSecureStore(toolId, repository, FakeCipher())
        store.set("a", RpcValue.StringValue("one"), 1L)
        store.set("b", RpcValue.StringValue("two"), 1L)
        val rowA = RuntimeSecureStore.ROW_PREFIX + encodeRuntimeStorageKey("a")
        val rowB = RuntimeSecureStore.ROW_PREFIX + encodeRuntimeStorageKey("b")
        repository.rows[rowB] = repository.rows.getValue(rowA)
        try {
            store.get("b")
            fail("A swapped row must not decrypt")
        } catch (_: RuntimeHandlerException) {
        }
    }

    @Test
    fun clearRemovesLegacyAndPerKeyRowsEvenWhenLegacyIsUnreadable() = runBlocking {
        val repository = MemoryKv()
        repository.rows[RuntimeSecureStore.LEGACY_ROOT] = "{\"v\":1,\"iv\":\"x\",\"ciphertext\":\"damaged\"}"
        repository.rows["unrelated"] = "{}"
        val store = RuntimeSecureStore(toolId, repository, FakeCipher())
        store.set("a", RpcValue.StringValue("one"), 1L)
        val handler = SecureToolKvStorageHandler(toolId, store, { 1L })
        handler.clear()
        assertEquals(setOf("unrelated"), repository.rows.keys)
        assertNull(store.readAll())
    }

    /** Binds associated data like AES-GCM, without the Android Keystore. */
    private class FakeCipher : SecureValueCipher {
        var decrypts = 0
        override fun encrypt(plaintext: String, associatedData: ByteArray?): String {
            val bound = (associatedData?.let { String(it) } ?: "") + "\u0000" + plaintext
            return RuntimeRpcJson.encodeValue(RpcValue.ObjectValue(linkedMapOf(
                "v" to RpcValue.Number(if (associatedData == null) 1.0 else 2.0),
                "iv" to RpcValue.StringValue("iv"),
                "ciphertext" to RpcValue.StringValue(Base64.getEncoder().encodeToString(bound.toByteArray())),
            )))
        }

        override fun decrypt(encoded: String, associatedData: ByteArray?): String {
            decrypts++
            val envelope = (RuntimeRpcJson.parseValue(encoded) as RpcValue.ObjectValue).value
            val text = (envelope["ciphertext"] as? RpcValue.StringValue)?.value ?: unreadable()
            val bound = runCatching { String(Base64.getDecoder().decode(text)) }.getOrNull() ?: unreadable()
            val prefix = (associatedData?.let { String(it) } ?: "") + "\u0000"
            if (!bound.startsWith(prefix)) unreadable()
            return bound.removePrefix(prefix)
        }

        private fun unreadable(): Nothing = throw RuntimeHandlerException(
            io.toolbox.tool.runtime.RuntimeRpcErrorCode.INTERNAL_ERROR, "unreadable",
        )
    }

    private class MemoryKv : ToolKvRepository {
        val rows = linkedMapOf<String, String>()
        private val version = MutableStateFlow(0)
        var replaceCalls = 0

        override suspend fun <T> readSnapshot(toolId: String, action: suspend (ToolKvSnapshot) -> T): T =
            action(object : ToolKvSnapshot {
                override suspend fun getMany(keys: Set<String>): Map<String, ToolKvValue> =
                    keys.mapNotNull { key -> rows[key]?.let { key to ToolKvValue(key, it, 0) } }.toMap()
            })

        override fun observe(toolId: String, key: String): Flow<ToolKvValue?> =
            version.map { rows[key]?.let { ToolKvValue(key, it, 0) } }

        override suspend fun put(toolId: String, key: String, valueJson: String, updatedAt: Long) =
            replace(toolId, emptySet(), mapOf(key to valueJson), updatedAt)

        override suspend fun keys(toolId: String): List<String> = rows.keys.sorted()

        override suspend fun replace(
            toolId: String,
            removeKeys: Set<String>,
            values: Map<String, String>,
            updatedAt: Long,
        ): DataResult<Unit> {
            replaceCalls++
            removeKeys.forEach(rows::remove)
            rows.putAll(values)
            version.value++
            return DataResult.Success(Unit)
        }

        override suspend fun remove(toolId: String, key: String): DataResult<Unit> {
            rows.remove(key)
            version.value++
            return DataResult.Success(Unit)
        }

        override suspend fun bytesUsed(toolId: String): Long = rows.values.sumOf { it.length.toLong() }
    }
}
