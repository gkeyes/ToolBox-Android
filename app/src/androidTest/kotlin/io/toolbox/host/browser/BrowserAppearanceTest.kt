package io.toolbox.host.browser

import android.content.Intent
import android.net.Uri
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserAppearanceTest {
    @Test
    fun browserProcessIntentRetainsBothStylesAndEveryAppearanceMode() {
        for (style in ToolBoxThemeStyle.entries) {
            for (mode in ToolBoxThemeMode.entries) {
                for (reduced in listOf(false, true)) {
                    val appearance = BrowserAppearance(mode, reduced, style)
                    val original = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/page"))
                    appearance.writeTo(original)

                    val received = parcelRoundTrip(original)

                    assertEquals(appearance, BrowserAppearance.fromIntent(received))
                    assertEquals(original.action, received.action)
                    assertEquals(original.data, received.data)
                }
            }
        }
    }

    @Test
    fun missingOrUnknownStyleKeepsTheDefaultWithoutLosingOtherAppearanceChoices() {
        assertEquals(BrowserAppearance(), BrowserAppearance.fromIntent(parcelRoundTrip(Intent())))
        val original = Intent().apply {
            putExtra("io.toolbox.host.browser.THEME_MODE", ToolBoxThemeMode.Dark.name)
            putExtra("io.toolbox.host.browser.REDUCE_TRANSPARENCY", true)
            putExtra("io.toolbox.host.browser.THEME_STYLE", "UNKNOWN_STYLE")
        }

        assertEquals(
            BrowserAppearance(ToolBoxThemeMode.Dark, true, ToolBoxThemeStyle.LiquidGlass),
            BrowserAppearance.fromIntent(parcelRoundTrip(original)),
        )
    }

    private fun parcelRoundTrip(intent: Intent): Intent {
        val parcel = Parcel.obtain()
        return try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
