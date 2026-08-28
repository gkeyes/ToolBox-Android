package io.toolbox.host.settings

import io.toolbox.core.data.AuditEvent
import io.toolbox.core.data.AuditRepository
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.memory.InMemoryHostSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun auditRetentionPrunesAtTheSelectedCutoffAndReportsPruneFailureAfterPersistence() = runTest(mainDispatcher) {
        val now = 365L * MILLIS_PER_DAY
        val repository = InMemoryHostSettingsRepository()
        val audit = RecordingAuditRepository(DataResult.Success(2))
        val viewModel = SettingsViewModel(
            repository = repository,
            audit = audit,
            nowMillis = { now },
        )

        advanceUntilIdle()
        viewModel.selectAuditRetention(days = 7)
        advanceUntilIdle()

        assertEquals(7, repository.settings.first().auditRetentionDays)
        assertEquals(listOf(now - 7L * MILLIS_PER_DAY), audit.deletedBefore)
        assertEquals(null, viewModel.state.value.updateError)

        val supersedingRepository = FirstUpdateSuspendingRepository()
        val supersedingAudit = RecordingAuditRepository(DataResult.Success(0))
        val supersedingViewModel = SettingsViewModel(
            repository = supersedingRepository,
            audit = supersedingAudit,
            nowMillis = { now },
        )

        advanceUntilIdle()
        supersedingViewModel.selectAuditRetention(days = 7)
        runCurrent()
        supersedingRepository.firstUpdateStarted.await()
        supersedingViewModel.selectAuditRetention(days = 90)
        supersedingRepository.releaseFirstUpdate()
        advanceUntilIdle()

        assertEquals(90, supersedingRepository.settings.first().auditRetentionDays)
        assertEquals(listOf(now - 90L * MILLIS_PER_DAY), supersedingAudit.deletedBefore)

        val failedRepository = InMemoryHostSettingsRepository()
        val failedAudit = RecordingAuditRepository(DataResult.Failure.StorageFailure("deleteAudit"))
        val failedViewModel = SettingsViewModel(
            repository = failedRepository,
            audit = failedAudit,
            nowMillis = { now },
        )

        advanceUntilIdle()
        failedViewModel.selectAuditRetention(days = 90)
        advanceUntilIdle()

        assertEquals(90, failedRepository.settings.first().auditRetentionDays)
        assertEquals(listOf(now - 90L * MILLIS_PER_DAY), failedAudit.deletedBefore)
        assertEquals(
            "审计日志留存已保存，但清理旧记录失败：存储暂时不可用",
            failedViewModel.state.value.updateError,
        )
    }

    private class RecordingAuditRepository(
        private val deleteResult: DataResult<Int>,
    ) : AuditRepository {
        private val events = MutableStateFlow<List<AuditEvent>>(emptyList())
        val deletedBefore = mutableListOf<Long>()

        override fun observeRecent(limit: Int): Flow<List<AuditEvent>> = events

        override suspend fun append(event: AuditEvent): DataResult<Long> = DataResult.Success(1)

        override suspend fun deleteBefore(timestamp: Long): DataResult<Int> {
            deletedBefore += timestamp
            return deleteResult
        }
    }

    private class FirstUpdateSuspendingRepository : HostSettingsRepository {
        private val delegate = InMemoryHostSettingsRepository()
        private var isFirstUpdate = true
        val firstUpdateStarted = CompletableDeferred<Unit>()
        private val firstUpdateMayComplete = CompletableDeferred<Unit>()

        override val settings: Flow<HostSettings> = delegate.settings

        override suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit> {
            if (isFirstUpdate) {
                isFirstUpdate = false
                firstUpdateStarted.complete(Unit)
                firstUpdateMayComplete.await()
            }
            return delegate.update(transform)
        }

        fun releaseFirstUpdate() {
            firstUpdateMayComplete.complete(Unit)
        }
    }
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
