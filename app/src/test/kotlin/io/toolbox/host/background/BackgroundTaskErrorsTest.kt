package io.toolbox.host.background

import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTaskErrorsTest {
    @Test
    fun networkCodesUseTheForegroundMappingAndKeepTheRawCode() {
        val expected = mapOf(
            "NETWORK_TIMEOUT" to RuntimeRpcErrorCode.NETWORK_TIMEOUT,
            "NETWORK_IO" to RuntimeRpcErrorCode.NETWORK_UNAVAILABLE,
            "HTTP_404" to RuntimeRpcErrorCode.NETWORK_UNAVAILABLE,
            "HTTP_503" to RuntimeRpcErrorCode.NETWORK_UNAVAILABLE,
            "PROXY_AUTHENTICATION_REQUIRED" to RuntimeRpcErrorCode.NETWORK_UNAVAILABLE,
            "RESULT_TOO_LARGE" to RuntimeRpcErrorCode.QUOTA_EXCEEDED,
            "INVALID_URL" to RuntimeRpcErrorCode.INVALID_REQUEST,
            "HTTPS_REQUIRED" to RuntimeRpcErrorCode.NETWORK_BLOCKED,
            "REDIRECT_LOOP" to RuntimeRpcErrorCode.NETWORK_BLOCKED,
            "NETWORK_NOT_ALLOWED" to RuntimeRpcErrorCode.PERMISSION_DENIED,
            "BACKGROUND_EXECUTION_FAILED" to RuntimeRpcErrorCode.INTERNAL_ERROR,
            "INVALID_TASK_SPEC" to RuntimeRpcErrorCode.INVALID_REQUEST,
            "DUPLICATE_TASK_KEY" to RuntimeRpcErrorCode.DUPLICATE_TASK,
            "CANCELLED" to RuntimeRpcErrorCode.CANCELLED,
        )
        expected.forEach { (code, rpc) ->
            val failure = backgroundTaskFailure(code, "后台任务未成功完成。")
            assertEquals(code, rpc, failure.errorCode)
            assertTrue(failure.message, code in failure.message)
        }
    }

    @Test
    fun failedHttpStatusIsReported() {
        assertEquals(404, backgroundHttpStatus("HTTP_404"))
        assertEquals(503, backgroundHttpStatus("HTTP_503"))
        assertNull(backgroundHttpStatus("NETWORK_IO"))
        assertNull(backgroundHttpStatus("HTTP_x"))
        assertNull(backgroundHttpStatus(null))
    }

    @Test
    fun enqueueUsesTheProxyEndpointValidation() {
        assertNull(backgroundHttpEndpointError("https://example.test/feed"))
        assertEquals("HTTPS_REQUIRED", backgroundHttpEndpointError("http://example.test/feed"))
        assertEquals("URL_CREDENTIALS_FORBIDDEN", backgroundHttpEndpointError("https://user:pass@example.test/"))
        assertEquals("INVALID_URL", backgroundHttpEndpointError("not a url"))
        assertEquals("INVALID_URL", backgroundHttpEndpointError("ftp://example.test/"))
    }
}
