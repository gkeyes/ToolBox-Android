package io.toolbox.host.icons

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.toolbox.host.MainActivity
import io.toolbox.host.ui.LocalToolIconLoader
import io.toolbox.host.ui.rememberCatalogToolBitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogToolIconBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun hotBitmapIsPresentInTheFirstCommittedCompositionAndOnReentry() {
        InstalledIconFixture().use { fixture ->
            val loader = ToolIconLoader(fixture.catalog, privateFilesRoot = { fixture.root })
            val hotBitmap = runBlocking { requireNotNull(loader.load(fixture.id, 1)) }
            val visible = mutableStateOf(true)
            val firstBitmaps = mutableListOf<Bitmap?>()
            compose.activity.setContent {
                CompositionLocalProvider(LocalToolIconLoader provides loader) {
                    if (visible.value) {
                        val bitmap = rememberCatalogToolBitmap(fixture.id, 1)
                        val first = remember { AtomicBoolean(true) }
                        SideEffect { if (first.getAndSet(false)) firstBitmaps += bitmap }
                    }
                }
            }
            compose.runOnIdle {
                assertEquals(1, firstBitmaps.size)
                assertSame("The first committed value must already contain the hot bitmap", hotBitmap, firstBitmaps.single())
                visible.value = false
            }
            compose.runOnIdle { visible.value = true }
            compose.runOnIdle {
                assertEquals(2, firstBitmaps.size)
                assertSame("Reentry must not commit a null placeholder", hotBitmap, firstBitmaps.last())
            }
            compose.activity.setContent { }
            compose.waitForIdle()
        }
    }

    @Test
    fun visibleIconRefreshesOnSameVersionReplacementAndUninstall() {
        InstalledIconFixture().use { fixture ->
            val loader = ToolIconLoader(fixture.catalog, privateFilesRoot = { fixture.root })
            val oldBitmap = runBlocking { requireNotNull(loader.load(fixture.id, 1)) }
            val observed = AtomicReference<Bitmap?>()
            compose.activity.setContent {
                CompositionLocalProvider(LocalToolIconLoader provides loader) {
                    val bitmap = rememberCatalogToolBitmap(fixture.id, 1)
                    SideEffect { observed.set(bitmap) }
                }
            }
            compose.runOnIdle { assertSame(oldBitmap, observed.get()) }
            fixture.writeIcon("blue")
            fixture.current.value = fixture.initialTool.copy(currentVersion = fixture.initialTool.currentVersion.copy(
                integrityHash = "replacement", installedAt = 2,
            ))
            runBlocking { loader.invalidate(fixture.id) }
            compose.waitUntil(10_000) { observed.get()?.getPixel(128, 128) == Color.BLUE }
            compose.runOnIdle { assertSame(loader.cached(fixture.id, 1), observed.get()) }
            fixture.current.value = null
            runBlocking { loader.invalidate(fixture.id) }
            compose.waitUntil(10_000) { observed.get() == null }
            compose.activity.setContent { }
            compose.waitForIdle()
        }
    }
}
