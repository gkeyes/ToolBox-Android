package io.toolbox.host.runtime

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeImageShareFormat
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RuntimeImageShareSessionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jVZ0AAAAASUVORK5CYII=",
    )
    private var preparedImage: Pair<File, RuntimeImageShareFormat>? = null
    private val shares = RuntimeImageShareSession(context) { file, type ->
        preparedImage = file to type
    }

    @After fun close() = shares.close()

    @Test fun originalBytesRemainReadableAfterTheRuntimeCloses() {
        val authority = "${context.packageName}.fileprovider"
        val info = checkNotNull(context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA))
        // FileProvider caches paths by authority; other Robolectric tests can have
        // different cache directories under the same authority.
        FileProvider().attachInfo(context, info)
        var actualChooser: Intent? = null
        onMain {
            val sharing = RuntimeImageShareSession(context) { file, type ->
                actualChooser = createImageShareChooser(context, file, type)
            }
            try {
                sharing.begin("first", RuntimeImageShareFormat.PNG, png.size)
                sharing.write("first", 0, png.copyOfRange(0, 17))
                sharing.write("first", 17, png.copyOfRange(17, png.size))
                sharing.finish("first") {}
            } finally {
                sharing.close()
            }
        }

        val chooser = checkNotNull(actualChooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = checkNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        val uri = checkNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("content", uri.scheme)
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertArrayEquals(png, context.contentResolver.openInputStream(uri)?.use { it.readBytes() })
    }

    @Test fun forgedImageAndOtherSessionCannotLaunchChooser() {
        assertError(RuntimeRpcErrorCode.INVALID_MIME) {
            shares.begin("forged", RuntimeImageShareFormat.WEBP, png.size)
            shares.write("forged", 0, png)
            shares.finish("forged") {}
        }
        val other = RuntimeImageShareSession(context) { _, _ -> error("Another session's bytes were shared") }
        onMain {
            shares.begin("mine", RuntimeImageShareFormat.PNG, png.size)
            shares.write("mine", 0, png)
            other.cancel("mine")
        }
        try {
            assertError(RuntimeRpcErrorCode.CANCELLED) { other.finish("mine") {} }
            onMain { shares.finish("mine") {} }
            assertNotNull(preparedImage)
        } finally {
            other.close()
        }
    }

    @Test fun navigationAndLateVisibilityRevocationInvalidateThePendingImage() {
        onMain { shares.begin("old-document", RuntimeImageShareFormat.PNG, png.size) }
        shares.resetForNavigation()
        assertError(RuntimeRpcErrorCode.CANCELLED) { shares.write("old-document", 0, png) }
        assertError(RuntimeRpcErrorCode.SESSION_ENDED) {
            shares.begin("new-document", RuntimeImageShareFormat.PNG, png.size)
            shares.write("new-document", 0, png)
            shares.finish("new-document") {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "Read another article")
            }
        }
        assertEquals(null, preparedImage)
    }

    @Test fun cacheFileLimitRetainsFreshSharesAndReclaimsExpiredImages() {
        val ready = File(context.cacheDir, "toolbox-share-images/ready").apply { mkdirs() }
        val retained = (1..64).map { index -> File(ready, "image-$index.png").apply { writeBytes(png) } }
        assertError(RuntimeRpcErrorCode.QUOTA_EXCEEDED) {
            shares.begin("full", RuntimeImageShareFormat.PNG, png.size)
        }
        assertTrue(retained.all(File::exists))
        val expired = retained.first()
        assertTrue(expired.setLastModified(System.currentTimeMillis() - 48 * 60 * 60 * 1000L))
        onMain {
            shares.begin("space-after-expiration", RuntimeImageShareFormat.PNG, png.size)
            shares.cancel("space-after-expiration")
        }
        assertFalse(expired.exists())
        assertTrue(retained.drop(1).all(File::exists))
    }

    private fun assertError(code: RuntimeRpcErrorCode, block: suspend () -> Unit) {
        val failure = assertThrows(RuntimeHandlerException::class.java) { onMain(block) }
        assertEquals(code, failure.errorCode)
    }

    private fun onMain(block: suspend () -> Unit) {
        var result: Result<Unit>? = null
        val job = CoroutineScope(Dispatchers.Main.immediate).launch { result = runCatching { block() } }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!job.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        if (!job.isCompleted) job.cancel()
        checkNotNull(result) { "Image share did not finish" }.getOrThrow()
    }
}
