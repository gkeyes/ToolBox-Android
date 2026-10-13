package io.toolbox.tool.packagekit

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class ZipStructureOverlapTest {
    @Test
    fun regularArchivesWithStoredDeflatedAndDescriptorEntriesStillPass() = withTempDir { dir ->
        val archive = dir.resolve("valid.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("assets/").apply {
                method = ZipEntry.STORED
                size = 0
                compressedSize = 0
                crc = 0
            })
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("index.html"))
            zip.write("<p>deflated with a data descriptor</p>".repeat(50).toByteArray())
            zip.closeEntry()
            val stored = "stored bytes".toByteArray()
            zip.putNextEntry(ZipEntry("assets/data.txt").apply {
                method = ZipEntry.STORED
                size = stored.size.toLong()
                compressedSize = stored.size.toLong()
                crc = CRC32().apply { update(stored) }.value
            })
            zip.write(stored)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("empty.txt"))
            zip.closeEntry()
        }
        val checked = ZipStructureReader.read(archive)
        assertEquals(listOf("assets/", "index.html", "assets/data.txt", "empty.txt"), checked.entries.map { it.rawName })
    }

    @Test
    fun entryWhosePayloadContainsAnotherEntryIsRejected() = withTempDir { dir ->
        val archive = dir.resolve("overlap.zip")
        val inner = localRecord("b.txt", "inner".toByteArray())
        val outer = localRecord("a.txt", inner)
        val innerOffset = outer.size.toLong() - inner.size
        Files.write(archive, centralArchive(outer, listOf(
            CentralEntry("a.txt", inner, 0L),
            CentralEntry("b.txt", "inner".toByteArray(), innerOffset),
        )))
        assertEquals(PackageRejectionCode.MALFORMED_ARCHIVE, rejection { ZipStructureReader.read(archive) })
    }

    @Test
    fun overlapIsDetectedRegardlessOfCentralDirectoryOrder() = withTempDir { dir ->
        val archive = dir.resolve("overlap-reversed.zip")
        val inner = localRecord("b.txt", "inner".toByteArray())
        val outer = localRecord("a.txt", inner)
        Files.write(archive, centralArchive(outer, listOf(
            CentralEntry("b.txt", "inner".toByteArray(), outer.size.toLong() - inner.size),
            CentralEntry("a.txt", inner, 0L),
        )))
        assertEquals(PackageRejectionCode.MALFORMED_ARCHIVE, rejection { ZipStructureReader.read(archive) })
    }

    @Test
    fun adjacentHandBuiltEntriesAreAccepted() = withTempDir { dir ->
        val archive = dir.resolve("adjacent.zip")
        val first = localRecord("a.txt", "first".toByteArray())
        val second = localRecord("b.txt", "second".toByteArray())
        Files.write(archive, centralArchive(first + second, listOf(
            CentralEntry("a.txt", "first".toByteArray(), 0L),
            CentralEntry("b.txt", "second".toByteArray(), first.size.toLong()),
        )))
        assertEquals(listOf("a.txt", "b.txt"), ZipStructureReader.read(archive).entries.map { it.rawName })
    }

    private class CentralEntry(val name: String, val payload: ByteArray, val offset: Long)

    private fun localRecord(name: String, payload: ByteArray): ByteArray {
        val nameBytes = name.toByteArray()
        return ByteArrayOutputStream().apply {
            le32(0x04034b50L); le16(20); le16(0); le16(0); le16(0); le16(0)
            le32(crc(payload)); le32(payload.size.toLong()); le32(payload.size.toLong())
            le16(nameBytes.size); le16(0)
            write(nameBytes); write(payload)
        }.toByteArray()
    }

    private fun centralArchive(locals: ByteArray, entries: List<CentralEntry>): ByteArray {
        val central = ByteArrayOutputStream().apply {
            entries.forEach { entry ->
                val nameBytes = entry.name.toByteArray()
                le32(0x02014b50L); le16(20); le16(20); le16(0); le16(0); le16(0); le16(0)
                le32(crc(entry.payload)); le32(entry.payload.size.toLong()); le32(entry.payload.size.toLong())
                le16(nameBytes.size); le16(0); le16(0); le16(0); le16(0); le32(0); le32(entry.offset)
                write(nameBytes)
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(locals)
            write(central)
            le32(0x06054b50L); le16(0); le16(0); le16(entries.size); le16(entries.size)
            le32(central.size.toLong()); le32(locals.size.toLong()); le16(0)
        }.toByteArray()
    }

    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value

    private fun ByteArrayOutputStream.le16(value: Int) { write(value and 0xff); write((value ushr 8) and 0xff) }

    private fun ByteArrayOutputStream.le32(value: Long) { le16((value and 0xffff).toInt()); le16(((value ushr 16) and 0xffff).toInt()) }

    private fun rejection(block: () -> Unit): PackageRejectionCode? =
        (runCatching(block).exceptionOrNull() as? InspectionRejected)?.rejection?.code

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("zip-overlap")
        try { block(dir) } finally { dir.toFile().deleteRecursively() }
    }
}
