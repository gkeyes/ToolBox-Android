package io.toolbox.core.data

import io.toolbox.core.data.memory.InMemoryCoreData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRepositoryTest {
    @Test
    fun versionCanBeRegisteredThenActivated() = runTest {
        val repository = InMemoryCoreData.create().catalog
        repository.registerVersion(tool(), version())

        val result = repository.activateVersion(TOOL_ID, VERSION_CODE)

        assertTrue(result is DataResult.Success)
        assertEquals(VERSION_CODE, repository.observeTool(TOOL_ID).first()?.activeVersionCode)
        assertEquals(LaunchState.STABLE, repository.observeVersions(TOOL_ID).first().single().launchState)
    }

    @Test
    fun duplicateVersionIsRejectedWithoutReplacingCatalogState() = runTest {
        val repository = InMemoryCoreData.create().catalog
        repository.registerVersion(tool(), version(version = "1.0.0"))

        val result = repository.registerVersion(tool(name = "Replaced"), version(version = "9.9.9"))

        assertEquals(DataResult.Failure.DuplicateVersion(TOOL_ID, VERSION_CODE), result)
        assertEquals("Test tool", repository.observeTool(TOOL_ID).first()?.metadata?.name)
        assertEquals("1.0.0", repository.observeVersions(TOOL_ID).first().single().version)
    }

    @Test
    fun commitFailureLeavesNoActiveVersion() = runTest {
        val repository = InMemoryCoreData.create(
            commitHook = CatalogCommitHook { error("injected commit failure") },
        ).catalog
        repository.registerVersion(tool(), version())

        val result = repository.activateVersion(TOOL_ID, VERSION_CODE)

        assertEquals(DataResult.Failure.StorageFailure("activateVersion"), result)
        assertNull(repository.observeTool(TOOL_ID).first()?.activeVersionCode)
        assertEquals(LaunchState.PENDING, repository.observeVersions(TOOL_ID).first().single().launchState)
    }

    private fun tool(name: String = "Test tool") = ToolMetadata(
        id = TOOL_ID,
        name = name,
        signatureState = SignatureState.VERIFIED_UNKNOWN,
        publisherKeyId = null,
        securityProfile = SecurityProfile.STRICT,
        installedAt = 100,
    )

    private fun version(version: String = "1.0.0") = ToolVersion(
        toolId = TOOL_ID,
        versionCode = VERSION_CODE,
        version = version,
        bundleLocator = BundleLocator("miniapps/test/versions/1/bundle"),
        bundleBytes = 128,
        integrityHash = "sha256:test",
        installedAt = 100,
        launchState = LaunchState.PENDING,
    )

    private companion object {
        const val TOOL_ID = "io.toolbox.test.tool"
        const val VERSION_CODE = 1
    }
}
