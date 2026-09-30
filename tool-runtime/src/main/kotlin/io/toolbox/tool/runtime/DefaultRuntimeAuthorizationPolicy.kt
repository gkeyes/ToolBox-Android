package io.toolbox.tool.runtime

import android.content.Context
import android.content.pm.PackageManager
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId

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
) : RuntimeAuthorizationPolicy {
    override suspend fun isCurrent(identity: RuntimeSessionIdentity): Boolean =
        state.currentVersionCode(identity.toolId) == identity.versionCode

    override suspend fun isGranted(
        identity: RuntimeSessionIdentity,
        capability: ToolBoxCapabilityId,
    ): Boolean = state.isGranted(identity.toolId, capability)

    override suspend fun hasSystemPermissions(
        identity: RuntimeSessionIdentity,
        permissions: Set<String>,
    ): Boolean = systemPermissions.hasAll(permissions)

    override suspend fun admit(
        identity: RuntimeSessionIdentity,
        method: MethodDescriptor,
        encodedBytes: Int,
    ): RuntimePolicyDecision {
        // Grants authorize use; admission protects retained message resources only.
        // The dispatcher checks the current version before and after this suspend point.
        // Completed calls never consume a rolling per-minute allowance.
        return quota.admit(identity, method, encodedBytes)
    }
}
