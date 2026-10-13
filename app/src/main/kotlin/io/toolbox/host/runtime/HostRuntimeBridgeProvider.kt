package io.toolbox.host.runtime

import android.content.Context
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.host.BuildConfig
import io.toolbox.host.HostInstalledManifestReader
import io.toolbox.host.HostInstalledManifestResult
import io.toolbox.host.HostRuntimeServiceHandlerFactory
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.runtime.AndroidRuntimeSystemPermissionChecker
import io.toolbox.tool.runtime.DefaultRuntimeAuthorizationPolicy
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RuntimeBridgeConfiguration
import io.toolbox.tool.runtime.RuntimeBridgeProvider
import io.toolbox.tool.runtime.RuntimeCoreHandlers
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.UUID

internal class HostRuntimeBridgeProvider(
    context: Context,
    repositories: CoreDataRepositories,
    private val serviceHandlerFactory: HostRuntimeServiceHandlerFactory,
    private val continuityHandlerFactory: (PreparedToolRuntime) -> HostRuntimeContinuityHandlers,
    private val hostVersion: String = BuildConfig.VERSION_NAME,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RuntimeBridgeProvider {
    private val applicationContext = context.applicationContext
    private val grantState = RepositoryRuntimeGrantStateSource(repositories.catalog, repositories.grants)
    private val systemPermissions = AndroidRuntimeSystemPermissionChecker(applicationContext)
    private val keyValues = repositories.keyValues
    private val installedManifests = HostInstalledManifestReader(applicationContext.filesDir, repositories.catalog)

    override fun create(runtime: PreparedToolRuntime): RuntimeBridgeConfiguration {
        val continuity = continuityHandlerFactory(runtime)
        val serviceHandlers = serviceHandlerFactory.createHandlers(runtime).copy(
            continuousBackground = continuity.background,
            alarms = continuity.alarms,
        )
        val foregroundHandlers = ForegroundCapabilityBroker.activeHandlers(
            context = applicationContext,
            toolId = runtime.toolId,
            toolName = runtime.installedManifest.name,
            requireForegroundRuntime = continuity.requireForegroundRuntime,
            authorizeBrowserLaunch = {
                val current = (installedManifests.read(runtime.toolId) as? HostInstalledManifestResult.Found)?.manifest
                if (current?.versionCode != runtime.versionCode) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
                }
                if (current.permissions.none { it.capability == "browser" }) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_DECLARED, "Browser permission is not declared by this tool")
                }
                if (!grantState.isGranted(runtime.toolId, ToolBoxCapabilityId.BROWSER)) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Enable browser access in this tool's permissions")
                }
            },
        )
        val systemHandlers = foregroundHandlers.copy(
            locationWatch = continuity.locationWatch,
        )
        val authorization = DefaultRuntimeAuthorizationPolicy(
            state = grantState,
            systemPermissions = systemPermissions,
            quota = HostRuntimeQuotaChecker(),
        )
        return RuntimeBridgeConfiguration(
            authorization = authorization,
            handlers = createCoreHandlers(runtime, continuity.requireForegroundRuntime),
            serviceHandlers = serviceHandlers,
            systemHandlers = systemHandlers,
            hostVersion = hostVersion,
            generation = "${runtime.toolId}:${runtime.versionCode}:${UUID.randomUUID()}",
            browserLaunchGuard = continuity.requireForegroundRuntime,
        )
    }

    private fun createCoreHandlers(runtime: PreparedToolRuntime, requireForegroundRuntime: () -> Unit): RuntimeCoreHandlers = RuntimeCoreHandlers(
        toast = AndroidToastHandler(applicationContext),
        storage = StandardToolKvStorageHandler(
            toolId = runtime.toolId,
            repository = keyValues,
            nowMillis = nowMillis,
            canAccess = {
                grantState.currentVersionCode(runtime.toolId) == runtime.versionCode &&
                    grantState.isGranted(runtime.toolId, ToolBoxCapabilityId.STORAGE)
            },
        ),
        secureStorage = AndroidKeyStoreCipher.isAvailable().takeIf { it }?.let {
            createRuntimeSecureStorageHandler(
                toolId = runtime.toolId,
                repository = keyValues,
                nowMillis = nowMillis,
                canAccess = {
                    grantState.currentVersionCode(runtime.toolId) == runtime.versionCode &&
                        grantState.isGranted(runtime.toolId, ToolBoxCapabilityId.STORAGE_SECURE)
                },
            )
        },
        deviceBasic = AndroidBasicDeviceHandler(applicationContext),
        haptics = AndroidHapticsHandler(applicationContext, requireForegroundRuntime),
        clipboardWrite = AndroidClipboardWriteHandler(applicationContext, requireForegroundRuntime),
    )
}
