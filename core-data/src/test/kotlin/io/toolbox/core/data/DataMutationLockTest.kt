package io.toolbox.core.data

import java.lang.reflect.Proxy
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DataMutationLockTest {
    @Test
    fun nestedRunOnTheSameLockIsReentrant() = runBlocking {
        val lock = DataMutationLock()
        val value = withTimeout(5_000) {
            lock.run { lock.run { lock.tryRun(onBusy = { "busy" }) { "inner" } } }
        }
        assertEquals("inner", value)
    }

    @Test
    fun tryRunReportsBusyInsteadOfQueueingBehindAnotherWriter() = runBlocking {
        val lock = DataMutationLock()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) { lock.run { entered.complete(Unit); release.await() } }
        entered.await()
        var ran = false
        assertEquals("busy", lock.tryRun(onBusy = { "busy" }) { ran = true; "ran" })
        assertFalse(ran)
        release.complete(Unit)
        holder.join()
        assertEquals("ran", lock.tryRun(onBusy = { "busy" }) { "ran" })
    }

    @Test
    fun runSerializesConcurrentWriters() = runBlocking {
        val lock = DataMutationLock()
        var inside = 0
        var maxInside = 0
        val jobs = List(8) {
            launch(Dispatchers.Default) {
                lock.run {
                    synchronized(this@DataMutationLockTest) { inside++; maxInside = maxOf(maxInside, inside) }
                    Thread.sleep(5)
                    synchronized(this@DataMutationLockTest) { inside-- }
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(1, maxInside)
    }

    @Test
    fun separateLocksDoNotShareOwnership() = runBlocking {
        val first = DataMutationLock()
        val second = DataMutationLock()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) { second.run { entered.complete(Unit); release.await() } }
        entered.await()
        // Holding `first` must not let this coroutine skip `second`, which another writer owns.
        assertEquals("busy", first.run { second.tryRun(onBusy = { "busy" }) { "ran" } })
        release.complete(Unit)
        holder.join()
    }

    @Test
    fun blockedLockRejectsWritesWithRecoveryCode() = runBlocking {
        val lock = DataMutationLock()
        lock.blockUntilRestart()
        var ran = false
        assertThrows(DataRecoveryRequiredException::class.java) { runBlocking { lock.run { ran = true } } }
        assertThrows(DataRecoveryRequiredException::class.java) {
            runBlocking { lock.tryRun(onBusy = { Unit }) { ran = true } }
        }
        assertEquals(
            DataResult.Failure.StorageFailure("RESTORE_RECOVERY_REQUIRED"),
            lock.write { ran = true; DataResult.Success(Unit) },
        )
        assertFalse(ran)
    }

    @Test
    fun blockedLockStillLetsTheRestoreThatHoldsItFinish() = runBlocking {
        val lock = DataMutationLock()
        val result = lock.run {
            lock.blockUntilRestart()
            // A restore that blocks the lock may still finish its own nested writes.
            lock.write { DataResult.Success("restored") }
        }
        assertEquals(DataResult.Success("restored"), result)
    }

    @Test
    fun decoratedRepositoriesRouteEveryWriteThroughTheLock() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val raw = CoreDataRepositories(
            catalog = recording(calls),
            lifecycle = recording(calls),
            organization = recording(calls),
            grants = recording(calls),
            keyValues = recording(calls),
            installs = recording(calls),
            backgroundTasks = recording(calls),
            settings = recording(calls),
        )
        val repositories = raw.withMutationLock()
        assertSame(raw.mutations, repositories.mutations)
        assertSame(raw.catalog, repositories.catalog)

        val writes = writes(repositories)
        // Unlocked: every decorated write reaches the underlying repository.
        writes.forEach { (name, write) -> assertEquals(name, DataResult.Success(Unit), write()) }
        assertEquals(writes.map { it.first }.sorted(), calls.sorted())

        // While another writer holds the lock, a decorated write waits for it.
        calls.clear()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) { repositories.mutations.run { entered.complete(Unit); release.await() } }
        entered.await()
        val pending = async(Dispatchers.Default) { repositories.keyValues.put("tool", "k", "1", 1) }
        Thread.sleep(50)
        assertTrue(calls.isEmpty())
        release.complete(Unit)
        holder.join()
        assertEquals(DataResult.Success(Unit), pending.await())
        assertEquals(listOf("put"), calls.toList())

        // After a failed restore, no write reaches storage but reads still do.
        calls.clear()
        repositories.mutations.blockUntilRestart()
        writes.forEach { (name, write) ->
            assertEquals(name, DataResult.Failure.StorageFailure("RESTORE_RECOVERY_REQUIRED"), write())
        }
        assertTrue(calls.isEmpty())
        repositories.backgroundTasks.getTask("task")
        repositories.installs.get("tx")
        assertEquals(listOf("getTask", "get"), calls.toList())
    }

    private fun writes(repositories: CoreDataRepositories): List<Pair<String, suspend () -> DataResult<*>>> {
        val task = BackgroundTask("task", "tool", 1, "key", BackgroundOperation.NOTIFY, "{}", false, null, TaskState.QUEUED, 1, 1, null, 0)
        val result = TaskRunResult("task", RunOutcome.SUCCEEDED, 2, null, null, 1)
        val metadata = ToolMetadata("tool", "Tool", SecurityProfile.STRICT, 1)
        val version = ToolVersion("tool", 1, "1.0.0", BundleLocator("tools/tool/1"), 1, "hash", 1)
        val grant = PermissionGrant("tool", "network", true, 1)
        return listOf(
            op("commitInstall") { repositories.lifecycle.commitInstall(CatalogInstallAttempt("tx", metadata, version, listOf(grant))) },
            op("deleteToolCatalog") { repositories.lifecycle.deleteToolCatalog("tool") },
            op("recordOpened") { repositories.organization.recordOpened("tool", 1) },
            op("put") { repositories.grants.put(grant) },
            op("putForVersion") { repositories.grants.putForVersion(grant, 1) },
            op("put") { repositories.keyValues.put("tool", "k", "1", 1) },
            op("replace") { repositories.keyValues.replace("tool", setOf("a"), mapOf("b" to "1"), 1) },
            op("remove") { repositories.keyValues.remove("tool", "k") },
            op("begin") { repositories.installs.begin(InstallTransaction("tx", "tool", 1, InstallTransactionState.PREPARING, 1, 1)) },
            op("markCommitting") { repositories.installs.markCommitting("tx", 1) },
            op("fail") { repositories.installs.fail("tx", 1, "E") },
            op("create") { repositories.backgroundTasks.create(task) },
            op("claimExecution") { repositories.backgroundTasks.claimExecution("task", 1, null, "token", 1, 1) },
            op("deferRetry") { repositories.backgroundTasks.deferRetry("task", 1, 2, 1, null) },
            op("requeueInterruptedRun") { repositories.backgroundTasks.requeueInterruptedRun("task", 1) },
            op("finishRun") { repositories.backgroundTasks.finishRun("task", result, TaskState.COMPLETED, null, null) },
            op("finishCancelled") { repositories.backgroundTasks.finishCancelled("task", result, null) },
            op("cancel") { repositories.backgroundTasks.cancel("task", 1) },
            op("deleteForTool") { repositories.backgroundTasks.deleteForTool("tool") },
            op("update") { repositories.settings.update { it } },
        )
    }

    private fun op(name: String, write: suspend () -> DataResult<*>): Pair<String, suspend () -> DataResult<*>> = name to write

    /** Records each call by method name; suspend calls complete immediately with Success(Unit) or Success(null). */
    private inline fun <reified T : Any> recording(calls: MutableList<String>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "recording ${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> {
                    calls += method.name
                    if (method.name.startsWith("get")) DataResult.Success(null) else DataResult.Success(Unit)
                }
            }
        } as T
}
