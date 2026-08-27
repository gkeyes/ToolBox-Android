package io.toolbox.tool.packagekit

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MaliciousPackageMatrixTest(private val case: ArchiveCase) {
    @Test
    fun adversarialArchiveFailsClosedWithoutSessionResidue() {
        val sessions = Files.createTempDirectory("tbx-rejection")
        try {
            val result = inspectSynchronously(
                ToolPackageInspectors.create(sessions, case.limits),
                BytePackageInput("${case.name}.tbx", case.archive),
            )

            val rejection = result as InspectionResult.Rejected
            assertEquals(case.expected, rejection.rejection.code)
            case.expectedDetailContains?.let { expected ->
                assertTrue(
                    "Expected rejection detail to contain '$expected', but was '${rejection.rejection.detail}'",
                    rejection.rejection.detail.contains(expected),
                )
            }
            PackageTestFixtures.assertDirectoryEmpty(sessions)
        } finally {
            PackageTestFixtures.deleteTree(sessions)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val validManifest = PackageTestFixtures.manifest()
            val base = linkedMapOf(
                "manifest.json" to validManifest,
                "index.html" to PackageTestFixtures.indexHtml,
            )
            return listOf(
                archiveCase("malformed", byteArrayOf(1, 2, 3), expected = PackageRejectionCode.MALFORMED_ARCHIVE),
                archiveCase("local-metadata-contradiction", PackageTestFixtures.withLocalMetadataContradiction(PackageTestFixtures.validUnsigned()), expected = PackageRejectionCode.MALFORMED_ARCHIVE),
                archiveCase("descriptor-contradiction", PackageTestFixtures.withDescriptorContradiction(PackageTestFixtures.validUnsigned()), expected = PackageRejectionCode.MALFORMED_ARCHIVE),
                archiveCase(
                    "streamed-crc32-mismatch",
                    PackageTestFixtures.withCorruptedStoredPayload(),
                    expected = PackageRejectionCode.EXTRACTION_FAILED,
                    expectedDetailContains = "CRC32",
                ),
                archiveCase("traversal", PackageTestFixtures.zip(linkedMapOf("../escape.txt" to byteArrayOf(1)) + base), expected = PackageRejectionCode.PATH_INVALID),
                archiveCase("backslash", PackageTestFixtures.zip(linkedMapOf("assets\\escape.txt" to byteArrayOf(1)) + base), expected = PackageRejectionCode.PATH_INVALID),
                archiveCase("case-collision", PackageTestFixtures.zip(base + linkedMapOf("asset.txt" to byteArrayOf(1), "ASSET.TXT" to byteArrayOf(2))), expected = PackageRejectionCode.PATH_COLLISION),
                archiveCase("unicode-collision", PackageTestFixtures.zip(base + linkedMapOf("café.txt" to byteArrayOf(1), "cafe\u0301.txt" to byteArrayOf(2))), expected = PackageRejectionCode.PATH_COLLISION),
                archiveCase("file-directory-collision", PackageTestFixtures.zip(base + linkedMapOf("assets" to byteArrayOf(1), "assets/item.txt" to byteArrayOf(2))), expected = PackageRejectionCode.PATH_COLLISION),
                archiveCase("symlink", PackageTestFixtures.zip(linkedMapOf("link" to "target".toByteArray()) + base, unixMode = 0xa1ff), expected = PackageRejectionCode.SYMLINK),
                archiveCase("special-file", PackageTestFixtures.zip(linkedMapOf("device" to byteArrayOf()) + base, unixMode = 0x21ff), expected = PackageRejectionCode.SPECIAL_FILE),
                archiveCase("directory-payload", PackageTestFixtures.zip(linkedMapOf("assets/" to byteArrayOf(1)) + base), expected = PackageRejectionCode.SPECIAL_FILE),
                archiveCase("nested-by-magic", PackageTestFixtures.zip(base + ("payload.dat" to byteArrayOf(0x1f, 0x8b.toByte(), 0x08))), expected = PackageRejectionCode.NESTED_ARCHIVE),
                archiveCase("native-by-magic", PackageTestFixtures.zip(base + ("payload.dat" to byteArrayOf(0x7f, 0x45, 0x4c, 0x46))), expected = PackageRejectionCode.NATIVE_OR_DYNAMIC_CODE),
                archiveCase("compressed-limit", PackageTestFixtures.validUnsigned(), PackageLimits(maxCompressedBytes = 100), PackageRejectionCode.COMPRESSED_SIZE_LIMIT),
                archiveCase("entry-count-limit", PackageTestFixtures.validUnsigned(), PackageLimits(maxEntries = 3), PackageRejectionCode.ENTRY_COUNT_LIMIT),
                archiveCase("entry-size-limit", PackageTestFixtures.validUnsigned(), PackageLimits(maxEntryBytes = 100), PackageRejectionCode.ENTRY_SIZE_LIMIT),
                archiveCase("total-size-limit", PackageTestFixtures.validUnsigned(), PackageLimits(maxExtractedBytes = 100), PackageRejectionCode.TOTAL_SIZE_LIMIT),
                archiveCase(
                    "ratio-limit",
                    PackageTestFixtures.validUnsigned(linkedMapOf("repeated.txt" to ByteArray(4096))),
                    PackageLimits(maxCompressionRatio = 2.0),
                    PackageRejectionCode.COMPRESSION_RATIO_LIMIT,
                ),
                archiveCase("path-length-limit", PackageTestFixtures.zip(linkedMapOf("manifest.json" to validManifest)), PackageLimits(maxPathCharacters = 12), PackageRejectionCode.PATH_TOO_LONG),
                archiveCase("manifest-size-limit", PackageTestFixtures.validUnsigned(), PackageLimits(maxManifestBytes = 64), PackageRejectionCode.MANIFEST_TOO_LARGE),
                archiveCase(
                    "strict-schema",
                    PackageTestFixtures.validUnsigned(manifestBytes = PackageTestFixtures.manifest(extraField = ",\"unexpected\":true")),
                    expected = PackageRejectionCode.MANIFEST_INVALID,
                ),
                archiveCase(
                    "missing-declared-icon",
                    PackageTestFixtures.validUnsigned(manifestBytes = PackageTestFixtures.manifest(icon = "missing.png")),
                    expected = PackageRejectionCode.ENTRY_MISSING,
                ),
                archiveCase(
                    "invalid-entry-mime",
                    PackageTestFixtures.validUnsigned(
                        additionalFiles = linkedMapOf("bad.html" to byteArrayOf(0, 1, 2)),
                        manifestBytes = PackageTestFixtures.manifest(entry = "bad.html"),
                    ),
                    expected = PackageRejectionCode.ENTRY_MIME_INVALID,
                ),
            ).map { arrayOf(it) }
        }

        private fun archiveCase(
            name: String,
            archive: ByteArray,
            limits: PackageLimits = PackageLimits(),
            expected: PackageRejectionCode,
            expectedDetailContains: String? = null,
        ) = ArchiveCase(name, archive, limits, expected, expectedDetailContains)
    }
}

data class ArchiveCase(
    val name: String,
    val archive: ByteArray,
    val limits: PackageLimits,
    val expected: PackageRejectionCode,
    val expectedDetailContains: String?,
) {
    override fun toString() = name
}
