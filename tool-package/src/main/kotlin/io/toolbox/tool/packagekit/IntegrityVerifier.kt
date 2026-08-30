package io.toolbox.tool.packagekit

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal object IntegrityVerifier {
    private val hashPattern = Regex("^[0-9a-fA-F]{64}$")

    fun verify(
        metadata: Map<String, ByteArray>,
        actualHashes: Map<String, String>,
        limits: PackageLimits,
    ) {
        val integrityBytes = metadata["integrity.json"]
        val signatureBytes = metadata["signature.json"]
        if (integrityBytes == null) {
            if (signatureBytes != null) {
                reject(PackageRejectionCode.INTEGRITY_MALFORMED, "signature.json requires integrity.json")
            }
            return
        }
        val expectedHashes = try {
            parseIntegrity(integrityBytes, limits)
        } catch (error: JsonFormatException) {
            reject(PackageRejectionCode.INTEGRITY_MALFORMED, error.message ?: "Malformed integrity.json")
        } catch (error: InspectionRejected) {
            reject(PackageRejectionCode.INTEGRITY_MALFORMED, error.rejection.detail)
        }
        val contentHashes = actualHashes - setOf("integrity.json", "signature.json")
        if (expectedHashes.keys != contentHashes.keys) {
            reject(PackageRejectionCode.INTEGRITY_FILE_SET_MISMATCH, "Integrity file set must cover every package file")
        }
        val mismatch = expectedHashes.keys.firstOrNull { path ->
            !MessageDigest.isEqual(
                expectedHashes.getValue(path).lowercase().toByteArray(Charsets.US_ASCII),
                contentHashes.getValue(path).lowercase().toByteArray(Charsets.US_ASCII),
            )
        }
        if (mismatch != null) {
            reject(PackageRejectionCode.INTEGRITY_HASH_MISMATCH, "Content hash mismatch: $mismatch")
        }
        if (signatureBytes != null) verifySignature(signatureBytes, integrityBytes)
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
        val result = linkedMapOf<String, String>()
        val collisions = mutableSetOf<String>()
        for ((rawPath, value) in root.required("files").asObject("integrity.files")) {
            val safe = PackagePathPolicy.validate(rawPath, limits)
            if (safe.directory || safe.normalized in setOf("integrity.json", "signature.json")) {
                throw JsonFormatException("integrity.files contains forbidden metadata path: $rawPath")
            }
            if (!collisions.add(safe.collisionKey)) throw JsonFormatException("integrity.files contains colliding paths")
            val hash = value.asString("integrity.files.$rawPath")
            if (!hashPattern.matches(hash)) throw JsonFormatException("Invalid SHA-256 for $rawPath")
            result[safe.normalized] = hash.lowercase()
        }
        return result
    }

    private fun verifySignature(bytes: ByteArray, integrityBytes: ByteArray) {
        val parsed = try {
            val root = StrictJson.parse(bytes).asObject("signature")
            root.requireOnly(
                "signature",
                setOf("schemaVersion", "algorithm", "keyId", "publicKey", "signedFile", "signature"),
            )
            if (root.required("schemaVersion").asInt("signature.schemaVersion") != 1) {
                throw JsonFormatException("signature.schemaVersion must be 1")
            }
            if (root.required("algorithm").asString("signature.algorithm") != "Ed25519") {
                throw JsonFormatException("signature.algorithm must be Ed25519")
            }
            if (root.required("signedFile").asString("signature.signedFile") != "integrity.json") {
                throw JsonFormatException("signature.signedFile must be integrity.json")
            }
            val keyId = root.required("keyId").asString("signature.keyId")
            if (keyId.length !in 8..128) throw JsonFormatException("signature.keyId length is invalid")
            val publicKey = decodeCanonicalBase64(root.required("publicKey").asString("signature.publicKey"))
            val signature = decodeCanonicalBase64(root.required("signature").asString("signature.signature"))
            if (signature.size != 64) throw JsonFormatException("Ed25519 signature must be 64 bytes")
            ParsedSignature(keyId, publicKey, signature)
        } catch (error: JsonFormatException) {
            reject(PackageRejectionCode.SIGNATURE_MALFORMED, error.message ?: "Malformed signature.json")
        }
        val publicKey = try {
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(parsed.publicKey))
        } catch (_: Exception) {
            reject(PackageRejectionCode.SIGNATURE_MALFORMED, "signature.publicKey is not an Ed25519 public key")
        }
        val expectedKeyId = "sha256:${MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).toHex()}"
        if (!MessageDigest.isEqual(expectedKeyId.toByteArray(), parsed.keyId.toByteArray())) {
            reject(PackageRejectionCode.SIGNATURE_KEY_ID_MISMATCH, "signature.keyId does not match publicKey")
        }
        val valid = runCatching {
            Signature.getInstance("Ed25519").run {
                initVerify(publicKey)
                update(integrityBytes)
                verify(parsed.signature)
            }
        }.getOrDefault(false)
        if (!valid) {
            reject(PackageRejectionCode.SIGNATURE_INVALID, "Ed25519 signature is invalid")
        }
    }

    private fun decodeCanonicalBase64(value: String): ByteArray {
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw JsonFormatException("Signature metadata is not base64")
        }
        if (Base64.getEncoder().encodeToString(decoded) != value) {
            throw JsonFormatException("Signature metadata is not canonical base64")
        }
        return decoded
    }

    private data class ParsedSignature(val keyId: String, val publicKey: ByteArray, val signature: ByteArray)
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
