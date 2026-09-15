package io.toolbox.tool.packagekit

import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal object IntegrityVerifier {
    private val hashPattern = Regex("^[0-9a-fA-F]{64}$")

    fun verify(
        metadata: Map<String, Path>,
        actualHashes: Map<String, String>,
        limits: PackageLimits,
        resources: PackageResourceGuard,
    ) {
        val integrityPath = metadata["integrity.json"]
        val signaturePath = metadata["signature.json"]
        if (integrityPath == null) {
            if (signaturePath != null) reject(PackageRejectionCode.INTEGRITY_MALFORMED, "signature.json requires integrity.json")
            return
        }
        val seen = mutableSetOf<String>()
        val collisions = mutableSetOf<String>()
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            Files.newInputStream(integrityPath).use { input ->
                InputStreamReader(input, decoder).buffered().use { reader ->
                    IntegrityJsonReader(reader, resources).read { rawPath, hash ->
                        val safe = try {
                            PackagePathPolicy.validate(rawPath, limits)
                        } catch (error: InspectionRejected) {
                            throw JsonFormatException("Invalid integrity path: ${error.rejection.code}")
                        }
                        if (safe.directory || safe.normalized in setOf("integrity.json", "signature.json")) {
                            throw JsonFormatException("Integrity contains a directory or metadata path")
                        }
                        if (!seen.add(safe.normalized) || !collisions.add(safe.collisionKey)) {
                            throw JsonFormatException("Duplicate or colliding integrity path")
                        }
                        if (!hashPattern.matches(hash)) throw JsonFormatException("Invalid SHA-256")
                        val actual = actualHashes[safe.normalized]
                            ?: reject(PackageRejectionCode.INTEGRITY_FILE_SET_MISMATCH, "Integrity includes an absent file")
                        if (!MessageDigest.isEqual(actual.lowercase().toByteArray(), hash.lowercase().toByteArray())) {
                            reject(PackageRejectionCode.INTEGRITY_HASH_MISMATCH, "Content hash mismatch: ${safe.normalized}")
                        }
                    }
                }
            }
        } catch (error: JsonFormatException) {
            reject(PackageRejectionCode.INTEGRITY_MALFORMED, error.message ?: "Malformed integrity.json")
        } catch (_: java.nio.charset.CharacterCodingException) {
            reject(PackageRejectionCode.INTEGRITY_MALFORMED, "Integrity JSON is not valid UTF-8")
        }
        val contentCount = actualHashes.keys.count { it != "integrity.json" && it != "signature.json" }
        if (seen.size != contentCount) {
            reject(PackageRejectionCode.INTEGRITY_FILE_SET_MISMATCH, "Integrity must cover every package file")
        }
        if (signaturePath != null) {
            resources.check()
            verifySignature(Files.readAllBytes(signaturePath), integrityPath, resources)
        }
    }

    private fun verifySignature(bytes: ByteArray, integrityPath: Path, resources: PackageResourceGuard) {
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
        val valid = try {
            Signature.getInstance("Ed25519").run {
                initVerify(publicKey)
                Files.newInputStream(integrityPath).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        resources.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) update(buffer, 0, count)
                    }
                }
                verify(parsed.signature)
            }
        } catch (error: InspectionRejected) {
            throw error
        } catch (error: Exception) {
            checkPackageInterrupted()
            false
        }
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
