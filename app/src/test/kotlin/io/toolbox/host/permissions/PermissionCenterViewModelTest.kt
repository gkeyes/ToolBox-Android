package io.toolbox.host.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.core.data.memory.InMemoryCoreData
import io.toolbox.host.runtime.UserNetworkDomainStore
import io.toolbox.host.BuildConfig
import io.toolbox.host.HostInstalledManifest
import io.toolbox.host.HostInstalledManifestReader
import io.toolbox.host.HostInstalledManifestResult
import io.toolbox.host.HostManifestPermission
import io.toolbox.host.HostPackageOperations
import io.toolbox.host.HostPermissionSideEffects
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.lifecycle.PackageInstallResult
import io.toolbox.tool.packagekit.lifecycle.ToolPackageManagers
import io.toolbox.tool.runtime.RuntimePreparationCode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionCenterViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun permissionListUsesManifestAndToggleWritesGrantState() = runTest(mainDispatcher) {
        val grants = FakePermissionGrantRepository(
            PermissionGrant(
                toolId = TOOL_ID,
                capability = "storage",
                granted = true,
                updatedAt = 100L,
            ),
        )
        val sideEffects = RecordingPermissionSideEffects()
        val viewModel = permissionViewModel(
            toolId = TOOL_ID,
            packages = FakeHostPackageOperations,
            catalog = InMemoryCoreData.create().catalog,
            grants = grants,
            sideEffects = sideEffects,
            now = { 200L },
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.loaded)
        assertEquals("工具示例", viewModel.state.value.toolName)
        assertEquals(
            listOf("storage", "clipboard.write"),
            viewModel.state.value.items.map(PermissionItem::capability),
        )
        assertTrue(viewModel.state.value.items.first().enabled)

        viewModel.setEnabled("storage", false)
        advanceUntilIdle()

        assertEquals(
            listOf(PermissionGrant(TOOL_ID, "storage", false, 200L)),
            grants.putCalls,
        )
        assertEquals(listOf("$TOOL_ID:storage"), sideEffects.disabled)
        assertFalse(viewModel.state.value.items.first().enabled)

        viewModel.setEnabled("unknown", true)
        advanceUntilIdle()
        assertEquals(1, grants.putCalls.size)
    }

    @Test
    fun unknownCapabilityCannotBeMutated() = runTest(mainDispatcher) {
        val grants = FakePermissionGrantRepository()
        val viewModel = permissionViewModel(
            toolId = TOOL_ID,
            packages = FakeHostPackageOperations,
            catalog = InMemoryCoreData.create().catalog,
            grants = grants,
            sideEffects = RecordingPermissionSideEffects(),
        )
        advanceUntilIdle()

        viewModel.setEnabled("network", true)
        advanceUntilIdle()

        assertTrue(grants.putCalls.isEmpty())
    }

    @Test
    fun installedToolRequiringCurrentHostLoadsEveryPermissionWithoutChangingGrants() = runTest(mainDispatcher) {
        val filesRoot = temporaryFolder.newFolder()
        val repositories = InMemoryCoreData.create()
        installFixture(filesRoot, repositories)
        val originalGrants = repositories.grants.observeGrants(TOOL_ID).first()
        val viewModel = installedPermissionViewModel(filesRoot, repositories)

        val state = viewModel.state.first { it.loaded }

        assertEquals(PermissionLoadState.Ready, state.loadState)
        assertEquals(FIXTURE_CAPABILITIES, state.items.map(PermissionItem::capability))
        assertEquals("工具示例", state.toolName)
        assertNull(state.message)
        assertEquals(originalGrants, repositories.grants.observeGrants(TOOL_ID).first())
        assertTrue(state.items.single { it.capability == "storage" }.enabled)
        assertTrue(state.items.single { it.capability == "storage.secure" }.enabled)
        assertFalse(state.items.single { it.capability == "network" }.enabled)
        assertFalse(state.items.single { it.capability == "notifications" }.enabled)
        assertFalse(state.items.single { it.capability == "background.runtime" }.enabled)
        val browser = state.items.single { it.capability == "browser" }
        assertFalse(browser.enabled)
        assertEquals("浏览器打开", browser.title)
        assertTrue(browser.androidPermissions.isEmpty())

        viewModel.setEnabled("browser", true)
        viewModel.state.first { current -> current.items.any { it.capability == "browser" && it.enabled } }
        assertTrue(repositories.grants.observeGrants(TOOL_ID).first().single { it.capability == "browser" }.granted)
        assertFalse(repositories.grants.observeGrants(TOOL_ID).first().single { it.capability == "network" }.granted)

        viewModel.setEnabled("network", true)
        viewModel.state.first { current -> current.items.any { it.capability == "network" && it.enabled } }
        assertTrue(repositories.grants.observeGrants(TOOL_ID).first().single { it.capability == "network" }.granted)
    }

    @Test
    fun failedManifestReadPreservesItsCauseInsteadOfReportingMissingToolOrNoPermissions() = runTest(mainDispatcher) {
        val filesRoot = temporaryFolder.newFolder()
        val repositories = InMemoryCoreData.create()
        installFixture(filesRoot, repositories)
        val originalGrants = repositories.grants.observeGrants(TOOL_ID).first()
        val cases = listOf(
            fixtureManifest(minHostVersion = "999.0.0") to RuntimePreparationCode.UNSUPPORTED_HOST_VERSION,
            "{broken" to RuntimePreparationCode.MANIFEST_INVALID,
        )

        for ((manifest, code) in cases) {
            Files.writeString(filesRoot.toPath().resolve("miniapps/$TOOL_ID/versions/1/bundle/manifest.json"), manifest)
            val state = installedPermissionViewModel(filesRoot, repositories).state.first { it.loaded }

            assertTrue(state.loadState is PermissionLoadState.Failed)
            val failure = state.loadState as PermissionLoadState.Failed
            assertEquals(code, failure.code)
            assertTrue(failure.message.isNotBlank())
            assertTrue(state.items.isEmpty())
            assertNotNull(repositories.catalog.observeTool(TOOL_ID).first())
            assertEquals(originalGrants, repositories.grants.observeGrants(TOOL_ID).first())
        }
    }

    @Test
    fun missingToolAndSuccessfullyLoadedEmptyManifestHaveDifferentStates() = runTest(mainDispatcher) {
        val filesRoot = temporaryFolder.newFolder()
        val repositories = InMemoryCoreData.create()

        val missing = installedPermissionViewModel(filesRoot, repositories).state.first { it.loaded }
        assertEquals(PermissionLoadState.NotInstalled, missing.loadState)
        assertTrue(missing.items.isEmpty())

        installFixture(filesRoot, repositories, permissions = emptyList())
        val empty = installedPermissionViewModel(filesRoot, repositories).state.first { it.loaded }
        assertEquals(PermissionLoadState.Ready, empty.loadState)
        assertTrue(empty.items.isEmpty())
        assertNull(empty.message)
    }

    @Test
    fun retainedViewModelReloadsDeclarationsOnUpdateAndRemoval() = runTest(mainDispatcher) {
        val filesRoot = temporaryFolder.newFolder()
        val repositories = InMemoryCoreData.create()
        installFixture(filesRoot, repositories)
        val viewModel = installedPermissionViewModel(filesRoot, repositories)
        viewModel.state.first { it.loaded }

        val next = listOf("network", "clipboard.read")
        installFixture(filesRoot, repositories, permissions = next, versionCode = 2)
        val updated = viewModel.state.first { it.items.map(PermissionItem::capability) == next }
        assertTrue(updated.items.none { it.enabled })
        // A delayed Android permission callback from an earlier version must not grant anything.
        viewModel.systemPermissionResult("expired-request", mapOf("android.permission.POST_NOTIFICATIONS" to true))
        advanceUntilIdle()
        assertTrue(repositories.grants.observeGrants(TOOL_ID).first().none { it.capability == "notifications" })
        repositories.lifecycle.deleteToolCatalog(TOOL_ID)
        assertTrue(viewModel.state.first { it.loadState == PermissionLoadState.NotInstalled }.items.isEmpty())
    }

    @Test
    fun secureRevocationDeniesBeforeCleanupAndFailedWipeCannotBeReenabled() = runTest(mainDispatcher) {
        val grants = FakePermissionGrantRepository(PermissionGrant(TOOL_ID, "storage.secure", true, 1L))
        var failCleanup = true
        var cleanups = 0
        val viewModel = permissionViewModel(
            toolId = TOOL_ID,
            packages = object : HostPackageOperations by FakeHostPackageOperations {
                override suspend fun installedManifest(toolId: String): HostInstalledManifestResult {
                    val base = FakeHostPackageOperations.installedManifest(toolId) as HostInstalledManifestResult.Found
                    return HostInstalledManifestResult.Found(base.manifest.copy(
                        permissions = listOf(HostManifestPermission("storage.secure", "保存密钥", false)),
                    ))
                }
            },
            catalog = InMemoryCoreData.create().catalog,
            grants = grants,
            sideEffects = object : HostPermissionSideEffects {
                override suspend fun onCapabilityDisabled(toolId: String, capability: String) {
                    assertFalse(grants.observeGrants(toolId).first().single().granted)
                    cleanups += 1
                    if (failCleanup) error("wipe unavailable")
                }
            },
        )
        advanceUntilIdle()
        viewModel.setEnabled("storage.secure", false)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.items.single().enabled)
        assertNotNull(viewModel.state.value.message)
        viewModel.setEnabled("storage.secure", true)
        advanceUntilIdle()
        assertFalse(grants.observeGrants(TOOL_ID).first().single().granted)
        failCleanup = false
        viewModel.setEnabled("storage.secure", true)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.items.single().enabled)
        assertEquals(3, cleanups)
    }

    @Test
    fun networkOffDisablesGrantBeforeLegacyStateCleanupAndCancelsActiveStreams() = runTest(mainDispatcher) {
        val repositories = InMemoryCoreData.create()
        installFixture(temporaryFolder.newFolder(), repositories)
        val store = UserNetworkDomainStore(repositories.keyValues)
        repositories.keyValues.put(TOOL_ID, "toolbox.host.v1.network.domains", "[1,\"api.example.com\"]", 1L)
        val grants = FakePermissionGrantRepository(PermissionGrant(TOOL_ID, "network", true, 100L))
        var cleanups = 0
        val viewModel = permissionViewModel(
            toolId = TOOL_ID,
            packages = object : HostPackageOperations by FakeHostPackageOperations {
                override suspend fun installedManifest(toolId: String) = HostInstalledManifestResult.Found(
                    HostInstalledManifest(TOOL_ID, "工具示例", 1, "1.0.0",
                        listOf(HostManifestPermission("network", "连接服务器", false))),
                )
            },
            catalog = repositories.catalog,
            grants = grants,
            sideEffects = object : HostPermissionSideEffects {
                override suspend fun onCapabilityDisabled(toolId: String, capability: String) {
                    assertEquals("network", capability)
                    assertFalse(grants.observeGrants(toolId).first().single().granted)
                    cleanups++
                    store.clear(toolId)
                }
            },
        )
        advanceUntilIdle()
        var cancellations = 0
        val listener = Any()
        io.toolbox.host.runtime.NetworkDomainInvalidation.register(TOOL_ID, listener) { cancellations++ }
        viewModel.setEnabled("network", false)
        advanceUntilIdle()
        assertEquals(1, cleanups)
        assertEquals(1, cancellations)
        assertNull(repositories.keyValues.observe(TOOL_ID, "toolbox.host.v1.network.domains").first())
        io.toolbox.host.runtime.NetworkDomainInvalidation.unregister(TOOL_ID, listener)
        assertFalse(viewModel.state.value.items.single().enabled)
    }

    private fun installedPermissionViewModel(filesRoot: File, repositories: CoreDataRepositories): PermissionCenterViewModel {
        val reader = HostInstalledManifestReader(filesRoot, repositories.catalog)
        return permissionViewModel(
            toolId = TOOL_ID,
            packages = object : HostPackageOperations by FakeHostPackageOperations {
                override suspend fun installedManifest(toolId: String) = reader.read(toolId)
            },
            catalog = repositories.catalog,
            grants = repositories.grants,
            sideEffects = RecordingPermissionSideEffects(),
        )
    }

    @Test
    fun clearingRouteStopsObserversButAcceptedWriteFinishes() = runTest(mainDispatcher) {
        var catalogCollectors = 0
        var grantCollectors = 0
        val repositories = InMemoryCoreData.create()
        val catalog = object : CatalogRepository by repositories.catalog {
            override fun observeTool(toolId: String) = repositories.catalog.observeTool(toolId)
                .onStart { catalogCollectors += 1 }.onCompletion { catalogCollectors -= 1 }
        }
        val stored = FakePermissionGrantRepository(PermissionGrant(TOOL_ID, "storage", true, 1L))
        val writeEntered = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        val grants = object : PermissionGrantRepository by stored {
            override fun observeGrants(toolId: String) = stored.observeGrants(toolId)
                .onStart { grantCollectors += 1 }.onCompletion { grantCollectors -= 1 }

            override suspend fun putForVersion(grant: PermissionGrant, expectedVersionCode: Int): DataResult<Unit> {
                writeEntered.complete(Unit)
                allowWrite.await()
                return stored.putForVersion(grant, expectedVersionCode)
            }
        }
        val sideEffects = RecordingPermissionSideEffects()
        val viewModel = permissionViewModel(catalog = catalog, grants = grants, sideEffects = sideEffects)
        val store = storeFor(viewModel)
        advanceUntilIdle()
        assertEquals(1, catalogCollectors)
        assertEquals(1, grantCollectors)

        viewModel.setEnabled("storage", false)
        store.clear()
        writeEntered.await()
        runCurrent()
        assertEquals(0, catalogCollectors)
        assertEquals(0, grantCollectors)
        val oldState = viewModel.state.value

        allowWrite.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(false), stored.putCalls.map { it.granted })
        assertEquals(listOf("$TOOL_ID:storage"), sideEffects.disabled)
        assertEquals(oldState, viewModel.state.value)
        viewModel.setEnabled("clipboard.write", false)
        advanceUntilIdle()
        assertEquals(1, stored.putCalls.size)
    }

    @Test
    fun requestIdentitySurvivesNewCollectorButEmptyAndForeignResultsNeverGrant() = runTest(mainDispatcher) {
        val grants = FakePermissionGrantRepository()
        val packages = manifestWithPermissions("location", "location.background")
        val viewModel = permissionViewModel(packages = packages, grants = grants)
        advanceUntilIdle()
        val firstRequest = async(start = CoroutineStart.UNDISPATCHED) { viewModel.requests.first() }
        viewModel.setEnabled("location.background", true)
        val request = firstRequest.await()
        viewModel.systemPermissionResult("wrong-id", mapOf(BACKGROUND_LOCATION to true))
        advanceUntilIdle()
        assertTrue(grants.putCalls.isEmpty())
        viewModel.systemPermissionResult(request.id, emptyMap())
        advanceUntilIdle()
        assertTrue(grants.putCalls.isEmpty())
        assertTrue(viewModel.state.value.showSystemSettings)

        val retryRequest = async(start = CoroutineStart.UNDISPATCHED) { viewModel.requests.first() }
        viewModel.setEnabled("location.background", true)
        val retry = retryRequest.await()
        viewModel.systemPermissionResult(request.id, mapOf(BACKGROUND_LOCATION to true))
        viewModel.systemPermissionResult(retry.id, mapOf("unrequested.permission" to true))
        advanceUntilIdle()
        assertTrue(grants.putCalls.isEmpty())

        val validRequest = async(start = CoroutineStart.UNDISPATCHED) { viewModel.requests.first() }
        viewModel.setEnabled("location", true)
        val savedRequestId = validRequest.await().id
        viewModel.systemPermissionResult(savedRequestId, mapOf(COARSE_LOCATION to true))
        viewModel.systemPermissionResult(savedRequestId, mapOf(COARSE_LOCATION to true))
        advanceUntilIdle()
        assertEquals(listOf("location"), grants.putCalls.map { it.capability })
        assertTrue(grants.putCalls.single().granted)
    }

    @Test
    fun pendingSystemRequestCannotCrossToolUpdateOrNewRouteInstance() = runTest(mainDispatcher) {
        val filesRoot = temporaryFolder.newFolder()
        val repositories = InMemoryCoreData.create()
        installFixture(filesRoot, repositories, permissions = listOf("location"))
        val oldPage = installedPermissionViewModel(filesRoot, repositories)
        oldPage.state.first { it.loaded }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { oldPage.requests.first() }
        oldPage.setEnabled("location", true)
        val oldRequest = pending.await()
        installFixture(filesRoot, repositories, permissions = listOf("location", "network"), versionCode = 2)
        oldPage.state.first { it.items.size == 2 }
        oldPage.systemPermissionResult(oldRequest.id, mapOf(COARSE_LOCATION to true))
        assertTrue(repositories.grants.observeGrants(TOOL_ID).first().none { it.granted })
        storeFor(oldPage).clear()
        val newPage = installedPermissionViewModel(filesRoot, repositories)
        newPage.state.first { it.loaded }
        val current = async(start = CoroutineStart.UNDISPATCHED) { newPage.requests.first() }
        newPage.setEnabled("location", true)
        val newRequest = current.await()
        newPage.systemPermissionResult(oldRequest.id, mapOf(COARSE_LOCATION to true))
        assertTrue(repositories.grants.observeGrants(TOOL_ID).first().none { it.granted })
        newPage.systemPermissionResult(newRequest.id, mapOf(COARSE_LOCATION to true))
        newPage.state.first { it.items.single { item -> item.capability == "location" }.enabled }
        assertTrue(repositories.grants.observeGrants(TOOL_ID).first().single { it.capability == "location" }.granted)
    }

    private fun permissionViewModel(
        toolId: String = TOOL_ID,
        packages: HostPackageOperations = FakeHostPackageOperations,
        catalog: CatalogRepository = InMemoryCoreData.create().catalog,
        grants: PermissionGrantRepository,
        sideEffects: HostPermissionSideEffects = RecordingPermissionSideEffects(),
        now: () -> Long = System::currentTimeMillis,
        mutations: PermissionMutationRunner = PermissionMutationRunner(
            packages, grants, sideEffects, CoroutineScope(SupervisorJob() + mainDispatcher), now,
        ),
    ) = PermissionCenterViewModel(toolId, packages, catalog, grants, mutations)

    private fun storeFor(viewModel: PermissionCenterViewModel): ViewModelStore {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
        }).get(PermissionCenterViewModel::class.java)
        return owner.viewModelStore
    }

    private fun manifestWithPermissions(vararg capabilities: String) = object : HostPackageOperations by FakeHostPackageOperations {
        override suspend fun installedManifest(toolId: String): HostInstalledManifestResult {
            val base = FakeHostPackageOperations.installedManifest(toolId) as HostInstalledManifestResult.Found
            return HostInstalledManifestResult.Found(base.manifest.copy(
                permissions = capabilities.map { HostManifestPermission(it, "系统权限测试", false) },
            ))
        }
    }

    private suspend fun installFixture(
        filesRoot: File,
        repositories: CoreDataRepositories,
        permissions: List<String> = FIXTURE_CAPABILITIES,
        versionCode: Int = 1,
    ) {
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { archive ->
                mapOf(
                    "manifest.json" to fixtureManifest(permissions = permissions, versionCode = versionCode),
                    "index.html" to "<!doctype html><html><body>Permission fixture</body></html>",
                ).forEach { (name, content) ->
                    archive.putNextEntry(ZipEntry(name))
                    archive.write(content.toByteArray(Charsets.UTF_8))
                    archive.closeEntry()
                }
            }
            output.toByteArray()
        }
        val manager = ToolPackageManagers.create(
            privateFilesDirectory = filesRoot,
            catalog = repositories.catalog,
            lifecycle = repositories.lifecycle,
            transactions = repositories.installs,
            hostVersion = BuildConfig.VERSION_NAME,
        )
        val input = object : PackageInput {
            override val displayName = "permission-fixture.tbx"
            override fun openStream() = ByteArrayInputStream(bytes)
        }
        assertEquals(PackageInstallResult.Installed(TOOL_ID, versionCode, versionCode > 1), manager.importAndInstall(input))
    }
}

