package io.toolbox.host.runtime

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.runtime.RuntimeFileSelection
import io.toolbox.tool.runtime.RuntimeFilesHandler
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    @Config(sdk = [35])
    fun orderedPhotoPickerKeepsTheTapOrderAndUsesSingleReadTokensAfterActivityRecreation() {
        val first = resumedActivityWithBroker()
        val files = foregroundFiles()
        val pending = openImagePicker(files, maxItems = 3)
        val launch = shadowOf(first.get()).nextStartedActivityForResult
        assertEquals(MediaStore.ACTION_PICK_IMAGES, launch.intent.action)
        assertEquals("image/*", launch.intent.type)
        assertEquals(3, launch.intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 0))
        assertTrue(launch.intent.getBooleanExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER, false))

        val saved = Bundle()
        first.pause().saveInstanceState(saved).stop()
        broker!!.close(keepPendingRequests = true)
        first.destroy()
        assertFalse(pending.isCompleted)

        val second = Robolectric.buildActivity(ComponentActivity::class.java).create(saved)
        broker = ForegroundCapabilityBroker.attach(second.get())
        second.start().resume()
        val firstPick = Uri.parse("content://io.toolbox.test/3")
        val secondPick = Uri.parse("content://io.toolbox.test/1")
        val resolver = shadowOf(app.contentResolver)
        resolver.registerInputStream(firstPick, ByteArrayInputStream(byteArrayOf(3)))
        resolver.registerInputStream(secondPick, ByteArrayInputStream(byteArrayOf(1)))
        second.get().activityResultRegistry.dispatchResult(
            launch.requestCode,
            Activity.RESULT_OK,
            multiPick(firstPick, secondPick).setData(secondPick),
        )
        awaitCall()
        assertTrue(pending.isCompleted)

        val selection = checkNotNull(runBlocking { pending.await().getOrThrow() })
        assertTrue(selection.ordered)
        assertEquals(2, selection.files.size)
        assertEquals(listOf(3, 1), runBlocking {
            selection.files.map { file ->
                assertEquals(ToolBoxCapabilityId.FILES_OPEN, files.capabilityFor(file.token))
                files.consume(file.token, 16).single().toInt()
            }
        })
        assertEquals(RuntimeRpcErrorCode.NOT_FOUND, assertThrows(RuntimeHandlerException::class.java) {
            runBlocking { files.consume(selection.files.first().token, 16) }
        }.errorCode)
    }

    @Test
    @Config(sdk = [34])
    fun imagePickerWithOneRemainingSlotCanBeCancelled() {
        val controller = resumedActivityWithBroker()
        val pending = openImagePicker(foregroundFiles(), maxItems = 1)
        val launch = shadowOf(controller.get()).nextStartedActivityForResult
        assertEquals(MediaStore.ACTION_PICK_IMAGES, launch.intent.action)
        assertFalse(launch.intent.hasExtra(MediaStore.EXTRA_PICK_IMAGES_MAX))
        controller.get().activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null)
        awaitCall()

        assertTrue(pending.isCompleted)
        assertEquals(null, runBlocking { pending.await().getOrThrow() })
        assertEquals(null, PendingForegroundRequests.openMultiple)
        assertFalse(PendingForegroundRequests.mutex.isLocked)
    }

    @Test
    fun clipDataOrderWinsAndDuplicateOrNonContentUrisDoNotConsumeSlots() {
        val first = Uri.parse("content://io.toolbox.test/z")
        val second = Uri.parse("content://io.toolbox.test/a")
        val invalid = Uri.parse("file:///test")
        val data = multiPick(first, invalid, second, first).setData(second)

        assertEquals(listOf(first, second), selectedImageUris(data, 2))
        assertEquals(listOf(second), selectedImageUris(Intent().setData(second), 1))
        assertEquals(emptyList<Uri>(), selectedImageUris(Intent().setData(invalid), 1))
        assertEquals(RuntimeRpcErrorCode.QUOTA_EXCEEDED, assertThrows(RuntimeHandlerException::class.java) {
            selectedImageUris(data, 1)
        }.errorCode)
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
        val files = foregroundFiles()
        call = scope.launch { runCatching { files.open(mimeTypes) } }
        return checkNotNull(PendingForegroundRequests.open) { "The picker did not start" }
    }

    private fun foregroundFiles(): RuntimeFilesHandler =
        ForegroundCapabilityBroker.activeHandlers(app, "demo.tool", "Demo", requireForegroundRuntime = {}).files!!

    private fun openImagePicker(files: RuntimeFilesHandler, maxItems: Int): CompletableDeferred<Result<RuntimeFileSelection?>> {
        val pending = CompletableDeferred<Result<RuntimeFileSelection?>>()
        call = scope.launch { pending.complete(runCatching { files.openMultiple(maxItems) }) }
        return pending
    }

    private fun multiPick(first: Uri, vararg others: Uri): Intent = Intent().apply {
        clipData = ClipData.newRawUri("selection", first).also { clips ->
            others.forEach { uri -> clips.addItem(ClipData.Item(uri)) }
        }
    }

    private fun awaitCall() {
        val deadline = System.currentTimeMillis() + 5_000
        while (call?.isCompleted == false && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
