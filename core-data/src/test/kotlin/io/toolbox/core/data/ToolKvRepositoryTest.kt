package io.toolbox.core.data

import io.toolbox.core.data.memory.InMemoryCoreData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ToolKvRepositoryTest(private val scenario: Scenario) {
    @Test
    fun quotaAndConcurrentWritesRemainAtomic() = runTest {
        val repositories = InMemoryCoreData.create()
        repositories.lifecycle.commitInstall(
            CatalogInstallAttempt(tool(), version(), emptyList()),
        )
        val repository = repositories.keyValues

        val results = scenario.values.mapIndexed { index, value ->
            async {
                repository.put(
                    toolId = TOOL_ID,
                    key = "key-$index",
                    valueJson = value,
                    updatedAt = index.toLong(),
                    quotaBytes = scenario.quotaBytes,
                )
            }
        }.awaitAll()

        assertEquals(scenario.expectedSuccesses, results.count { it is DataResult.Success })
        assertEquals(scenario.expectedBytesUsed, repository.bytesUsed(TOOL_ID))
        assertEquals(
            scenario.values.size - scenario.expectedSuccesses,
            results.count { it is DataResult.Failure.QuotaExceeded },
        )
    }

    data class Scenario(
        val label: String,
        val quotaBytes: Long,
        val values: List<String>,
        val expectedSuccesses: Int,
        val expectedBytesUsed: Long,
    ) {
        override fun toString(): String = label
    }

    companion object {
        private const val TOOL_ID = "io.toolbox.test.storage"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios() = listOf(
            arrayOf(Scenario("within quota", 5, listOf("aa", "bbb"), 2, 5)),
            arrayOf(Scenario("quota rejects whole write", 5, listOf("aaaa", "bb"), 1, 4)),
            arrayOf(Scenario("concurrent exact quota", 5, List(8) { "x" }, 5, 5)),
        )
    }

    private fun tool() = ToolMetadata(
        id = TOOL_ID,
        name = "Storage tool",
        signatureState = SignatureState.UNSIGNED,
        publisherKeyId = null,
        securityProfile = SecurityProfile.STRICT,
        installedAt = 100,
    )

    private fun version() = ToolVersion(
        toolId = TOOL_ID,
        versionCode = 1,
        version = "1.0.0",
        bundleLocator = BundleLocator("miniapps/storage/versions/1/bundle"),
        bundleBytes = 1,
        integrityHash = "sha256:storage",
        installedAt = 100,
        launchState = LaunchState.PENDING,
        sourceSessionId = "storage-session",
        identity = ToolVersionIdentity(
            name = "Storage tool",
            signatureState = SignatureState.UNSIGNED,
            publisherKeyId = null,
            securityProfile = SecurityProfile.STRICT,
        ),
    )
}
