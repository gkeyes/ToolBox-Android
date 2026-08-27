package io.toolbox.tool.packagekit

import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

internal data class IntegrityVerification(
    val evidence: SignatureEvidence,
    val blockers: List<InspectionBlocker>,
)

internal object IntegrityVerifier {
    private val hashPattern = Regex("^[0-9a-fA-F]{64}$")

    fun verify(
        metadata: Map<String, ByteArray>,
        actualHashes: Map<String, String>,
        manifest: ToolManifest,
        limits: PackageLimits,
        keyResolver: PublisherKeyResolver,
    ): IntegrityVerification {
        val integrityBytes = metadata["integrity.json"]
        val signatureBytes = metadata["signature.json"]
        if (integrityBytes == null) {
            return if (signatureBytes == null) {
                IntegrityVerification(
                    SignatureEvidence(SignatureState.UNSIGNED, detail = "No integrity or signature metadata"),
                    emptyList(),
                )
            } else {
                invalid(PackageRejectionCode.INTEGRITY_MALFORMED, "signature.json requires integrity.json")
            }
        }

        val expectedHashes = try {
            parseIntegrity(integrityBytes, limits)
        } catch (error: JsonFormatException) {
            return invalid(PackageRejectionCode.INTEGRITY_MALFORMED, error.message ?: "Malformed integrity.json")
        } catch (error: InspectionRejected) {
            return invalid(PackageRejectionCode.INTEGRITY_MALFORMED, error.rejection.detail)
        }

        val signatureEvidence = if (signatureBytes == null) {
            SignatureEvidence(SignatureState.UNSIGNED, detail = "Integrity verified without a publisher signature")
        } else {
            when (val signature = verifySignature(signatureBytes, integrityBytes, manifest, keyResolver)) {
                is SignatureCheck.Valid -> SignatureEvidence(
                    state = SignatureState.VERIFIED_UNKNOWN,
                    keyId = signature.keyId,
                    detail = "Ed25519 signature is valid; publisher trust is not assigned by package inspection",
                )
                is SignatureCheck.Invalid -> return invalid(signature.code, signature.detail, signature.keyId)
            }
        }

        val contentHashes = actualHashes - setOf("integrity.json", "signature.json")
        if (expectedHashes.keys != contentHashes.keys) {
            val missing = (contentHashes.keys - expectedHashes.keys).sorted()
            val extra = (expectedHashes.keys - contentHashes.keys).sorted()
            return invalid(
                PackageRejectionCode.INTEGRITY_FILE_SET_MISMATCH,
                "Integrity file set is not exact; uncovered=$missing nonexistent=$extra",
                signatureEvidence.keyId,
            )
        }
        val mismatches = expectedHashes.keys.filter { path ->
            !MessageDigest.isEqual(
                expectedHashes.getValue(path).lowercase().toByteArray(Charsets.US_ASCII),
                contentHashes.getValue(path).lowercase().toByteArray(Charsets.US_ASCII),
            )
        }
        if (mismatches.isNotEmpty()) {
            return invalid(
                PackageRejectionCode.INTEGRITY_HASH_MISMATCH,
                "Content hash mismatch: ${mismatches.sorted()}",
                signatureEvidence.keyId,
            )
        }
        return IntegrityVerification(signatureEvidence, emptyList())
    }

    private fun parseIntegrity(bytes: ByteArray, limits: PackageLimits): Map<String, String> {
        val root = StrictJson.parse(bytes).asObject("integrity")
        root.requireOnly("integrity", setOf("schemaVersion", "algorithm", "files"))
        if (root.required("schemaVersion").asInt("integrity.schemaVersion") != 1) {
            throw JsonFormatException("integrity.schemaVersion must be 1")
        }
        if (root.required("algorithm").asString("integrity.algorithm") != "SHA-256") {
            throw JsonFormatException("integrity.algorithm must be SHA-256")
        }
        val files = root.required("files").asObject("integrity.files")
        val result = linkedMapOf<String, String>()
        val collisionKeys = mutableSetOf<String>()
        for ((rawPath, value) in files) {
            val safe = PackagePathPolicy.validate(rawPath, limits)
            if (safe.directory || safe.normalized in setOf("integrity.json", "signature.json")) {
                throw JsonFormatException("integrity.files contains forbidden metadata path: $rawPath")
            }
            if (!collisionKeys.add(safe.collisionKey)) throw JsonFormatException("integrity.files contains colliding paths")
            val hash = value.asString("integrity.files.$rawPath")
            if (!hashPattern.matches(hash)) throw JsonFormatException("Invalid SHA-256 for $rawPath")
            result[safe.normalized] = hash.lowercase()
        }
        return result
    }

