package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserExternalTargetKeyTest {
    @Test fun declinesAreRememberedPerAppNotPerUrl() {
        // Query changes such as a timestamp keep the same key, so 取消 is not asked again.
        assertEquals("weixin://dl", externalTargetKey("weixin", "dl", null))
        assertEquals("weixin://dl", externalTargetKey("WEIXIN", "DL", null))
        assertEquals("package:com.eg.android.AlipayGphone", externalTargetKey("alipays", "platformapi", "com.eg.android.AlipayGphone"))
        assertEquals("mailto://", externalTargetKey("mailto", null, " "))
    }
}
