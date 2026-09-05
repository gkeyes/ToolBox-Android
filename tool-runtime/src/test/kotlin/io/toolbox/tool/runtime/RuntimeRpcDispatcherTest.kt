package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRpcDispatcherTest {
    private val identity = RuntimeSessionIdentity(
        toolId = "io.toolbox.runtime.test",
        versionCode = 7,
        generation = "io.toolbox.runtime.test:7",
        hostVersion = "0.3.0",
        nonce = "nonce-with-enough-entropy-for-test",
        exactOrigin = "https://runtime-test.toolbox.invalid/",
        declaredCapabilities = setOf("clipboard.write"),
    )

    @Test
    fun authorizationLayersFailClosedBeforeNativeEffect() = runTest {
        val policy = MutablePolicy()
        val effectCalled = AtomicBoolean(false)
        val dispatcher = dispatcher(policy, effectCalled)
        val validRequest = request()
        val validInbound = RuntimeInboundContext(identity.exactOrigin, true, 10)

        assertFailure(RuntimeRpcErrorCode.WRONG_ORIGIN, dispatcher.dispatch(validRequest, validInbound.copy(sourceOrigin = "https://evil.invalid")))
        assertFailure(RuntimeRpcErrorCode.NOT_MAIN_FRAME, dispatcher.dispatch(validRequest, validInbound.copy(isMainFrame = false)))
        assertFailure(RuntimeRpcErrorCode.INVALID_SESSION, dispatcher.dispatch(validRequest.copy(generation = "stale"), validInbound))

        policy.current = false
        assertFailure(RuntimeRpcErrorCode.INVALID_SESSION, dispatcher.dispatch(validRequest, validInbound))
        policy.current = true

        val undeclared = RuntimeRpcDispatcher(identity.copy(declaredCapabilities = emptySet()), policy, handlers(effectCalled))
        assertFailure(RuntimeRpcErrorCode.NOT_DECLARED, undeclared.dispatch(validRequest, validInbound))

        policy.granted = false
        assertFailure(RuntimeRpcErrorCode.PERMISSION_DENIED, dispatcher.dispatch(validRequest, validInbound))
        policy.granted = true

        policy.systemAvailable = false
        assertFailure(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, dispatcher.dispatch(validRequest, validInbound))
        policy.systemAvailable = true

        assertFailure(RuntimeRpcErrorCode.USER_GESTURE_REQUIRED, dispatcher.dispatch(validRequest, validInbound.copy(recentTouchAgeMillis = null)))

        policy.decision = RuntimePolicyDecision.Denied(RuntimeRpcErrorCode.RATE_LIMITED, "slow down")
        assertFailure(RuntimeRpcErrorCode.RATE_LIMITED, dispatcher.dispatch(validRequest, validInbound))
        assertTrue(!effectCalled.get())

        policy.decision = RuntimePolicyDecision.Allowed
        assertTrue(dispatcher.dispatch(validRequest, validInbound) is RuntimeRpcResponse.Success)
        assertTrue(effectCalled.get())
    }

    @Test
    fun readyReportsCanonicalApiAndHostGeneration() = runTest {
        val response = dispatcher(MutablePolicy(), AtomicBoolean()).dispatch(
            request(method = "ready", params = RpcValue.ObjectValue(emptyMap())),
            RuntimeInboundContext(identity.exactOrigin.removeSuffix("/"), true, null),
        ) as RuntimeRpcResponse.Success
        val result = (response.result as RpcValue.ObjectValue).value
        assertEquals("1.0", (result.getValue("apiVersion") as RpcValue.StringValue).value)
        assertEquals("0.3.0", (result.getValue("hostVersion") as RpcValue.StringValue).value)
        assertEquals(identity.generation, (result.getValue("generation") as RpcValue.StringValue).value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun admissionBoundsQueuedBytesAndReleasesEvenBeforeCoroutineStarts() = runTest {
        val shared = RuntimeRequestBudget(2, 10)
        val first = RuntimeSessionJobs(StandardTestDispatcher(testScheduler), RuntimeRequestBudget(1, 8), shared)
        val second = RuntimeSessionJobs(StandardTestDispatcher(testScheduler), RuntimeRequestBudget(2, 10), shared)
        var executions = 0
        try {
            val queued = checkNotNull(first.launch(8) { executions += 1 })
            assertNull(first.launch(0) { error("Count quota bypassed") })
            assertNull(second.launch(3) { error("Global byte quota bypassed") })
            queued.cancel()
            runCurrent()
            assertEquals(0, executions)
            val a = checkNotNull(first.launch(5) { awaitCancellation() })
            val b = checkNotNull(second.launch(5) { awaitCancellation() })
            assertNull(second.launch(0) { error("Global count quota bypassed") })
            runCurrent()
            a.cancel(); b.cancel()
            runCurrent()
            assertNotNull(second.launch(10) { executions += 1 })
            runCurrent()
            assertEquals(1, executions)
            first.close()
            assertNull(first.launch { error("Closed session accepted a request") })
        } finally {
            first.close(); second.close()
        }
    }

    @Test
    fun overloadCorrelationOnlyReadsBoundedSafeLeadingId() {
        assertEquals("safe-12", runtimeRejectedRequestId("""{"id":"safe-12","params":"${"x".repeat(1_000)}"}"""))
        assertEquals("", runtimeRejectedRequestId("""{"id":"unsafe\\\"id"}"""))
        assertEquals("", runtimeRejectedRequestId(" ".repeat(512) + """{"id":"late"}"""))
    }

    @Test
    fun closingSessionJobsCancelsInflightOperation() = runBlocking {
        val jobs = RuntimeSessionJobs()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        jobs.launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        withTimeout(5_000) { started.await() }
        jobs.close()
        withTimeout(5_000) { cancelled.await() }
    }

    @Test
    fun m2MethodsUseTypedNativeHandlersAndContractValues() = runTest {
        val recorder = M2Recorder()
        val dispatcher = RuntimeRpcDispatcher(
            identity.copy(
                declaredCapabilities = setOf(
                    "network",
                    "notifications",
                    "background.tasks",
                    "background.runtime",
                    "alarms",
                ),
            ),
            MutablePolicy(),
            RuntimeM1Handlers(),
            recorder.handlers(),
        )
        val inbound = RuntimeInboundContext(identity.exactOrigin, true, 1)

        val networkResponse = dispatcher.dispatch(
            request(
                method = "network.request",
                params = RpcValue.ObjectValue(
                    mapOf(
                        "url" to RpcValue.StringValue("https://api.example.invalid/value"),
                        "method" to RpcValue.StringValue("POST"),
                        "headers" to RpcValue.ObjectValue(
                            mapOf("Authorization" to RpcValue.StringValue("Bearer test-token")),
                        ),
                        "body" to RpcValue.ObjectValue(
                            mapOf("symbol" to RpcValue.StringValue("TEST")),
                        ),
                        "timeoutMs" to RpcValue.Number(120_000.0),
                        "maxResponseBytes" to RpcValue.Number(1_048_576.0),
                    ),
                ),
            ),
            inbound,
        )
        assertTrue(networkResponse.toString(), networkResponse is RuntimeRpcResponse.Success)
        val network = networkResponse as RuntimeRpcResponse.Success
        assertEquals(RuntimeNetworkMethod.POST, recorder.networkRequest?.method)
        assertEquals("Bearer test-token", recorder.networkRequest?.headers?.get("Authorization"))
        assertEquals("{\"symbol\":\"TEST\"}", recorder.networkRequest?.body?.toString(Charsets.UTF_8))
        assertEquals(true, recorder.networkRequest?.bodyIsJson)
        assertEquals(120_000L, recorder.networkRequest?.timeoutMillis)
        assertEquals(1_048_576, recorder.networkRequest?.maxResponseBytes)
        assertEquals(201.0, ((network.result as RpcValue.ObjectValue).value.getValue("status") as RpcValue.Number).value, 0.0)

        recorder.networkRequest = null
        assertFailure(
            RuntimeRpcErrorCode.INVALID_REQUEST,
            dispatcher.dispatch(
                request(
                    method = "network.request",
                    params = RpcValue.ObjectValue(
                        mapOf(
                            "url" to RpcValue.StringValue("https://api.example.invalid/value"),
                            "headers" to RpcValue.ObjectValue(
                                mapOf("Host" to RpcValue.StringValue("other.example.invalid")),
                            ),
                        ),
                    ),
                ),
                inbound,
            ),
        )
        assertEquals(null, recorder.networkRequest)

        val notification = dispatcher.dispatch(
            request(
                method = "notifications.post",
                params = RpcValue.ObjectValue(
                    mapOf(
                        "id" to RpcValue.StringValue("sync-ready"),
                        "title" to RpcValue.StringValue("Ready"),
                        "body" to RpcValue.StringValue("Task finished"),
                    ),
                ),
            ),
            inbound,
        )
        assertTrue(notification is RuntimeRpcResponse.Success)
        assertEquals("sync-ready", recorder.postedNotificationId)

        val liveStart = dispatcher.dispatch(
            request(
                method = "notifications.live.start",
                params = RpcValue.ObjectValue(
                    mapOf(
                        "sessionId" to RpcValue.StringValue("runtime-session"),
                        "title" to RpcValue.StringValue("Example Corp · TEST"),
                        "primaryText" to RpcValue.StringValue("12.34"),
                        "secondaryText" to RpcValue.StringValue("+1.25% · 10:30"),
                        "body" to RpcValue.StringValue("Market data updated"),
                        "shortText" to RpcValue.StringValue("12.34"),
                        "updatedAt" to RpcValue.Number(1_700_000_000_000.0),
                        "progress" to RpcValue.Number(100.0),
                        "accentColor" to RpcValue.StringValue("#E53935"),
                        "tone" to RpcValue.StringValue("positive"),
                    ),
                ),
            ),
            inbound,
        ) as RuntimeRpcResponse.Success
        val liveResult = (liveStart.result as RpcValue.ObjectValue).value
        assertEquals("POSTED", (liveResult.getValue("standard") as RpcValue.StringValue).value)
        assertEquals("NOT_ALLOWED", (liveResult.getValue("androidLive") as RpcValue.StringValue).value)
        assertEquals("REQUESTED", (liveResult.getValue("hyperOsIsland") as RpcValue.StringValue).value)
        assertEquals(
            "12.34",
            recorder.liveNotification?.primaryText,
        )

        val liveUpdate = dispatcher.dispatch(
            request(
                method = "notifications.live.update",
                params = RpcValue.ObjectValue(
                    mapOf(
                        "sessionId" to RpcValue.StringValue("runtime-session"),
                        "title" to RpcValue.StringValue("Example Corp · TEST"),
                        "primaryText" to RpcValue.StringValue("12.56"),
                    ),
                ),
            ),
            inbound,
        )
        assertTrue(liveUpdate is RuntimeRpcResponse.Success)
        assertEquals("12.56", recorder.liveNotification?.primaryText)

        assertFailure(
            RuntimeRpcErrorCode.INVALID_REQUEST,
            dispatcher.dispatch(
                request(
                    method = "notifications.live.update",
                    params = RpcValue.ObjectValue(
                        mapOf(
                            "sessionId" to RpcValue.StringValue("runtime-session"),
                            "title" to RpcValue.StringValue("Example Corp · TEST"),
                            "primaryText" to RpcValue.StringValue("12.56"),
                            "accentColor" to RpcValue.StringValue("red"),
                        ),
                    ),
                ),
                inbound,
            ),
        )
        assertFailure(
            RuntimeRpcErrorCode.INVALID_SESSION,
            dispatcher.dispatch(
                request(
                    method = "notifications.live.start",
                    params = RpcValue.ObjectValue(
                        mapOf(
                            "sessionId" to RpcValue.StringValue("other-session"),
                            "title" to RpcValue.StringValue("Wrong owner"),
                            "primaryText" to RpcValue.StringValue("0"),
                        ),
                    ),
                ),
                inbound,
            ),
        )

        val liveEnd = dispatcher.dispatch(
            request(
                method = "notifications.live.end",
                params = RpcValue.ObjectValue(mapOf("sessionId" to RpcValue.StringValue("runtime-session"))),
            ),
            inbound,
        )
        assertTrue(liveEnd is RuntimeRpcResponse.Success)
        assertEquals("runtime-session", recorder.endedLiveSessionId)

        val notificationCancelled = dispatcher.dispatch(
            request(
                method = "notifications.cancel",
                params = RpcValue.ObjectValue(mapOf("id" to RpcValue.StringValue("sync-ready"))),
            ),
            inbound,
        )
        assertTrue(notificationCancelled is RuntimeRpcResponse.Success)
        assertEquals("sync-ready", recorder.cancelledNotificationId)

        assertFailure(
            RuntimeRpcErrorCode.INVALID_REQUEST,
            dispatcher.dispatch(
                request(
                    method = "background.enqueue",
                    params = RpcValue.ObjectValue(
                        mapOf(
                            "key" to RpcValue.StringValue("unsupported-schedule"),
                            "operation" to RpcValue.ObjectValue(
                                mapOf(
                                    "type" to RpcValue.StringValue("notify"),
                                    "title" to RpcValue.StringValue("Reminder"),
                                    "body" to RpcValue.StringValue("Unsupported schedule"),
                                ),
                            ),
                            "earliestAt" to RpcValue.Number(1_000.0),
                        ),
                    ),
                ),
                inbound,
            ),
        )
        assertFailure(
            RuntimeRpcErrorCode.INVALID_REQUEST,
            dispatcher.dispatch(
                request(
                    method = "background.enqueue",
                    params = RpcValue.ObjectValue(
                        mapOf(
                            "key" to RpcValue.StringValue("unsupported-constraint"),
                            "operation" to RpcValue.ObjectValue(
                                mapOf(
                                    "type" to RpcValue.StringValue("notify"),
                                    "title" to RpcValue.StringValue("Reminder"),
                                    "body" to RpcValue.StringValue("Unsupported constraint"),
                                ),
                            ),
                            "constraints" to RpcValue.ObjectValue(
                                mapOf(
                                    "network" to RpcValue.StringValue("connected"),
                                    "requiresCharging" to RpcValue.Bool(true),
                                    "batteryNotLow" to RpcValue.Bool(true),
                                ),
                            ),
                        ),
                    ),
                ),
                inbound,
            ),
        )
        assertEquals(null, recorder.enqueuedSpec)

        val enqueued = dispatcher.dispatch(
            request(
                method = "background.enqueue",
                params = RpcValue.ObjectValue(
                    mapOf(
                        "key" to RpcValue.StringValue("fetch-repository"),
                        "operation" to RpcValue.ObjectValue(
                            mapOf(
                                "type" to RpcValue.StringValue("httpGet"),
                                "url" to RpcValue.StringValue("https://api.example.invalid/repository"),
                            ),
                        ),
                        "constraints" to RpcValue.ObjectValue(
                            mapOf("network" to RpcValue.StringValue("connected")),
                        ),
                    ),
                ),
            ),
            inbound,
        ) as RuntimeRpcResponse.Success
        assertEquals("task-enqueued", (enqueued.result as RpcValue.StringValue).value)
        assertEquals(RuntimeNetworkConstraint.CONNECTED, recorder.enqueuedSpec?.constraints?.network)

        val periodic = dispatcher.dispatch(
            request(
                method = "background.schedulePeriodic",
                params = RpcValue.ObjectValue(
                    mapOf(
                        "key" to RpcValue.StringValue("notify-hourly"),
                        "operation" to RpcValue.ObjectValue(
                            mapOf(
                                "type" to RpcValue.StringValue("notify"),
                                "title" to RpcValue.StringValue("Reminder"),
                                "body" to RpcValue.StringValue("Time to check ToolBox"),
                            ),
                        ),
                        "intervalMinutes" to RpcValue.Number(15.0),
                    ),
                ),
            ),
            inbound,
        ) as RuntimeRpcResponse.Success
        assertEquals("task-periodic", (periodic.result as RpcValue.StringValue).value)
        assertEquals(15L, recorder.periodicIntervalMinutes)

        val listed = dispatcher.dispatch(
            request(method = "background.list", params = RpcValue.ObjectValue(emptyMap())),
            inbound,
        ) as RuntimeRpcResponse.Success
        assertEquals(1, (listed.result as RpcValue.ArrayValue).value.size)

        val sessions = dispatcher.dispatch(
            request(method = "background.listSessions", params = RpcValue.ObjectValue(emptyMap())),
            inbound,
        ) as RuntimeRpcResponse.Success
        val session = ((sessions.result as RpcValue.ArrayValue).value.single() as RpcValue.ObjectValue).value
        assertEquals("runtime-session", (session.getValue("sessionId") as RpcValue.StringValue).value)
        assertTrue("taskId" !in session)

        val alarm = dispatcher.dispatch(
            request(
                method = "alarms.schedule",
                params = RpcValue.ObjectValue(
                    mapOf(
                        "id" to RpcValue.StringValue("market-open"),
                        "triggerAt" to RpcValue.Number(1_900_000_000_000.0),
                    ),
                ),
            ),
            inbound,
        ) as RuntimeRpcResponse.Success
        val alarmSummary = (alarm.result as RpcValue.ObjectValue).value
        assertEquals("market-open", (alarmSummary.getValue("id") as RpcValue.StringValue).value)
        assertEquals(setOf("id", "triggerAt", "scheduledAt"), alarmSummary.keys)

        val result = dispatcher.dispatch(
            request(
                method = "background.getResult",
                params = RpcValue.ObjectValue(mapOf("taskId" to RpcValue.StringValue("task-enqueued"))),
            ),
            inbound,
        ) as RuntimeRpcResponse.Success
        assertEquals("SUCCEEDED", ((result.result as RpcValue.ObjectValue).value.getValue("outcome") as RpcValue.StringValue).value)

        val cancelled = dispatcher.dispatch(
            request(
                method = "background.cancel",
                params = RpcValue.ObjectValue(mapOf("taskId" to RpcValue.StringValue("task-enqueued"))),
            ),
            inbound,
        )
        assertTrue(cancelled is RuntimeRpcResponse.Success)
        assertEquals("task-enqueued", recorder.cancelledTaskId)
    }

    @Test
    fun m2AuthorizationAndParameterValidationFailBeforeNativeEffects() = runTest {
        val policy = MutablePolicy()
        val recorder = M2Recorder()
        val dispatcher = RuntimeRpcDispatcher(
            identity.copy(declaredCapabilities = setOf("network", "notifications", "background.tasks")),
            policy,
            RuntimeM1Handlers(),
            recorder.handlers(),
        )
        val notification = request(
            method = "notifications.post",
            params = RpcValue.ObjectValue(
                mapOf(
                    "id" to RpcValue.StringValue("permission-check"),
                    "title" to RpcValue.StringValue("Title"),
                    "body" to RpcValue.StringValue("Body"),
                ),
            ),
        )
        policy.systemAvailable = false
        assertFailure(
            RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED,
            dispatcher.dispatch(notification, RuntimeInboundContext(identity.exactOrigin, true, 1)),
        )
        assertEquals(null, recorder.postedNotificationId)

        policy.systemAvailable = true
        assertFailure(
            RuntimeRpcErrorCode.INVALID_REQUEST,
            dispatcher.dispatch(
                request(
                    method = "background.enqueue",
                    params = RpcValue.ObjectValue(
                        mapOf(
                            "key" to RpcValue.StringValue("bad-task"),
                            "operation" to RpcValue.ObjectValue(
                                mapOf(
                                    "type" to RpcValue.StringValue("httpGet"),
                                    "url" to RpcValue.StringValue("https://api.example.invalid/value"),
                                    "unexpected" to RpcValue.Bool(true),
                                ),
                            ),
                        ),
                    ),
                ),
                RuntimeInboundContext(identity.exactOrigin, true, 1),
            ),
        )
        assertEquals(null, recorder.enqueuedSpec)
    }

    @Test
    fun m3FileTokensAndLocationFailClosedThroughTypedHandlers() = runTest {
        val policy = MutablePolicy()
        var consumed = false
        var consumeLimit: Int? = null
        var preciseRequested: Boolean? = null
        var watchOptions: RuntimeLocationWatchOptions? = null
        var clearedWatchId: String? = null
        val files = object : RuntimeFilesHandler {
            override suspend fun open(mimeTypes: List<String>): RuntimeFileToken? = null

            override suspend fun save(
                suggestedName: String,
                mimeType: String,
                content: ByteArray,
            ): RuntimeFileToken? = null

            override suspend fun capabilityFor(token: String): ToolBoxCapabilityId {
                if (consumed || token != "one-shot-token") {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "File token is unavailable")
                }
                return ToolBoxCapabilityId.FILES_OPEN
            }

            override suspend fun consume(token: String, maxBytes: Int): ByteArray {
                consumeLimit = maxBytes
                if (consumed || token != "one-shot-token") {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "File token is unavailable")
                }
                consumed = true
                return "photo".toByteArray()
            }
        }
        val dispatcher = RuntimeRpcDispatcher(
            identity.copy(declaredCapabilities = setOf("files.open", "location")),
            policy,
            RuntimeM1Handlers(),
            m3Handlers = RuntimeM3Handlers(
                files = files,
                location = RuntimeLocationHandler { precise, _ ->
                    preciseRequested = precise
                    RuntimeLocationResult(31.2, 121.5, 12.0, 1_700_000_000_000)
                },
                locationWatch = object : RuntimeLocationWatchHandler {
                    override suspend fun watch(options: RuntimeLocationWatchOptions): String {
                        watchOptions = options
                        return "watch-1"
                    }

                    override suspend fun clearWatch(watchId: String): Boolean {
                        clearedWatchId = watchId
                        return true
                    }
                },
            ),
            maxResponseBytes = 4_096,
        )
        val fileRequest = request(
            method = "files.read",
            params = RpcValue.ObjectValue(mapOf("token" to RpcValue.StringValue("one-shot-token"))),
        )

        val invalidId = dispatcher.dispatch(
            fileRequest.copy(id = "bad-\"-id"),
            RuntimeInboundContext(identity.exactOrigin, true, null),
        )
        assertFailure(RuntimeRpcErrorCode.INVALID_REQUEST, invalidId)
        assertEquals("", invalidId.id)
        assertTrue(!consumed)

        val nearLimitId = "a".repeat(128)
        val fileResponse = dispatcher.dispatch(
            fileRequest.copy(id = nearLimitId),
            RuntimeInboundContext(identity.exactOrigin, true, null),
        ) as RuntimeRpcResponse.Success
        assertEquals(
            "cGhvdG8=",
            ((fileResponse.result as RpcValue.ObjectValue).value.getValue("base64") as RpcValue.StringValue).value,
        )
        assertTrue(checkNotNull(consumeLimit) < 4_096)
        assertTrue(runtimeFileReadEncodedUpperBound(nearLimitId, checkNotNull(consumeLimit)) <= 4_096)
        assertFailure(
            RuntimeRpcErrorCode.NOT_FOUND,
            dispatcher.dispatch(fileRequest.copy(id = "request-2"), RuntimeInboundContext(identity.exactOrigin, true, 1)),
        )

        val location = dispatcher.dispatch(
            request(
                method = "location.getCurrent",
                params = RpcValue.ObjectValue(
                    mapOf(
                        "accuracy" to RpcValue.StringValue("precise"),
                        "timeoutMs" to RpcValue.Number(5_000.0),
                    ),
                ),
            ),
            RuntimeInboundContext(identity.exactOrigin, true, 1),
        )
        assertTrue(location is RuntimeRpcResponse.Success)
        assertEquals(true, preciseRequested)
        assertEquals(setOf("android.permission.ACCESS_COARSE_LOCATION"), policy.checkedSystemPermissions)

        val watch = dispatcher.dispatch(
            request(
                method = "location.watch",
                params = RpcValue.ObjectValue(
                    mapOf(
                        "accuracy" to RpcValue.StringValue("precise"),
                        "intervalMs" to RpcValue.Number(2_500.0),
                        "minDistanceMeters" to RpcValue.Number(3.5),
                        "allowBackground" to RpcValue.Bool(true),
                    ),
                ),
            ),
            RuntimeInboundContext(identity.exactOrigin, true, 1),
        ) as RuntimeRpcResponse.Success
        assertEquals("watch-1", (watch.result as RpcValue.StringValue).value)
        assertEquals(true, watchOptions?.precise)
        assertEquals(2_500L, watchOptions?.intervalMillis)
        assertEquals(3.5f, watchOptions?.minDistanceMeters)
        assertEquals(true, watchOptions?.allowBackground)

        val clearWatch = dispatcher.dispatch(
            request(
                method = "location.clearWatch",
                params = RpcValue.ObjectValue(mapOf("watchId" to RpcValue.StringValue("watch-1"))),
            ),
            RuntimeInboundContext(identity.exactOrigin, true, 1),
        )
        assertTrue(clearWatch is RuntimeRpcResponse.Success)
        assertEquals("watch-1", clearedWatchId)

        val unavailableLocation = RuntimeRpcDispatcher(
            identity.copy(declaredCapabilities = setOf("location")),
            policy,
            RuntimeM1Handlers(),
            m3Handlers = RuntimeM3Handlers(
                location = RuntimeLocationHandler { _, _ ->
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Location is unavailable")
                },
            ),
        ).dispatch(
            request(method = "location.getCurrent", params = RpcValue.ObjectValue(emptyMap())),
            RuntimeInboundContext(identity.exactOrigin, true, 1),
        )
        assertFailure(RuntimeRpcErrorCode.NOT_FOUND, unavailableLocation)

        assertEquals("small", enforceRuntimeResponseLimit("small", 4_096) { "quota" })
        assertEquals("quota", enforceRuntimeResponseLimit("x".repeat(4_097), 4_096) { "quota" })
    }

    private fun dispatcher(policy: MutablePolicy, called: AtomicBoolean) =
        RuntimeRpcDispatcher(identity, policy, handlers(called))

    private fun handlers(called: AtomicBoolean) = RuntimeM1Handlers(
        clipboardWrite = RuntimeClipboardWriteHandler {
            called.set(true)
            assertEquals("copied", it)
        },
    )

    private fun request(
        method: String = "clipboard.writeText",
        params: RpcValue.ObjectValue = RpcValue.ObjectValue(mapOf("text" to RpcValue.StringValue("copied"))),
    ) = RuntimeRpcRequest(
        id = "request-1",
        method = method,
        nonce = identity.nonce,
        toolId = identity.toolId,
        versionCode = identity.versionCode,
        generation = identity.generation,
        params = params,
        encodedBytes = 128,
    )

    private fun assertFailure(code: RuntimeRpcErrorCode, response: RuntimeRpcResponse) {
        assertEquals(code, (response as RuntimeRpcResponse.Failure).error.code)
    }

    private class MutablePolicy : RuntimeAuthorizationPolicy {
        var current = true
        var granted = true
        var systemAvailable = true
        var checkedSystemPermissions: Set<String> = emptySet()
        var decision: RuntimePolicyDecision = RuntimePolicyDecision.Allowed

        override suspend fun isCurrent(identity: RuntimeSessionIdentity): Boolean = current

        override suspend fun isGranted(
            identity: RuntimeSessionIdentity,
            capability: ToolBoxCapabilityId,
        ): Boolean = granted

        override suspend fun hasSystemPermissions(
            identity: RuntimeSessionIdentity,
            permissions: Set<String>,
        ): Boolean {
            checkedSystemPermissions = permissions
            return systemAvailable
        }

        override suspend fun admit(
            identity: RuntimeSessionIdentity,
            method: MethodDescriptor,
            encodedBytes: Int,
        ): RuntimePolicyDecision = decision
    }

    private class M2Recorder {
        var networkRequest: RuntimeNetworkRequest? = null
        var postedNotificationId: String? = null
        var cancelledNotificationId: String? = null
        var enqueuedSpec: RuntimeBackgroundTaskSpec? = null
        var periodicSpec: RuntimeBackgroundTaskSpec? = null
        var periodicIntervalMinutes: Long? = null
        var cancelledTaskId: String? = null
        var liveNotification: RuntimeLiveNotificationRequest? = null
        var endedLiveSessionId: String? = null

        fun handlers() = RuntimeM2Handlers(
            network = RuntimeNetworkHandler { request ->
                networkRequest = request
                RuntimeNetworkResponse(
                    status = 201,
                    headers = mapOf("etag" to "example"),
                    body = "{\"created\":true}",
                )
            },
            notifications = object : RuntimeNotificationHandler {
                override suspend fun post(notificationId: String, title: String, body: String) {
                    postedNotificationId = notificationId
                }

                override suspend fun cancel(notificationId: String) {
                    cancelledNotificationId = notificationId
                }

                override suspend fun startLive(request: RuntimeLiveNotificationRequest): RuntimeLiveNotificationResult {
                    if (request.sessionId != "runtime-session") {
                        throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "Wrong session")
                    }
                    liveNotification = request
                    return RuntimeLiveNotificationResult(
                        androidLive = RuntimeAndroidLiveStatus.NOT_ALLOWED,
                        hyperOsIsland = RuntimeHyperOsIslandStatus.REQUESTED,
                        hyperOsProtocolVersion = 3,
                        hyperOsPermissionReported = false,
                    )
                }

                override suspend fun updateLive(request: RuntimeLiveNotificationRequest) = startLive(request)

                override suspend fun endLive(sessionId: String) {
                    endedLiveSessionId = sessionId
                }
            },
            background = object : RuntimeBackgroundTaskHandler {
                override suspend fun enqueue(spec: RuntimeBackgroundTaskSpec): String {
                    enqueuedSpec = spec
                    return "task-enqueued"
                }

                override suspend fun schedulePeriodic(spec: RuntimeBackgroundTaskSpec, intervalMinutes: Long): String {
                    periodicSpec = spec
                    periodicIntervalMinutes = intervalMinutes
                    return "task-periodic"
                }

                override suspend fun list(): List<RuntimeBackgroundTaskSummary> = listOf(
                    RuntimeBackgroundTaskSummary(
                        taskId = "task-enqueued",
                        key = "fetch-repository",
                        state = RuntimeBackgroundTaskState.QUEUED,
                        periodic = false,
                        nextRunAt = null,
                    ),
                )

                override suspend fun getResult(taskId: String): RuntimeBackgroundTaskRunResult? =
                    RuntimeBackgroundTaskRunResult(
                        taskId = taskId,
                        outcome = RuntimeBackgroundRunOutcome.SUCCEEDED,
                        completedAt = 1L,
                        status = 200,
                        body = "{\"ok\":true}",
                        error = null,
                    )

                override suspend fun cancel(taskId: String): Boolean {
                    cancelledTaskId = taskId
                    return true
                }
            },
            continuousBackground = object : RuntimeContinuousBackgroundHandler {
                private val session = RuntimeBackgroundSessionSummary(
                    sessionId = "runtime-session",
                    startedAt = 1_700_000_000_000,
                    restoreAfterProcessDeath = true,
                    restoreAfterReboot = false,
                )

                override suspend fun start(options: RuntimeBackgroundStartOptions) = session

                override suspend fun stop(sessionId: String) = sessionId == session.sessionId

                override suspend fun status(sessionId: String) = session.takeIf { sessionId == session.sessionId }

                override suspend fun list() = listOf(session)

                override suspend fun setTimer(key: String, intervalMillis: Long) = Unit

                override suspend fun cancelTimer(key: String) = true
            },
            alarms = object : RuntimeAlarmHandler {
                private var scheduled: RuntimeAlarmSummary? = null

                override suspend fun schedule(alarm: RuntimeAlarmSummary): RuntimeAlarmSummary {
                    scheduled = alarm
                    return alarm
                }

                override suspend fun list(): List<RuntimeAlarmSummary> = listOfNotNull(scheduled)

                override suspend fun cancel(alarmId: String): Boolean {
                    val matches = scheduled?.alarmId == alarmId
                    if (matches) scheduled = null
                    return matches
                }
            },
        )
    }
}
