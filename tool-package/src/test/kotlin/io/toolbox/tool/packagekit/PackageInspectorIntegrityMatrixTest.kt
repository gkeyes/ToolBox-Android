package io.toolbox.tool.packagekit

import java.nio.file.Files
import java.security.PublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PackageInspectorIntegrityMatrixTest(private val case: IntegrityCase) {
    @Test
    fun integrityAndRawSignatureMatrixBlocksEveryInvalidPackageBeforeInstall() {
        val sessions = Files.createTempDirectory("tbx-integrity")
        try {
            val resolver = PublisherKeyResolver { keyId -> case.keys[keyId] }
            val inspector = ToolPackageInspectors.create(sessions, keyResolver = resolver)
            val result = inspectSynchronously(
                inspector,
                BytePackageInput("${case.name}.tbx", case.archive),
            )
            val inspection = (result as InspectionResult.Inspected).inspection

            assertEquals(case.expectedState, inspection.signature.state)
            if (case.expectedBlocker == null) {
                assertTrue(inspection.installable)
                assertEquals(DiscardResult.Discarded, discardSynchronously(inspector, inspection.sessionId))
            } else {
                assertFalse(inspection.installable)
                assertEquals(case.expectedBlocker, inspection.blockers.single().code)
                assertEquals(DiscardResult.NotFound, discardSynchronously(inspector, inspection.sessionId))
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
            val validPair = PackageTestFixtures.keyPair()
            val validSigned = PackageTestFixtures.signed(validPair)
            val rawChanged = PackageTestFixtures.signed(validPair, mutateRawIntegrityAfterSigning = true)
            val invalidSignature = PackageTestFixtures.signed(validPair) { signature ->
                signature.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
            }
            val wrongPair = PackageTestFixtures.keyPair()
            return listOf(
                IntegrityCase(
                    "valid-unsigned",
                    PackageTestFixtures.validUnsigned(),
                    emptyMap(),
                    SignatureState.UNSIGNED,
                    null,
                ),
                IntegrityCase(
                    "integrity-missing-file",
                    PackageTestFixtures.validUnsigned(
                        integrityTransform = { it.replace(Regex(",\"app\\.js\":\"[0-9a-f]{64}\""), "") },
                    ),
                    emptyMap(),
                    SignatureState.INVALID,
                    PackageRejectionCode.INTEGRITY_FILE_SET_MISMATCH,
                ),
                IntegrityCase(
                    "integrity-extra-file",
                    PackageTestFixtures.validUnsigned(
                        integrityTransform = {
                            it.dropLast(2) + ",\"ghost.txt\":\"0000000000000000000000000000000000000000000000000000000000000000\"}}"
                        },
                    ),
                    emptyMap(),
                    SignatureState.INVALID,
                    PackageRejectionCode.INTEGRITY_FILE_SET_MISMATCH,
                ),
                IntegrityCase(
                    "integrity-tampered-content",
                    PackageTestFixtures.validUnsigned(
                        integrityTransform = { it.replaceFirst(Regex("[0-9a-f]{64}"), "0".repeat(64)) },
                    ),
                    emptyMap(),
                    SignatureState.INVALID,
                    PackageRejectionCode.INTEGRITY_HASH_MISMATCH,
                ),
                IntegrityCase(
                    "integrity-malformed",
                    PackageTestFixtures.validUnsigned(integrityTransform = { "{" }),
                    emptyMap(),
                    SignatureState.INVALID,
                    PackageRejectionCode.INTEGRITY_MALFORMED,
                ),
                IntegrityCase(
                    "signature-valid-unknown",
                    validSigned.archive,
                    mapOf(validSigned.keyId to validPair.public),
                    SignatureState.VERIFIED_UNKNOWN,
                    null,
                ),
                IntegrityCase(
                    "signature-raw-bytes-changed",
                    rawChanged.archive,
                    mapOf(rawChanged.keyId to validPair.public),
                    SignatureState.INVALID,
                    PackageRejectionCode.SIGNATURE_INVALID,
                ),
                IntegrityCase(
                    "signature-key-id-mismatch",
                    validSigned.archive,
                    mapOf(validSigned.keyId to wrongPair.public),
                    SignatureState.INVALID,
                    PackageRejectionCode.SIGNATURE_KEY_ID_MISMATCH,
                ),
                IntegrityCase(
                    "signature-key-unavailable",
                    validSigned.archive,
                    emptyMap(),
                    SignatureState.INVALID,
                    PackageRejectionCode.SIGNATURE_KEY_UNAVAILABLE,
                ),
                IntegrityCase(
                    "signature-invalid",
                    invalidSignature.archive,
                    mapOf(invalidSignature.keyId to validPair.public),
                    SignatureState.INVALID,
                    PackageRejectionCode.SIGNATURE_INVALID,
                ),
            ).map { arrayOf(it) }
        }
    }
}

data class IntegrityCase(
    val name: String,
    val archive: ByteArray,
    val keys: Map<String, PublicKey>,
    val expectedState: SignatureState,
    val expectedBlocker: PackageRejectionCode?,
) {
    override fun toString() = name
}
