package io.toolbox.host.update

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.host.HostPackageOperations
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.lifecycle.PackageImportControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<UpdateViewModel>()
    private val releases = ControlledReleases()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        releases.finishPending()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun refreshImmediatelyShowsLoadingAndIgnoresDuplicateClicksWithCachedFeed() = runTest(dispatcher) {
        val model = model()
        releases.attempts.receive().succeed("99.0.0")
        val cached = model.state.first { it.completedChecks == 1L }
        assertNotNull(cached.feed)
        assertTrue(cached.tools.isNotEmpty())
        assertNotNull(cached.lastCheckedAt)
        assertEquals("检查完成，发现ToolBox 新版本。", cached.checkSummary)

        model.refresh()
        model.refresh()
        model.refresh()
        val checking = model.state.value
        assertTrue(checking.loading)
        assertSame(cached.feed, checking.feed)
        assertSame(cached.tools, checking.tools)
        assertNull(checking.checkSummary)
        assertEquals(1L, checking.completedChecks)

        releases.attempts.receive().succeed("99.0.0")
        val complete = model.state.first { it.completedChecks == 2L }
        assertFalse(complete.loading)
        assertNull(complete.error)
        assertEquals(cached.feed, complete.feed)
        assertEquals(cached.checkSummary, complete.checkSummary)
        // The completion identity changes even when all fetched data and its result text are identical.
        assertEquals(2, releases.requestCount)
    }

    @Test fun aFailedCheckKeepsOldResultsAndRetryFeedbackIsIndependentOfInstallationMessages() = runTest(dispatcher) {
        val model = model()
        releases.attempts.receive().succeed("99.0.0")
        val cached = model.state.first { it.completedChecks == 1L }
        model.apkHandOffFailed()
        val installationMessage = model.state.value.message

        model.refresh()
        releases.attempts.receive().fail(503)
        val failed = model.state.first { it.completedChecks == 2L }
        assertFalse(failed.loading)
        assertSame(cached.feed, failed.feed)
        assertSame(cached.tools, failed.tools)
        assertEquals("检查更新失败（HTTP 503）。", failed.error)
        assertNull(failed.checkSummary)
        assertEquals(installationMessage, failed.message)
        assertNotNull(failed.lastCheckedAt)

        model.refresh()
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.error)
        assertSame(cached.feed, model.state.value.feed)
        releases.attempts.receive().succeed("0.0.1")
        val retried = model.state.first { it.completedChecks == 3L }
        assertFalse(retried.loading)
        assertNull(retried.error)
        assertEquals("检查完成，未发现可用更新。", retried.checkSummary)
        assertEquals(installationMessage, retried.message)
    }

    @Test fun switchingChannelsDoesNotAnnounceALateResultFromTheOldChannel() = runTest(dispatcher) {
        val model = model()
        val oldCheck = releases.attempts.receive()
        model.selectChannel(UpdateChannel.TEST)
        oldCheck.succeed("99.0.0")

        val newStableCheck = releases.attempts.receive()
        val checking = model.state.value
        assertEquals(UpdateChannel.TEST, checking.channel)
        assertTrue(checking.loading)
        assertNull(checking.feed)
        assertNull(checking.checkSummary)
        assertNull(checking.lastCheckedAt)
        assertEquals(0L, checking.completedChecks)

        newStableCheck.succeed("0.0.1")
        val newTestCheck = releases.attempts.receive()
        assertTrue(newTestCheck.path.endsWith("/releases/tags/dev"))
        newTestCheck.succeed("99.0.0")
        val complete = model.state.first { it.completedChecks == 1L }
        assertFalse(complete.loading)
        assertEquals(UpdateChannel.TEST, complete.feed?.channel)
        assertEquals("99.0.0", complete.feed?.app?.versionName)
        assertNotNull(complete.lastCheckedAt)
        assertEquals("检查完成，发现ToolBox 新版本。", complete.checkSummary)
    }

    private fun model(): UpdateViewModel {
        val repository = UpdateRepository(
            RuntimeEnvironment.getApplication(),
            OkHttpClient.Builder().addInterceptor(releases).build(),
        )
        val catalog = object : CatalogRepository {
            override fun observeCatalogProjection(): Flow<List<CatalogEntry>> = flowOf(emptyList())
            override fun observeTools(): Flow<List<InstalledTool>> = flowOf(emptyList())
            override fun observeTool(toolId: String): Flow<InstalledTool?> = flowOf(null)
        }
        return UpdateViewModel(repository, catalog, unusedPackages).also(models::add)
    }
}

/** Uses the production repository and parser; only its synchronous HTTP boundary is controlled. */
private class ControlledReleases : Interceptor {
    val attempts = Channel<Attempt>(Channel.UNLIMITED)
    private val requested = CopyOnWriteArrayList<Attempt>()
    val requestCount: Int get() = requested.size

    class Attempt(val path: String) {
        val reply = CompletableDeferred<Pair<Int, String>>()

        fun succeed(version: String) {
            reply.complete(200 to """
                {"tag_name":"v$version","assets":[
                  {"name":"toolbox-v$version-release.apk","size":100,"browser_download_url":"https://example.test/app.apk","digest":"sha256:${"a".repeat(64)}"},
                  {"name":"nextflux-v1.4.3.tbx","size":100,"browser_download_url":"https://example.test/tool.tbx","digest":"sha256:${"b".repeat(64)}"}
                ]}
            """.trimIndent())
        }

        fun fail(code: Int) { reply.complete(code to "{}") }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val attempt = Attempt(chain.request().url.encodedPath)
        requested += attempt
        check(attempts.trySend(attempt).isSuccess)
        val (code, body) = runBlocking { attempt.reply.await() }
        return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("controlled release").body(body.toResponseBody()).build()
    }

    fun finishPending() { requested.forEach { it.fail(503) } }
}

private val unusedPackages = object : HostPackageOperations {
    override suspend fun importPackage(input: PackageInput, control: PackageImportControl): Nothing = error("unused")
    override suspend fun confirmImport(confirmationId: String, control: PackageImportControl): Nothing = error("unused")
    override suspend fun cancelImport(confirmationId: String): Nothing = error("unused")
    override suspend fun installedManifest(toolId: String): Nothing = error("unused")
    override suspend fun deleteTool(toolId: String): Nothing = error("unused")
    override suspend fun installBundledExamples(): Nothing = error("unused")
}
