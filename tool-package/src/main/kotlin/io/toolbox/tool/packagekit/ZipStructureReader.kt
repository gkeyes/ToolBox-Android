package io.toolbox.tool.packagekit

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal data class CheckedZipEntry(
    val rawName: String,
    val path: SafePackagePath,
    val crc32: Long,
    val compressedBytes: Long,
    val extractedBytes: Long,
)

internal data class CheckedArchive(
    val entries: List<CheckedZipEntry>,
    val compressedBytes: Long,
    val extractedBytes: Long,
)

internal object ZipStructureReader {
    private const val EOCD = 0x06054b50L
    private const val CENTRAL = 0x02014b50L
    private const val LOCAL = 0x04034b50L
    private const val DESCRIPTOR = 0x08074b50L
    private const val MAX_EOCD_SEARCH = 65_557 // 22-byte EOCD + unsigned 16-bit comment length

    fun read(archive: Path, forbidCode: Boolean = true, resources: PackageResourceGuard? = null): CheckedArchive =
        RandomAccessFile(archive.toFile(), "r").use { file -> read(file, archive, forbidCode, resources) }

    private fun read(file: RandomAccessFile, archive: Path, forbidCode: Boolean, resources: PackageResourceGuard?): CheckedArchive {
        resources?.check()
        checkPackageInterrupted()
        val eocd = findEocd(file)
        val entriesOnDisk = u16(eocd, 8)
        var entryCount = u16(eocd, 10).toLong()
        var centralSize = u32(eocd, 12)
        var centralOffset = u32(eocd, 16)
        if (u16(eocd, 4) != 0 || u16(eocd, 6) != 0 || entriesOnDisk.toLong() != entryCount) {
            reject(PackageRejectionCode.UNSUPPORTED_ZIP_FEATURE, "Multi-disk ZIP archives are forbidden")
        }
        if (entryCount == 0xffffL || centralSize == 0xffffffffL || centralOffset == 0xffffffffL) {
            val locatorOffset = file.length() - 22 - u16(eocd, 20) - 20
            if (locatorOffset < 0) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "ZIP64 locator is missing")
            file.seek(locatorOffset)
            val locator = ByteArray(20).also(file::readFully)
            if (u32(locator, 0) != 0x07064b50L || u32(locator, 4) != 0L || u32(locator, 16) != 1L) {
                reject(PackageRejectionCode.UNSUPPORTED_ZIP_FEATURE, "Invalid or multi-disk ZIP64 locator")
            }
            val offset = u64(locator, 8)
            if (offset > locatorOffset - 56) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "ZIP64 end record is truncated")
            file.seek(offset)
            val record = ByteArray(56).also(file::readFully)
            if (u32(record, 0) != 0x06064b50L || u64(record, 4) < 44 ||
                addExact(offset, addExact(12, u64(record, 4))) != locatorOffset ||
                u32(record, 16) != 0L || u32(record, 20) != 0L || u64(record, 24) != u64(record, 32)) {
                reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Invalid ZIP64 end record")
            }
            entryCount = u64(record, 32)
            centralSize = u64(record, 40)
            centralOffset = u64(record, 48)
        }
        if (entryCount > centralSize / 46 || entryCount > Int.MAX_VALUE) {
            reject(PackageRejectionCode.MALFORMED_ARCHIVE, "ZIP entry count cannot fit its central directory")
        }
        if (addExact(centralOffset, centralSize) > file.length()) {
            reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Central directory is outside the archive")
        }
        file.seek(centralOffset)
        val entries = ArrayList<CheckedZipEntry>()
        val collisions = mutableMapOf<String, CheckedZipEntry>()
        var totalExtracted = 0L
        repeat(entryCount.toInt()) {
            resources?.check()
            checkPackageInterrupted()
            val fixed = ByteArray(46).also(file::readFully)
            if (u32(fixed, 0) != CENTRAL) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Invalid central-directory entry")
            val versionMadeBy = u16(fixed, 4)
            val flags = u16(fixed, 8)
            val method = u16(fixed, 10)
            val crc32 = u32(fixed, 16)
            var compressed = u32(fixed, 20)
            var extracted = u32(fixed, 24)
            val nameLength = u16(fixed, 28)
            val extraLength = u16(fixed, 30)
            val commentLength = u16(fixed, 32)
            val diskStart = u16(fixed, 34)
            val externalAttributes = u32(fixed, 38)
            var localOffset = u32(fixed, 42)
            val nameBytes = ByteArray(nameLength).also(file::readFully)
            val rawName = decodeName(nameBytes, flags and 0x800 != 0)
            val extra = ByteArray(extraLength).also(file::readFully)
            val zip64 = zip64Extra(extra)
            var position = 0
            fun nextSize(): Long {
                if (zip64 == null || position + 8 > zip64.size) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "ZIP64 entry sizes are missing")
                return u64(zip64, position).also { position += 8 }
            }
            val wideSizes = compressed == 0xffffffffL || extracted == 0xffffffffL
            if (extracted == 0xffffffffL) extracted = nextSize()
            if (compressed == 0xffffffffL) compressed = nextSize()
            if (localOffset == 0xffffffffL) localOffset = nextSize()
            validateFlags(flags, method, diskStart, localOffset, file.length())
            file.seek(file.filePointer + commentLength)
            val nextCentral = file.filePointer
            validateLocalAndDescriptor(file, localOffset, centralOffset, flags, method, rawName, crc32, compressed, extracted, wideSizes)
            file.seek(nextCentral)
            val safePath = PackagePathPolicy.validate(rawName)
            validateEntryType(versionMadeBy, externalAttributes, safePath, compressed, extracted, forbidCode)
            totalExtracted = addExact(totalExtracted, extracted)
            val checked = CheckedZipEntry(rawName, safePath, crc32, compressed, extracted)
            collisions.putIfAbsent(safePath.collisionKey, checked)?.let { prior ->
                reject(PackageRejectionCode.PATH_COLLISION, "Paths collide after NFC/case folding: ${prior.rawName}, $rawName")
            }
            entries += checked
        }
        if (file.filePointer != centralOffset + centralSize) {
            reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Central-directory size does not match its entries")
        }
        rejectFileDirectoryCollisions(entries)
        return CheckedArchive(entries, Files.size(archive), totalExtracted)
    }

    private fun validateLocalAndDescriptor(
        file: RandomAccessFile,
        offset: Long,
        centralOffset: Long,
        flags: Int,
        method: Int,
        name: String,
        crc32: Long,
        compressed: Long,
        extracted: Long,
        wideSizes: Boolean,
    ) {
        if (offset + 30 > file.length()) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Local ZIP header is truncated")
        file.seek(offset)
        val fixed = ByteArray(30).also(file::readFully)
        if (u32(fixed, 0) != LOCAL || u16(fixed, 6) != flags || u16(fixed, 8) != method) {
            reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Local and central ZIP headers disagree")
        }
        val nameLength = u16(fixed, 26)
        val extraLength = u16(fixed, 28)
        if (file.filePointer + nameLength + extraLength > file.length()) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Local ZIP header is truncated")
        val localName = decodeName(ByteArray(nameLength).also(file::readFully), flags and 0x800 != 0)
        if (localName != name) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Local and central entry names disagree")
        val localExtra = ByteArray(extraLength).also(file::readFully)
        val payloadEnd = addExact(file.filePointer, compressed)
        if (payloadEnd > centralOffset) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Entry payload overlaps the central directory")
        var localCompressed = u32(fixed, 18)
        var localExtracted = u32(fixed, 22)
        if (localCompressed == 0xffffffffL || localExtracted == 0xffffffffL) {
            val extra = zip64Extra(localExtra) ?: reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Missing local ZIP64 sizes")
            var position = 0
            if (localExtracted == 0xffffffffL) { localExtracted = u64(extra, position); position += 8 }
            if (localCompressed == 0xffffffffL) localCompressed = u64(extra, position)
        }
        val localValues = listOf(u32(fixed, 14), localCompressed, localExtracted)
        if (flags and 0x8 == 0) {
            if (localValues != listOf(crc32, compressed, extracted)) {
                reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Local CRC or sizes disagree with the central directory")
            }
            return
        }
        if (localValues.any { it != 0L }) {
            reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Data-descriptor entries require zero local CRC and sizes")
        }
        validateDescriptor(file, payloadEnd, centralOffset, crc32, compressed, extracted, wideSizes)
    }

    private fun validateDescriptor(
        file: RandomAccessFile,
        offset: Long,
        centralOffset: Long,
        crc32: Long,
        compressed: Long,
        extracted: Long,
        wideSizes: Boolean,
    ) {
        if (offset + 12 > centralOffset) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Data descriptor is truncated")
        file.seek(offset)
        val first = ByteArray(4).also(file::readFully).let { u32(it, 0) }
        val hasSignature = first == DESCRIPTOR
        val descriptorSize = (if (hasSignature) 16 else 12) + if (wideSizes) 8 else 0
        if (offset + descriptorSize > centralOffset) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Data descriptor overlaps the central directory")
        val descriptor = ByteArray(descriptorSize - 4).also(file::readFully)
        val actual = if (hasSignature) {
            if (wideSizes) listOf(u32(descriptor, 0), u64(descriptor, 4), u64(descriptor, 12))
            else listOf(u32(descriptor, 0), u32(descriptor, 4), u32(descriptor, 8))
        } else {
            if (wideSizes) listOf(first, u64(descriptor, 0), u64(descriptor, 8))
            else listOf(first, u32(descriptor, 0), u32(descriptor, 4))
        }
        if (actual != listOf(crc32, compressed, extracted)) {
            reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Data descriptor disagrees with the central directory")
        }
    }

    private fun validateFlags(flags: Int, method: Int, diskStart: Int, localOffset: Long, fileLength: Long) {
        val allowedFlags = if (method == 8) 0x080e else 0x0808
        if (flags and allowedFlags.inv() != 0 || flags and 0x1 != 0 || method !in setOf(0, 8) || diskStart != 0 || localOffset >= fileLength) {
            reject(PackageRejectionCode.UNSUPPORTED_ZIP_FEATURE, "Encrypted, unsupported-method or split entries are forbidden")
        }
    }

    private fun validateEntryType(
        versionMadeBy: Int,
        externalAttributes: Long,
        path: SafePackagePath,
        compressed: Long,
        extracted: Long,
        forbidCode: Boolean,
    ) {
        if (versionMadeBy ushr 8 == 3) {
            val mode = (externalAttributes ushr 16).toInt() and 0xffff
            if (mode != 0) {
                when (mode and 0xf000) {
                    0x8000 -> if (path.directory) reject(PackageRejectionCode.SPECIAL_FILE, "Regular file is marked as a directory")
                    0x4000 -> if (!path.directory) reject(PackageRejectionCode.SPECIAL_FILE, "Directory is marked as a file")
                    0xa000 -> reject(PackageRejectionCode.SYMLINK, "Symbolic links are forbidden: ${path.normalized}")
                    else -> reject(PackageRejectionCode.SPECIAL_FILE, "Special files are forbidden: ${path.normalized}")
                }
            }
        }
        if (path.directory && (compressed != 0L || extracted != 0L)) reject(PackageRejectionCode.SPECIAL_FILE, "Directory entries must not carry payload bytes")
        if (forbidCode && PackagePathPolicy.forbiddenCode(path.normalized) == PackageRejectionCode.NESTED_ARCHIVE) {
            reject(PackageRejectionCode.NESTED_ARCHIVE, "Nested archive: ${path.normalized}")
        }
        if (!path.directory && extracted > 0 && compressed == 0L) {
            reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Nonempty entry has no compressed payload")
        }
    }

    private fun rejectFileDirectoryCollisions(entries: List<CheckedZipEntry>) {
        val files = entries.filterNot { it.path.directory }.associateBy { it.path.collisionKey }
        entries.forEach { entry ->
            checkPackageInterrupted()
            val segments = entry.path.collisionKey.split('/')
            for (end in 1 until segments.size) {
                files[segments.take(end).joinToString("/")]?.let { file ->
                    reject(PackageRejectionCode.PATH_COLLISION, "File is also used as a directory: ${file.rawName}")
                }
            }
        }
    }

    private fun findEocd(file: RandomAccessFile): ByteArray {
        val size = minOf(file.length(), MAX_EOCD_SEARCH.toLong()).toInt()
        val bytes = ByteArray(size)
        file.seek(file.length() - size)
        file.readFully(bytes)
        for (index in bytes.size - 22 downTo 0) {
            if (u32(bytes, index) == EOCD && index + 22 + u16(bytes, index + 20) == bytes.size) {
                return bytes.copyOfRange(index, index + 22)
            }
        }
        reject(PackageRejectionCode.MALFORMED_ARCHIVE, "ZIP end-of-central-directory record is missing")
    }

    private fun decodeName(bytes: ByteArray, utf8: Boolean): String = try {
        (if (utf8) StandardCharsets.UTF_8 else Charset.forName("CP437")).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: Exception) {
        reject(PackageRejectionCode.PATH_INVALID, "ZIP entry name is not valid text")
    }

    private fun addExact(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Archive size overflow")
    }

    private fun zip64Extra(bytes: ByteArray): ByteArray? {
        var offset = 0
        var result: ByteArray? = null
        while (offset < bytes.size) {
            if (offset + 4 > bytes.size) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Truncated ZIP extra field")
            val id = u16(bytes, offset)
            val length = u16(bytes, offset + 2)
            offset += 4
            if (offset + length > bytes.size) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Truncated ZIP extra value")
            if (id == 1) {
                if (result != null) reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Duplicate ZIP64 extra field")
                result = bytes.copyOfRange(offset, offset + length)
            }
            offset += length
        }
        return result
    }

    private fun u64(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 8 > bytes.size || bytes[offset + 7] < 0) {
            reject(PackageRejectionCode.MALFORMED_ARCHIVE, "ZIP64 size is truncated or exceeds signed file offsets")
        }
        return u32(bytes, offset) or (u32(bytes, offset + 4) shl 32)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or ((bytes[offset + 3].toLong() and 0xff) shl 24)
}
