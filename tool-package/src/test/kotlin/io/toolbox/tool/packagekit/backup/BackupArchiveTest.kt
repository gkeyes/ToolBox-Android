package io.toolbox.tool.packagekit.backup

import java.io.*
import java.util.zip.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val archive = BackupArchive()

    @Test fun roundTripIncludesSettingsPackagesConfigurationAndBinaryData() = runBlocking {
        val source = fixture(2)
        val output = File(temporary.root, "backup.zip")
        archive.write(source, output, contents(2))
        archive.read(output.inputStream(), temporary.root).use { restored ->
            assertEquals(contents(2), restored.contents)
            BackupArchive.regularFiles(source).forEach { original ->
                assertArrayEquals(original.readBytes(), File(restored.directory, original.relativeTo(source).invariantSeparatorsPath).readBytes())
            }
        }
        assertFalse(temporary.root.listFiles()!!.any { it.name.startsWith("backup-") })
    }
    @Test fun emptyToolListAndRepeatedReadAreSupported() = runBlocking {
        val output = File(temporary.root, "empty.zip")
        archive.write(fixture(0), output, contents(0))
        repeat(2) { archive.read(output.inputStream(), temporary.root).use { assertTrue(it.contents.tools.isEmpty()) } }
    }
    @Test fun missingIndexedPackageAndChecksumMismatchAreRejected() = runBlocking {
        val entries = validEntries()
        reject(zip(entries - "tools/io.toolbox.t0/package.tbx"))
        reject(zip(entries + ("host/settings.json" to "changed".toByteArray())), "CHECKSUM")
    }
    @Test fun corruptZipAndSourceReadFailureLeaveNoStaging() = runBlocking {
        val bytes = zip(validEntries())
        reject(bytes.copyOf(bytes.size - 8))
        val before = temporary.root.listFiles()!!.map { it.name }.toSet()
        try {
            archive.read(object : InputStream() { override fun read(): Int = throw IOException("synthetic source failure") }, temporary.root)
            fail("Expected source error")
        } catch (_: IOException) { }
        assertEquals(before, temporary.root.listFiles()!!.map { it.name }.toSet())
    }
    @Test fun traversalAndFileDirectoryCollisionsAreRejected() = runBlocking {
        for (name in listOf("../outside", "/absolute", "C:/drive", "a\\b", "a/../b", "a//b", "%2e%2e/outside", "a\u0000b")) reject(zip(mapOf(name to byteArrayOf(1))))
        reject(zip(mapOf("same" to byteArrayOf(1), "same/child" to byteArrayOf(2))))
    }
    @Test fun duplicateCaseFoldUnicodeAndIdenticalPathsAreRejected() = runBlocking {
        reject(zip(mapOf("A" to byteArrayOf(1), "a" to byteArrayOf(2))))
        reject(zip(mapOf("é" to byteArrayOf(1), "e\u0301" to byteArrayOf(2))))
        val bytes = zip(mapOf("one" to byteArrayOf(1), "two" to byteArrayOf(2)))
        for (i in 0..bytes.size - 3) if (bytes[i] == 't'.code.toByte() && bytes[i + 1] == 'w'.code.toByte() && bytes[i + 2] == 'o'.code.toByte()) "one".toByteArray().copyInto(bytes, i)
        reject(bytes)
    }
    @Test fun unixSymlinksAndCompressionBombsAreRejected() = runBlocking {
        val bytes = zip(mapOf("link" to "../../outside".toByteArray()))
        val central = (0..bytes.size - 46).first { u32(bytes, it) == 0x02014b50L }
        bytes[central + 5] = 3
        val mode = 0xa1ffL shl 16
        repeat(4) { bytes[central + 38 + it] = (mode shr (8 * it)).toByte() }
        reject(bytes)
        reject(zip(mapOf("bomb" to ByteArray(2 * 1024 * 1024))))
    }
    @Test fun unknownFieldsAreReportedButFutureMajorIsRejected() = runBlocking {
        val entries = validEntries().toMutableMap()
        val manifest = BackupJson.obj(BackupJson.parse(entries.getValue("manifest.json"))).toMutableMap()
        manifest["futureExtension"] = mapOf("anything" to true)
        entries["manifest.json"] = BackupJson.encode(manifest).toByteArray(); resign(entries)
        archive.read(ByteArrayInputStream(zip(entries)), temporary.root).use { assertTrue(it.contents.warnings.any { warning -> "未知" in warning }) }
        manifest["formatVersion"] = 2
        entries["manifest.json"] = BackupJson.encode(manifest).toByteArray(); resign(entries)
        reject(zip(entries), "VERSION")
    }
    @Test fun duplicateJsonKeysFailEvenWithMatchingChecksum() = runBlocking {
        val entries = validEntries().toMutableMap()
        entries["manifest.json"] = "{\"format\":\"io.toolbox.backup\",\"format\":\"other\"}".toByteArray()
        resign(entries); reject(zip(entries))
    }
    @Test fun exportFailureAndCancellationRemovePartialOutput() = runBlocking {
        val output = File(temporary.root, "cancelled.zip")
        try { archive.write(fixture(1), output, contents(1)) { if (it > 40) throw CancellationException("synthetic cancellation") }; fail() }
        catch (_: CancellationException) { }
        assertFalse(output.exists()); assertFalse(File(temporary.root, "cancelled.zip.partial").exists())
        try { archive.write(fixture(1), File(temporary.root, "missing/backup.zip"), contents(1)); fail() }
        catch (_: IOException) { }
        assertFalse(File(temporary.root, "missing/backup.zip.partial").exists())
    }
    @Test fun boundsAreEnforcedBeforePublication() = runBlocking {
        val output = File(temporary.root, "limited.zip")
        try { BackupArchive(BackupLimits(entries = 1)).write(fixture(1), output, contents(1)); fail() }
        catch (failure: BackupException) { assertEquals("LIMIT", failure.code) }
        assertFalse(output.exists())
    }
    @Test fun strictJsonPreservesEscapesAndRejectsDuplicates() {
        val data = mapOf("a" to "line\n\t\\\"😀", "flag" to true, "n" to 3L)
        assertEquals(BackupJson.encode(data), BackupJson.encode(BackupJson.parse(BackupJson.encode(data).toByteArray())))
        try { BackupJson.parse("{\"k\":1,\"k\":2}".toByteArray()); fail() } catch (_: Exception) { }
    }
    private fun fixture(count: Int): File = temporary.newFolder().also { root ->
        put(root, "host/settings.json", "{\"theme\":\"DARK\"}".toByteArray())
        repeat(count) { i ->
            put(root, "tools/io.toolbox.t$i/package.tbx", byteArrayOf(80, 75, 3, 4, i.toByte()))
            put(root, "tools/io.toolbox.t$i/metadata.json", "{}".toByteArray())
            put(root, "tools/io.toolbox.t$i/data/config.json", "{\"keep\":true}".toByteArray())
            put(root, "tools/io.toolbox.t$i/data/file.bin", ByteArray(256) { it.toByte() })
        }
    }
    private fun contents(n: Int) = BackupContents("0.7.6", 100L, (0 until n).map { BackupTool("io.toolbox.t$it", "Tool $it", "1.0.0", 1) })
    private fun put(root: File, path: String, bytes: ByteArray) { File(root, path).also { it.parentFile.mkdirs(); it.writeBytes(bytes) } }
    private suspend fun validEntries(): Map<String, ByteArray> {
        val output = temporary.newFile().apply { delete() }
        archive.write(fixture(1), output, contents(1))
        return ZipFile(output).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() } }
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        out.toByteArray()
    }
    private suspend fun resign(entries: MutableMap<String, ByteArray>) {
        val sums = BackupJson.obj(BackupJson.parse(entries.getValue("checksums.json"))).toMutableMap()
        val file = temporary.newFile().apply { writeBytes(entries.getValue("manifest.json")) }
        sums["manifest.json"] = BackupArchive.hash(file)
        entries["checksums.json"] = BackupJson.encode(sums).toByteArray()
    }
    private suspend fun reject(bytes: ByteArray, code: String? = null) {
        val before = temporary.root.listFiles()!!.map { it.name }.toSet()
        try { archive.read(ByteArrayInputStream(bytes), temporary.root).close(); fail("Expected rejection") }
        catch (failure: BackupException) { if (code != null) assertEquals(code, failure.code) }
        assertEquals(before, temporary.root.listFiles()!!.map { it.name }.toSet())
    }
    private fun u32(bytes: ByteArray, i: Int): Long = (0 until 4).fold(0L) { result, offset -> result or ((bytes[i + offset].toLong() and 255) shl (8 * offset)) }
}
