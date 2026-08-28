package io.toolbox.core.data

import io.toolbox.core.data.memory.InMemoryCoreData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CatalogLifecyclePolicyTest(private val scenario: Scenario) {
    @Test
    fun transactionPolicyAndCompensationLeaveOnlyCommittedCatalogState() = runTest {
        scenario.verify()
    }

    data class Scenario(
        val label: String,
        val verify: suspend () -> Unit,
    ) {
        override fun toString(): String = label
    }

    companion object {
        private const val TOOL_ID = "io.toolbox.test.lifecycle"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Scenario>> = listOf(
            scenario("source session and version identity are unique") {
                val repositories = InMemoryCoreData.create()
                val original = attempt(versionCode = 2, sourceSessionId = "session-a")
                assertEquals(
                    DataResult.Success(CommitInstallOutcome.Committed),
                    repositories.lifecycle.commitInstall(original),
                )
                assertEquals(
                    DataResult.Success(CommittedInstall(TOOL_ID, 2)),
                    repositories.lifecycle.findCommittedInstall("session-a"),
                )
                assertEquals(
                    DataResult.Success(null),
                    repositories.lifecycle.findCommittedInstall("session-a-missing"),
                )
                assertEquals(
                    DataResult.Failure.InvalidInput("sourceSessionId"),
                    repositories.lifecycle.findCommittedInstall(" "),
                )
                assertEquals(
                    DataResult.Failure.InvalidInput("sourceSessionId"),
                    repositories.lifecycle.findCommittedInstall("s".repeat(129)),
                )
                assertEquals(
                    DataResult.Success(CommitInstallOutcome.AlreadyCommitted),
                    repositories.lifecycle.commitInstall(original),
                )
                assertEquals(
                    DataResult.Failure.DuplicateSourceSession("session-a"),
                    repositories.lifecycle.commitInstall(attempt(versionCode = 3, sourceSessionId = "session-a")),
                )
                assertEquals(
                    DataResult.Failure.DuplicateVersion(TOOL_ID, 2),
                    repositories.lifecycle.commitInstall(attempt(versionCode = 2, sourceSessionId = "session-b")),
                )
                assertEquals(listOf(2), repositories.catalog.observeVersions(TOOL_ID).first().map { it.versionCode })
            },
            scenario("version code is monotonic") {
                val repositories = InMemoryCoreData.create()
                repositories.lifecycle.commitInstall(attempt(versionCode = 3, sourceSessionId = "session-3"))
                assertEquals(
                    DataResult.Failure.NonMonotonicVersion(TOOL_ID, 2, 3),
                    repositories.lifecycle.commitInstall(attempt(versionCode = 2, sourceSessionId = "session-2")),
                )
                assertEquals(listOf(3), repositories.catalog.observeVersions(TOOL_ID).first().map { it.versionCode })
            },
            scenario("signed identity cannot downgrade or change key") {
                val repositories = InMemoryCoreData.create()
                repositories.lifecycle.commitInstall(
                    attempt(1, "signed-1", SignatureState.VERIFIED_UNKNOWN, "publisher-a"),
                )
                assertEquals(
                    DataResult.Failure.SignatureContinuityViolation(TOOL_ID),
                    repositories.lifecycle.commitInstall(attempt(2, "unsigned-2", SignatureState.UNSIGNED, null)),
                )
                assertEquals(
                    DataResult.Failure.SignatureContinuityViolation(TOOL_ID),
                    repositories.lifecycle.commitInstall(
                        attempt(3, "signed-3", SignatureState.VERIFIED_TRUSTED, "publisher-b"),
                    ),
                )
                assertEquals(listOf(1), repositories.catalog.observeVersions(TOOL_ID).first().map { it.versionCode })
            },
            scenario("transaction hook failure rolls back without residue") {
                val repositories = InMemoryCoreData.create(
                    commitHook = CatalogCommitHook { error("injected commit failure") },
                )
                val before = repositories.lifecycle.snapshot(TOOL_ID)
                assertEquals(
                    DataResult.Failure.StorageFailure("commitInstall"),
                    repositories.lifecycle.commitInstall(attempt(1, "failed-session")),
                )
                assertEquals(before, repositories.lifecycle.snapshot(TOOL_ID))
            },
            scenario("unsigned tools cannot receive persistent granted permissions") {
                val repositories = InMemoryCoreData.create()
                val before = repositories.lifecycle.snapshot(TOOL_ID)
                assertEquals(
                    DataResult.Failure.UnsignedPersistentGrant(TOOL_ID, "storage"),
                    repositories.lifecycle.commitInstall(
                        attempt(
                            1,
                            "unsafe-grant",
                            grants = listOf(grant("storage", GrantState.GRANTED)),
                        ),
                    ),
                )
                assertEquals(before, repositories.lifecycle.snapshot(TOOL_ID))

                val safeGrants = listOf(
                    grant("storage", GrantState.GRANTED, GrantScope.SESSION),
                    grant("network", GrantState.DENIED),
                )
                assertEquals(
                    DataResult.Success(CommitInstallOutcome.Committed),
                    repositories.lifecycle.commitInstall(attempt(1, "safe-grants", grants = safeGrants)),
                )
                val snapshot = (repositories.lifecycle.snapshot(TOOL_ID) as DataResult.Success).value
                assertEquals(safeGrants.sortedBy { it.permission }, snapshot.grants)
            },
            scenario("compensation restores the pre-install snapshot") {
                val repositories = InMemoryCoreData.create()
                val first = attempt(
                    versionCode = 1,
                    sourceSessionId = "session-1",
                    signatureState = SignatureState.VERIFIED_UNKNOWN,
                    publisherKeyId = "publisher-a",
                    name = "Original",
                    grants = listOf(grant("storage", GrantState.GRANTED)),
                )
                repositories.lifecycle.commitInstall(first)
                repositories.lifecycle.markActiveVersionStable(TOOL_ID, 1)
                val before = (repositories.lifecycle.snapshot(TOOL_ID) as DataResult.Success).value
                val update = attempt(
                    versionCode = 2,
                    sourceSessionId = "session-2",
                    signatureState = SignatureState.VERIFIED_TRUSTED,
                    publisherKeyId = "publisher-a",
                    name = "Updated",
                    grants = listOf(grant("network", GrantState.DENIED)),
                )
                assertTrue(repositories.lifecycle.commitInstall(update) is DataResult.Success)
                assertEquals(DataResult.Success(Unit), repositories.lifecycle.compensateInstall(update, before))
                assertEquals(DataResult.Success(before), repositories.lifecycle.snapshot(TOOL_ID))

                assertTrue(repositories.lifecycle.commitInstall(update) is DataResult.Success)
                assertEquals(DataResult.Success(Unit), repositories.lifecycle.markActiveVersionStable(TOOL_ID, 2))
                val stableState = repositories.lifecycle.snapshot(TOOL_ID)
                assertEquals(
                    DataResult.Failure.LifecycleConflict(TOOL_ID),
                    repositories.lifecycle.compensateInstall(update, before),
                )
                assertEquals(stableState, repositories.lifecycle.snapshot(TOOL_ID))
            },
        )

        private fun scenario(label: String, verify: suspend () -> Unit) = arrayOf(Scenario(label, verify))

        private fun attempt(
            versionCode: Int,
            sourceSessionId: String,
            signatureState: SignatureState = SignatureState.UNSIGNED,
            publisherKeyId: String? = null,
            name: String = "Lifecycle tool",
            grants: List<PermissionGrant> = emptyList(),
        ): CatalogInstallAttempt {
            val identity = ToolVersionIdentity(name, signatureState, publisherKeyId, SecurityProfile.STRICT)
            return CatalogInstallAttempt(
                metadata = ToolMetadata(
                    id = TOOL_ID,
                    name = name,
                    signatureState = signatureState,
                    publisherKeyId = publisherKeyId,
                    securityProfile = SecurityProfile.STRICT,
                    installedAt = versionCode.toLong(),
                ),
                version = ToolVersion(
                    toolId = TOOL_ID,
                    versionCode = versionCode,
                    version = "$versionCode.0.0",
                    bundleLocator = BundleLocator("miniapps/lifecycle/versions/$versionCode/bundle"),
                    bundleBytes = versionCode.toLong(),
                    integrityHash = "sha256:$versionCode",
                    installedAt = versionCode.toLong(),
                    launchState = LaunchState.PENDING,
                    sourceSessionId = sourceSessionId,
                    identity = identity,
                ),
                initialGrants = grants,
            )
        }

        private fun grant(
            permission: String,
            state: GrantState,
            scope: GrantScope = GrantScope.PERSISTENT,
        ) = PermissionGrant(
            toolId = TOOL_ID,
            permission = permission,
            state = state,
            scope = scope,
            grantedAt = 1,
            expiresAt = null,
            source = GrantSource.INSTALL,
        )
    }
}
