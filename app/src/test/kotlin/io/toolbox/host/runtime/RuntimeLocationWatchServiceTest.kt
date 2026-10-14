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

    private val service = RuntimeLocationWatchService(
        appContext = app,
        grantsFor = { emptyMap() },
        hostFor = { hostLookups++; null },
        isForeground = { foreground },
        ensureCurrentRuntime = { _, _ -> },
        hasBackgroundSession = { false },
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

    private fun options() = RuntimeLocationWatchOptions(
        precise = true, intervalMillis = 1_000L, minDistanceMeters = 0f, allowBackground = false,
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
