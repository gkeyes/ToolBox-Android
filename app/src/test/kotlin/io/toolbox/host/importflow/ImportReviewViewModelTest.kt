package io.toolbox.host.importflow

import io.toolbox.core.data.GrantScope
import io.toolbox.core.data.GrantState
import io.toolbox.core.data.PermissionGrant
import io.toolbox.tool.packagekit.ArchiveSummary
import io.toolbox.tool.packagekit.DiscardResult
import io.toolbox.tool.packagekit.ImportInspection
import io.toolbox.tool.packagekit.InspectionBlocker
import io.toolbox.tool.packagekit.InspectionResult
import io.toolbox.tool.packagekit.ManifestLimits
import io.toolbox.tool.packagekit.ManifestNetwork
import io.toolbox.tool.packagekit.ManifestPermission
import io.toolbox.tool.packagekit.ManifestStatusBarStyle
import io.toolbox.tool.packagekit.ManifestUi
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.PackageRejection
import io.toolbox.tool.packagekit.PackageRejectionCode
import io.toolbox.tool.packagekit.ResumeInspectionResult
import io.toolbox.tool.packagekit.ResumableInspectionRecovery
import io.toolbox.tool.packagekit.SecurityProfile
import io.toolbox.tool.packagekit.SignatureEvidence
import io.toolbox.tool.packagekit.SignatureState
import io.toolbox.tool.packagekit.ToolManifest
import io.toolbox.tool.packagekit.ToolPackageInspector
import io.toolbox.tool.packagekit.lifecycle.InstallLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.LifecycleFailure
import io.toolbox.tool.packagekit.lifecycle.LifecycleFailureCode
import io.toolbox.tool.packagekit.lifecycle.RecoveryLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.RollbackLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.ToolPackageLifecycle
import io.toolbox.tool.packagekit.lifecycle.ToolPackageStartupRecovery
import io.toolbox.tool.packagekit.lifecycle.ToolPackageStartupRecoveryResult
import io.toolbox.tool.packagekit.lifecycle.UninstallLifecycleResult
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportReviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun reviewStateMachineKeepsInspectionRecoveryGrantInstallAndCancelBoundaries() = runTest(dispatcher) {
        val validInspection = inspection(sessionId = "valid-session")
        val validInspector = FakeInspector(inspectResult = InspectionResult.Inspected(validInspection))
        val validLifecycle = FakeLifecycle(installResult = InstallLifecycleResult.Committed("io.example.tool", 7))
        val viewModel = ImportReviewViewModel(validInspector, validLifecycle, now = { 1234L })

        viewModel.requestPicker()
        assertNotNull(viewModel.state.value.pickerRequestToken)
        assertEquals(ImportReviewPhase.PICKING, viewModel.state.value.phase)
        viewModel.recoverColdStart()
        viewModel.cancelAndExit()
        assertTrue(validLifecycle.events.isEmpty())
        assertNull(viewModel.state.value.exitRequestToken)
        viewModel.pickerRejected("文件名不安全")
        assertEquals(ImportReviewPhase.IDLE, viewModel.state.value.phase)
        assertEquals("文件名不安全", viewModel.state.value.error?.userMessage)
        viewModel.dismissError()
        viewModel.requestPicker()
        viewModel.pickerCancelled()
        assertEquals(ImportReviewPhase.IDLE, viewModel.state.value.phase)
        viewModel.requestPicker()
        viewModel.inspect(TestPackageInput)
        assertEquals(ImportReviewPhase.INSPECTING, viewModel.state.value.phase)
        viewModel.cancelAndExit()
        viewModel.requestPicker()
        assertEquals(ImportReviewPhase.INSPECTING, viewModel.state.value.phase)
        assertNull(viewModel.state.value.exitRequestToken)
        advanceUntilIdle()

        assertEquals(ImportReviewPhase.REVIEW, viewModel.state.value.phase)
        assertEquals(
            mapOf("storage" to ImportGrantChoice.DENY, "network" to ImportGrantChoice.DENY),
            viewModel.state.value.grants,
        )
        viewModel.setPermissionGrant("invented.permission", ImportGrantChoice.ALLOW_SESSION)
        assertTrue(viewModel.state.value.error is ImportReviewError.InvalidGrant)
        assertFalse("invented.permission" in viewModel.state.value.grants)
        viewModel.dismissError()
        viewModel.confirmReview()
        assertFalse(viewModel.state.value.canInstall)
        viewModel.setPermissionGrant("storage", ImportGrantChoice.ALLOW_SESSION)
        viewModel.confirmReview()
        assertTrue(viewModel.state.value.canInstall)
        viewModel.install()
        advanceUntilIdle()

        assertEquals(ImportReviewPhase.INSTALLED, viewModel.state.value.phase)
        assertTrue(viewModel.state.value.installFeedback is ImportInstallFeedback.Committed)
        assertEquals("valid-session", validLifecycle.installedSessionId)
        assertEquals(setOf("storage", "network"), validLifecycle.installedGrants.map(PermissionGrant::permission).toSet())
        assertTrue(validLifecycle.installedGrants.all { it.scope == GrantScope.SESSION })
        assertEquals(GrantState.GRANTED, validLifecycle.installedGrants.single { it.permission == "storage" }.state)
        assertEquals(GrantState.DENIED, validLifecycle.installedGrants.single { it.permission == "network" }.state)
        viewModel.cancelAndExit()
        val installedExitToken = requireNotNull(viewModel.state.value.exitRequestToken)
        assertTrue(viewModel.state.value.installFeedback is ImportInstallFeedback.Committed)
        viewModel.markExitHandled(installedExitToken)
        assertEquals(ImportReviewPhase.IDLE, viewModel.state.value.phase)
        assertNull(viewModel.state.value.installFeedback)
        viewModel.requestPicker()
        assertEquals(ImportReviewPhase.PICKING, viewModel.state.value.phase)

        val blockedInspection = inspection(
            sessionId = "blocked-session",
            blockers = listOf(InspectionBlocker(PackageRejectionCode.SIGNATURE_INVALID, "签名无效")),
        )
        val cleanupFailure = PackageRejection(PackageRejectionCode.CLEANUP_FAILED, "会话目录暂时被占用")
        val blockedInspector = FakeInspector(
            inspectResult = InspectionResult.Inspected(blockedInspection),
            discardResults = ArrayDeque(listOf(DiscardResult.Failed(cleanupFailure), DiscardResult.Discarded)),
        )
        val blockedViewModel = ImportReviewViewModel(blockedInspector, FakeLifecycle())
        blockedViewModel.requestPicker()
        blockedViewModel.inspect(TestPackageInput)
        advanceUntilIdle()
        blockedViewModel.setPermissionGrant("storage", ImportGrantChoice.ALLOW_SESSION)
        blockedViewModel.confirmReview()
        assertFalse(blockedViewModel.state.value.canInstall)
        blockedViewModel.cancelAndExit()
        advanceUntilIdle()
        assertEquals(ImportReviewPhase.REVIEW, blockedViewModel.state.value.phase)
        assertTrue(blockedViewModel.state.value.cancelRetryAvailable)
        assertNull(blockedViewModel.state.value.exitRequestToken)
        blockedViewModel.cancelAndExit()
        advanceUntilIdle()
        assertEquals(ImportReviewPhase.IDLE, blockedViewModel.state.value.phase)
        assertNotNull(blockedViewModel.state.value.exitRequestToken)

        val blockedRecoveryInspector = FakeInspector()
        val blockedRecoveryLifecycle = FakeLifecycle()
        val blockedStartupEvents = mutableListOf<String>()
        val blockedStartupRecovery = FakeStartupRecovery(
            result = ToolPackageStartupRecoveryResult.Pending(
                LifecycleFailure(LifecycleFailureCode.RECOVERY_REQUIRED, "journal pending"),
            ),
            events = blockedStartupEvents,
        )
        val blockedRecoveryViewModel = ImportReviewViewModel(
            blockedRecoveryInspector,
            blockedRecoveryLifecycle,
            startupRecovery = blockedStartupRecovery,
        )
        blockedRecoveryViewModel.recoverColdStart()
        advanceUntilIdle()
        assertEquals(listOf("startup.recover"), blockedStartupEvents)
        assertTrue(blockedRecoveryLifecycle.events.isEmpty())
        assertTrue(blockedRecoveryInspector.events.isEmpty())
        assertEquals(ImportReviewPhase.IDLE, blockedRecoveryViewModel.state.value.phase)

        val recreatedEvents = mutableListOf<String>()
        val recreatedInspector = FakeInspector(
            resumedInspection = validInspection,
            sharedEvents = recreatedEvents,
        )
        val recreatedLifecycle = FakeLifecycle(sharedEvents = recreatedEvents)
        val recreatedViewModel = ImportReviewViewModel(recreatedInspector, recreatedLifecycle)
        recreatedViewModel.resume("valid-session")
        advanceUntilIdle()
        assertEquals(listOf("lifecycle.recover", "inspector.resume:valid-session"), recreatedEvents)
        assertEquals("valid-session", recreatedViewModel.state.value.review?.sessionId)

        val coldEvents = mutableListOf<String>()
        val coldInspector = FakeInspector(sharedEvents = coldEvents)
        val coldStartupRecovery = FakeStartupRecovery(
            ToolPackageStartupRecoveryResult.Recovered(
                ResumableInspectionRecovery(listOf(validInspection), 0, 0, emptyList(), truncated = false),
            ),
            coldEvents,
        )
        val coldViewModel = ImportReviewViewModel(
            coldInspector,
            FakeLifecycle(sharedEvents = coldEvents),
            startupRecovery = coldStartupRecovery,
        )
        coldViewModel.recoverColdStart()
        advanceUntilIdle()
        assertEquals(listOf("startup.recover"), coldEvents)
        assertEquals("valid-session", coldViewModel.state.value.review?.sessionId)

        val throwingViewModel = ImportReviewViewModel(
            FakeInspector(inspectFailure = IllegalStateException("provider failed")),
            FakeLifecycle(),
        )
        throwingViewModel.requestPicker()
        throwingViewModel.inspect(TestPackageInput)
        advanceUntilIdle()
        assertEquals(ImportReviewPhase.IDLE, throwingViewModel.state.value.phase)
        assertTrue(throwingViewModel.state.value.error is ImportReviewError.Message)
    }
}

