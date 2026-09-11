package io.toolbox.tool.packagekit.backup

import io.toolbox.tool.packagekit.PackagePathPolicy
import io.toolbox.tool.packagekit.ZipReadLimits
import io.toolbox.tool.packagekit.ZipStructureReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*

class BackupException(val code: String, cause: Throwable? = null) : Exception(code, cause)
data class BackupLimits(
    val archiveBytes: Long = 2L * 1024 * 1024 * 1024,
    val expandedBytes: Long = 4L * 1024 * 1024 * 1024 - 1,
    val entryBytes: Long = 256L * 1024 * 1024,
    val entries: Int = 60_000,
    val metadataBytes: Long = 8L * 1024 * 1024,
    val ratio: Double = 500.0,
)
data class BackupTool(val id: String, val name: String, val version: String, val versionCode: Int, val dataVersion: Int = 1)
data class BackupContents(val appVersion: String, val createdAt: Long, val tools: List<BackupTool>, val warnings: List<String> = emptyList())
/** directory always contains the extracted content; owner also removes the private source ZIP. */
data class PreparedBackup(val directory: File, val contents: BackupContents, private val owner: File = directory) : AutoCloseable {
    override fun close() { owner.deleteRecursively() }
}

/** An outer backup can contain .tbx files, but each is still subject to normal installation checks. */
class BackupArchive(private val limits: BackupLimits = BackupLimits()) {
    suspend fun write(directory: File, output: File, contents: BackupContents, progress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        check(!output.exists()) { "OUTPUT_EXISTS" }
        validateTools(contents.tools)
        val indexed = linkedMapOf<String, Any?>()
        val payload = regularFiles(directory).filterNot { it.parentFile == directory && it.name in setOf("manifest.json", "checksums.json") }
        payload.forEachIndexed { index, file ->
            val path = safePath(file.relativeTo(directory).invariantSeparatorsPath)
            indexed[path] = mapOf("sha256" to hash(file), "size" to file.length())
            progress((index + 1) * 35 / maxOf(1, payload.size))
        }
        val manifest = mapOf(
            "format" to FORMAT, "formatVersion" to 1, "minimumReaderVersion" to 1,
            "appVersion" to contents.appVersion, "createdAt" to contents.createdAt,
            "tools" to contents.tools.map { mapOf("id" to it.id, "name" to it.name, "version" to it.version, "versionCode" to it.versionCode, "dataVersion" to it.dataVersion) },
            "warnings" to contents.warnings, "files" to indexed,
        )
        File(directory, "manifest.json").writeText(BackupJson.encode(manifest))
        val checksums = indexed.mapValues { BackupJson.obj(it.value)["sha256"] }.toMutableMap()
        checksums["manifest.json"] = hash(File(directory, "manifest.json"))
        File(directory, "checksums.json").writeText(BackupJson.encode(checksums))
        val files = regularFiles(directory)
        if (files.size > limits.entries || files.sumOf(File::length) > limits.expandedBytes) fail("LIMIT")
        val partial = File(output.parentFile, "${output.name}.partial")
        try {
            FileOutputStream(partial).use { raw ->
                // Level 1 also keeps valid repetitive-data exports under the reader's ratio limit.
                val zip = ZipOutputStream(raw).apply { setLevel(Deflater.BEST_SPEED) }
                files.forEachIndexed { index, file ->
                    if (file.length() > limits.entryBytes) fail("LIMIT")
                    zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                    file.inputStream().use { copy(it, zip, limits.entryBytes) }
                    zip.closeEntry()
                    progress(35 + (index + 1) * 60 / maxOf(1, files.size))
                }
                zip.finish(); zip.flush(); raw.fd.sync()
            }
            if (partial.length() > limits.archiveBytes) fail("LIMIT")
            val verification = File(directory.parentFile, "verify-${UUID.randomUUID()}")
            try { unpack(partial, verification).close() } finally { verification.deleteRecursively() }
            currentCoroutineContext().ensureActive()
            Files.move(partial.toPath(), output.toPath(), ATOMIC_MOVE)
            progress(100)
        } finally { partial.delete() }
    }

    suspend fun read(input: InputStream, parent: File, progress: (Int) -> Unit = {}): PreparedBackup = withContext(Dispatchers.IO) {
        val session = File(parent, "backup-${UUID.randomUUID()}")
        try {
            check(session.mkdirs())
            val zip = File(session, "source.zip")
            FileOutputStream(zip).use { out -> copy(input, out, limits.archiveBytes); out.fd.sync() }
            val extracted = unpack(zip, File(session, "content"), progress)
            check(zip.delete())
            PreparedBackup(extracted.directory, extracted.contents, session)
        } catch (failure: Throwable) { session.deleteRecursively(); throw failure }
    }

