package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import java.util.concurrent.atomic.AtomicBoolean
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
        hostVersion = "0.2.0",
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
        assertEquals("0.2.0", (result.getValue("hostVersion") as RpcValue.StringValue).value)
        assertEquals(identity.generation, (result.getValue("generation") as RpcValue.StringValue).value)
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
            identity.copy(declaredCapabilities = setOf("network", "notifications", "background.tasks")),
            MutablePolicy(),
            RuntimeM1Handlers(),
            recorder.handlers(),
        )
        val inbound = RuntimeInboundContext(identity.exactOrigin, true, 1)

        assertFailure(
            RuntimeRpcErrorCode.INVALID_REQUEST,
            dispatcher.dispatch(
                request(
                    method = "network.request",
                    params = RpcValue.ObjectValue(
                        mapOf(
                            "url" to RpcValue.StringValue("https://api.example.invalid/value"),
                            "method" to RpcValue.StringValue("POST"),
                        ),
                    ),
                ),
                inbound,
            ),
        )
        assertEquals(null, recorder.networkRequest)

        val network = dispatcher.dispatch(
            request(
                method = "network.request",
                params = RpcValue.ObjectValue(
                    mapOf(
                        "url" to RpcValue.StringValue("https://api.example.invalid/value"),
                        "method" to RpcValue.StringValue("GET"),
                    ),
                ),
            ),
            inbound,
        ) as RuntimeRpcResponse.Success
        assertEquals(RuntimeNetworkMethod.GET, recorder.networkRequest?.method)
        assertEquals(201.0, ((network.result as RpcValue.ObjectValue).value.getValue("status") as RpcValue.Number).value, 0.0)

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
        assertFailure(
            RuntimeRpcErrorCode.USER_GESTURE_REQUIRED,
            dispatcher.dispatch(notification, RuntimeInboundContext(identity.exactOrigin, true, null)),
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
        )
    }
}
