package io.toolbox.host.runtime

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

        val failure = locationResult(null).exceptionOrNull()
        assertTrue(failure is RuntimeHandlerException)
        assertFalse(failure is CancellationException)
        assertEquals(RuntimeRpcErrorCode.NOT_FOUND, (failure as RuntimeHandlerException).errorCode)
        assertTrue(locationResult(Location("test")).isSuccess)
    }
}
