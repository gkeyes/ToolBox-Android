package io.toolbox.tool.packagekit

import io.toolbox.core.data.memory.InMemoryCoreData
import io.toolbox.tool.packagekit.lifecycle.PackageInstallResult
import io.toolbox.tool.packagekit.lifecycle.ToolPackageManagers
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageWasmResourceTest {
    @Test
    fun wasmAndCompanionResourcesInstallWithTheirOriginalBytesWithoutCapabilities() = runBlocking {
        withHarness {
            val resources = linkedMapOf(
                "compute.wasm" to WASM_ADD,
                "upper.WASM" to WASM_ADD,
                "modules/side.so" to WASM_ADD,
                "modules/extensionless" to WASM_ADD,
                "modules/custom-section.wasm" to WASM_WITH_USTAR_CUSTOM_SECTION,
                "assets/model.data" to ByteArray(1024) { it.toByte() },
                "assets/payload.bin" to byteArrayOf(0, 0xff.toByte(), 0x80.toByte(), 13, 10),
                "assets/raw" to byteArrayOf(0, 0xfe.toByte(), 0xff.toByte()),
            )
            val content = content(resources = resources)
            assertEquals(
                PackageInstallResult.Installed(TOOL_ID, 1, false),
                manager().importAndInstall(archive("wasm", content)),
            )
            content.forEach { (name, bytes) -> assertArrayEquals(name, bytes, Files.readAllBytes(bundle(1).resolve(name))) }
            assertTrue(repositories.grants.observeGrants(TOOL_ID).first().isEmpty())
            assertClean()
        }
    }

    @Test
    fun nativeAndArchivePayloadsCannotUseWasmExtensionToBypassContentChecks() = runBlocking {
        val cases = listOf(
            "elf" to byteArrayOf(0x7f, 0x45, 0x4c, 0x46),
            "dex" to byteArrayOf(0x64, 0x65, 0x78, 0x0a),
            "class" to byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte()),
            "zip" to byteArrayOf(0x50, 0x4b, 0x03, 0x04),
        )
        cases.forEach { (name, bytes) ->
            withHarness {
                val result = manager().importAndInstall(archive(name, content(resources = mapOf("payload.wasm" to bytes))))
                assertRejected(
                    if (name == "zip") PackageRejectionCode.NESTED_ARCHIVE else PackageRejectionCode.NATIVE_OR_DYNAMIC_CODE,
                    result,
                )
                assertTrue(repositories.catalog.observeTools().first().isEmpty())
                assertFalse(Files.exists(host.resolve("miniapps/$TOOL_ID")))
                assertClean()
            }
        }
    }

    @Test
    fun legitimatePackageExceedingFormerResourceLimitsInstallsAndRetainsAllHashes() = runBlocking {
        withHarness {
            val expectedHashes = linkedMapOf<String, String>()
            val source = packages.resolve("large.tbx")
            ZipOutputStream(Files.newOutputStream(source).buffered()).use { zip ->
                content().forEach { (name, bytes) ->
                    putBytes(zip, name, bytes)
                    expectedHashes[name] = sha256(bytes)
                }
                // 64 MiB of compressible data and 21 MiB of fresh pseudorandom bytes exceed the
                // former total/file/ratio/compressed limits without allocating either file in memory.
                expectedHashes["assets/large.data"] = putGenerated(zip, "assets/large.data", 64 * MIB, random = false)
                expectedHashes["assets/random.bin"] = putGenerated(zip, "assets/random.bin", 21 * MIB, random = true)
                repeat(513) { index ->
                    val name = "data/part-${index.toString().padStart(4, '0')}.bin"
                    val bytes = byteArrayOf((index and 255).toByte(), (index ushr 8).toByte())
                    putBytes(zip, name, bytes)
                    expectedHashes[name] = sha256(bytes)
                }
                zip.putNextEntry(ZipEntry("integrity.json"))
                val whitespace = ByteArray(8192) { ' '.code.toByte() }
                repeat(128) { zip.write(whitespace) }
                zip.write(integrity(expectedHashes).toByteArray())
                zip.closeEntry()
            }
            ZipFile(source.toFile()).use { zip ->
                assertTrue(Files.size(source) > 20 * MIB)
                assertTrue(zip.size() > 512)
                assertTrue(zip.entries().asSequence().sumOf { it.size } > 80 * MIB)
                val large = zip.getEntry("assets/large.data")
                assertTrue(large.size > 20 * MIB)
                assertTrue(large.size > 100 * large.compressedSize)
                assertTrue(zip.getEntry("integrity.json").size > MIB)
            }

            assertEquals(
                PackageInstallResult.Installed(TOOL_ID, 1, false),
                manager().importAndInstall(FileInput(source)),
            )
            expectedHashes.forEach { (name, hash) -> assertEquals(name, hash, sha256(bundle(1).resolve(name))) }
            assertTrue(Files.size(bundle(1).resolve("integrity.json")) > MIB)
            assertClean()
        }
    }

    @Test
    fun spaceLossDuringCopyExtractionAndStagingPreservesPreviousVersionAndCleansResidue() = runBlocking {
        listOf("copy", "extract", "stage").forEach { phase ->
            withHarness {
                installPreviousVersion()
                val random = ByteArray(256 * 1024).also(Random(27)::nextBytes)
                random[0] = 0x54
                val incoming = archive("space-$phase", content(version = 2, resources = mapOf("assets/probe.bin" to random)))
                var failedAfterWrite = false
                val probe = PackageResourceProbe {
                    val phaseRoot = host.resolve(if (phase == "stage") "miniapps/.staging" else "miniapps/.imports")
                    val suffix = if (phase == "copy") Path.of("source.tbx") else Path.of("bundle/assets/probe.bin")
                    val hasWrittenChunk = hasFileAtLeast(phaseRoot, suffix, 8192)
                    if (hasWrittenChunk) failedAfterWrite = true
                    PackageResourceSnapshot(if (failedAfterWrite) 0 else Long.MAX_VALUE, lowMemory = false)
                }

                assertRejected(PackageRejectionCode.INSUFFICIENT_SPACE, manager(probe).importAndInstall(incoming))
                assertTrue("$phase must fail after at least one chunk was written", failedAfterWrite)
                assertPreviousVersion()
                assertClean()
            }
        }
    }

    @Test
    fun declaredExtractionSpaceIsCheckedBeforeWritingBundleContents() = runBlocking {
        withHarness {
            installPreviousVersion()
            val incoming = archive("preflight", content(version = 2, resources = mapOf("large.data" to ByteArray(128 * 1024))))
            val available = Files.size(incoming.path) + 8192
            var bundleContentsWritten = false
            val probe = PackageResourceProbe { directory ->
                val bundle = directory.resolve("bundle")
                if (Files.exists(bundle)) {
                    Files.walk(bundle).use { paths ->
                        if (paths.anyMatch { Files.isRegularFile(it) && Files.size(it) > 0 }) bundleContentsWritten = true
                    }
                }
                PackageResourceSnapshot(available, lowMemory = false)
            }
            assertRejected(PackageRejectionCode.INSUFFICIENT_SPACE, manager(probe).importAndInstall(incoming))
            assertFalse(bundleContentsWritten)
            assertPreviousVersion()
            assertClean()
        }
    }

    @Test
    fun systemMemoryPressureReturnsTypedFailureAndPreservesPreviousVersion() = runBlocking {
        withHarness {
            installPreviousVersion()
            val probe = PackageResourceProbe { PackageResourceSnapshot(Long.MAX_VALUE, lowMemory = true) }
            val result = manager(probe).importAndInstall(archive("memory", content(version = 2)))
            assertRejected(PackageRejectionCode.INSUFFICIENT_RESOURCES, result)
            assertPreviousVersion()
            assertClean()
        }
    }

    @Test
    fun streamingIntegrityRejectsDuplicateKeysHashAndFileSetChanges() = runBlocking {
        val content = content(resources = mapOf("module.wasm" to WASM_ADD))
        val hashes = content.mapValues { sha256(it.value) }
        val fields = hashes.entries.joinToString(",") { (name, hash) -> "\"$name\":\"$hash\"" }
        val duplicate = "\"index.html\":\"${hashes.getValue("index.html")}\""
        val cases = listOf(
            "duplicate-root" to ("{\"schemaVersion\":1,\"schemaVersion\":1,\"algorithm\":\"SHA-256\",\"files\":{$fields}}" to PackageRejectionCode.INTEGRITY_MALFORMED),
            "duplicate-file" to ("{\"schemaVersion\":1,\"algorithm\":\"SHA-256\",\"files\":{$fields,$duplicate}}" to PackageRejectionCode.INTEGRITY_MALFORMED),
            "colliding-file" to (integrity(hashes + ("INDEX.html" to hashes.getValue("index.html"))) to PackageRejectionCode.INTEGRITY_MALFORMED),
            "wrong-hash" to (integrity(hashes + ("module.wasm" to "0".repeat(64))) to PackageRejectionCode.INTEGRITY_HASH_MISMATCH),
            "missing-file" to (integrity(hashes - "module.wasm") to PackageRejectionCode.INTEGRITY_FILE_SET_MISMATCH),
            "extra-file" to (integrity(hashes + ("missing.bin" to sha256(byteArrayOf(1)))) to PackageRejectionCode.INTEGRITY_FILE_SET_MISMATCH),
            "trailing-json" to (integrity(hashes) + "{}" to PackageRejectionCode.INTEGRITY_MALFORMED),
        )
        cases.forEach { (name, case) ->
            withHarness {
                assertRejected(case.second, manager().importAndInstall(archive(name, content, case.first.toByteArray())))
                assertTrue(repositories.catalog.observeTools().first().isEmpty())
                assertClean()
            }
        }
    }

    @Test
    fun signatureUsesRawIntegrityBytesAcrossChunksAndRejectsSemanticallyEqualReformatting() = runBlocking {
        val content = content(resources = mapOf("module.wasm" to WASM_ADD))
        // Noncanonical order and whitespace across many read boundaries must remain signed verbatim.
        val raw = (" \n".repeat(40_000) + integrity(content.mapValues { sha256(it.value) }) + "\n").toByteArray()
        val signature = signature(raw)
        withHarness {
            assertEquals(
                PackageInstallResult.Installed(TOOL_ID, 1, false),
                manager().importAndInstall(archive("signed", content, raw, signature)),
            )
            assertArrayEquals(raw, Files.readAllBytes(bundle(1).resolve("integrity.json")))
            assertClean()
        }
        withHarness {
            val reformatted = raw.copyOf().also { it[0] = '\n'.code.toByte() }
            assertRejected(
                PackageRejectionCode.SIGNATURE_INVALID,
                manager().importAndInstall(archive("reformatted", content, reformatted, signature)),
            )
            assertTrue(repositories.catalog.observeTools().first().isEmpty())
            assertClean()
        }
    }

    @Test
    fun accumulatedSizeOverflowFailsClosed() {
        val rejection = try {
            packageByteTotal(Long.MAX_VALUE, 1)
            throw AssertionError("Overflow must be rejected")
        } catch (error: InspectionRejected) {
            error.rejection
        }
        assertEquals(PackageRejectionCode.MALFORMED_ARCHIVE, rejection.code)
    }

    @Test
    fun consistentZipMetadataCannotUnderstateActualInflatedContent() = runBlocking {
        withHarness {
            installPreviousVersion()
            val incoming = archive("forged-size", content(version = 2, resources = mapOf("assets/forged.bin" to ByteArray(32 * 1024))))
            understateEntrySize(incoming.path, "assets/forged.bin")
            assertRejected(PackageRejectionCode.EXTRACTION_FAILED, manager().importAndInstall(incoming))
            assertPreviousVersion()
            assertClean()
        }
    }

    private class Harness {
        val root: Path = Files.createTempDirectory("toolbox-wasm-resource-test-")
        val host: Path = Files.createDirectories(root.resolve("host"))
        val packages: Path = Files.createDirectories(root.resolve("packages"))
        val repositories = InMemoryCoreData.create()

        fun manager(probe: PackageResourceProbe = PackageResourceProbe { PackageResourceSnapshot(Long.MAX_VALUE, false) }) =
            ToolPackageManagers.create(
                privateFilesDirectory = host.toFile(),
                catalog = repositories.catalog,
                lifecycle = repositories.lifecycle,
                transactions = repositories.installs,
                resourceProbe = probe,
            )

        fun bundle(version: Int): Path = host.resolve("miniapps/$TOOL_ID/versions/$version/bundle")

        fun archive(
            name: String,
            content: Map<String, ByteArray>,
            integrityBytes: ByteArray = integrity(content.mapValues { sha256(it.value) }).toByteArray(),
            signatureBytes: ByteArray? = null,
        ): FileInput {
            val path = packages.resolve("$name.tbx")
            ZipOutputStream(Files.newOutputStream(path).buffered()).use { zip ->
                content.forEach { (entry, bytes) -> putBytes(zip, entry, bytes) }
                putBytes(zip, "integrity.json", integrityBytes)
                signatureBytes?.let { putBytes(zip, "signature.json", it) }
            }
            return FileInput(path)
        }

        suspend fun installPreviousVersion() {
            assertEquals(PackageInstallResult.Installed(TOOL_ID, 1, false), manager().importAndInstall(archive("old", content())))
            assertPreviousVersion()
        }

        suspend fun assertPreviousVersion() {
            assertEquals(1, repositories.catalog.observeTool(TOOL_ID).first()?.currentVersion?.versionCode)
            assertArrayEquals(HTML, Files.readAllBytes(bundle(1).resolve("index.html")))
            assertFalse(Files.exists(host.resolve("miniapps/$TOOL_ID/versions/2")))
        }

        suspend fun assertClean() {
            listOf(".imports", ".staging", ".lifecycle/replacement-cleanup", ".lifecycle/replacement-backups", ".lifecycle/uninstall-cleanup").forEach { name ->
                val directory = host.resolve("miniapps/$name")
                assertTrue("Residual installation data in $name", !Files.exists(directory) || Files.list(directory).use { it.findAny().isEmpty })
            }
            assertTrue(repositories.installs.observeIncomplete().first().isEmpty())
        }
    }

    private data class FileInput(val path: Path) : PackageInput {
        override val displayName = path.fileName.toString()
        override fun openStream() = Files.newInputStream(path)
    }

    private companion object {
        const val TOOL_ID = "io.toolbox.wasmfixture"
        const val MIB = 1024L * 1024
        val HTML = "<!doctype html><html><body>Wasm resource fixture</body></html>".toByteArray()
        // (module (func (export "add") (param i32 i32) (result i32) local.get 0 local.get 1 i32.add))
        val WASM_ADD = "0061736d0100000001070160027f7f017f030201000707010361646400000a09010700200020016a0b"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // Valid custom section: id 0, unsigned LEB128 length 300, empty name and opaque data.
        // Its arbitrary payload must not be mistaken for a TAR header by the offset-257 heuristic.
        val WASM_WITH_USTAR_CUSTOM_SECTION = ByteArray(311).apply {
            WASM_ADD.copyInto(this, endIndex = 8)
            this[9] = 0xac.toByte()
            this[10] = 0x02
            "ustar".toByteArray().copyInto(this, destinationOffset = 257)
        }

        suspend fun withHarness(block: suspend Harness.() -> Unit) {
            val harness = Harness()
            try {
                harness.block()
            } finally {
                Files.walk(harness.root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }

        fun content(version: Int = 1, resources: Map<String, ByteArray> = emptyMap()): Map<String, ByteArray> = linkedMapOf(
            "manifest.json" to """{"schemaVersion":1,"id":"$TOOL_ID","name":"Wasm fixture","version":"1.0.$version","versionCode":$version,"entry":"index.html","apiVersion":"1.0","minHostVersion":"0.2.0","permissions":[],"securityProfile":"strict"}""".toByteArray(),
            "index.html" to HTML,
        ).apply { putAll(resources) }

        fun integrity(hashes: Map<String, String>): String =
            "{\"files\":{${hashes.entries.joinToString(",") { (name, hash) -> "\"$name\":\"$hash\"" }}},\"algorithm\":\"SHA-256\",\"schemaVersion\":1}"

        fun signature(raw: ByteArray): ByteArray {
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val signature = Signature.getInstance("Ed25519").run {
                initSign(pair.private)
                update(raw)
                sign()
            }
            val encodedKey = Base64.getEncoder().encodeToString(pair.public.encoded)
            val encodedSignature = Base64.getEncoder().encodeToString(signature)
            return """{"schemaVersion":1,"algorithm":"Ed25519","keyId":"sha256:${sha256(pair.public.encoded)}","publicKey":"$encodedKey","signedFile":"integrity.json","signature":"$encodedSignature"}""".toByteArray()
        }

        fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }

        fun putGenerated(zip: ZipOutputStream, name: String, size: Long, random: Boolean): String {
            zip.putNextEntry(ZipEntry(name))
            val generator = Random(1927)
            val buffer = ByteArray(64 * 1024)
            val digest = MessageDigest.getInstance("SHA-256")
            var remaining = size
            while (remaining > 0) {
                if (random) generator.nextBytes(buffer)
                if (remaining == size && random) buffer[0] = 0x54 // ordinary data, never native/archive magic
                val count = minOf(remaining, buffer.size.toLong()).toInt()
                digest.update(buffer, 0, count)
                zip.write(buffer, 0, count)
                remaining -= count
            }
            zip.closeEntry()
            return hex(digest.digest())
        }

        fun hasFileAtLeast(root: Path, suffix: Path, bytes: Long): Boolean =
            Files.exists(root) && Files.walk(root).use { paths ->
                paths.anyMatch { it.endsWith(suffix) && Files.isRegularFile(it) && Files.size(it) >= bytes }
            }

        fun understateEntrySize(path: Path, entryName: String) {
            RandomAccessFile(path.toFile(), "rw").use { file ->
                val eocd = file.length() - 22
                check(readU32(file, eocd) == 0x06054b50L)
                val entries = readU16(file, eocd + 10)
                var central = readU32(file, eocd + 16)
                repeat(entries) {
                    check(readU32(file, central) == 0x02014b50L)
                    val nameLength = readU16(file, central + 28)
                    val extraLength = readU16(file, central + 30)
                    val commentLength = readU16(file, central + 32)
                    file.seek(central + 46)
                    val name = ByteArray(nameLength).also(file::readFully).toString(Charsets.UTF_8)
                    if (name == entryName) {
                        val compressed = readU32(file, central + 20)
                        val declared = readU32(file, central + 24)
                        val local = readU32(file, central + 42)
                        check(readU16(file, local + 6) and 8 != 0)
                        val descriptor = local + 30 + readU16(file, local + 26) + readU16(file, local + 28) + compressed
                        check(readU32(file, descriptor) == 0x08074b50L)
                        check(readU32(file, descriptor + 12) == declared && declared > 1)
                        // Both metadata copies agree. Only reading actual bytes reveals the lie.
                        writeU32(file, central + 24, declared - 1)
                        writeU32(file, descriptor + 12, declared - 1)
                        return
                    }
                    central += 46 + nameLength + extraLength + commentLength
                }
            }
            throw AssertionError("Fixture entry was not found: $entryName")
        }

        fun readU16(file: RandomAccessFile, offset: Long): Int {
            file.seek(offset)
            return file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
        }

        fun readU32(file: RandomAccessFile, offset: Long): Long {
            file.seek(offset)
            var value = 0L
            repeat(4) { shift -> value = value or (file.readUnsignedByte().toLong() shl (shift * 8)) }
            return value
        }

        fun writeU32(file: RandomAccessFile, offset: Long, value: Long) {
            file.seek(offset)
            repeat(4) { shift -> file.write(((value ushr (shift * 8)) and 255).toInt()) }
        }

        fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return hex(digest.digest())
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        fun assertRejected(code: PackageRejectionCode, result: PackageInstallResult) {
            assertTrue("Expected $code, received $result", result is PackageInstallResult.Rejected)
            assertEquals(code, (result as PackageInstallResult.Rejected).rejection.code)
        }
    }
}