    private fun verifySignature(
        bytes: ByteArray,
        integrityBytes: ByteArray,
        manifest: ToolManifest,
        keyResolver: PublisherKeyResolver,
    ): SignatureCheck {
        val parsed = try {
            val root = StrictJson.parse(bytes).asObject("signature")
            root.requireOnly("signature", setOf("schemaVersion", "algorithm", "keyId", "signedFile", "signature"))
            if (root.required("schemaVersion").asInt("signature.schemaVersion") != 1) {
                throw JsonFormatException("signature.schemaVersion must be 1")
            }
            if (root.required("algorithm").asString("signature.algorithm") != "Ed25519") {
                throw JsonFormatException("signature.algorithm must be Ed25519")
            }
            val keyId = root.required("keyId").asString("signature.keyId")
            if (keyId.length !in 8..128) throw JsonFormatException("signature.keyId length is invalid")
            if (root.required("signedFile").asString("signature.signedFile") != "integrity.json") {
                throw JsonFormatException("signature.signedFile must be integrity.json")
            }
            val signature = try {
                Base64.getDecoder().decode(root.required("signature").asString("signature.signature"))
            } catch (error: IllegalArgumentException) {
                throw JsonFormatException("signature.signature is not canonical base64")
            }
            if (signature.size != 64) throw JsonFormatException("Ed25519 signature must be 64 bytes")
            ParsedSignature(keyId, signature)
        } catch (error: JsonFormatException) {
            return SignatureCheck.Invalid(
                PackageRejectionCode.SIGNATURE_MALFORMED,
                error.message ?: "Malformed signature.json",
                null,
            )
        }
        if (manifest.publisher?.keyId != null && manifest.publisher.keyId != parsed.keyId) {
            return SignatureCheck.Invalid(
                PackageRejectionCode.SIGNATURE_KEY_ID_MISMATCH,
                "Manifest and signature publisher key IDs differ",
                parsed.keyId,
            )
        }
        val publicKey = keyResolver.resolve(parsed.keyId)
            ?: return SignatureCheck.Invalid(
                PackageRejectionCode.SIGNATURE_KEY_UNAVAILABLE,
                "No public key is available for ${parsed.keyId}",
                parsed.keyId,
            )
        val expectedKeyId = "sha256:${MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).toHex()}"
        if (!MessageDigest.isEqual(expectedKeyId.toByteArray(), parsed.keyId.toByteArray())) {
            return SignatureCheck.Invalid(
                PackageRejectionCode.SIGNATURE_KEY_ID_MISMATCH,
                "signature.keyId does not match the resolved public key",
                parsed.keyId,
            )
        }
        val valid = runCatching {
            Signature.getInstance("Ed25519").run {
                initVerify(publicKey)
                update(integrityBytes)
                verify(parsed.signature)
            }
        }.getOrDefault(false)
        return if (valid) {
            SignatureCheck.Valid(parsed.keyId)
        } else {
            SignatureCheck.Invalid(
                PackageRejectionCode.SIGNATURE_INVALID,
                "Ed25519 signature does not verify over the raw integrity.json bytes",
                parsed.keyId,
            )
        }
    }

    private fun invalid(code: PackageRejectionCode, detail: String, keyId: String? = null) =
        IntegrityVerification(
            evidence = SignatureEvidence(SignatureState.INVALID, keyId, detail),
            blockers = listOf(InspectionBlocker(code, detail)),
        )

    private sealed interface SignatureCheck {
        data class Valid(val keyId: String) : SignatureCheck
        data class Invalid(val code: PackageRejectionCode, val detail: String, val keyId: String?) : SignatureCheck
    }

    private data class ParsedSignature(val keyId: String, val signature: ByteArray)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
