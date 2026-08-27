package io.toolbox.tool.packagekit

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal data class ExtractedArchive(
    val bundleDirectory: Path,
    val hashes: Map<String, String>,
    val metadata: Map<String, ByteArray>,
)

internal object ZipArchiveReader {
    fun extract(archive: Path, checked: CheckedArchive, sessionDirectory: Path, limits: PackageLimits): ExtractedArchive {
        val bundle = sessionDirectory.resolve("bundle")
        Files.createDirectories(bundle)
        val hashes = linkedMapOf<String, String>()
        val metadata = linkedMapOf<String, ByteArray>()
        var actualTotal = 0L
        ZipFile(archive.toFile()).use { zip ->
            val byName = zip.entries().asSequence().associateBy { it.name }
            for (entry in checked.entries) {
                if (entry.path.directory) continue
                val zipEntry = byName[entry.rawName]
                    ?: reject(PackageRejectionCode.EXTRACTION_FAILED, "Central entry disappeared: ${entry.rawName}")
                val target = bundle.resolve(entry.path.normalized).normalize()
                if (!target.startsWith(bundle)) reject(PackageRejectionCode.PATH_INVALID, "Extraction escaped the session")
                Files.createDirectories(target.parent)
                val digest = MessageDigest.getInstance("SHA-256")
                val crc32 = CRC32()
                val metadataBuffer = if (entry.path.normalized in METADATA_FILES) java.io.ByteArrayOutputStream() else null
                val prefix = java.io.ByteArrayOutputStream(SNIFF_BYTES)
                var actualEntry = 0L
                try {
                    zip.getInputStream(zipEntry).use { input ->
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                actualEntry += count
                                actualTotal += count
                                if (actualEntry > limits.maxEntryBytes) reject(PackageRejectionCode.ENTRY_SIZE_LIMIT, "${entry.path.normalized} exceeded its declared limit while reading")
                                if (actualTotal > limits.maxExtractedBytes) reject(PackageRejectionCode.TOTAL_SIZE_LIMIT, "Archive exceeded its total limit while reading")
                                digest.update(buffer, 0, count)
                                crc32.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                metadataBuffer?.write(buffer, 0, count)
                                if (prefix.size() < SNIFF_BYTES) {
                                    prefix.write(buffer, 0, minOf(count, SNIFF_BYTES - prefix.size()))
                                }
                            }
                        }
                    }
                } catch (error: InspectionRejected) {
                    throw error
                } catch (error: Exception) {
                    reject(PackageRejectionCode.EXTRACTION_FAILED, "Failed to extract ${entry.path.normalized}: ${error.message}")
                }
                if (actualEntry != entry.extractedBytes) {
                    reject(PackageRejectionCode.EXTRACTION_FAILED, "${entry.path.normalized} size differs from the central directory")
                }
                if (crc32.value != entry.crc32) {
                    reject(PackageRejectionCode.EXTRACTION_FAILED, "${entry.path.normalized} CRC32 differs from the central directory")
                }
                sniffPayload(entry.path.normalized, prefix.toByteArray())
                hashes[entry.path.normalized] = digest.digest().toHex()
                metadataBuffer?.let { metadata[entry.path.normalized] = it.toByteArray() }
            }
        }
        return ExtractedArchive(bundle, hashes, metadata)
    }

    private fun sniffPayload(path: String, prefix: ByteArray) {
        val code = when {
            prefix.startsWith(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) -> PackageRejectionCode.NESTED_ARCHIVE
            prefix.startsWith(byteArrayOf(0x1f, 0x8b.toByte())) -> PackageRejectionCode.NESTED_ARCHIVE
            prefix.startsWith(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)) -> PackageRejectionCode.NESTED_ARCHIVE
            prefix.startsWith(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)) -> PackageRejectionCode.NESTED_ARCHIVE
            prefix.startsWith(byteArrayOf(0x42, 0x5a, 0x68)) -> PackageRejectionCode.NESTED_ARCHIVE
            prefix.startsWith(byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0x00)) -> PackageRejectionCode.NESTED_ARCHIVE
            prefix.hasBytesAt(257, byteArrayOf(0x75, 0x73, 0x74, 0x61, 0x72)) -> PackageRejectionCode.NESTED_ARCHIVE
            prefix.startsWith(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) -> PackageRejectionCode.NATIVE_OR_DYNAMIC_CODE
            prefix.startsWith(byteArrayOf(0x64, 0x65, 0x78, 0x0a)) -> PackageRejectionCode.NATIVE_OR_DYNAMIC_CODE
            prefix.startsWith(byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte())) -> PackageRejectionCode.NATIVE_OR_DYNAMIC_CODE
            prefix.startsWith(byteArrayOf(0x00, 0x61, 0x73, 0x6d)) -> PackageRejectionCode.NATIVE_OR_DYNAMIC_CODE
            else -> null
        }
        if (code != null) reject(code, "Payload magic is forbidden regardless of extension: $path")
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.hasBytesAt(offset: Int, expected: ByteArray): Boolean =
        size >= offset + expected.size && expected.indices.all { this[offset + it] == expected[it] }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val METADATA_FILES = setOf("manifest.json", "integrity.json", "signature.json")
    private const val SNIFF_BYTES = 512
}
