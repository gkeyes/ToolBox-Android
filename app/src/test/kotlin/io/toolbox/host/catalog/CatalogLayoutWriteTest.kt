package io.toolbox.host.catalog

import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogGroup
import io.toolbox.core.data.CatalogLayout
import io.toolbox.core.data.CatalogLayoutRepository
import io.toolbox.core.data.CatalogOrganizationRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.CatalogSort
import io.toolbox.core.data.DataMutationLock
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.host.HostPackageOperations
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.lifecycle.PackageImportControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogLayoutWriteTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<CatalogViewModel>()
    private val original = CatalogLayout(groups = listOf(
        CatalogGroup("first", "原分组", expanded = false),
        CatalogGroup("second", "另一个分组"),
    ))

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun delayedSaveStaysWritingAndKeepsOldLayoutUntilCommit() = layoutTest {
        val settings = ControlledLayoutSettings(original)
        val viewModel = loadedViewModel(settings)

        viewModel.dispatch(CatalogAction.SaveGroup("first", "新分组", emptyList(), "editor"))
        assertEquals(CatalogLayoutWriteStatus.Writing, viewModel.state.value.layoutWrites["editor"])
        val attempt = settings.attempts.receive()
        assertEquals("新分组", attempt.next.catalogLayout.groups.first().name)
        assertEquals(original, settings.saved.value.catalogLayout)
        assertEquals(original, viewModel.state.value.layout)

        attempt.complete()
        viewModel.awaitStatus("editor", CatalogLayoutWriteStatus.Succeeded)
        viewModel.state.first { it.layout.groups.first().name == "新分组" }
        assertFalse(viewModel.state.value.layout.groups.first().expanded)
        assertNull(viewModel.state.value.feedback)
    }

    @Test fun failedSavePreservesDataAndCanRetryUsingTheSameEditorId() = layoutTest {
        val settings = ControlledLayoutSettings(original)
        val viewModel = loadedViewModel(settings)
        val action = CatalogAction.SaveGroup("first", "新名称", emptyList(), "editor")
        viewModel.dispatch(action)
        settings.attempts.receive().complete(DataResult.Failure.StorageFailure("disk-write"))

        val failed = viewModel.state.first { it.layoutWrites["editor"] is CatalogLayoutWriteStatus.Failed }
        val status = failed.layoutWrites.getValue("editor") as CatalogLayoutWriteStatus.Failed
        assertEquals("CATALOG_LAYOUT_WRITE", status.code)
        assertTrue(status.message.isNotBlank())
        assertEquals(original, settings.saved.value.catalogLayout)
        assertEquals(original, failed.layout)
        assertNull(failed.feedback)

        viewModel.dispatch(action)
        assertEquals(CatalogLayoutWriteStatus.Writing, viewModel.state.value.layoutWrites["editor"])
        settings.attempts.receive().complete()
        viewModel.awaitStatus("editor", CatalogLayoutWriteStatus.Succeeded)
        assertEquals("新名称", settings.saved.value.catalogLayout.groups.first().name)
    }

    @Test fun differentEditorsKeepIndependentQueuedAndCompletedResults() = layoutTest {
        val settings = ControlledLayoutSettings(original)
        val viewModel = loadedViewModel(settings)
        viewModel.dispatch(CatalogAction.SaveGroup("first", "失败保存", emptyList(), "first-editor"))
        viewModel.dispatch(CatalogAction.SaveGroup("second", "成功保存", emptyList(), "second-editor"))
        assertEquals(mapOf(
            "first-editor" to CatalogLayoutWriteStatus.Writing,
            "second-editor" to CatalogLayoutWriteStatus.Writing,
        ), viewModel.state.value.layoutWrites)

        settings.attempts.receive().complete(DataResult.Failure.StorageFailure("disk-write"))
        val second = settings.attempts.receive()
        assertTrue(viewModel.state.value.layoutWrites["first-editor"] is CatalogLayoutWriteStatus.Failed)
        assertEquals(CatalogLayoutWriteStatus.Writing, viewModel.state.value.layoutWrites["second-editor"])
        second.complete()
        viewModel.awaitStatus("second-editor", CatalogLayoutWriteStatus.Succeeded)
        assertTrue(viewModel.state.value.layoutWrites["first-editor"] is CatalogLayoutWriteStatus.Failed)
        assertEquals(listOf("原分组", "成功保存"), settings.saved.value.catalogLayout.groups.map { it.name })
    }

    @Test fun duplicateSubmissionIsIgnoredOnlyWhileItsOperationIsWriting() = layoutTest {
        val settings = ControlledLayoutSettings(original)
        val viewModel = loadedViewModel(settings)
        viewModel.dispatch(CatalogAction.CreateGroup("一次创建", "editor"))
        val first = settings.attempts.receive()
        viewModel.dispatch(CatalogAction.CreateGroup("重复创建", "editor"))
        // A different operation still enters the existing write queue.
        viewModel.dispatch(CatalogAction.SetSort(CatalogSort.NAME, "sort"))
        first.complete()
        val next = settings.attempts.receive()
        assertEquals(CatalogSort.NAME, next.next.catalogLayout.sort)
        assertEquals(listOf("原分组", "另一个分组", "一次创建"), next.next.catalogLayout.groups.map { it.name })
        next.complete()
        viewModel.awaitStatus("sort", CatalogLayoutWriteStatus.Succeeded)
        assertEquals(CatalogLayoutWriteStatus.Succeeded, viewModel.state.value.layoutWrites["editor"])
    }

    @Test fun forgottenSuccessAndFailureNeverReinsertStateOrShowGlobalFeedback() = layoutTest {
        val settings = ControlledLayoutSettings(original)
        val viewModel = loadedViewModel(settings)
        viewModel.dispatch(CatalogAction.RenameGroup("first", "不应保存", "failure"))
        viewModel.dispatch(CatalogAction.RenameGroup("second", "仍会保存", "success"))
        val failure = settings.attempts.receive()
        viewModel.dispatch(CatalogAction.ForgetLayoutWrite("failure"))
        viewModel.dispatch(CatalogAction.ForgetLayoutWrite("success"))
        assertTrue(viewModel.state.value.layoutWrites.isEmpty())
        failure.complete(DataResult.Failure.StorageFailure("disk-write"))
        settings.attempts.receive().complete()

        // Entering this later repository write proves both forgotten completions were handled.
        viewModel.dispatch(CatalogAction.SetSort(CatalogSort.NAME, "barrier"))
        val barrier = settings.attempts.receive()
        assertEquals(mapOf("barrier" to CatalogLayoutWriteStatus.Writing), viewModel.state.value.layoutWrites)
        assertNull(viewModel.state.value.feedback)
        assertEquals(listOf("原分组", "仍会保存"), settings.saved.value.catalogLayout.groups.map { it.name })
        barrier.complete()
        viewModel.awaitStatus("barrier", CatalogLayoutWriteStatus.Succeeded)
        viewModel.dispatch(CatalogAction.ForgetLayoutWrite("barrier"))
        assertTrue(viewModel.state.value.layoutWrites.isEmpty())
    }

    @Test fun reusedOperationIdCannotReceiveTheForgottenSubmissionResult() = layoutTest {
        val settings = ControlledLayoutSettings(original)
        val viewModel = loadedViewModel(settings)
        viewModel.dispatch(CatalogAction.RenameGroup("first", "已提交的旧名称", "editor"))
        val old = settings.attempts.receive()
        viewModel.dispatch(CatalogAction.ForgetLayoutWrite("editor"))
        viewModel.dispatch(CatalogAction.RenameGroup("first", "新的编辑", "editor"))
        old.complete()
        val current = settings.attempts.receive()
        assertEquals(CatalogLayoutWriteStatus.Writing, viewModel.state.value.layoutWrites["editor"])
        current.complete(DataResult.Failure.StorageFailure("disk-write"))
        viewModel.state.first { it.layoutWrites["editor"] is CatalogLayoutWriteStatus.Failed }
        assertEquals("已提交的旧名称", settings.saved.value.catalogLayout.groups.first().name)
    }

    @Test fun cancelledSaveDoesNotPublishSuccessOrChangeStoredData() = layoutTest {
        val settings = ControlledLayoutSettings(original)
        val viewModel = loadedViewModel(settings)
        viewModel.dispatch(CatalogAction.SaveGroup("first", "取消的修改", emptyList(), "editor"))
        settings.attempts.receive()
        viewModel.viewModelScope.cancel()
        val state = viewModel.state.first { it.layoutWrites["editor"] is CatalogLayoutWriteStatus.Failed }
        assertEquals("CATALOG_LAYOUT_CANCELLED", (state.layoutWrites.getValue("editor") as CatalogLayoutWriteStatus.Failed).code)
        assertEquals(original, settings.saved.value.catalogLayout)
        assertNull(state.feedback)
    }

    @Test fun untrackedSortingKeepsExistingSaveAndFailureBehavior() = layoutTest {
        val settings = ControlledLayoutSettings(original)
        val viewModel = loadedViewModel(settings)
        viewModel.dispatch(CatalogAction.SetSort(CatalogSort.NAME))
        settings.attempts.receive().complete()
        viewModel.state.first { it.layout.sort == CatalogSort.NAME }
        assertTrue(viewModel.state.value.layoutWrites.isEmpty())
        viewModel.dispatch(CatalogAction.SetSort(CatalogSort.INSTALLED))
        settings.attempts.receive().complete(DataResult.Failure.StorageFailure("disk-write"))
        val failed = viewModel.state.first { it.feedback is CatalogFeedback.Failure }
        assertEquals("CATALOG_LAYOUT_WRITE", (failed.feedback as CatalogFeedback.Failure).code)
        assertEquals(CatalogSort.NAME, failed.layout.sort)
        assertTrue(failed.layoutWrites.isEmpty())
    }

    @Test fun thrownWriteFailureLeavesTheQueueUsableForAnotherOperation() = layoutTest {
        val settings = ControlledLayoutSettings(original)
        val viewModel = loadedViewModel(settings)
        viewModel.dispatch(CatalogAction.DeleteGroup("first", "delete"))
        settings.attempts.receive().result.completeExceptionally(java.io.IOException("write failed"))
        viewModel.state.first { it.layoutWrites["delete"] is CatalogLayoutWriteStatus.Failed }
        assertEquals(original, settings.saved.value.catalogLayout)
        viewModel.dispatch(CatalogAction.SetGroupExpanded("first", true, "expand"))
        settings.attempts.receive().complete()
        viewModel.awaitStatus("expand", CatalogLayoutWriteStatus.Succeeded)
        assertTrue(settings.saved.value.catalogLayout.groups.first().expanded)
    }

    @Test fun invalidGroupNameAndDeletedGroupNeverReportSuccessfulSave() = layoutTest {
        val settings = ControlledLayoutSettings(original)
        val viewModel = loadedViewModel(settings)
        viewModel.dispatch(CatalogAction.SaveGroup("first", "  ", emptyList(), "blank"))
        assertEquals("CATALOG_GROUP_NAME", (viewModel.state.value.layoutWrites["blank"] as CatalogLayoutWriteStatus.Failed).code)
        viewModel.dispatch(CatalogAction.SaveGroup("deleted", "旧编辑器", emptyList(), "missing"))
        viewModel.state.first { it.layoutWrites["missing"] is CatalogLayoutWriteStatus.Failed }
        assertTrue(settings.attempts.tryReceive().isFailure)
        assertEquals(original, settings.saved.value.catalogLayout)
    }

    private fun layoutTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            // The real repository and catalog projection use IO/Default. Running only the
            // current test queue cannot finish their cancellation; join every child before
            // runTest exits and @After removes the Main dispatcher, even when an assertion fails.
            withContext(NonCancellable) {
                val jobs = viewModels.map { it.viewModelScope.coroutineContext.job }
                jobs.forEach { it.cancel() }
                jobs.joinAll()
            }
        }
    }

    private suspend fun loadedViewModel(settings: ControlledLayoutSettings): CatalogViewModel {
        val catalog = object : CatalogRepository {
            override fun observeCatalogProjection(): Flow<List<CatalogEntry>> = flowOf(emptyList())
            override fun observeTools(): Flow<List<InstalledTool>> = error("unused")
            override fun observeTool(toolId: String): Flow<InstalledTool?> = error("unused")
        }
        val organization = object : CatalogOrganizationRepository {
            override suspend fun recordOpened(toolId: String, timestamp: Long): DataResult<Unit> = error("unused")
        }
        return CatalogViewModel(catalog, organization, unusedPackages, settings = settings,
            layoutRepository = CatalogLayoutRepository(settings, catalog, DataMutationLock()))
            .also { viewModels += it; it.state.first { state -> state.isLoaded } }
    }

    private suspend fun CatalogViewModel.awaitStatus(id: String, status: CatalogLayoutWriteStatus) {
        state.first { it.layoutWrites[id] == status }
    }
}

