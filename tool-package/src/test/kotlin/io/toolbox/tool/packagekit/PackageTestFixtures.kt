package io.toolbox.tool.packagekit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

internal data class BytePackageInput(
    override val displayName: String,
    val bytes: ByteArray,
) : PackageInput {
    override fun openStream() = ByteArrayInputStream(bytes)
}

internal fun inspectSynchronously(inspector: ToolPackageInspector, input: PackageInput): InspectionResult {
    return runSuspend { inspector.inspect(input) }
}

internal fun discardSynchronously(inspector: ToolPackageInspector, sessionId: String): DiscardResult {
    return runSuspend { inspector.discard(sessionId) }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome = result
            latch.countDown()
        }
    })
    check(latch.await(10, TimeUnit.SECONDS)) { "Inspection timed out" }
    return checkNotNull(outcome).getOrThrow()
}

internal object PackageTestFixtures {
    val indexHtml = "<!doctype html><html><head></head><body>fixture</body></html>".toByteArray()
    val appJs = "document.body.dataset.ready = 'true';".toByteArray()

    fun manifest(
        entry: String = "index.html",
        icon: String? = null,
        publisherKeyId: String? = null,
        extraField: String = "",
    ): ByteArray {
        val iconField = icon?.let { "\"icon\":\"$it\"," }.orEmpty()
        val publisher = publisherKeyId?.let { ",\"publisher\":{\"name\":\"Fixture\",\"keyId\":\"$it\"}" }.orEmpty()
        return ("{" +
            "\"schemaVersion\":1," +
            "\"id\":\"io.toolbox.fixture\"," +
            "\"name\":\"Fixture\"," +
            "\"version\":\"1.0.0\"," +
            "\"versionCode\":1," +
            "\"entry\":\"$entry\"," +
            iconField +
            "\"apiVersion\":\"1.0\"," +
            "\"minHostVersion\":\"1.0.0\"," +
            "\"permissions\":[]," +
            "\"securityProfile\":\"strict\"" +
            publisher + extraField + "}").toByteArray()
    }

    fun validUnsigned(
        additionalFiles: LinkedHashMap<String, ByteArray> = linkedMapOf(),
        manifestBytes: ByteArray = manifest(),
        integrityTransform: (String) -> String = { it },
    ): ByteArray {
        val content = linkedMapOf(
            "manifest.json" to manifestBytes,
            "index.html" to indexHtml,
            "app.js" to appJs,
        )
        content.putAll(additionalFiles)
        val integrity = integrityTransform(integrityJson(content))
        return zip(content + ("integrity.json" to integrity.toByteArray()))
    }

    fun withCorruptedStoredPayload(): ByteArray {
        val payloadName = "crc-payload.txt"
        val content = linkedMapOf(
            "manifest.json" to manifest(),
            "index.html" to indexHtml,
            "app.js" to appJs,
            payloadName to "original payload".toByteArray(),
        )
        val archive = zip(
            content + ("integrity.json" to integrityJson(content).toByteArray()),
            storedEntry = payloadName,
        )
        val nameBytes = payloadName.toByteArray()
        val nameOffset = firstSignature(archive, nameBytes)
        val local = nameOffset - 30
        check(u32(archive, local) == 0x04034b50L)
        val payloadOffset = local + 30 + u16(archive, local + 26) + u16(archive, local + 28)
        archive[payloadOffset] = (archive[payloadOffset].toInt() xor 1).toByte()
        return archive
    }

