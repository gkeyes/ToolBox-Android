package io.toolbox.host.runtime

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeLocationWatchOptions
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RuntimeLocationWatchServiceTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val locationManager = app.getSystemService(LocationManager::class.java)
    private var foreground = true
    private var hostLookups = 0
    private var backgroundSession = false

    private val service = RuntimeLocationWatchService(
        appContext = app,
        grantsFor = { mapOf("location" to true, "location.background" to true, "background.runtime" to true) },
        hostFor = { hostLookups++; null },
        isForeground = { foreground },
        ensureCurrentRuntime = { _, _ -> },
        hasBackgroundSession = { backgroundSession },
        setRecoveryStatus = { _, _ -> },
        refreshForegroundService = {},
    )
    private val handler = service.handler(TOOL, versionCode = 1, declaredCapabilities = setOf("location"))

    @Before
    fun grantLocation() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }

    @Test
    fun hiddenToolCannotStartAForegroundOnlyWatch() {
        foreground = false
        try {
            runBlocking { handler.watch(options()) }
            fail("A hidden tool started a foreground-only location watch")
        } catch (expected: RuntimeHandlerException) {
            assertEquals(RuntimeRpcErrorCode.SESSION_ENDED, expected.errorCode)
        }
        assertEquals(0, service.watchCount(TOOL))
    }

    @Test
    fun watchStopsWhenItsRuntimeIsGone() {
        runBlocking { handler.watch(options()) }
        assertEquals(1, service.watchCount(TOOL))

        deliver()
        assertEquals("the update found no runtime", 1, hostLookups)
        assertEquals(0, service.watchCount(TOOL))

        deliver()
        assertEquals("the listener was unregistered, so no further update arrives", 1, hostLookups)
    }

    @Test
    fun clearingAllWatchesUnregistersTheListener() {
        runBlocking { handler.watch(options()) }
        service.clearAllWatches(TOOL)
        deliver()
        assertEquals(0, hostLookups)
        assertEquals(0, service.watchCount(TOOL))
    }

    @Test
    fun foregroundOnlyWatchStopsWhileTheActivityIsPausedAndResumesWithTheToolShown() {
        runBlocking { handler.watch(options()) }

        service.onHostActivityResumed(resumed = false, visibleTools = setOf(TOOL))
        deliver()
        assertEquals("no updates are requested while paused", 0, hostLookups)
        assertEquals(1, service.watchCount(TOOL))
        assertEquals(1, service.pausedWatchCount(TOOL))

        service.onHostActivityResumed(resumed = true, visibleTools = setOf(TOOL))
        assertEquals(0, service.pausedWatchCount(TOOL))
        deliver()
        assertEquals("updates are requested again on resume", 1, hostLookups)
    }

    @Test
    fun pausedWatchOfAToolNoLongerShownIsNotRequestedAgain() {
        runBlocking { handler.watch(options()) }
        service.onHostActivityResumed(resumed = false, visibleTools = setOf(TOOL))
        service.onHostActivityResumed(resumed = true, visibleTools = emptySet())
        deliver()
        assertEquals(0, hostLookups)
        assertEquals(1, service.pausedWatchCount(TOOL))

        service.clearForegroundOnlyWatches(TOOL)
        assertEquals(0, service.watchCount(TOOL))
    }

    @Test
    fun backgroundWatchKeepsRunningWhileTheActivityIsPaused() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        backgroundSession = true
        foreground = false
        val backgroundHandler = service.handler(
            TOOL, versionCode = 1, declaredCapabilities = setOf("location", "location.background", "background.runtime"),
        )
        runBlocking { backgroundHandler.watch(options(allowBackground = true)) }

        service.onHostActivityResumed(resumed = false, visibleTools = emptySet())
        assertEquals(0, service.pausedWatchCount(TOOL))
        deliver()
        assertEquals("the background watch still receives updates", 1, hostLookups)
    }

    private fun options(allowBackground: Boolean = false) = RuntimeLocationWatchOptions(
        precise = true, intervalMillis = 1_000L, minDistanceMeters = 0f, allowBackground = allowBackground,
    )

    private fun deliver() {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 25.03
            longitude = 121.56
            accuracy = 5f
            time = System.currentTimeMillis()
        }
        shadowOf(locationManager).simulateLocation(location)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val TOOL = "location-tool"
    }
}