private const val TOOL_ID = "io.toolbox.example"
private const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
private const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
private val FIXTURE_CAPABILITIES = listOf("storage", "storage.secure", "network", "notifications", "background.runtime", "browser")

private fun fixtureManifest(
    minHostVersion: String = BuildConfig.VERSION_NAME,
    permissions: List<String> = FIXTURE_CAPABILITIES,
    versionCode: Int = 1,
): String {
    val declarations = permissions.joinToString(",") { """{"name":"$it","reason":"Fixture permission"}""" }
    val network = if ("network" in permissions) ""","network":{"allowDomains":["api.github.com"]}""" else ""
    return """
        {"schemaVersion":1,"id":"$TOOL_ID","name":"工具示例","version":"1.0.$versionCode","versionCode":$versionCode,"entry":"index.html","apiVersion":"1.0","minHostVersion":"$minHostVersion","permissions":[$declarations],"securityProfile":"strict"$network}
    """.trimIndent()
}

private object FakeHostPackageOperations : HostPackageOperations {
    override suspend fun importPackage(input: PackageInput): io.toolbox.host.HostImportResult =
        error("not used")

    override suspend fun confirmImport(confirmationId: String): io.toolbox.host.HostImportResult = error("not used")

    override suspend fun cancelImport(
        confirmationId: String,
    ): io.toolbox.host.HostImportCancellationResult = error("not used")

