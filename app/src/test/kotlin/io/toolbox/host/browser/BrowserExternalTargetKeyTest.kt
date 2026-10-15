package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserExternalTargetKeyTest {
    @Test fun declinesAreRememberedPerAppNotPerUrl() {
        // Changing the query or host keeps the same key, so 取消 is not asked again.
        assertEquals("scheme:weixin", externalTargetKey("weixin", null))
        assertEquals("scheme:weixin", externalTargetKey("WEIXIN", null))
        assertEquals("package:com.eg.android.AlipayGphone", externalTargetKey("alipays", "com.eg.android.AlipayGphone"))
        assertEquals("scheme:mailto", externalTargetKey("mailto", " "))
    }
}
