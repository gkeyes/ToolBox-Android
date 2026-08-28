package io.toolbox.core.data

import io.toolbox.core.data.memory.InMemoryCoreData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogRepositoryTest {
    @Test
    fun installRemainsPendingUntilActiveVersionIsExplicitlyMarkedStable() = runTest {
        val repositories = InMemoryCoreData.create()

        val installed = repositories.lifecycle.commitInstall(attempt())

        assertEquals(DataResult.Success(CommitInstallOutcome.Committed), installed)
        assertEquals(VERSION_CODE, repositories.catalog.observeTool(TOOL_ID).first()?.activeVersionCode)
        assertEquals(LaunchState.PENDING, repositories.catalog.observeVersions(TOOL_ID).first().single().launchState)

        val marked = repositories.lifecycle.markActiveVersionStable(TOOL_ID, VERSION_CODE)

        assertEquals(DataResult.Success(Unit), marked)
        assertEquals(LaunchState.STABLE, repositories.catalog.observeVersions(TOOL_ID).first().single().launchState)
    }

    private fun tool(name: String = "Test tool") = ToolMetadata(
        id = TOOL_ID,
        name = name,
        signatureState = SignatureState.VERIFIED_UNKNOWN,
        publisherKeyId = "publisher-key-1",
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
        sourceSessionId = "import-session-1",
        identity = ToolVersionIdentity(
            name = "Test tool",
            signatureState = SignatureState.VERIFIED_UNKNOWN,
            publisherKeyId = "publisher-key-1",
            securityProfile = SecurityProfile.STRICT,
        ),
    )

    private fun attempt() = CatalogInstallAttempt(
        metadata = tool(),
        version = version(),
        initialGrants = emptyList(),
    )

    private companion object {
        const val TOOL_ID = "io.toolbox.test.tool"
        const val VERSION_CODE = 1
    }
}
