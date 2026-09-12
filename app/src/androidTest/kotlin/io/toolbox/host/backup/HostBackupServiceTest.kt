package io.toolbox.host.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.toolbox.core.data.*
import io.toolbox.host.*
import io.toolbox.host.runtime.AndroidKeyStoreCipher
import io.toolbox.host.runtime.RuntimeSecureEnvelopeStorage
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.backup.*
import io.toolbox.tool.runtime.RuntimeProfileManager
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room, DataStore, .tbx inspector/installer and Android Keystore; isolated from the host's own data. */
@RunWith(AndroidJUnit4::class)
class HostBackupServiceTest {
    @Test fun roundTripRestoresSettingsPackagesConfigurationBinaryAndSecureData() = runBlocking(Dispatchers.IO) {
        fixture { f ->
            val id = f.id(0)
            f.install(id, 1)
            val settings = HostSettings(ThemeMode.MONET_DARK, false, ThemeStyle.MIUIX, true)
            ok(f.repos.settings.update { settings })
            ok(f.repos.keyValues.put(id, "fixture.config", "{\"setting\":true}", 12))
            ok(f.repos.keyValues.put(id, "fixture.binary", "AAECA//+/Q==", 13))
            ok(f.repos.grants.put(PermissionGrant(id, "network", true, 14)))
            val cipher = AndroidKeyStoreCipher(id)
            ok(RuntimeSecureEnvelopeStorage(id, f.repos.keyValues).write(cipher.encrypt("{\"fixture.login\":{\"token\":\"synthetic-only\"}}"), 15))
            val output = f.service().export { _, _ -> }
            val backup = File(f.root, "saved.zip").apply { output.file.copyTo(this) }
            assertTrue(f.packages.deleteTool(id) is HostDeleteResult.Deleted)
            ok(f.repos.settings.update { HostSettings() })
            val preview = backup.inputStream().use { f.service().inspect(it) { _, _ -> } }
            try {
                assertEquals(0, preview.conflicts)
                f.service().restore(preview) { _, _ -> }
                assertEquals(settings, f.repos.settings.settings.first())
                assertEquals(1, f.repos.catalog.observeTool(id).first()!!.currentVersion.versionCode)
                assertEquals("{\"setting\":true}", f.repos.keyValues.observe(id, "fixture.config").first()!!.valueJson)
                assertEquals("AAECA//+/Q==", f.repos.keyValues.observe(id, "fixture.binary").first()!!.valueJson)
                val encrypted = RuntimeSecureEnvelopeStorage(id, f.repos.keyValues).read()!!
                assertEquals("{\"fixture.login\":{\"token\":\"synthetic-only\"}}", cipher.decrypt(encrypted))
                assertFalse(f.repos.grants.observeGrants(id).first().first { it.capability == "network" }.granted)
                val version = f.repos.catalog.observeTool(id).first()!!.currentVersion
                assertTrue(File(f.app.filesDir, "${version.bundleLocator.value}/index.html").readText().contains("fixture-1"))
                assertFalse(File(f.app.filesDir, "backup-restore/journal").exists())
            } finally { preview.prepared.close() }
        }
    }
    @Test fun multipleToolsRepeatedRestoreAndMissingLocalToolAreSupported() = runBlocking(Dispatchers.IO) {
        fixture { f ->
            val first = f.id(0); val second = f.id(1); val unrelated = f.id(2)
            f.install(first, 1); f.install(second, 1)
            ok(f.repos.keyValues.put(first, "value", "old", 1))
            val backup = f.service().export { _, _ -> }.file
            f.install(first, 2); f.packages.deleteTool(second); f.install(unrelated, 3)
            repeat(2) {
                val service = f.service()
                val preview = backup.inputStream().use { service.inspect(it) { _, _ -> } }
                try {
                    assertEquals(if (it == 0) 1 else 2, preview.conflicts)
                    service.restore(preview) { _, _ -> }
                    assertEquals(1, f.repos.catalog.observeTool(first).first()!!.currentVersion.versionCode)
                    assertEquals(1, f.repos.catalog.observeTool(second).first()!!.currentVersion.versionCode)
                    assertEquals(3, f.repos.catalog.observeTool(unrelated).first()!!.currentVersion.versionCode)
                    assertEquals("old", f.repos.keyValues.observe(first, "value").first()!!.valueJson)
                } finally { preview.prepared.close() }
            }
        }
    }
    @Test fun emptyBackupRestoresHostSettingsWithoutDeletingOtherTools() = runBlocking(Dispatchers.IO) {
        fixture { f ->
            val settings = HostSettings(ThemeMode.DARK, false)
            ok(f.repos.settings.update { settings })
            val output = f.service().export { _, _ -> }
            f.install(f.id(0), 1); ok(f.repos.settings.update { HostSettings() })
            val service = f.service(); val preview = output.file.inputStream().use { service.inspect(it) { _, _ -> } }
            try { assertTrue(preview.tools.isEmpty()); service.restore(preview) { _, _ -> }; assertEquals(settings, f.repos.settings.settings.first()); assertEquals(1, f.repos.catalog.observeTools().first().size) }
            finally { preview.prepared.close() }
        }
    }
    @Test fun failedSecondInstallRollsBackFirstToolAndSettings() = runBlocking(Dispatchers.IO) {
        fixture { f ->
            val a = f.id(0); val b = f.id(1)
            f.install(a, 1); f.install(b, 1)
            ok(f.repos.keyValues.put(a, "value", "backup", 1))
            val backup = f.service().export { _, _ -> }.file
            f.install(a, 2); f.install(b, 2); ok(f.repos.keyValues.put(a, "value", "current", 2))
            val current = f.repos.catalog.observeTools().first().associateBy { it.metadata.id }
            var installs = 0
            val failing = object : HostPackageOperations by f.packages {
                override suspend fun importPackage(input: PackageInput): HostImportResult {
                    if (++installs == 2) return HostImportResult.Failed("Injected", "Synthetic failure")
                    return f.packages.importPackage(input)
                }
            }
            val service = f.service(packages = failing)
            val preview = backup.inputStream().use { service.inspect(it) { _, _ -> } }
            try {
                try { service.restore(preview) { _, _ -> }; fail("Expected failed restore") } catch (_: BackupException) { }
                assertEquals(current, f.repos.catalog.observeTools().first().associateBy { it.metadata.id })
                assertEquals("current", f.repos.keyValues.observe(a, "value").first()!!.valueJson)
                assertFalse(File(f.app.filesDir, "backup-restore/journal").exists())
                assertFalse(BackupRuntimeGate.paused)
            } finally { preview.prepared.close() }
        }
    }
    @Test fun cancellationAfterSnapshotRevertsAndCleansJournal() = runBlocking(Dispatchers.IO) {
        fixture { f ->
            val id = f.id(0); f.install(id, 1)
            val backup = f.service().export { _, _ -> }.file
            f.install(id, 2)
            val original = f.repos.catalog.observeTool(id).first()
            val service = f.service(point = { if (it == "prepared") throw CancellationException("synthetic cancellation") })
            val preview = backup.inputStream().use { service.inspect(it) { _, _ -> } }
            try {
                try { service.restore(preview) { _, _ -> }; fail() } catch (_: CancellationException) { }
                assertEquals(original, f.repos.catalog.observeTool(id).first())
                assertFalse(File(f.app.filesDir, "backup-restore/journal").exists()); assertFalse(BackupRuntimeGate.paused)
            } finally { preview.prepared.close() }
        }
    }
    @Test fun tasksArePreviewedStoppedAndImportedOnlyAsHistory() = runBlocking(Dispatchers.IO) {
        fixture { f ->
            val id = f.id(0); f.install(id, 1)
            val task = BackgroundTask("fixture:${f.suffix}", id, 1, "task", BackgroundOperation.HTTP_GET, "{}", false, null, TaskState.QUEUED, 1, 1, 1, 0)
            ok(f.repos.backgroundTasks.create(task))
            val backup = f.service().export { _, _ -> }.file
            val running = setOf(id)
            val service = f.service(runtime = { running })
            val preview = backup.inputStream().use { service.inspect(it) { _, _ -> } }
            try {
                assertEquals(running, preview.runtimeIds); assertEquals(setOf(task.taskId), preview.activeTaskIds)
                service.restore(preview) { _, _ -> }
                assertEquals(TaskState.CANCELLED, (f.repos.backgroundTasks.getTask(task.taskId) as DataResult.Success).value!!.state)
                assertTrue(id in f.background.released); assertTrue(f.background.cancelled > 0)
            } finally { preview.prepared.close() }
        }
    }
    @Test fun changedToolListRequiresNewPreviewWithoutStoppingTasks() = runBlocking(Dispatchers.IO) {
        fixture { f ->
            f.install(f.id(0), 1)
            val service = f.service(); val output = service.export { _, _ -> }
            val preview = output.file.inputStream().use { service.inspect(it) { _, _ -> } }
            try {
                f.install(f.id(1), 1)
                try { service.restore(preview) { _, _ -> }; fail() } catch (e: BackupException) { assertEquals("PREVIEW_CHANGED", e.code) }
                assertEquals(0, f.background.cancelled)
                assertFalse(File(f.app.filesDir, "backup-restore/journal").exists())
            } finally { preview.prepared.close() }
        }
    }

