package io.toolbox.host.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateDownloadTest {
    private val asset = ReleaseAsset("sample.apk", "https://example.test/sample.apk", 3, null)
    private val repository = UpdateRepository(RuntimeEnvironment.getApplication(), OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("apk".toResponseBody()).build()
        }.build())

    @Test fun retryCannotOverwriteAFileAlreadyHandedToTheInstaller() = runBlocking {
        val first = repository.download(asset)
        val second = repository.download(asset)
        assertNotEquals(first, second)
        assertEquals("apk", first.readText())
        assertEquals("apk", second.readText())
        assertTrue(second.name.endsWith(".apk"))
    }

    @Test fun interruptedOrTruncatedDownloadLeavesOnlyTheVerifiedFile() {
        val verified = runBlocking { repository.download(asset) }
        val previous = verified.parentFile!!.list()!!.toSet()
        assertThrows(IOException::class.java) {
            runBlocking { repository.download(asset) { throw IOException("interrupted") } }
        }
        assertThrows(StaleAssetException::class.java) {
            runBlocking { repository.download(asset.copy(size = 4)) }
        }
        assertEquals(previous, verified.parentFile!!.list()!!.toSet())
        assertEquals("apk", verified.readText())
    }
}
