package io.toolbox.host.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import io.toolbox.tool.runtime.HardenedRuntimeWebView
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeLocationWatchHandler
import io.toolbox.tool.runtime.RuntimeLocationWatchOptions
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Location watches per tool, foreground-only or kept alive by a background session. Main thread only. */
internal class RuntimeLocationWatchService(
    private val appContext: Context,
    private val grantsFor: suspend (toolId: String) -> Map<String, Boolean>,
    private val hostFor: (toolId: String) -> RuntimeHost?,
    /** The tool is open and shown, the only state in which a foreground-only watch may start. */
    private val isForeground: (toolId: String) -> Boolean,
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
                val grants = grantsFor(toolId)
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
            if (!options.allowBackground && !isForeground(toolId)) {
                // A hidden tool kept alive by a background session would otherwise get location
                // without the location.background grant, and nothing would stop the watch.
                throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "前台定位需要工具在前台打开")
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
                provider = provider,
                intervalMillis = options.intervalMillis,
                minDistanceMeters = options.minDistanceMeters,
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
        val host = hostFor(toolId)
        if (host == null) {
            // The runtime that started this watch is gone, so no one can clear it: stop the updates.
            removeWatch(toolId, watchId)
            return
        }
        if (HardenedRuntimeWebView.isClosing(host.webView)) return
        val watch = watchesByTool[toolId]?.get(watchId) ?: return
        // A foreground-only watch started while the tool was shown delivers nothing once it is hidden;
        // otherwise a background session would keep receiving it without location.background.
        // Its updates are also stopped while the Activity is paused (onHostActivityResumed); this
        // check stays as the safety net for any update that still arrives.
        if (!watch.allowBackground && !isForeground(toolId)) return
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
            setRecoveryStatus(toolId, "定位事件积压，已停止该监听，请重新开启。")
            removeWatch(toolId, watchId)
        }
    }

    private fun removeWatch(toolId: String, watchId: String) {
        val watches = watchesByTool[toolId] ?: return
        val watch = watches.remove(watchId) ?: return
        locationManager?.removeUpdates(watch.listener)
        if (watches.isEmpty()) watchesByTool.remove(toolId)
        refreshForegroundService()
    }

    fun watchCount(toolId: String): Int = watchesByTool[toolId]?.size ?: 0

    private fun chooseProvider(manager: LocationManager, precise: Boolean): String? {
        val candidates = if (precise) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        return candidates.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /**
     * A foreground-only watch can deliver nothing while the host Activity is paused, so its
     * LocationManager updates are stopped then (no GPS running for nothing) and requested again
     * when the Activity resumes with its tool still in [visibleTools]. Background watches are untouched.
     */
    fun onHostActivityResumed(resumed: Boolean, visibleTools: Set<String>) {
        if (!resumed) {
            watchesByTool.values.forEach { watches ->
                watches.values.filter { !it.allowBackground && !it.paused }.forEach { watch ->
                    locationManager?.removeUpdates(watch.listener)
                    watch.paused = true
                }
            }
            return
        }
        visibleTools.forEach { toolId ->
            val watches = watchesByTool[toolId] ?: return@forEach
            watches.filterValues { !it.allowBackground && it.paused }.forEach { (watchId, watch) ->
                val manager = locationManager
                // The location permission can be revoked while paused (the process usually dies then); any failure
                // drops the watch.
                val requested = manager != null && try {
                    manager.requestLocationUpdates(
                        watch.provider,
                        watch.intervalMillis,
                        watch.minDistanceMeters,
                        appContext.mainExecutor,
                        watch.listener,
                    )
                    true
                } catch (_: SecurityException) {
                    false
                } catch (_: RuntimeException) {
                    false
                }
                if (requested) {
                    watch.paused = false
                } else {
                    watches.remove(watchId)
                    setRecoveryStatus(toolId, "定位监听未能恢复，请重新开启。")
                }
            }
            if (watches.isEmpty()) watchesByTool.remove(toolId)
        }
    }

    /** Foreground-only watches whose updates are stopped while the host Activity is paused. */
    fun pausedWatchCount(toolId: String): Int = watchesByTool[toolId]?.values?.count(ActiveLocationWatch::paused) ?: 0

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
        val watches = watchesByTool.remove(toolId) ?: return
        watches.values.forEach { locationManager?.removeUpdates(it.listener) }
        refreshForegroundService()
    }

    private class ActiveLocationWatch(
        val listener: LocationListener,
        val allowBackground: Boolean,
        val provider: String,
        val intervalMillis: Long,
        val minDistanceMeters: Float,
    ) {
        /** Updates are stopped because the host Activity is paused; only foreground-only watches. */
        var paused = false
    }

    private companion object {
        const val EVENT_LOCATION_CHANGED = "location.onChanged"
    }
}
