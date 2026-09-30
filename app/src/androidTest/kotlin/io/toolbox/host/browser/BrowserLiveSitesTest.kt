package io.toolbox.host.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Live diagnostics: failures retain the real network/TLS error, never bypass certificate checks. */
@RunWith(AndroidJUnit4::class)
class BrowserLiveSitesTest {
    private lateinit var browser: BrowsingTestPage

    @Before fun setUp() {
        browser = BrowsingTestPage()
    }

    @After fun tearDown() {
        if (::browser.isInitialized) browser.close()
    }

    @Test fun neonDefenseExecutesMenuAndDrawsTheFirstLevel() {
        val original = "https://cpska9691sv5s.space.mcode.cn/"
        browser.load(original)
        browser.await("live game menu built by JavaScript", 45) {
            browser.evaluate("document.querySelector('#sMenu')?.classList.contains('show') === true && document.querySelector('#lvGrid')?.children.length === 10 && !!document.querySelector('#cv')") == "true"
        }
        assertEquals(original, browser.currentUrl())
        browser.tap("#lvGrid > :first-child")
        browser.await("live game canvas actually painted", 30) {
            browser.evaluate("""(() => {
                const c=document.querySelector('#cv');
                if(!c || document.querySelector('#sMenu').classList.contains('show') || document.querySelector('#hud').classList.contains('hide')) return false;
                const pixels=c.getContext('2d').getImageData(0,0,c.width,c.height).data;
                for(let i=3;i<pixels.length;i+=4) if(pixels[i]>0) return true;
                return false;
            })()""") == "true"
        }
        assertEquals(original, browser.currentUrl())
        assertTrue(browser.notifications.toString(), browser.notifications.isEmpty())
    }

    @Test fun newsmthKeepsArticleAddressAndDownloadsItsVerificationImage() {
        val original = "https://www.newsmth.net/nForum/article/AutoWorld/1945318701"
        browser.load(original)
        browser.await("live NewSMTH verification form and downloaded image", 45) {
            browser.evaluate("""(() => {
                const form=document.querySelector('.guest-read-challenge form');
                const image=form?.querySelector('img');
                return !!form && form.method==='post' && form.action===${org.json.JSONObject.quote(original)} &&
                  !!form.querySelector('[name=auth]') && !!image && image.complete && image.naturalWidth>0 && image.naturalHeight>0;
            })()""") == "true"
        }
        assertEquals(original, browser.currentUrl())
        assertTrue(browser.notifications.toString(), browser.notifications.isEmpty())
        // The captcha belongs to the site. Do not solve or submit it from this diagnostic.
    }
}
