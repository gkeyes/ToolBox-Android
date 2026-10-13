package io.toolbox.host.browser

import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewRenderProcess
import androidx.compose.runtime.*

/**
 * Browser UI state for one [BrowserActivity] instance.
 *
 * This is deliberately a plain state holder owned by the Activity, not an androidx ViewModel. The WebView is
 * created with the Activity context and, on every Activity recreation, is destroyed in onDestroy and rebuilt from
 * onSaveInstanceState. A ViewModel would outlive the Activity: it would either retain (and leak) that WebView, or
 * carry open menus, sheets, errors and progress across recreation, which the browser does not do today.
 */
internal class BrowserUiState {
    var webView by mutableStateOf<WebView?>(null)
    var address by mutableStateOf("")
    var title by mutableStateOf("")
    var loadProgress by mutableIntStateOf(0)
    var interaction by mutableStateOf(BrowserInteractionState())
    var resumed by mutableStateOf(false)
    var unresponsiveRenderer: WebViewRenderProcess? = null
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var menu by mutableStateOf(false)
    var mediaDiagnosticsSheet by mutableStateOf(false)
    var mediaDiagnosticsRunning by mutableStateOf(false)
    var mediaDiagnosticsReport by mutableStateOf("尚未运行媒体诊断。")
    var mediaDiagnosticsCapture = false
    var mediaDiagnosticsEpoch = 0
    val mediaDiagnosticEvents = ArrayDeque<String>()
    var userAgentSheet by mutableStateOf(false)
    var userAgentMode by mutableStateOf(BrowserUserAgentMode.ChromeMobile)
    var clearConfirmation by mutableStateOf(false)
    var fullAddress by mutableStateOf(false)
    var clearing by mutableStateOf(false)
    var fullScreenView by mutableStateOf<View?>(null)
    var fullScreenCallback: WebChromeClient.CustomViewCallback? = null

    /** Scroll-driven show/hide of the toolbars; owns the `hidden` flag. */
    val chrome = BrowserChromeGesture()
}
