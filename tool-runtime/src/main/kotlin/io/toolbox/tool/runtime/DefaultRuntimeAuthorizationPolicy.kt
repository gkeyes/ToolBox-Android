package io.toolbox.tool.runtime

import android.content.Context
import android.content.pm.PackageManager
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import java.util.ArrayDeque

interface RuntimeGrantStateSource {
    suspend fun currentVersionCode(toolId: String): Int?
    suspend fun isGranted(toolId: String, capability: ToolBoxCapabilityId): Boolean
}

fun interface RuntimeSystemPermissionChecker {
    fun hasAll(permissions: Set<String>): Boolean
}

fun interface RuntimeQuotaChecker {
    suspend fun admit(
        identity: RuntimeSessionIdentity,
        method: MethodDescriptor,
        encodedBytes: Int,
    ): RuntimePolicyDecision
}

class AndroidRuntimeSystemPermissionChecker(context: Context) : RuntimeSystemPermissionChecker {
    private val applicationContext = context.applicationContext

    override fun hasAll(permissions: Set<String>): Boolean = permissions.all { permission ->
        applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}

class DefaultRuntimeAuthorizationPolicy(
    private val state: RuntimeGrantStateSource,
    private val systemPermissions: RuntimeSystemPermissionChecker,
    private val quota: RuntimeQuotaChecker,
    private val clockMillis: () -> Long,
    private val maxCallsPerMinute: Int = 120,
) : RuntimeAuthorizationPolicy {
    private val rateWindows = hashMapOf<Pair<String, String>, ArrayDeque<Long>>()

    init {
        require(maxCallsPerMinute in 1..1_000)
    }

    override suspend fun isCurrent(identity: RuntimeSessionIdentity): Boolean =
        state.currentVersionCode(identity.toolId) == identity.versionCode

    override suspend fun isGranted(
        identity: RuntimeSessionIdentity,
        capability: ToolBoxCapabilityId,
    ): Boolean = isCurrent(identity) && state.isGranted(identity.toolId, capability)

    override suspend fun hasSystemPermissions(
        identity: RuntimeSessionIdentity,
        permissions: Set<String>,
    ): Boolean = isCurrent(identity) && systemPermissions.hasAll(permissions)

    override suspend fun admit(
        identity: RuntimeSessionIdentity,
        method: MethodDescriptor,
        encodedBytes: Int,
    ): RuntimePolicyDecision {
        if (!isCurrent(identity)) {
            return RuntimePolicyDecision.Denied(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
        }
        val quotaDecision = quota.admit(identity, method, encodedBytes)
        if (quotaDecision is RuntimePolicyDecision.Denied) return quotaDecision
        val now = clockMillis()
        val permitted = synchronized(rateWindows) {
            val window = rateWindows.getOrPut(identity.toolId to method.name, ::ArrayDeque)
            while (window.isNotEmpty() && now - window.first() >= RATE_WINDOW_MILLIS) window.removeFirst()
            if (window.size >= methodLimit(method.name)) {
                false
            } else {
                window.addLast(now)
                true
            }
        }
        return if (permitted) {
            RuntimePolicyDecision.Allowed
        } else {
            RuntimePolicyDecision.Denied(RuntimeRpcErrorCode.RATE_LIMITED, "ToolBox method rate limit exceeded")
        }
    }

    private fun methodLimit(method: String): Int = when (method) {
        "haptics.perform" -> minOf(maxCallsPerMinute, 30)
        "clipboard.writeText" -> minOf(maxCallsPerMinute, 20)
        "ui.toast" -> minOf(maxCallsPerMinute, 30)
        else -> maxCallsPerMinute
    }

    private companion object {
        const val RATE_WINDOW_MILLIS = 60_000L
    }
}
