package io.toolbox.host.browser

internal enum class BrowserUserAgentMode(
    val storageValue: String,
    val shortLabel: String,
) {
    ChromeMobile("chrome_mobile", "Chrome Mobile"),
    AndroidWebView("android_webview", "WebView"),
    DesktopChrome("desktop_chrome", "Desktop");

    companion object {
        fun fromStored(value: String?): BrowserUserAgentMode =
            entries.firstOrNull { it.storageValue == value } ?: ChromeMobile
    }
}

internal data class BrowserUserAgentProfile(
    val userAgent: String,
    val mobile: Boolean,
    val platform: String,
    val chromeVersion: String?,
    val overrideMetadata: Boolean,
)

internal object BrowserUserAgentPolicy {
    private val chromeVersionPattern = Regex("""Chrome/([^\s]+)""")
    private val webViewTokenPattern = Regex(""";\s*wv(?=\))""", RegexOption.IGNORE_CASE)
    private val legacyVersionTokenPattern = Regex("""\s+Version/4\.0(?=\s)""", RegexOption.IGNORE_CASE)
    private val duplicateWhitespacePattern = Regex("""\s{2,}""")

    fun profile(mode: BrowserUserAgentMode, defaultUserAgent: String): BrowserUserAgentProfile {
        val chromeVersion = chromeVersion(defaultUserAgent)
        return when (mode) {
            BrowserUserAgentMode.ChromeMobile -> BrowserUserAgentProfile(
                userAgent = chromeMobile(defaultUserAgent),
                mobile = true,
                platform = "Android",
                chromeVersion = chromeVersion,
                overrideMetadata = true,
            )
            BrowserUserAgentMode.AndroidWebView -> BrowserUserAgentProfile(
                userAgent = defaultUserAgent,
                mobile = true,
                platform = "Android",
                chromeVersion = chromeVersion,
                overrideMetadata = false,
            )
            BrowserUserAgentMode.DesktopChrome -> BrowserUserAgentProfile(
                userAgent = desktopChrome(chromeVersion),
                mobile = false,
                platform = "Linux",
                chromeVersion = chromeVersion,
                overrideMetadata = true,
            )
        }
    }

    fun chromeMobile(defaultUserAgent: String): String =
        defaultUserAgent
            .replace(webViewTokenPattern, "")
            .replace(legacyVersionTokenPattern, "")
            .replace(duplicateWhitespacePattern, " ")
            .trim()

    fun desktopChrome(chromeVersion: String?): String {
        val version = chromeVersion?.takeIf { it.isNotBlank() } ?: "125.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$version Safari/537.36"
    }

    fun chromeVersion(userAgent: String): String? =
        chromeVersionPattern.find(userAgent)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
}
