package io.toolbox.tool.packagekit.backup

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface RestoreCheckpoint {
    suspend fun save(directory: File)
    suspend fun restore(directory: File)
}

/** Durable compensation for Room + DataStore + installer directory publication, including process death. */
class RestoreJournal(
    private val root: File,
    private val liveTree: File,
    private val checkpoint: RestoreCheckpoint,
    private val syncDirectory: (File) -> Unit = { FileChannel.open(it.toPath(), READ).use { channel -> channel.force(true) } },
    private val point: (String) -> Unit = {},
) {
    private val journal = File(root, "journal")
    private var commitRecorded = false
    val pending get() = journal.exists()
    val committed get() = commitRecorded || File(journal, "COMMITTED").isFile

    suspend fun begin() {
        check(!pending) { "RECOVERY_REQUIRED" }
        root.mkdirs()
        val preparing = File(root, "preparing-${UUID.randomUUID()}")
        check(preparing.mkdir())
        try {
            if (liveTree.exists()) { copyTree(liveTree, File(preparing, "tree")); marker(preparing, "HAD_TREE") }
            checkpoint.save(preparing)
            marker(preparing, "READY")
            syncDirectory(preparing)
            Files.move(preparing.toPath(), journal.toPath(), ATOMIC_MOVE)
            syncDirectory(root)
            point("prepared")
        } finally { preparing.deleteRecursively() }
    }

    suspend fun rollback() {
        if (!pending) return
        check(File(journal, "READY").isFile && !committed) { "INVALID_JOURNAL" }
        val old = File(journal, "tree")
        val hadTree = File(journal, "HAD_TREE").isFile
        if (!File(journal, "REVERTING").isFile) {
            check(!hadTree || old.isDirectory) { "MISSING_SNAPSHOT" }
            marker(journal, "REVERTING")
        }
        if (old.exists()) {
            if (liveTree.exists()) detachLive()
            Files.move(old.toPath(), liveTree.toPath(), ATOMIC_MOVE)
            syncDirectory(liveTree.parentFile); syncDirectory(journal)
            point("tree-restored")
        } else if (hadTree) {
            // A previous recovery already renamed the old tree back, with writers still blocked.
            check(liveTree.isDirectory) { "MISSING_RESTORED_TREE" }
        } else if (liveTree.exists()) detachLive()
        checkpoint.restore(journal)
        point("state-restored")
        retire()
    }

    fun commit() {
        check(File(journal, "READY").isFile)
        marker(journal, "COMMITTED")
        commitRecorded = true
        point("committed")
        retire()
    }

    suspend fun recover() {
        if (pending) { if (committed) retire() else rollback() }
        root.listFiles()?.filter { it.name.startsWith("preparing-") || it.name.startsWith("gc-") }?.forEach { it.deleteRecursively() }
    }

    private fun detachLive() {
        val discard = File(journal, "discard")
        check(!discard.exists()) { "AMBIGUOUS_ROLLBACK_TREE" }
        Files.move(liveTree.toPath(), discard.toPath(), ATOMIC_MOVE)
        syncDirectory(liveTree.parentFile); syncDirectory(journal)
        point("live-detached")
    }
    private fun retire() {
        val garbage = File(root, "gc-${UUID.randomUUID()}")
        Files.move(journal.toPath(), garbage.toPath(), ATOMIC_MOVE)
        syncDirectory(root)
        garbage.deleteRecursively() // A failed cleanup is only garbage, not an uncommitted restore.
    }
    private fun marker(directory: File, name: String) {
        val temporary = File(directory, "$name.partial")
        FileOutputStream(temporary).use { it.write("toolbox-restore-v1\n".toByteArray()); it.fd.sync() }
        Files.move(temporary.toPath(), File(directory, name).toPath(), ATOMIC_MOVE)
        syncDirectory(directory)
    }
    private suspend fun copyTree(source: File, target: File) {
        check(!Files.isSymbolicLink(source.toPath()))
        Files.walk(source.toPath()).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                currentCoroutineContext().ensureActive()
                val path = iterator.next()
                check(!Files.isSymbolicLink(path))
                val destination = target.toPath().resolve(source.toPath().relativize(path))
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.createDirectories(destination)
                else {
                    check(Files.isRegularFile(path, NOFOLLOW_LINKS))
                    FileOutputStream(destination.toFile()).use { output ->
                        Files.newInputStream(path).use { BackupArchive.copy(it, output, Long.MAX_VALUE) }
                        output.fd.sync()
                    }
                }
            }
        }
        Files.walk(target.toPath()).use { paths -> paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.sorted(Comparator.reverseOrder()).forEach { syncDirectory(it.toFile()) } }
    }
}