    override suspend fun installedManifest(toolId: String): HostInstalledManifestResult =
        if (toolId == TOOL_ID) {
            HostInstalledManifestResult.Found(
                HostInstalledManifest(
                    toolId = TOOL_ID,
                    toolName = "工具示例",
                    versionCode = 1,
                    versionName = "1.0.0",
                    permissions = listOf(
                        HostManifestPermission("storage", "保存工具数据", required = false),
                        HostManifestPermission("clipboard.write", "复制计算结果", required = false),
                    ),
                ),
            )
        } else {
            HostInstalledManifestResult.NotInstalled
        }

    override suspend fun deleteTool(toolId: String): io.toolbox.host.HostDeleteResult = error("not used")

    override suspend fun installBundledExamples(): io.toolbox.host.HostExampleInstallResult = error("not used")
}

private class RecordingPermissionSideEffects : HostPermissionSideEffects {
    val disabled = mutableListOf<String>()

    override suspend fun onCapabilityDisabled(toolId: String, capability: String) {
        disabled += "$toolId:$capability"
    }
}

private class FakePermissionGrantRepository(vararg initial: PermissionGrant) : PermissionGrantRepository {
    private val values = MutableStateFlow(initial.toList())
    val putCalls = mutableListOf<PermissionGrant>()

    override fun observeGrants(toolId: String): Flow<List<PermissionGrant>> = values

    override suspend fun put(grant: PermissionGrant): DataResult<Unit> {
        putCalls += grant
        values.value = values.value.filterNot { it.toolId == grant.toolId && it.capability == grant.capability } + grant
        return DataResult.Success(Unit)
    }

    override suspend fun putForVersion(grant: PermissionGrant, expectedVersionCode: Int): DataResult<Unit> {
        assertEquals(1, expectedVersionCode)
        return put(grant)
    }

    override suspend fun revoke(toolId: String, capability: String): DataResult<Unit> =
        error("not used")
}
