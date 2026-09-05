package io.toolbox.host.runtime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ActivityScenario
import io.toolbox.host.MainActivity
import kotlinx.coroutines.runBlocking
import android.location.Location
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.io.File
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M3BrokerLifecycleInstrumentedTest {
    @Test
    fun handlersCreatedBeforeActivityRebindAfterRecreation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handlers = ForegroundCapabilityBroker.activeHandlers(
            instrumentation.targetContext, "io.toolbox.lifecycle.test", "Lifecycle fixture",
        )
        // Block the system chooser: exercise production routing without sending any content.
        val monitor = instrumentation.addMonitor(
            IntentFilter(Intent.ACTION_CHOOSER),
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            true,
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                checkNotNull(handlers.shareText).shareText("Lifecycle fixture")
                scenario.recreate()
                checkNotNull(handlers.shareText).shareText("Lifecycle fixture")
                assertEquals(2, monitor.hits)
            }
            try {
                checkNotNull(handlers.shareText).shareText("Must not be sent")
                error("Closed Activity accepted a foreground operation")
            } catch (failure: RuntimeHandlerException) {
                assertEquals(RuntimeRpcErrorCode.SESSION_ENDED, failure.errorCode)
            }
        } finally {
            handlers.sessionCleanup?.close()
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun sessionCloseDeletesTemporaryHandlesAndNullLocationIsTyped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val temporaryFile = File.createTempFile("m3-session-", ".jpg", context.cacheDir)
            .also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val resources = RuntimeFileSessionResources { files -> files.forEach(File::delete) }
        resources.trackTemporary(temporaryFile)
        resources.register(
            "session-token",
            RuntimeFileHandle(
                uri = Uri.parse("content://io.toolbox.host.fileprovider/toolbox-captures/test.jpg"),
                capability = ToolBoxCapabilityId.CAMERA,
                temporaryFile = temporaryFile,
            ),
        )

        assertEquals(ToolBoxCapabilityId.CAMERA, resources.capabilityFor("session-token"))
        assertEquals(1, resources.handleCount())
        assertEquals(1, resources.temporaryFileCount())
        resources.close()
        assertNull(resources.capabilityFor("session-token"))
        assertEquals(0, resources.handleCount())
        assertEquals(0, resources.temporaryFileCount())
        assertFalse(temporaryFile.exists())
        resources.close() // Idempotent; late picker/camera results cannot repopulate a closed session.
        val lateFile = File.createTempFile("m3-late-", ".jpg", context.cacheDir)
        try {
            resources.trackTemporary(lateFile)
            error("Closed session accepted a late file")
        } catch (failure: RuntimeHandlerException) {
            assertEquals(RuntimeRpcErrorCode.SESSION_ENDED, failure.errorCode)
        }
        assertFalse(lateFile.exists())
        assertEquals(0, resources.temporaryFileCount())

        val failure = locationResult(null).exceptionOrNull()
        assertTrue(failure is RuntimeHandlerException)
        assertFalse(failure is CancellationException)
        assertEquals(RuntimeRpcErrorCode.NOT_FOUND, (failure as RuntimeHandlerException).errorCode)
        assertTrue(locationResult(Location("test")).isSuccess)
    }
}
