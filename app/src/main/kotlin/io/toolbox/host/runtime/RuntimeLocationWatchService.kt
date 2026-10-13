package io.toolbox.host.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.tool.runtime.HardenedRuntimeWebView
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeLocationWatchHandler
import io.toolbox.tool.runtime.RuntimeLocationWatchOptions
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Location watches per tool, foreground-only or kept alive by a background session. Main thread only. */
internal class RuntimeLocationWatchService(
    private val appContext: Context,
    private val repositories: CoreDataRepositories,
    private val hostFor: (toolId: String) -> RuntimeHost?,
    private val ensureCurrentRuntime: (toolId: String, versionCode: Int) -> Unit,
    private val hasBackgroundSession: (toolId: String) -> Boolean,
    private val setRecoveryStatus: (toolId: String, status: String) -> Unit,
    private val refreshForegroundService: () -> Unit,
) {
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val watchesByTool = mutableMapOf<String, MutableMap<String, ActiveLocationWatch>>()

    fun handler(toolId: String, versionCode: Int, declaredCapabilities: Set<String>): RuntimeLocationWatchHandler =
        LocationWatchHandler(toolId, versionCode, declaredCapabilities)

    fun hasBackgroundLocationWatch(): Boolean = watchesByTool.values.any { watches ->
        watches.values.any(ActiveLocationWatch::allowBackground)
    }

    private inner class LocationWatchHandler(
        private val toolId: String,
        private val versionCode: Int,
        private val declaredCapabilities: Set<String>,
    ) : RuntimeLocationWatchHandler {
        override suspend fun watch(options: RuntimeLocationWatchOptions): String {
            if (options.allowBackground) {
                if ("location.background" !in declaredCapabilities || "background.runtime" !in declaredCapabilities) {
                    throw RuntimeHandlerException(
                        RuntimeRpcErrorCode.NOT_DECLARED,
                        "后台定位需要声明 location.background 与 background.runtime",
                    )
                }
                val grants = repositories.grants.observeGrants(toolId).first().associate { it.capability to it.granted }
                if (grants["location.background"] != true || grants["background.runtime"] != true) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "后台定位权限未开启")
                }
            }
            return withContext(Dispatchers.Main.immediate) {
            ensureCurrentRuntime(toolId, versionCode)
            val foregroundPermission = if (options.precise) {
                Manifest.permission.ACCESS_FINE_LOCATION
            } else {
                Manifest.permission.ACCESS_COARSE_LOCATION
            }
            if (appContext.checkSelfPermission(foregroundPermission) != PackageManager.PERMISSION_GRANTED) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "位置权限不可用")
            }
            if (options.allowBackground) {
                if (!hasBackgroundSession(toolId)) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "后台定位需要正在运行的后台环境")
                }
                if (appContext.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "请在后台保障中允许始终访问位置")
                }
            }
            val manager = locationManager
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "位置服务不可用")
            val provider = chooseProvider(manager, options.precise)
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "没有可用的位置来源")
            val watchId = UUID.randomUUID().toString()
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    emitLocation(toolId, watchId, location)
                }
            }
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    options.intervalMillis,
                    options.minDistanceMeters,
                    appContext.mainExecutor,
                    listener,
                )
            }.getOrElse {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "位置监听未能启动")
            }
            watchesByTool.getOrPut(toolId, ::linkedMapOf)[watchId] = ActiveLocationWatch(
                listener = listener,
                allowBackground = options.allowBackground,
            )
            if (options.allowBackground) refreshForegroundService()
            watchId
            }
        }

        override suspend fun clearWatch(watchId: String): Boolean = withContext(Dispatchers.Main.immediate) {
            val watch = watchesByTool[toolId]?.remove(watchId) ?: return@withContext false
            locationManager?.removeUpdates(watch.listener)
            refreshForegroundService()
            true
        }
    }

    private fun emitLocation(toolId: String, watchId: String, location: Location) {
        val host = hostFor(toolId) ?: return
        if (HardenedRuntimeWebView.isClosing(host.webView)) return
        val emitted = HardenedRuntimeWebView.emitEvent(
            host.webView,
            EVENT_LOCATION_CHANGED,
            RpcValue.ObjectValue(
                mapOf(
                    "watchId" to RpcValue.StringValue(watchId),
                    "latitude" to RpcValue.Number(location.latitude),
                    "longitude" to RpcValue.Number(location.longitude),
                    "accuracyMeters" to RpcValue.Number(location.accuracy.toDouble()),
                    "capturedAt" to RpcValue.Number(location.time.toDouble()),
                ),
            ),
        )
        if (!emitted) {
            watchesByTool[toolId]?.remove(watchId)?.let { locationManager?.removeUpdates(it.listener) }
            setRecoveryStatus(toolId, "定位事件积压，已停止该监听，请重新开启。")
            refreshForegroundService()
        }
    }

    private fun chooseProvider(manager: LocationManager, precise: Boolean): String? {
        val candidates = if (precise) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        return candidates.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    fun clearForegroundOnlyWatches(toolId: String) {
        val watches = watchesByTool[toolId] ?: return
        val foregroundOnly = watches.filterValues { !it.allowBackground }.keys
        foregroundOnly.forEach { watchId -> watches.remove(watchId)?.let { locationManager?.removeUpdates(it.listener) } }
    }

    fun clearBackgroundWatches(toolId: String) {
        val watches = watchesByTool[toolId] ?: return
        val background = watches.filterValues(ActiveLocationWatch::allowBackground).keys
        background.forEach { watchId -> watches.remove(watchId)?.let { locationManager?.removeUpdates(it.listener) } }
        refreshForegroundService()
    }

    fun clearAllWatches(toolId: String) {
        watchesByTool.remove(toolId)?.values?.forEach { locationManager?.removeUpdates(it.listener) }
        refreshForegroundService()
    }

    private data class ActiveLocationWatch(
        val listener: LocationListener,
        val allowBackground: Boolean,
    )

    private companion object {
        const val EVENT_LOCATION_CHANGED = "location.onChanged"
    }
}