private object TestPackageInput : PackageInput {
    override val displayName = "selected.tbx"
    override fun openStream() = ByteArrayInputStream(byteArrayOf(1))
}

private class FakeInspector(
    private val inspectResult: InspectionResult = InspectionResult.Rejected(
        PackageRejection(PackageRejectionCode.SOURCE_READ_FAILED, "not configured"),
    ),
    private val resumedInspection: ImportInspection? = null,
    private val discardResults: ArrayDeque<DiscardResult> = ArrayDeque(listOf(DiscardResult.Discarded)),
    private val sharedEvents: MutableList<String> = mutableListOf(),
    private val inspectFailure: Exception? = null,
) : ToolPackageInspector {
    val events: List<String> get() = sharedEvents

    override suspend fun inspect(input: PackageInput): InspectionResult =
        inspectFailure?.let { throw it } ?: inspectResult

    override suspend fun resume(sessionId: String): ResumeInspectionResult {
        sharedEvents += "inspector.resume:$sessionId"
        return resumedInspection?.let(ResumeInspectionResult::Resumed) ?: ResumeInspectionResult.NotFound
    }

    override suspend fun discard(sessionId: String): DiscardResult = discardResults.removeFirst()
}

private class FakeStartupRecovery(
    private val result: ToolPackageStartupRecoveryResult,
    private val events: MutableList<String>,
) : ToolPackageStartupRecovery {
    override suspend fun recover(): ToolPackageStartupRecoveryResult {
        events += "startup.recover"
        return result
    }
}

