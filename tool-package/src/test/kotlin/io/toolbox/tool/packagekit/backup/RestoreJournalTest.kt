package io.toolbox.tool.packagekit.backup

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private var state = "original database and settings"
    private var rejectRestore = false
    private val checkpoint = object : RestoreCheckpoint {
        override suspend fun save(directory: File) { File(directory, "state").writeText(state) }
        override suspend fun restore(directory: File) {
            if (rejectRestore) throw IOException("synthetic database failure")
            state = File(directory, "state").readText()
        }
    }
    private fun live() = File(temporary.root, "miniapps")
    private fun journal(point: (String) -> Unit = {}) = RestoreJournal(File(temporary.root, "rollback"), live(), checkpoint, point = point)
    private fun original() { live().mkdirs(); File(live(), "package").writeText("original") }
    private fun mutate() { live().mkdirs(); File(live(), "package").writeText("incoming"); state = "incoming database and settings" }
    private fun assertOriginal() { assertEquals("original", File(live(), "package").readText()); assertEquals("original database and settings", state) }

    @Test fun interruptedRestoreRevertsAllDomainsOnStartup() = runBlocking {
        original(); journal().begin(); mutate(); journal().recover(); assertOriginal(); assertFalse(journal().pending)
    }
    @Test fun commitIsTerminalAcrossRepeatedRecovery() = runBlocking {
        original(); val j = journal(); j.begin(); mutate(); j.commit(); assertTrue(j.committed)
        repeat(2) { journal().recover() }
        assertEquals("incoming", File(live(), "package").readText()); assertEquals("incoming database and settings", state)
    }
    @Test fun rollbackResumesBetweenDirectoryRenames() = runBlocking {
        original(); journal().begin(); mutate()
        try { journal { if (it == "live-detached") throw IOException("power loss") }.rollback(); fail() } catch (_: IOException) { }
        journal().recover(); assertOriginal()
    }
    @Test fun rollbackResumesAfterTreeButBeforeDatabaseRestore() = runBlocking {
        original(); journal().begin(); mutate()
        try { journal { if (it == "tree-restored") throw IOException("power loss") }.rollback(); fail() } catch (_: IOException) { }
        journal().recover(); assertOriginal()
    }
    @Test fun failedDatabaseRollbackKeepsSnapshotForRetry() = runBlocking {
        original(); journal().begin(); mutate(); rejectRestore = true
        try { journal().rollback(); fail() } catch (_: IOException) { }
        assertTrue(journal().pending)
        rejectRestore = false; journal().recover(); assertOriginal()
    }
    @Test fun failedFirstRestoreRemovesNewTree() = runBlocking {
        journal().begin(); mutate(); journal().rollback()
        assertFalse(live().exists()); assertEquals("original database and settings", state)
    }
    @Test fun postCommitCleanupFailureNeverRevertsSuccessfulData() = runBlocking {
        original(); val j = journal { if (it == "committed") throw IOException("cleanup failure") }
        j.begin(); mutate()
        try { j.commit(); fail() } catch (_: IOException) { }
        assertTrue(j.committed); journal().recover(); assertEquals("incoming", File(live(), "package").readText())
    }
}
