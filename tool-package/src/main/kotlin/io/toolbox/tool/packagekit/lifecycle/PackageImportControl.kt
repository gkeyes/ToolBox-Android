package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.tool.packagekit.InspectionRejected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class PackageImportPhase { IMPORTING, CANCELLING, COMMITTING, FINISHED }

/** A single import attempt. Cancelling preparation never cancels the caller delivering its result. */
class PackageImportControl {
    private val monitor = Any()
    private val mutablePhase = MutableStateFlow(PackageImportPhase.IMPORTING)
    val phase: StateFlow<PackageImportPhase> = mutablePhase.asStateFlow()
    private var operation: Job? = null
    private var used = false
    private var cancelRequested = false
    private var cancellationFailure: PackageOperationFailure? = null

    fun requestCancel(): Boolean = synchronized(monitor) {
        if (mutablePhase.value != PackageImportPhase.IMPORTING) return@synchronized false
        cancelRequested = true
        mutablePhase.value = PackageImportPhase.CANCELLING
        operation?.cancel()
        true
    }

    internal fun beginCommit() = synchronized(monitor) {
        checkNotNull(operation).ensureActive()
        if (cancelRequested) throw CancellationException("Package import cancelled")
        mutablePhase.value = PackageImportPhase.COMMITTING
    }

    internal fun finish() = synchronized(monitor) {
        checkNotNull(operation).ensureActive()
        mutablePhase.value = PackageImportPhase.FINISHED
    }

    internal fun recordCancellationFailure(failure: PackageOperationFailure) = synchronized(monitor) {
        cancellationFailure = failure
    }

    internal suspend fun run(block: suspend () -> PackageInstallResult): PackageInstallResult {
        val caller = currentCoroutineContext()
        val job = synchronized(monitor) {
            check(!used) { "An import control can only be used once" }
            used = true
            Job(caller[Job]).also {
                operation = it
                if (cancelRequested) it.cancel()
            }
        }
        return try {
            withContext(job) {
                block().also { finish() }
            }
        } catch (cancelled: CancellationException) {
            caller.ensureActive()
            synchronized(monitor) {
                if (!cancelRequested) throw cancelled
                cancellationFailure?.let(PackageInstallResult::Failed) ?: PackageInstallResult.Cancelled
            }
        } catch (rejected: InspectionRejected) {
            caller.ensureActive()
            PackageInstallResult.Rejected(rejected.rejection)
        } finally {
            synchronized(monitor) {
                operation = null
                mutablePhase.value = PackageImportPhase.FINISHED
            }
            job.complete()
        }
    }
}