/** Real layout repository and mutation lock, with the persistence boundary controlled by each test. */
private class ControlledLayoutSettings(layout: CatalogLayout) : HostSettingsRepository {
    val saved = MutableStateFlow(HostSettings(catalogLayout = layout))
    override val settings: Flow<HostSettings> = saved
    val attempts = Channel<Attempt>(Channel.UNLIMITED)

    class Attempt(val next: HostSettings) {
        val result = CompletableDeferred<DataResult<Unit>>()
        fun complete(value: DataResult<Unit> = DataResult.Success(Unit)) { result.complete(value) }
    }

    override suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit> {
        val attempt = Attempt(transform(saved.value))
        attempts.send(attempt)
        val result = attempt.result.await()
        if (result is DataResult.Success) saved.value = attempt.next
        return result
    }
}

private val unusedPackages = object : HostPackageOperations {
    override suspend fun importPackage(input: PackageInput, control: PackageImportControl): Nothing = error("unused")
    override suspend fun confirmImport(confirmationId: String, control: PackageImportControl): Nothing = error("unused")
    override suspend fun cancelImport(confirmationId: String): Nothing = error("unused")
    override suspend fun installedManifest(toolId: String): Nothing = error("unused")
    override suspend fun deleteTool(toolId: String): Nothing = error("unused")
    override suspend fun installBundledExamples(): Nothing = error("unused")
}
