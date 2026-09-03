package io.toolbox.host.permissions

import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.core.data.memory.InMemoryCoreData
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
        val viewModel = PermissionCenterViewModel(
            toolId = TOOL_ID,
            packages = FakeHostPackageOperations,
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
        val viewModel = PermissionCenterViewModel(
            toolId = TOOL_ID,
            packages = FakeHostPackageOperations,
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

    private fun installedPermissionViewModel(filesRoot: File, repositories: CoreDataRepositories): PermissionCenterViewModel {
        val reader = HostInstalledManifestReader(filesRoot, repositories.catalog)
        return PermissionCenterViewModel(
            toolId = TOOL_ID,
            packages = object : HostPackageOperations by FakeHostPackageOperations {
                override suspend fun installedManifest(toolId: String) = reader.read(toolId)
            },
            grants = repositories.grants,
            sideEffects = RecordingPermissionSideEffects(),
        )
    }

    private suspend fun installFixture(
        filesRoot: File,
        repositories: CoreDataRepositories,
        permissions: List<String> = FIXTURE_CAPABILITIES,
    ) {
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { archive ->
                mapOf(
                    "manifest.json" to fixtureManifest(permissions = permissions),
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
        assertEquals(PackageInstallResult.Installed(TOOL_ID, 1, false), manager.importAndInstall(input))
    }
}

private const val TOOL_ID = "io.toolbox.example"
private val FIXTURE_CAPABILITIES = listOf("storage", "storage.secure", "network", "notifications", "background.runtime")

private fun fixtureManifest(
    minHostVersion: String = BuildConfig.VERSION_NAME,
    permissions: List<String> = FIXTURE_CAPABILITIES,
): String {
    val declarations = permissions.joinToString(",") { """{"name":"$it","reason":"Fixture permission"}""" }
    val network = if ("network" in permissions) ""","network":{"allowDomains":["api.github.com"]}""" else ""
    return """
        {"schemaVersion":1,"id":"$TOOL_ID","name":"工具示例","version":"1.0.0","versionCode":1,"entry":"index.html","apiVersion":"1.0","minHostVersion":"$minHostVersion","permissions":[$declarations],"securityProfile":"strict"$network}
    """.trimIndent()
}

private object FakeHostPackageOperations : HostPackageOperations {
    override suspend fun importPackage(input: PackageInput): io.toolbox.host.HostImportResult =
        error("not used")

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

    override suspend fun revoke(toolId: String, capability: String): DataResult<Unit> =
        error("not used")
}
