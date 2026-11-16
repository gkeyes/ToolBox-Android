package io.toolbox.host.runtime

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ForegroundCapabilityBrokerTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var broker: ForegroundCapabilityBroker? = null
    private var call: Job? = null

    @After
    fun finishCall() {
        call?.cancel()
        awaitCall()
        broker?.close()
    }

    @Test
    fun pickerResultReachesTheActivityRecreatedByAConfigurationChange() {
        assertPickerResultSurvivesConfigurationChange(listOf("text/plain"))
    }

    @Test
    @Config(sdk = [34])
    fun imageRequestOpensPhotoPickerAndSurvivesActivityRecreation() {
        assertPickerResultSurvivesConfigurationChange(listOf("image/*"))
    }

    private fun assertPickerResultSurvivesConfigurationChange(mimeTypes: List<String>) {
        val first = resumedActivityWithBroker()
        val pending = openPicker(mimeTypes)
        val launch = shadowOf(first.get()).nextStartedActivityForResult
        if (mimeTypes == listOf("image/*")) {
            assertEquals(MediaStore.ACTION_PICK_IMAGES, launch.intent.action)
            assertEquals("image/*", launch.intent.type)
        } else {
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
        }
        val requestCode = launch.requestCode
        val saved = Bundle()
        first.pause().saveInstanceState(saved).stop()

        // MainActivity.onDestroy during a rotation or theme switch.
        broker!!.close(keepPendingRequests = true)
        first.destroy()
        assertFalse(pending.isCompleted)

        val second = Robolectric.buildActivity(ComponentActivity::class.java).create(saved)
        broker = ForegroundCapabilityBroker.attach(second.get())
        second.start().resume()
        val picked = Uri.parse("content://io.toolbox.test/picked")
        second.get().activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(picked))

        assertTrue(pending.isCompleted)
        assertEquals(picked, runBlocking { pending.await() })
    }

    @Test
    @Config(sdk = [34])
    fun cancellingPhotoPickerReturnsNullAndReleasesThePendingRequest() {
        val controller = resumedActivityWithBroker()
        val pending = openPicker(listOf("image/*"))
        val launch = shadowOf(controller.get()).nextStartedActivityForResult
        controller.get().activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null)
        awaitCall()

        assertTrue(pending.isCompleted)
        assertEquals(null, runBlocking { pending.await() })
        assertEquals(null, PendingForegroundRequests.open)
        assertFalse(PendingForegroundRequests.mutex.isLocked)
    }

    @Test
    @Config(sdk = [34])
    fun explicitImageFormatsKeepTheirDocumentPickerFilter() {
        val controller = resumedActivityWithBroker()
        openPicker(listOf("image/png", "image/jpeg", "image/webp"))
        val intent = shadowOf(controller.get()).nextStartedActivityForResult.intent

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(listOf("image/png", "image/jpeg", "image/webp"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
    }

    @Test
    fun closingTheActivityForGoodEndsThePendingRequest() {
        resumedActivityWithBroker()
        val pending = openPicker()

        broker!!.close(keepPendingRequests = false)
        awaitCall()

        assertTrue(pending.isCancelled)
        assertFalse(PendingForegroundRequests.mutex.isLocked)
    }

    @Test
    fun pinnedShortcutUsesTheManifestShortNameAsItsShortLabel() {
        assertEquals(
            ForegroundCapabilityBroker.ShortcutLabels("构建守望", "GitHub Actions 构建守望"),
            ForegroundCapabilityBroker.shortcutLabels(null, "构建守望", "GitHub Actions 构建守望"),
        )
        assertEquals(
            ForegroundCapabilityBroker.ShortcutLabels("Demo", "Demo"),
            ForegroundCapabilityBroker.shortcutLabels(null, null, "Demo"),
        )
        assertEquals(
            ForegroundCapabilityBroker.ShortcutLabels("Mine", "Mine"),
            ForegroundCapabilityBroker.shortcutLabels("Mine", "D", "Demo"),
        )
    }

    private fun resumedActivityWithBroker(): ActivityController<ComponentActivity> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        // Launchers are registered before the activity starts, as in MainActivity.onCreate.
        broker = ForegroundCapabilityBroker.attach(controller.get())
        return controller.start().resume()
    }

    private fun openPicker(mimeTypes: List<String> = listOf("text/plain")): CompletableDeferred<Uri?> {
        val files = ForegroundCapabilityBroker.activeHandlers(app, "demo.tool", "Demo", requireForegroundRuntime = {}).files!!
        call = scope.launch { runCatching { files.open(mimeTypes) } }
        return checkNotNull(PendingForegroundRequests.open) { "The picker did not start" }
    }

    private fun awaitCall() {
        val deadline = System.currentTimeMillis() + 5_000
        while (call?.isCompleted == false && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
