package io.toolbox.core.data

import io.toolbox.core.data.memory.InMemoryCoreData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CatalogOrganizationRepositoryTest(private val scenario: Scenario) {
    @Test
    fun organizationWritesValidateOwnershipAndUpdateOnlyHostFields() = runTest {
        scenario.verify()
    }

    data class Scenario(
        val label: String,
        val verify: suspend () -> Unit,
    ) {
        override fun toString(): String = label
    }

    companion object {
        private const val TOOL_ID = "io.toolbox.test.organization"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Scenario>> = listOf(
            scenario("valid host organization fields are stored") {
                val repositories = installedRepositories()
                val beforeTool = requireNotNull(repositories.catalog.observeTool(TOOL_ID).first())
                val beforeVersions = repositories.catalog.observeVersions(TOOL_ID).first()
                assertEquals(DataResult.Success(Unit), repositories.organization.setPinnedOrder(TOOL_ID, 0))
                assertEquals(DataResult.Success(Unit), repositories.organization.setCategory(TOOL_ID, "finance"))
                assertEquals(DataResult.Success(Unit), repositories.organization.recordOpened(TOOL_ID, 42))
                val tool = requireNotNull(repositories.catalog.observeTool(TOOL_ID).first())
                assertEquals(
                    beforeTool.metadata.copy(pinnedOrder = 0, categoryId = "finance"),
                    tool.metadata,
                )
                assertEquals(beforeTool.activeVersionCode, tool.activeVersionCode)
                assertEquals(42L, tool.lastOpenedAt)
                assertEquals(beforeVersions, repositories.catalog.observeVersions(TOOL_ID).first())
            },
            scenario("negative organization values are rejected") {
                val repositories = installedRepositories()
                val before = repositories.catalog.observeTool(TOOL_ID).first()
                assertEquals(
                    DataResult.Failure.InvalidInput("pinnedOrder"),
                    repositories.organization.setPinnedOrder(TOOL_ID, -1),
                )
                assertEquals(
                    DataResult.Failure.InvalidInput("timestamp"),
                    repositories.organization.recordOpened(TOOL_ID, -1),
                )
                assertEquals(before, repositories.catalog.observeTool(TOOL_ID).first())
            },
            scenario("blank or oversized category is rejected") {
                val repositories = installedRepositories()
                val before = repositories.catalog.observeTool(TOOL_ID).first()
                assertEquals(
                    DataResult.Failure.InvalidInput("categoryId"),
                    repositories.organization.setCategory(TOOL_ID, " "),
                )
                assertEquals(
                    DataResult.Failure.InvalidInput("categoryId"),
                    repositories.organization.setCategory(TOOL_ID, "c".repeat(129)),
                )
                assertEquals(before, repositories.catalog.observeTool(TOOL_ID).first())
            },
            scenario("unknown or blank tool id is rejected") {
                val repositories = installedRepositories()
                assertEquals(
                    DataResult.Failure.NotFound("tool"),
                    repositories.organization.setCategory("missing", null),
                )
                assertEquals(
                    DataResult.Failure.InvalidInput("toolId"),
                    repositories.organization.recordOpened(" ", 1),
                )
            },
        )

        private fun scenario(label: String, verify: suspend () -> Unit) = arrayOf(Scenario(label, verify))

        private suspend fun installedRepositories(): CoreDataRepositories {
            val repositories = InMemoryCoreData.create()
            val identity = ToolVersionIdentity(
                name = "Organization tool",
                signatureState = SignatureState.UNSIGNED,
                publisherKeyId = null,
                securityProfile = SecurityProfile.STRICT,
            )
            val attempt = CatalogInstallAttempt(
                metadata = ToolMetadata(
                    id = TOOL_ID,
                    name = identity.name,
                    signatureState = identity.signatureState,
                    publisherKeyId = identity.publisherKeyId,
                    securityProfile = identity.securityProfile,
                    installedAt = 1,
                ),
                version = ToolVersion(
                    toolId = TOOL_ID,
                    versionCode = 1,
                    version = "1.0.0",
                    bundleLocator = BundleLocator("miniapps/organization/versions/1/bundle"),
                    bundleBytes = 1,
                    integrityHash = "sha256:organization",
                    installedAt = 1,
                    launchState = LaunchState.PENDING,
                    sourceSessionId = "organization-session",
                    identity = identity,
                ),
                initialGrants = emptyList(),
            )
            assertEquals(
                DataResult.Success(CommitInstallOutcome.Committed),
                repositories.lifecycle.commitInstall(attempt),
            )
            return repositories
        }
    }
}
