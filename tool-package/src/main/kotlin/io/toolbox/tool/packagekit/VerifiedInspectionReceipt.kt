package io.toolbox.tool.packagekit

import android.system.Os
import android.system.OsConstants
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal data class VerifiedInspectionReceipt(
    val inspection: ImportInspection,
    val fileHashes: Map<String, String>,
)

internal fun interface ReceiptDirectorySync {
    fun sync(directory: Path)
}

internal object VerifiedInspectionReceipts {
    const val FILE_NAME = ".verified-inspection.json"
    private const val TEMP_FILE_NAME = ".verified-inspection.tmp"
    private const val MAX_RECEIPT_BYTES = 1024L * 1024
    private const val MAX_INTEGRITY_BYTES = 1024L * 1024
    private const val MAX_SIGNATURE_BYTES = 64L * 1024
    private val HASH_PATTERN = Regex("^[0-9a-f]{64}$")

    fun persist(
        sessionDirectory: Path,
        inspection: ImportInspection,
        fileHashes: Map<String, String>,
        limits: PackageLimits,
        directorySync: ReceiptDirectorySync = PlatformReceiptDirectorySync,
    ) {
        validateReceiptFacts(inspection.sourceName, inspection.archive.compressedBytes, fileHashes, limits)
        val reviewHash = reviewHash(inspection, fileHashes)
        val bytes = buildString {
            append("{\"schemaVersion\":1,\"sourceName\":")
            appendJsonString(inspection.sourceName)
            append(",\"compressedBytes\":").append(inspection.archive.compressedBytes)
            append(",\"reviewSha256\":\"").append(reviewHash).append("\",\"files\":{")
            fileHashes.toSortedMap().entries.forEachIndexed { index, (path, hash) ->
                if (index > 0) append(',')
                appendJsonString(path)
                append(':')
                appendJsonString(hash.lowercase())
            }
            append("}}")
        }.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_RECEIPT_BYTES) {
            reject(PackageRejectionCode.RECEIPT_INVALID, "Verified inspection receipt exceeds $MAX_RECEIPT_BYTES bytes")
        }
        val temporary = sessionDirectory.resolve(TEMP_FILE_NAME)
        val receipt = sessionDirectory.resolve(FILE_NAME)
        try {
            FileChannelWriter.writeForced(temporary, bytes)
            Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE)
            directorySync.sync(sessionDirectory)
        } catch (error: InspectionRejected) {
            throw error
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(temporary) }
            runCatching { Files.deleteIfExists(receipt) }
            if (
                error is InterruptedException ||
                error is InterruptedIOException ||
                error is ClosedByInterruptException ||
                Thread.currentThread().isInterrupted
            ) {
                throw error
            }
            reject(PackageRejectionCode.RECEIPT_INVALID, "Verified inspection receipt could not be persisted atomically")
        }
    }

    fun loadAndVerify(
        sessionId: String,
        sessionDirectory: Path,
        limits: PackageLimits,
        keyResolver: PublisherKeyResolver,
    ): VerifiedInspectionReceipt {
        val stored = parse(readReceipt(sessionDirectory), limits)
        val bundle = sessionDirectory.resolve("bundle")
        val snapshot = verifyTree(bundle, stored.fileHashes, limits)
        val metadata = linkedMapOf<String, ByteArray>()
        for ((name, bound) in mapOf(
            "manifest.json" to limits.maxManifestBytes,
            "integrity.json" to MAX_INTEGRITY_BYTES,
            "signature.json" to MAX_SIGNATURE_BYTES,
        )) {
            if (name in snapshot.hashes) metadata[name] = readBoundedFile(bundle.resolve(name), bound)
        }
        val manifestBytes = metadata["manifest.json"]
            ?: reject(PackageRejectionCode.RECEIPT_TREE_MISMATCH, "Verified bundle no longer contains manifest.json")
        val manifest = try {
            ManifestValidator.parse(manifestBytes, limits)
        } catch (error: JsonFormatException) {
            reject(PackageRejectionCode.RECEIPT_INVALID, "Verified manifest can no longer be reconstructed: ${error.message}")
        }
        BundleEntryValidator.validate(manifest, bundle, snapshot.hashes)
        val verification = IntegrityVerifier.verify(metadata, snapshot.hashes, manifest, limits, keyResolver)
        verification.blockers.firstOrNull()?.let { blocker -> reject(blocker.code, blocker.detail) }
        if (verification.evidence.state == SignatureState.INVALID) {
            reject(PackageRejectionCode.SIGNATURE_INVALID, verification.evidence.detail)
        }
        val archive = ArchiveSummary(
            compressedBytes = stored.compressedBytes,
            extractedBytes = snapshot.extractedBytes,
            fileCount = snapshot.hashes.size,
            files = snapshot.hashes.keys.sorted(),
        )
        val risks = StaticRiskScanner.scan(bundle, snapshot.hashes.keys, manifest.securityProfile)
        val inspection = ImportInspection(
            sourceName = stored.sourceName,
            sessionId = sessionId,
            manifest = manifest,
            archive = archive,
            signature = verification.evidence,
            riskFindings = risks,
            blockers = verification.blockers,
        )
        val actualReviewHash = reviewHash(inspection, snapshot.hashes)
        if (!MessageDigest.isEqual(actualReviewHash.toByteArray(), stored.reviewSha256.toByteArray())) {
            reject(PackageRejectionCode.RECEIPT_INVALID, "Verified inspection review facts do not match the receipt")
        }
        return VerifiedInspectionReceipt(inspection, snapshot.hashes.toMap())
    }

    private fun readReceipt(sessionDirectory: Path): ByteArray {
        val receipt = sessionDirectory.resolve(FILE_NAME)
        if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
            reject(PackageRejectionCode.RECEIPT_MISSING, "Verified inspection receipt is missing")
        }
        return readBoundedFile(receipt, MAX_RECEIPT_BYTES)
    }

    private fun parse(bytes: ByteArray, limits: PackageLimits): StoredReceipt {
        val root = try {
            StrictJson.parse(bytes).asObject("verified inspection receipt")
        } catch (error: JsonFormatException) {
            reject(PackageRejectionCode.RECEIPT_INVALID, error.message ?: "Verified inspection receipt is malformed")
        }
        root.requireOnly("verified inspection receipt", setOf("schemaVersion", "sourceName", "compressedBytes", "reviewSha256", "files"))
        if (root.required("schemaVersion").asInt("receipt.schemaVersion") != 1) {
            reject(PackageRejectionCode.RECEIPT_INVALID, "receipt.schemaVersion must be 1")
        }
        val sourceName = root.required("sourceName").asString("receipt.sourceName")
        val compressedBytes = root.required("compressedBytes").asLong("receipt.compressedBytes")
        val reviewSha256 = root.required("reviewSha256").asString("receipt.reviewSha256")
        val filesObject = root.required("files").asObject("receipt.files")
        val hashes = linkedMapOf<String, String>()
        val collisionKeys = mutableSetOf<String>()
        for ((rawPath, value) in filesObject) {
            val safe = try {
                PackagePathPolicy.validate(rawPath, limits)
            } catch (error: InspectionRejected) {
                reject(PackageRejectionCode.RECEIPT_INVALID, error.rejection.detail)
            }
            if (rawPath != safe.normalized || safe.directory || !collisionKeys.add(safe.collisionKey)) {
                reject(PackageRejectionCode.RECEIPT_INVALID, "Receipt contains a directory or colliding path: $rawPath")
            }
            val hash = value.asString("receipt.files.$rawPath")
            if (!HASH_PATTERN.matches(hash)) {
                reject(PackageRejectionCode.RECEIPT_INVALID, "Receipt contains an invalid SHA-256 for $rawPath")
            }
            hashes[safe.normalized] = hash
        }
        validateReceiptFacts(sourceName, compressedBytes, hashes, limits)
        if (!HASH_PATTERN.matches(reviewSha256)) {
            reject(PackageRejectionCode.RECEIPT_INVALID, "Receipt reviewSha256 is invalid")
        }
        return StoredReceipt(sourceName, compressedBytes, reviewSha256, hashes)
    }

    private fun verifyTree(bundle: Path, expected: Map<String, String>, limits: PackageLimits): TreeSnapshot {
        if (!Files.isDirectory(bundle, LinkOption.NOFOLLOW_LINKS)) {
            reject(PackageRejectionCode.RECEIPT_TREE_MISMATCH, "Verified bundle directory is missing")
        }
        val files = linkedMapOf<String, Path>()
        val collisionKeys = mutableSetOf<String>()
        val expectedDirectories = expected.keys.flatMapTo(mutableSetOf()) { file ->
            val segments = file.split('/')
            (1 until segments.size).map { count -> segments.take(count).joinToString("/") }
        }
        Files.walk(bundle).use { paths ->
            paths.forEach { path ->
                if (path == bundle) return@forEach
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                val relative = bundle.relativize(path).joinToString("/") { it.toString() }
                val safe = try {
                    PackagePathPolicy.validate(relative, limits)
                } catch (error: InspectionRejected) {
                    reject(PackageRejectionCode.RECEIPT_TREE_MISMATCH, error.rejection.detail)
                }
                if (!collisionKeys.add(safe.collisionKey)) {
                    reject(PackageRejectionCode.RECEIPT_TREE_MISMATCH, "Verified bundle contains a path collision: $relative")
                }
                when {
                    attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile) ->
                        reject(PackageRejectionCode.RECEIPT_TREE_MISMATCH, "Verified bundle contains a link or special file: $relative")
                    attributes.isDirectory && safe.normalized !in expectedDirectories ->
                        reject(PackageRejectionCode.RECEIPT_TREE_MISMATCH, "Verified bundle contains an added directory: $relative")
                    attributes.isRegularFile -> files[safe.normalized] = path
                }
            }
        }
        if (files.keys != expected.keys) {
            reject(PackageRejectionCode.RECEIPT_TREE_MISMATCH, "Verified bundle file set changed")
        }
        val actual = linkedMapOf<String, String>()
        var total = 0L
        for ((name, path) in files) {
            val hashed = hashBounded(path, limits.maxEntryBytes)
            total += hashed.bytes
            if (total > limits.maxExtractedBytes) {
                reject(PackageRejectionCode.RECEIPT_TREE_MISMATCH, "Verified bundle exceeds its extracted-size bound")
            }
            if (!MessageDigest.isEqual(hashed.sha256.toByteArray(), expected.getValue(name).toByteArray())) {
                reject(PackageRejectionCode.RECEIPT_TREE_MISMATCH, "Verified bundle SHA-256 changed: $name")
            }
            actual[name] = hashed.sha256
        }
        return TreeSnapshot(actual, total)
    }

    private fun hashBounded(path: Path, maxBytes: Long): HashedFile {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Receipt verification interrupted")
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > maxBytes) {
                    reject(PackageRejectionCode.RECEIPT_TREE_MISMATCH, "Verified bundle file exceeds its size bound")
                }
                digest.update(buffer, 0, count)
            }
        }
        return HashedFile(total, digest.digest().toHex())
    }

    private fun readBoundedFile(path: Path, maxBytes: Long): ByteArray {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() > maxBytes) {
            reject(PackageRejectionCode.RECEIPT_INVALID, "Verified metadata is not a bounded regular file")
        }
        return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            input.readNBytes((maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).also {
                if (it.size.toLong() > maxBytes) reject(PackageRejectionCode.RECEIPT_INVALID, "Verified metadata exceeds its size bound")
            }
        }
    }

    private fun validateReceiptFacts(
        sourceName: String,
        compressedBytes: Long,
        fileHashes: Map<String, String>,
        limits: PackageLimits,
    ) {
        if (sourceName.length > 1024) reject(PackageRejectionCode.RECEIPT_INVALID, "Receipt sourceName exceeds 1024 characters")
        if (compressedBytes !in 1..limits.maxCompressedBytes) reject(PackageRejectionCode.RECEIPT_INVALID, "Receipt compressed size is invalid")
        if (fileHashes.isEmpty() || fileHashes.size > limits.maxEntries) reject(PackageRejectionCode.RECEIPT_INVALID, "Receipt file count is invalid")
        if (fileHashes.any { (path, hash) -> path.length > limits.maxPathCharacters || !HASH_PATTERN.matches(hash.lowercase()) }) {
            reject(PackageRejectionCode.RECEIPT_INVALID, "Receipt file facts are invalid")
        }
    }

    private fun reviewHash(inspection: ImportInspection, fileHashes: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String?) {
            if (value == null) {
                digest.update(ByteBuffer.allocate(4).putInt(-1).array())
            } else {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        add(inspection.sourceName)
        add(inspection.archive.compressedBytes.toString())
        add(inspection.archive.extractedBytes.toString())
        add(inspection.archive.fileCount.toString())
        inspection.archive.files.forEach(::add)
        add(inspection.signature.state.name)
        add(inspection.signature.keyId)
        add(inspection.signature.detail)
        inspection.riskFindings.forEach { finding ->
            add(finding.code.name)
            add(finding.file)
            add(finding.detail)
        }
        inspection.blockers.forEach { blocker ->
            add(blocker.code.name)
            add(blocker.detail)
        }
        fileHashes.toSortedMap().forEach { (path, hash) ->
            add(path)
            add(hash.lowercase())
        }
        return digest.digest().toHex()
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private fun JsonValue.asLong(label: String): Long {
        val text = (this as? JsonValue.NumberValue)?.value ?: throw JsonFormatException("$label must be an integer")
        return try {
            text.longValueExact()
        } catch (error: ArithmeticException) {
            throw JsonFormatException("$label must be an integer")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class StoredReceipt(
        val sourceName: String,
        val compressedBytes: Long,
        val reviewSha256: String,
        val fileHashes: Map<String, String>,
    )

    private data class TreeSnapshot(val hashes: Map<String, String>, val extractedBytes: Long)
    private data class HashedFile(val bytes: Long, val sha256: String)
}

private object FileChannelWriter {
    fun writeForced(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
}

internal object PlatformReceiptDirectorySync : ReceiptDirectorySync {
    override fun sync(directory: Path) {
        if (System.getProperty("java.runtime.name") == "Android Runtime") {
            syncAndroid(directory)
        } else {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun syncAndroid(directory: Path) {
        val descriptor = Os.open(
            directory.toString(),
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
            0,
        )
        var failure: Throwable? = null
        try {
            Os.fsync(descriptor)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                Os.close(descriptor)
            } catch (closeError: Throwable) {
                if (failure != null) failure.addSuppressed(closeError) else throw closeError
            }
        }
    }
}

internal object BundleEntryValidator {
    fun validate(manifest: ToolManifest, bundleDirectory: Path, hashes: Map<String, String>) {
        if (manifest.entry !in hashes) {
            reject(PackageRejectionCode.ENTRY_MISSING, "Manifest entry does not exist: ${manifest.entry}")
        }
        manifest.icon?.let { icon ->
            if (icon !in hashes) reject(PackageRejectionCode.ENTRY_MISSING, "Manifest icon does not exist: $icon")
        }
        val prefix = Files.newInputStream(
            bundleDirectory.resolve(manifest.entry),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { it.readNBytes(4096) }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(prefix))
                .toString()
        } catch (error: Exception) {
            reject(PackageRejectionCode.ENTRY_MIME_INVALID, "Manifest entry is not UTF-8 HTML")
        }
        val lower = text.trimStart().lowercase()
        if ('\u0000' in text || (!lower.startsWith("<!doctype html") && !lower.startsWith("<html"))) {
            reject(PackageRejectionCode.ENTRY_MIME_INVALID, "Manifest entry does not have an HTML document signature")
        }
    }
}
