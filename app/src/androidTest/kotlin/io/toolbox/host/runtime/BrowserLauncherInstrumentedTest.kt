package io.toolbox.host.runtime

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserLauncherInstrumentedTest {
    @Test
    fun androidIntentKeepsTheUrlAndResolvesOnlyThroughTheFixedBrowserSelector() = runBlocking {
        var attempts = 0
        launchBrowserUrl("HTTPS://example.com/article?q=1#body", {}, {}, { intent ->
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://example.com/article?q=1#body", intent.dataString)
            assertNull(intent.component)
            assertNull(intent.`package`)
            assertNull(intent.extras)
            assertNull(intent.clipData)
            assertNull(intent.type)
            assertEquals(0, intent.flags)
            val selector = checkNotNull(intent.selector)
            assertEquals(Intent.ACTION_MAIN, selector.action)
            assertEquals(setOf(Intent.CATEGORY_APP_BROWSER), selector.categories)
            assertNull(selector.data)
            assertNull(selector.component)
            assertNull(selector.`package`)
            assertNull(selector.extras)
            attempts++
        })
        assertEquals(1, attempts) // Completion means only that startActivity returned normally.
    }

    @Test
    fun noBrowserAndSystemRejectionAreTypedAndNeverFallBackToAnUnrestrictedIntent() = runBlocking {
        for ((thrown, expected) in listOf(
            ActivityNotFoundException("No matching browser") to RuntimeRpcErrorCode.UNSUPPORTED,
            SecurityException("Browser disabled") to RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED,
        )) {
            var attempts = 0
            val result = runCatching {
                launchBrowserUrl("https://example.com/", {}, {}, {
                    attempts++
                    throw thrown
                })
            }.exceptionOrNull()
            assertTrue(result is RuntimeHandlerException)
            assertEquals(expected, (result as RuntimeHandlerException).errorCode)
            assertEquals(1, attempts)
        }
    }

    @Test
    fun revocationOrLosingForegroundWhileQueuedPreventsTheAndroidLaunch() = runBlocking {
        for (code in listOf(RuntimeRpcErrorCode.PERMISSION_DENIED, RuntimeRpcErrorCode.INVALID_SESSION)) {
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var authorized = true
            var attempts = 0
            val result = async(Dispatchers.Main) {
                runCatching {
                    launchBrowserUrl(
                        "https://example.com/",
                        beforeLaunch = {
                            entered.complete(Unit)
                            resume.await()
                            if (!authorized) throw RuntimeHandlerException(code, "Authorization changed")
                        },
                        ensureForeground = {},
                        startActivity = { attempts++ },
                    )
                }.exceptionOrNull()
            }
            entered.await()
            authorized = false
            resume.complete(Unit)
            assertEquals(code, (result.await() as RuntimeHandlerException).errorCode)
            assertEquals(0, attempts)
        }
        var attempts = 0
        val result = runCatching {
            launchBrowserUrl("https://example.com/", {}, {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "Activity paused")
            }, { attempts++ })
        }.exceptionOrNull()
        assertEquals(RuntimeRpcErrorCode.SESSION_ENDED, (result as RuntimeHandlerException).errorCode)
        assertEquals(0, attempts)
    }
}