    suspend fun unpack(zip: File, directory: File, progress: (Int) -> Unit = {}): PreparedBackup = withContext(Dispatchers.IO) {
        if (zip.length() > limits.archiveBytes) fail("LIMIT")
        try {
            val checked = ZipStructureReader.read(zip.toPath(), ZipReadLimits(limits.expandedBytes, limits.entries, limits.entryBytes, 240, limits.ratio, false))
            check(directory.mkdirs())
            val names = mutableSetOf<String>()
            ZipFile(zip).use { archive ->
                checked.entries.forEachIndexed { index, entry ->
                    currentCoroutineContext().ensureActive()
                    val path = safePath(entry.path.normalized)
                    if (entry.rawName != path + if (entry.path.directory) "/" else "") fail("PATH")
                    if (!entry.path.directory) {
                        names += path
                        val target = File(directory, path)
                        check(target.parentFile.isDirectory || target.parentFile.mkdirs())
                        val crc = CRC32()
                        var count = 0L
                        archive.getInputStream(archive.getEntry(entry.rawName)).use { input ->
                            Files.newOutputStream(target.toPath(), CREATE_NEW).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    count += n
                                    if (count > entry.extractedBytes || count > limits.entryBytes) fail("LIMIT")
                                    crc.update(buffer, 0, n); output.write(buffer, 0, n)
                                }
                            }
                        }
                        if (count != entry.extractedBytes || crc.value != entry.crc32) fail("CORRUPT")
                    }
                    progress((index + 1) * 65 / maxOf(1, checked.entries.size))
                }
            }
            val m = BackupJson.read(File(directory, "manifest.json"), limits.metadataBytes)
            if (m["format"] != FORMAT) fail("FORMAT")
            if (BackupJson.int(m["formatVersion"]) != 1 || BackupJson.int(m["minimumReaderVersion"]) !in 1..1) fail("VERSION")
            val warnings = mutableListOf<String>()
            unknown(m, setOf("format", "formatVersion", "minimumReaderVersion", "appVersion", "createdAt", "tools", "files", "warnings"), warnings)
            val tools = BackupJson.array(m["tools"]).map { value ->
                val t = BackupJson.obj(value)
                unknown(t, setOf("id", "name", "version", "versionCode", "dataVersion"), warnings)
                BackupTool(BackupJson.string(t["id"]), BackupJson.string(t["name"]), BackupJson.string(t["version"]), BackupJson.int(t["versionCode"]), BackupJson.int(t["dataVersion"]))
            }
            validateTools(tools)
            val index = BackupJson.obj(m["files"])
            val sums = BackupJson.read(File(directory, "checksums.json"), limits.metadataBytes)
            if (names != index.keys + setOf("manifest.json", "checksums.json") || sums.keys != index.keys + "manifest.json") fail("INDEX")
            sums.entries.forEachIndexed { i, (path, value) ->
                safePath(path)
                val expected = BackupJson.string(value)
                if (!SHA.matches(expected) || hash(File(directory, path)) != expected) fail("CHECKSUM")
                if (path != "manifest.json") {
                    val record = BackupJson.obj(index[path])
                    if (record["sha256"] != expected || BackupJson.long(record["size"]) != File(directory, path).length()) fail("INDEX")
                    unknown(record, setOf("sha256", "size"), warnings)
                }
                progress(65 + (i + 1) * 35 / maxOf(1, sums.size))
            }
            if ("host/settings.json" !in index) fail("MISSING")
            tools.forEach { if ("tools/${it.id}/package.tbx" !in index || "tools/${it.id}/metadata.json" !in index) fail("MISSING") }
            val ids = tools.map { it.id }.toSet()
            index.keys.forEach { path ->
                if (path.startsWith("tools/") && path.split('/').getOrNull(1) !in ids) fail("INDEX")
                if (path != "host/settings.json" && !path.startsWith("tools/") && !path.startsWith("extensions/")) fail("PATH")
            }
            if (index.keys.any { it.startsWith("extensions/") }) warnings += "扩展文件暂不应用；这些内容仍完整保留在原备份中。"
            warnings += (m["warnings"] as? List<*>)?.map { BackupJson.string(it).take(300) }.orEmpty()
            val time = BackupJson.long(m["createdAt"])
            if (time < 0) fail("INDEX")
            PreparedBackup(directory, BackupContents(BackupJson.string(m["appVersion"]), time, tools, warnings.distinct()))
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            if (failure is CancellationException || failure is BackupException) throw failure
            throw BackupException("CORRUPT", failure)
        }
    }

    companion object {
        const val FORMAT = "io.toolbox.backup"
        private val ID = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*){2,}$")
        private val SHA = Regex("^[0-9a-f]{64}$")
        fun validateTools(tools: List<BackupTool>) {
            if (tools.map { it.id }.toSet().size != tools.size || tools.any { !ID.matches(it.id) || it.id.length > 150 || it.versionCode <= 0 || it.dataVersion < 1 || it.name.length > 200 || it.version.length > 100 }) fail("TOOLS")
        }
        fun unknown(obj: Map<String, Any?>, known: Set<String>, warnings: MutableList<String>) {
            val count = (obj.keys - known).size
            if (count > 0) warnings += "已跳过 $count 个未知字段；原归档保留这些字段。"
        }
        fun safePath(path: String): String {
            val normalized = PackagePathPolicy.validate(path, 240).normalized
            if (path.any { it.code < 32 || it == ':' } || normalized != path) fail("PATH")
            return normalized
        }
        suspend fun hash(file: File): String = file.inputStream().use { hash(it) }
        suspend fun hash(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        suspend fun copy(input: InputStream, output: OutputStream, limit: Long): Long {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) return total
                total = Math.addExact(total, n.toLong())
                if (total > limit) fail("LIMIT")
                output.write(buffer, 0, n)
            }
        }
        fun regularFiles(directory: File): List<File> {
            if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) fail("PATH")
            return Files.walk(directory.toPath()).use { paths -> paths.filter { path ->
                if (Files.isSymbolicLink(path) || (!Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS))) fail("PATH")
                Files.isRegularFile(path, NOFOLLOW_LINKS)
            }.map { it.toFile() }.sorted().toList() }
        }
        private fun fail(code: String): Nothing = throw BackupException(code)
    }
}
