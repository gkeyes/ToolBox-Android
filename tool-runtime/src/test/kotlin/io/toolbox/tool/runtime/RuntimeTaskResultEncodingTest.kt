package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeTaskResultEncodingTest {
    private val identity = RuntimeSessionIdentity(
        toolId = "com.example.tasks",
        versionCode = 1,
        generation = "generation",
        hostVersion = "0.8.32",
        nonce = "nonce",
        exactOrigin = "https://tasks.toolbox.invalid/",
        declaredCapabilities = setOf(ToolBoxCapabilityId.BACKGROUND_TASKS),
    )
    private val allowAll = object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) = true
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = true
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) =
            RuntimePolicyDecision.Allowed
    }

    @Test
    fun binaryHttpResultReportsBase64Encoding() = runBlocking {
        val result = getResult(RuntimeNetworkBodyEncoding.BASE64)
        assertEquals(RpcValue.StringValue("AQID"), result["body"])
        assertEquals(RpcValue.StringValue("base64"), result["bodyEncoding"])
    }

    @Test
    fun resultFromAnOlderHostOmitsEncoding() = runBlocking {
        assertFalse("bodyEncoding" in getResult(null))
    }

    private suspend fun getResult(encoding: RuntimeNetworkBodyEncoding?): Map<String, RpcValue> {
        val handler = object : RuntimeBackgroundTaskHandler {
            override suspend fun enqueue(spec: RuntimeBackgroundTaskSpec) = error("unused")
            override suspend fun schedulePeriodic(spec: RuntimeBackgroundTaskSpec, intervalMinutes: Long) = error("unused")
            override suspend fun list(): List<RuntimeBackgroundTaskSummary> = emptyList()
            override suspend fun cancel(taskId: String) = false
            override suspend fun getResult(taskId: String) = RuntimeBackgroundTaskRunResult(
                taskId, RuntimeBackgroundRunOutcome.SUCCEEDED, 1L, 200, "AQID", null, encoding,
            )
        }
        val response = RuntimeRpcDispatcher(identity, allowAll, RuntimeM1Handlers(), RuntimeM2Handlers(background = handler))
            .dispatch(
                RuntimeRpcRequest("request", "background.getResult", identity.nonce, identity.toolId, identity.versionCode,
                    identity.generation, RpcValue.ObjectValue(mapOf("taskId" to RpcValue.StringValue("task-1"))), 100),
                RuntimeInboundContext(identity.exactOrigin, true),
            ) as RuntimeRpcResponse.Success
        return (response.result as RpcValue.ObjectValue).value
    }
}
