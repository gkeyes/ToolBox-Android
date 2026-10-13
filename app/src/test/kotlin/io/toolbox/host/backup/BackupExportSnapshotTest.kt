package io.toolbox.host.backup

import io.toolbox.core.data.ToolKvValue
import io.toolbox.host.runtime.RuntimeSecureStore
import io.toolbox.host.runtime.RuntimeValueJson
import io.toolbox.host.runtime.SecureValueCipher
import io.toolbox.tool.runtime.RpcValue
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupExportSnapshotTest {
    private val toolId = "io.example.backup"

    @Test
    fun secureDocumentDecryptsFromCapturedRowsOnly() = runBlocking {
        val cipher = PlainCipher()
        val large = "x".repeat(RuntimeSecureStore.CHUNK_CHARS * 2 + 3)
        val encrypted = RuntimeSecureStore(toolId, SnapshotToolKvRepository(toolId, emptyMap()), cipher).encryptRows(mapOf(
            "token" to RpcValue.StringValue("secret"),
            "large" to RpcValue.StringValue(large),
        ))
        val legacy = cipher.encrypt(RuntimeValueJson.encodeObject(mapOf("old" to RpcValue.Number(1.0))), null)
        val all = encrypted + (RuntimeSecureStore.LEGACY_ROOT to legacy) + ("plain" to "\"v\"")
        val captured = linkedMapOf<String, ToolKvValue>()
        all.keys.sorted().forEach { key -> captured[key] = ToolKvValue(key, all.getValue(key), 4) }
        val snapshot = SnapshotToolKvRepository(toolId, captured)

        assertTrue("Large value must use split rows", captured.keys.count { it.startsWith(RuntimeSecureStore.ROW_PREFIX) } > 2)
        assertEquals(
            mapOf("old" to RpcValue.Number(1.0), "large" to RpcValue.StringValue(large), "token" to RpcValue.StringValue("secret")),
            RuntimeSecureStore(toolId, snapshot, cipher).readAll(),
        )
        assertEquals(captured.keys.toList(), snapshot.keys(toolId))
        assertEquals("\"v\"", snapshot.observe(toolId, "plain").first()?.valueJson)
        assertEquals(setOf("plain"), snapshot.readSnapshot(toolId) { it.getMany(setOf("plain", "missing")) }.keys)
    }

    @Test
    fun snapshotIsReadOnlyAndScopedToItsTool() = runBlocking {
        val snapshot = SnapshotToolKvRepository(toolId, linkedMapOf("a" to ToolKvValue("a", "1", 0)))
        assertNull(snapshot.observe("io.example.other", "a").first())
        assertEquals(emptyList<String>(), snapshot.keys("io.example.other"))
        try {
            snapshot.put(toolId, "a", "2", 1)
            fail("Snapshot writes must be rejected")
        } catch (_: UnsupportedOperationException) {
        }
        assertEquals("1", snapshot.observe(toolId, "a").first()?.valueJson)
    }

    /** Envelope-shaped, associated-data-bound stand-in for the Android Keystore cipher. */
    private class PlainCipher : SecureValueCipher {
        override fun encrypt(plaintext: String, associatedData: ByteArray?): String {
            val bound = (associatedData?.let { String(it) } ?: "") + "\u0000" + plaintext
            return RuntimeValueJson.encodeObject(mapOf(
                "v" to RpcValue.Number(1.0),
                "iv" to RpcValue.StringValue("iv"),
                "ciphertext" to RpcValue.StringValue(Base64.getEncoder().encodeToString(bound.toByteArray())),
            ))
        }

        override fun decrypt(encoded: String, associatedData: ByteArray?): String {
            val text = (RuntimeValueJson.decodeObject(encoded)!!["ciphertext"] as RpcValue.StringValue).value
            val bound = String(Base64.getDecoder().decode(text))
            val prefix = (associatedData?.let { String(it) } ?: "") + "\u0000"
            check(bound.startsWith(prefix))
            return bound.removePrefix(prefix)
        }
    }
}