    private suspend fun fixture(action: suspend (Fixture) -> Unit) {
        BackupRuntimeGate.resume()
        val f = Fixture()
        try { action(f) } finally { f.close(); BackupRuntimeGate.resume() }
    }
    private class Fixture {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        private val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "backup-fixture-$suffix").apply { mkdirs() }
        val app = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile.mkdirs() }
        }
        val stores = CoreDataFactory.create(app, "fixture.db", "fixture-settings")
        val repos = stores.repositories
        val lock = DataMutationLock()
        val background = RecordingBackground(repos)
        val packages: HostPackageOperations = SerializedPackageOperations(ProductionHostPackageOperations(app, repos, RuntimeProfileManager(app.filesDir), background), lock)
        fun id(n: Int) = "io.toolbox.backupfixture.t${suffix}n$n"
        fun service(packages: HostPackageOperations = this.packages, runtime: suspend () -> Set<String> = { emptySet() }, point: (String) -> Unit = {}) =
            HostBackupService(app, repos, stores.backup, packages, lock, background, runtime, cancelScheduledWork = {}, checkpointPoint = point)
        suspend fun install(id: String, version: Int) {
            val manifest = """{"schemaVersion":1,"id":"$id","name":"Backup fixture","version":"1.0.$version","versionCode":$version,"entry":"index.html","apiVersion":"1.0","minHostVersion":"0.3.3","permissions":[{"name":"storage","reason":"Fixture data"},{"name":"storage.secure","reason":"Synthetic data"},{"name":"network","reason":"Permission boundary"}],"securityProfile":"strict"}"""
            val bytes = ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    mapOf("manifest.json" to manifest, "index.html" to "<!doctype html><html><body>fixture-$version</body></html>").forEach { (path, data) -> zip.putNextEntry(ZipEntry(path)); zip.write(data.toByteArray()); zip.closeEntry() }
                }; output.toByteArray()
            }
            val result = packages.importPackage(object : PackageInput { override val displayName = "fixture.tbx"; override fun openStream() = ByteArrayInputStream(bytes) })
            val finished = if (result is HostImportResult.ConfirmationRequired) packages.confirmImport(result.confirmation.id) else result
            assertTrue("Fixture must pass real installer: $finished", finished is HostImportResult.Installed)
        }
        suspend fun close() {
            repos.catalog.observeTools().first().forEach { AndroidKeyStoreCipher.deleteForTool(it.metadata.id) }
            stores.close(); root.deleteRecursively()
        }
    }
    private class RecordingBackground(private val repos: CoreDataRepositories) : HostBackgroundOperations {
        val released = mutableListOf<String>(); var cancelled = 0
        override fun observeTasks(toolId: String): Flow<List<BackgroundTask>> = repos.backgroundTasks.observeTasks(toolId)
        override fun observeResult(taskId: String): Flow<TaskRunResult?> = repos.backgroundTasks.observeResult(taskId)
        override suspend fun cancel(toolId: String, taskId: String) = true
        override suspend fun cancelTool(toolId: String) = cancelAll(listOf(toolId))
        override suspend fun cancelAll(toolIds: Collection<String>) {
            cancelled++
            toolIds.forEach { id -> repos.backgroundTasks.observeTasks(id).first().forEach { if (it.state == TaskState.QUEUED || it.state == TaskState.RUNNING) repos.backgroundTasks.cancel(it.taskId, System.currentTimeMillis()) } }
        }
        override suspend fun releaseRuntime(toolId: String) { released += toolId }
    }
    companion object { private fun ok(result: DataResult<*>) { assertTrue(result.toString(), result is DataResult.Success) } }
}
