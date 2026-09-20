package io.toolbox.host.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.*
import io.toolbox.host.*
import io.toolbox.tool.packagekit.*
import io.toolbox.tool.packagekit.backup.BackupException
import io.toolbox.tool.packagekit.lifecycle.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Covers the actual export and restore-preflight paths, including their default Android probe. */
@RunWith(AndroidJUnit4::class)
class HostBackupResourceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedPackageExportsAndPreviewsUsingDefaultAndroidResources() = runBlocking(Dispatchers.IO) {
        withHarness { stores, files, temporary ->
            val service = service(stores, files, temporary)
            val exported = service.export { _, _ -> }
            assertTrue(exported.file.length() > 0)
            val preview = service.inspect(exported.file.inputStream()) { _, _ -> }
            try {
                assertEquals(listOf(TOOL_ID), preview.recoverable.map { it.id })
                assertEquals(1, preview.currentVersions[TOOL_ID]?.versionCode)
            } finally { preview.prepared.close() }
            assertInspectionClean(temporary)
        }
    }

    @Test
    fun exportAndPreviewKeepDistinctResourceFailureCodesAndCleanTemporaryFiles() = runBlocking(Dispatchers.IO) {
        withHarness { stores, files, temporary ->
            val exported = service(stores, files, temporary).export { _, _ -> }
            val probes = listOf(
                "PACKAGE_RESOURCE_CHECK_FAILED" to PackageResourceProbe { throw IOException("probe unavailable") },
                "PACKAGE_INSUFFICIENT_SPACE" to PackageResourceProbe { PackageResourceSnapshot(0, false) },
                "PACKAGE_INSUFFICIENT_RESOURCES" to PackageResourceProbe { PackageResourceSnapshot(Long.MAX_VALUE, true) },
            )
            val messages = mutableSetOf<String>()
            for ((code, probe) in probes) {
                val service = service(stores, files, temporary, probe)
                for (export in listOf(true, false)) {
                    val failure = try {
                        if (export) service.export { _, _ -> }
                        else service.inspect(exported.file.inputStream()) { _, _ -> }
                        throw AssertionError("Expected $code from ${if (export) "export" else "preview"}")
                    } catch (failure: BackupException) { failure }
                    assertEquals(code, failure.code)
                    messages += backupMessage(failure)
                    assertInspectionClean(temporary)
                    assertEquals(1, stores.repositories.catalog.observeTools().first().size)
                }
            }
            assertEquals("Resource errors must have distinct actionable messages", 3, messages.size)
        }
    }

    @Test
    fun cancellingExportOrPreviewDoesNotBecomeARejectedPackage() = runBlocking(Dispatchers.IO) {
        withHarness { stores, files, temporary ->
            val exported = service(stores, files, temporary).export { _, _ -> }
            for (export in listOf(true, false)) {
                val entered = CountDownLatch(1)
                val blocked = PackageResourceProbe {
                    entered.countDown()
                    CountDownLatch(1).await()
                    PackageResourceSnapshot(Long.MAX_VALUE, false)
                }
                val service = service(stores, files, temporary, blocked)
                val task = async(Dispatchers.IO) {
                    if (export) service.export { _, _ -> }
                    else service.inspect(exported.file.inputStream()) { _, _ -> }
                }
                try {
                    assertTrue("Inspection did not start", entered.await(10, TimeUnit.SECONDS))
                } finally { task.cancelAndJoin() }
                assertTrue(task.isCancelled)
                assertInspectionClean(temporary)
                assertEquals(1, stores.repositories.catalog.observeTools().first().size)
            }
        }
    }

    @Test
    fun catalogLayoutRoundTripsThroughRealRestoreAndRollsBackAfterAPersistedFailure() = runBlocking(Dispatchers.IO) {
        withHarness { stores, files, temporary ->
            val saved = CatalogLayout(favorites = listOf(TOOL_ID), groups = listOf(CatalogGroup("saved", "已备份组", listOf(TOOL_ID))))
            val local = CatalogLayout(groups = listOf(CatalogGroup("local", "本机组", listOf(TOOL_ID))))
            val settings = stores.repositories.settings
            assertTrue(settings.update { it.copy(catalogLayout = saved) } is DataResult.Success)
            val normal = service(stores, files, temporary)
            val exported = normal.export { _, _ -> }
            assertTrue(settings.update { it.copy(catalogLayout = local) } is DataResult.Success)
            val preview = normal.inspect(exported.file.inputStream()) { _, _ -> }
            try { normal.restore(preview) { _, _ -> } } finally { preview.prepared.close() }
            assertEquals(saved, settings.settings.first().catalogLayout)
            assertTrue(normal.committed)

            assertTrue(settings.update { it.copy(catalogLayout = local) } is DataResult.Success)
            var injected = false
            val faulting = object : HostSettingsRepository by settings {
                override suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit> {
                    val result = settings.update(transform)
                    if (!injected && settings.settings.first().catalogLayout == saved) {
                        injected = true
                        throw IOException("Failure after incoming layout was persisted")
                    }
                    return result
                }
            }
            val failing = service(stores, files, temporary, repositories = stores.repositories.copy(settings = faulting))
            val beforeVersion = stores.repositories.catalog.observeTool(TOOL_ID).first()?.currentVersion
            val retry = failing.inspect(exported.file.inputStream()) { _, _ -> }
            try {
                try { failing.restore(retry) { _, _ -> }; fail("Restore must report injected failure") }
                catch (expected: IOException) { assertTrue(injected) }
            } finally { retry.prepared.close() }
            assertFalse(failing.committed)
            assertEquals(local, settings.settings.first().catalogLayout)
            assertEquals(beforeVersion, stores.repositories.catalog.observeTool(TOOL_ID).first()?.currentVersion)
            assertFalse(BackupRuntimeGate.paused)
        }
    }

    private suspend fun withHarness(action: suspend (CoreDataStores, File, File) -> Unit) {
        val name = "backup-resource-${UUID.randomUUID()}"
        val stores = CoreDataFactory.create(context, "$name.db", name)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), name).toFile()
        val files = File(root, "files").apply { mkdirs() }
        val temporary = File(root, "backup")
        try {
            val manager = ToolPackageManagers.create(files, stores.repositories.catalog, stores.repositories.lifecycle,
                stores.repositories.installs, hostVersion = BuildConfig.VERSION_NAME, resourceProbe = AndroidPackageResourceProbe(context))
            val bytes = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    mapOf(
                        "manifest.json" to """{"schemaVersion":1,"id":"$TOOL_ID","name":"Backup probe","version":"1.0.0","versionCode":1,"entry":"index.html","apiVersion":"1.0","minHostVersion":"0.3.0","permissions":[],"securityProfile":"strict"}""",
                        "index.html" to "\uFEFF<!-- generator --><p>Resource fixture</p>",
                    ).forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
                }
            }.toByteArray()
            val input = object : PackageInput {
                override val displayName = "probe.tbx"
                override fun openStream() = bytes.inputStream()
            }
            assertTrue(manager.importAndInstall(input) is PackageInstallResult.Installed)
            action(stores, files, temporary)
        } finally {
            stores.close()
            context.deleteDatabase("$name.db")
            File(context.filesDir, "datastore/$name.preferences_pb").delete()
            assertTrue(root.deleteRecursively())
        }
    }

    private fun service(
        stores: CoreDataStores, files: File, temporary: File, probe: PackageResourceProbe? = null,
        repositories: CoreDataRepositories = stores.repositories,
    ): HostBackupService {
        val packages = fixturePackages(stores, files)
        return if (probe == null) HostBackupService(context, repositories, stores.backup, packages, DataMutationLock(),
            unusedBackground, { emptySet() }, files = files, temporary = temporary, cancelScheduledWork = {})
        else HostBackupService(context, repositories, stores.backup, packages, DataMutationLock(),
            unusedBackground, { emptySet() }, files = files, temporary = temporary, cancelScheduledWork = {}, resourceProbe = probe)
    }

    private fun assertInspectionClean(temporary: File) {
        assertTrue(temporary.walkTopDown().none { it.name == "source.tbx" || it.name == "bundle" })
    }

    private fun fixturePackages(stores: CoreDataStores, files: File): HostPackageOperations {
        val manager = ToolPackageManagers.create(files, stores.repositories.catalog, stores.repositories.lifecycle,
            stores.repositories.installs, hostVersion = BuildConfig.VERSION_NAME, resourceProbe = AndroidPackageResourceProbe(context))
        fun converted(result: PackageInstallResult): HostImportResult = when (result) {
            PackageInstallResult.Cancelled -> HostImportResult.Cancelled
            is PackageInstallResult.Installed -> HostImportResult.Installed(result.toolId, "Backup probe")
            is PackageInstallResult.Rejected -> HostImportResult.Failed(result.rejection.code.name, result.rejection.detail)
            is PackageInstallResult.Failed -> HostImportResult.Failed(result.failure.code.name, result.failure.toString())
            is PackageInstallResult.ConfirmationRequired -> result.confirmation.let {
                HostImportResult.ConfirmationRequired(HostImportConfirmation(it.id, it.toolId, it.toolName,
                    it.installedVersionName, it.installedVersionCode, it.incomingVersionName, it.incomingVersionCode,
                    HostImportConfirmationKind.valueOf(it.kind.name)))
            }
        }
        return object : HostPackageOperations {
            override suspend fun importPackage(input: PackageInput, control: PackageImportControl) = converted(manager.importAndInstall(input, control = control))
            override suspend fun confirmImport(confirmationId: String, control: PackageImportControl) = converted(manager.confirmInstall(confirmationId, control = control))
            override suspend fun cancelImport(confirmationId: String): HostImportCancellationResult {
                check(manager.cancelInstall(confirmationId) == null)
                return HostImportCancellationResult.Cancelled
            }
            override suspend fun installedManifest(toolId: String): HostInstalledManifestResult = error("Unexpected manifest lookup")
            override suspend fun deleteTool(toolId: String): HostDeleteResult = error("Unexpected delete")
            override suspend fun installBundledExamples(): HostExampleInstallResult = error("Unexpected examples")
        }
    }
    private val unusedBackground = object : HostBackgroundOperations {
        override fun observeTasks(toolId: String) = flowOf(emptyList<BackgroundTask>())
        override fun observeResult(taskId: String) = flowOf<TaskRunResult?>(null)
        override suspend fun cancel(toolId: String, taskId: String) = io.toolbox.host.background.BackgroundCancellationResult.AlreadyFinished()
        override suspend fun cancelTool(toolId: String) = Unit
        override suspend fun cancelAll(toolIds: Collection<String>) = Unit
    }
    private companion object { const val TOOL_ID = "io.toolbox.backupprobe" }
}
