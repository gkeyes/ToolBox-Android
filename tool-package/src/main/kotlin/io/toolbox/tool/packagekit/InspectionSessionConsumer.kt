package io.toolbox.tool.packagekit

import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface InspectionSessionConsumer {
    suspend fun claimInspectionSession(sessionId: String): InspectionSessionClaimResult
}

internal suspend fun ToolPackageInspector.claimInspectionSession(
    sessionId: String,
): InspectionSessionClaimResult = (this as? InspectionSessionConsumer)?.claimInspectionSession(sessionId)
    ?: InspectionSessionClaimResult.Failed(
        PackageRejection(
            PackageRejectionCode.SESSION_IO_FAILED,
            "This inspector does not support internal session handoff",
        ),
    )

internal sealed interface InspectionSessionClaimResult {
    data class Claimed(val lease: ClaimedInspectionSession) : InspectionSessionClaimResult
    data object NotFound : InspectionSessionClaimResult
    data object AlreadyClaimed : InspectionSessionClaimResult
    data object Consumed : InspectionSessionClaimResult
    data class Failed(val rejection: PackageRejection) : InspectionSessionClaimResult
}

internal sealed interface ClaimYieldResult {
    data object Yielded : ClaimYieldResult
    data object AlreadyTerminal : ClaimYieldResult
    data class Failed(val rejection: PackageRejection) : ClaimYieldResult
}

internal class ClaimedInspectionSession(
    val sessionId: String,
    val bundleDirectory: Path,
    private val ioDispatcher: CoroutineDispatcher,
    private val discardAction: () -> DiscardResult,
    private val yieldAction: () -> PackageRejection?,
) {
    private val terminalLock = Mutex()
    private var terminalResult: DiscardResult? = null

    suspend fun cleanup(): DiscardResult = terminalLock.withLock {
        if (yielded) return@withLock DiscardResult.NotFound
        terminalResult ?: withContext(ioDispatcher) {
            runInterruptible { discardAction() }
        }.also { terminalResult = it }
    }

    suspend fun discard(): DiscardResult = cleanup()

    suspend fun yieldOwnership(): ClaimYieldResult = terminalLock.withLock {
        if (yielded || terminalResult != null) return@withLock ClaimYieldResult.AlreadyTerminal
        val rejection = withContext(ioDispatcher) {
            runInterruptible { yieldAction() }
        }
        if (rejection != null) {
            ClaimYieldResult.Failed(rejection)
        } else {
            yielded = true
            ClaimYieldResult.Yielded
        }
    }

    private var yielded = false
}