    fun signed(
        keyPair: KeyPair,
        mutateRawIntegrityAfterSigning: Boolean = false,
        signatureTransform: (ByteArray) -> ByteArray = { it },
    ): SignedFixture {
        val keyId = keyId(keyPair)
        val manifest = manifest(publisherKeyId = keyId)
        val content = linkedMapOf(
            "manifest.json" to manifest,
            "index.html" to indexHtml,
            "app.js" to appJs,
        )
        val rawIntegrity = integrityJson(content).toByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(rawIntegrity)
            sign()
        }
        val storedIntegrity = if (mutateRawIntegrityAfterSigning) {
            rawIntegrity.toString(Charsets.UTF_8).replace("{\"schemaVersion\"", "{ \"schemaVersion\"").toByteArray()
        } else {
            rawIntegrity
        }
        val signatureJson = "{" +
            "\"schemaVersion\":1," +
            "\"algorithm\":\"Ed25519\"," +
            "\"keyId\":\"$keyId\"," +
            "\"signedFile\":\"integrity.json\"," +
            "\"signature\":\"${Base64.getEncoder().encodeToString(signatureTransform(signature))}\"}"
        val archive = zip(content + linkedMapOf(
            "integrity.json" to storedIntegrity,
            "signature.json" to signatureJson.toByteArray(),
        ))
        return SignedFixture(archive, keyId, keyPair)
    }

    fun zip(
        entries: Map<String, ByteArray>,
        unixMode: Int? = null,
        storedEntry: String? = null,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name).apply {
                    time = 0L
                    if (name == storedEntry) {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    }
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val archive = output.toByteArray()
        if (unixMode != null) patchFirstCentralUnixMode(archive, unixMode)
        return archive
    }

    fun withLocalMetadataContradiction(source: ByteArray): ByteArray = source.clone().also { archive ->
        val central = firstSignature(archive, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        val local = u32(archive, central + 42).toInt()
        val flags = u16(archive, central + 8)
        if (flags and 0x8 != 0) {
            putU16(archive, central + 8, flags and 0x8.inv())
            putU16(archive, local + 6, flags and 0x8.inv())
        } else {
            archive[local + 14] = (archive[local + 14].toInt() xor 1).toByte()
        }
    }

    fun withDescriptorContradiction(source: ByteArray): ByteArray = source.clone().also { archive ->
        val central = firstSignature(archive, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        check(u16(archive, central + 8) and 0x8 != 0) { "fixture must use a data descriptor" }
        val local = u32(archive, central + 42).toInt()
        val dataOffset = local + 30 + u16(archive, local + 26) + u16(archive, local + 28)
        val descriptor = dataOffset + u32(archive, central + 20).toInt()
        val crcOffset = if (u32(archive, descriptor) == 0x08074b50L) descriptor + 4 else descriptor
        archive[crcOffset] = (archive[crcOffset].toInt() xor 1).toByte()
    }

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun keyId(pair: KeyPair): String =
        "sha256:${MessageDigest.getInstance("SHA-256").digest(pair.public.encoded).toHex()}"

    fun assertDirectoryEmpty(directory: Path) {
        if (!Files.exists(directory)) return
        Files.list(directory).use { check(it.findAny().isEmpty) { "Rejection residue remains in $directory" } }
    }

    fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun integrityJson(files: Map<String, ByteArray>): String = buildString {
        append("{\"schemaVersion\":1,\"algorithm\":\"SHA-256\",\"files\":{")
        files.entries.forEachIndexed { index, (path, bytes) ->
            if (index > 0) append(',')
            append('"').append(path).append("\":\"")
            append(MessageDigest.getInstance("SHA-256").digest(bytes).toHex())
            append('"')
        }
        append("}}")
    }

    private fun patchFirstCentralUnixMode(archive: ByteArray, mode: Int) {
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val index = firstSignature(archive, signature)
        putU16(archive, index + 4, (3 shl 8) or 20)
        putU32(archive, index + 38, mode.toLong() shl 16)
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }

    private fun firstSignature(bytes: ByteArray, signature: ByteArray): Int =
        bytes.indices.firstOrNull { offset ->
            offset + signature.size <= bytes.size && signature.indices.all { bytes[offset + it] == signature[it] }
        } ?: error("ZIP signature missing")

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

internal data class SignedFixture(val archive: ByteArray, val keyId: String, val keyPair: KeyPair)