private class FakeLifecycle(
    private val installResult: InstallLifecycleResult = InstallLifecycleResult.Failed(
        LifecycleFailure(LifecycleFailureCode.STORAGE_FAILURE, "not configured"),
    ),
    private val recoveryResult: RecoveryLifecycleResult = RecoveryLifecycleResult.Recovered,
    private val sharedEvents: MutableList<String> = mutableListOf(),
) : ToolPackageLifecycle {
    val events: List<String> get() = sharedEvents
    var installedSessionId: String? = null
    var installedGrants: List<PermissionGrant> = emptyList()

    override suspend fun install(
        inspectionSessionId: String,
        initialGrants: List<PermissionGrant>,
    ): InstallLifecycleResult {
        installedSessionId = inspectionSessionId
        installedGrants = initialGrants
        return installResult
    }

    override suspend fun rollback(toolId: String) = RollbackLifecycleResult.Failed(
        LifecycleFailure(LifecycleFailureCode.STORAGE_FAILURE, "not used"),
    )

    override suspend fun uninstall(toolId: String) = UninstallLifecycleResult.Failed(
        LifecycleFailure(LifecycleFailureCode.STORAGE_FAILURE, "not used"),
    )

    override suspend fun recover(): RecoveryLifecycleResult {
        sharedEvents += "lifecycle.recover"
        return recoveryResult
    }
}

private fun inspection(
    sessionId: String,
    blockers: List<InspectionBlocker> = emptyList(),
) = ImportInspection(
    sourceName = "real-tool.tbx",
    sessionId = sessionId,
    manifest = ToolManifest(
        schemaVersion = 1,
        id = "io.example.tool",
        name = "真实工具",
        shortName = "工具",
        description = "测试工具",
        version = "1.2.3",
        versionCode = 7,
        entry = "index.html",
        icon = null,
        apiVersion = "1.0",
        minHostVersion = "0.1.0",
        categories = listOf("实用"),
        permissions = listOf(
            ManifestPermission("storage", "保存工具配置", required = true),
            ManifestPermission("network", "访问精确白名单", required = false),
        ),
        securityProfile = SecurityProfile.STRICT,
        network = ManifestNetwork(listOf("api.example.com"), false, 1024, 1000),
        ui = ManifestUi(null, false, ManifestStatusBarStyle.AUTO, true),
        limits = ManifestLimits(65_536, 4_096),
        publisher = null,
    ),
    archive = ArchiveSummary(512, 1024, 2, listOf("manifest.json", "index.html")),
    signature = SignatureEvidence(SignatureState.UNSIGNED, detail = "未提供签名"),
    riskFindings = emptyList(),
    blockers = blockers,
)
