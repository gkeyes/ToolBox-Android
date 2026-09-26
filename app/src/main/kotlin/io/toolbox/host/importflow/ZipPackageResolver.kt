package io.toolbox.host.importflow

import io.toolbox.tool.packagekit.PackageInput
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * An outer ZIP is transport only. Exactly one embedded .tbx is accepted and
 * then passed unchanged through the normal package signature/integrity checks.
 */
internal object ZipPackageResolver {
    private const val IMPORT_CACHE_DIRECTORY = "zip-import"
    private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L
    private const val MAX_EMBEDDED_TBX_BYTES = 256L * 1024L * 1024L
    private const val MAX_ENTRY_COUNT = 2_048
    private const val STALE_CACHE_MILLIS = 24L * 60L * 60L * 1_000L

    fun resolve(
        cacheDirectory: File,
        sourceOpener: () -> InputStream,
    ): SelectedPackageSource {
        val staging = File(cacheDirectory, IMPORT_CACHE_DIRECTORY)
        if ((!staging.exists() && !staging.mkdirs()) || !staging.isDirectory) {
            return SelectedPackageSource.Rejected("无法准备压缩包导入缓存，请检查可用存储空间")
        }
        cleanupStaleFiles(staging)

        val archive = try {
            File.createTempFile("toolbox-import-", ".zip", staging)
        } catch (_: IOException) {
            return SelectedPackageSource.Rejected("无法准备压缩包导入缓存，请检查可用存储空间")
        }

        var keepArchive = false
        try {
            sourceOpener().use { input ->
                FileOutputStream(archive).use { output ->
                    copyWithLimit(input, output, MAX_ARCHIVE_BYTES)
                }
            }

            val candidates = mutableListOf<String>()
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                var entryCount = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount += 1
                    if (entryCount > MAX_ENTRY_COUNT) {
                        throw ZipImportRejected("压缩包文件数量过多，已停止解析")
                    }
                    if (entry.isDirectory ||
                        !entry.name.endsWith(ToolBoxOpenDocument.FILE_EXTENSION, ignoreCase = true)
                    ) {
                        continue
                    }
                    validateCandidatePath(entry.name)
                    if (entry.size > MAX_EMBEDDED_TBX_BYTES) {
                        throw ZipImportRejected("压缩包中的 TBX 工具过大，已停止导入")
                    }
                    candidates += entry.name
                }
            }

            when (candidates.size) {
                0 -> return SelectedPackageSource.Rejected("压缩包中没有找到 TBX 工具")
                1 -> Unit
                else -> return SelectedPackageSource.Rejected(
                    "压缩包中发现 ${candidates.size} 个 TBX 工具；当前一次只能导入一个，请使用只包含一个 TBX 的压缩包",
                )
            }

            val entryName = candidates.single()
            keepArchive = true
            return SelectedPackageSource.Ready(
                ZipEntryPackageInput(
                    displayName = safePackageDisplayName(entryName.substringAfterLast('/')),
                    archiveFile = archive,
                    entryName = entryName,
                    maxEntryBytes = MAX_EMBEDDED_TBX_BYTES,
                ),
            )
        } catch (rejected: ZipImportRejected) {
            return SelectedPackageSource.Rejected(rejected.userMessage)
        } catch (_: ZipException) {
            return SelectedPackageSource.Rejected("压缩包无法读取或已经损坏")
        } catch (_: IOException) {
            return SelectedPackageSource.Rejected("无法读取压缩包，请重新下载后重试")
        } catch (_: SecurityException) {
            return SelectedPackageSource.Rejected("系统未允许读取压缩包")
        } finally {
            if (!keepArchive) archive.delete()
        }
    }

    private fun copyWithLimit(input: InputStream, output: FileOutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total = try {
                Math.addExact(total, count.toLong())
            } catch (_: ArithmeticException) {
                throw ZipImportRejected("压缩包大小异常，已停止导入")
            }
            if (total > maxBytes) throw ZipImportRejected("压缩包超过 256 MB，已停止导入")
            output.write(buffer, 0, count)
        }
        output.fd.sync()
    }

    private fun validateCandidatePath(rawName: String) {
        if (rawName.isBlank() || rawName.indexOf('\u0000') >= 0 || rawName.contains('\\') ||
            rawName.startsWith('/') || DRIVE_PATH.containsMatchIn(rawName)
        ) {
            throw ZipImportRejected("压缩包中的 TBX 路径不安全")
        }
        val segments = rawName.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            throw ZipImportRejected("压缩包中的 TBX 路径不安全")
        }
        if (rawName.containsUnsafeFormatCodePointForZip()) {
            throw ZipImportRejected("压缩包中的 TBX 路径包含不安全字符")
        }
    }

    private fun cleanupStaleFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_CACHE_MILLIS
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("toolbox-import-") && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    private class ZipImportRejected(val userMessage: String) : IOException(userMessage)
    private val DRIVE_PATH = Regex("^[A-Za-z]:")
}

internal class ZipEntryPackageInput(
    override val displayName: String,
    private val archiveFile: File,
    private val entryName: String,
    private val maxEntryBytes: Long,
) : PackageInput {
    private val opened = AtomicBoolean(false)

    override fun openStream(): InputStream {
        check(opened.compareAndSet(false, true)) { "Selected package content may only be opened once" }
        val zip = try {
            ZipFile(archiveFile)
        } catch (error: Exception) {
            archiveFile.delete()
            throw error
        }
        val entry = zip.getEntry(entryName)
        if (entry == null || entry.isDirectory) {
            zip.close()
            archiveFile.delete()
            throw IOException("Embedded TBX entry is no longer available")
        }
        if (entry.size > maxEntryBytes) {
            zip.close()
            archiveFile.delete()
            throw IOException("Embedded TBX exceeds the import limit")
        }
        val input = try {
            zip.getInputStream(entry)
        } catch (error: Exception) {
            zip.close()
            archiveFile.delete()
            throw error
        }
        return DeleteArchiveOnCloseInputStream(input, zip, archiveFile, maxEntryBytes)
    }
}

private class DeleteArchiveOnCloseInputStream(
    input: InputStream,
    private val zip: ZipFile,
    private val archiveFile: File,
    private val maxBytes: Long,
) : FilterInputStream(input) {
    private var totalBytes = 0L
    private var closed = false

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) account(count)
        return count
    }

    private fun account(count: Int) {
        totalBytes = try {
            Math.addExact(totalBytes, count.toLong())
        } catch (_: ArithmeticException) {
            close()
            throw IOException("Embedded TBX size overflow")
        }
        if (totalBytes > maxBytes) {
            close()
            throw IOException("Embedded TBX exceeds the import limit")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            super.close()
        } finally {
            try {
                zip.close()
            } finally {
                archiveFile.delete()
            }
        }
    }
}

private fun String.containsUnsafeFormatCodePointForZip(): Boolean {
    var offset = 0
    while (offset < length) {
        val codePoint = Character.codePointAt(this, offset)
        if (Character.getType(codePoint) == Character.FORMAT.toInt()) return true
        offset += Character.charCount(codePoint)
    }
    return false
}
