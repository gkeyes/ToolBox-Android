package io.toolbox.host.backup

import io.toolbox.core.data.BackgroundOperation
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.RunOutcome
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskState
import io.toolbox.core.data.ToolKvValue
import io.toolbox.host.runtime.RuntimeSecureStore
import io.toolbox.host.runtime.RuntimeValueJson
import io.toolbox.host.runtime.SecureValueCipher
import io.toolbox.tool.runtime.RpcValue
import java.io.File
import java.io.IOException
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

    @Test
    fun spillRoundTripsRowsTasksAndResultsExactly() {
        val file = File.createTempFile("backup-spill", ".bin").apply { deleteOnExit() }
        val large = "y".repeat(3 * 1024 * 1024)
        val rows = listOf(
            ToolKvValue("a", "\"short\"", 1),
            ToolKvValue("lone\uD800surrogate é", "\"\uDC00 中文\"", Long.MAX_VALUE),
            ToolKvValue("large", large, 0),
            ToolKvValue("", "", -1),
        )
        val full = BackgroundTask(
            "task.1", toolId, 3, "k", BackgroundOperation.HTTP_GET, "{\"url\":\"https://x\"}", true, 15,
            TaskState.COMPLETED, 10, 20, 30, 2, executionToken = "secret-token",
        )
        val sparse = BackgroundTask(
            "task.2", toolId, 1, "n", BackgroundOperation.NOTIFY, "{}", false, null,
            TaskState.QUEUED, 5, 5, null, 0,
        )
        val result = TaskRunResult("task.1", RunOutcome.FAILED, 25, null, "E_NET", 2)
        val tasks = File.createTempFile("backup-spill", ".bin").apply { deleteOnExit() }
        ExportSpillWriter(file).use { writer -> rows.forEach(writer::kv); writer.finish() }
        ExportSpillWriter(tasks).use { writer ->
            writer.task(full, result)
            writer.task(sparse, null)
            writer.finish()
        }

        val readRows = mutableListOf<ToolKvValue>()
        ExportSpillReader(file).use { reader -> while (true) readRows += reader.nextKv() ?: break }
        assertEquals(rows, readRows)
        val readTasks = mutableListOf<Pair<BackgroundTask, TaskRunResult?>>()
        ExportSpillReader(tasks).use { reader -> while (true) readTasks += reader.nextTask() ?: break }
        assertEquals(listOf(full.copy(executionToken = null) to result, sparse to null), readTasks)
        file.delete(); tasks.delete()
    }

    @Test
    fun spillWithoutEndMarkerIsRejected() {
        val file = File.createTempFile("backup-spill", ".bin").apply { deleteOnExit() }
        ExportSpillWriter(file).use { it.kv(ToolKvValue("a", "1", 0)) }
        ExportSpillReader(file).use { reader ->
            assertEquals("a", reader.nextKv()?.key)
            try {
                reader.nextKv()
                fail("A truncated spill must not read as complete")
            } catch (_: IOException) {
            }
        }
        file.delete()
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
