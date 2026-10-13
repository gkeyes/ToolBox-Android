# Browser core

Ordinary browsing uses Android System WebView. Tool runtimes and their capability/network
boundaries are separate and unchanged. A webpage never receives the tool-native RPC bridge.

## User choices

- HTTP(S) navigation retains its original scheme, host and encoding. No host or protocol
  rewriting is performed. Generated `blob:`, `data:` and `about:blank` documents
  remain engine-owned. App links are offered explicitly, with a validated HTTP(S) fallback
  when an Android intent link supplies one.
- Recoverable TLS errors, including cross-origin page resources, are queued for a visible
  continue/cancel choice. WebView may reuse accepted certificate decisions during that
  document session; host navigation, reload, history traversal and disposal clear them.
  Non-recoverable TLS/network failures remain subject to the platform network stack.
- Safe Browsing warnings offer a continue/return choice rather than unconditional rejection.
- Camera, microphone, location and MIDI requests name the requesting website. Android app
  permission alone is not website consent. Grants are scoped to the pending page request;
  no website grant is persisted. Pure HTTPS protected-media playback keeps its prior behavior.
- File inputs open Android's document picker with MIME and single/multiple selection support.
  Only selected, readable external content URIs are returned; capture-directly-to-camera is
  not implemented. HTTP(S) downloads use DownloadManager after confirmation. Generated
  blob/data downloads are not currently transferable to the system downloader.

## Ownership

`BrowserActivity` owns the WebView and delegates decisions to `BrowserSslController`,
`BrowserCapabilityController` and `BrowserNavigationController`. Navigation invalidates stale
requests; delayed Android activity results retain one-shot request identities so they cannot
fulfil another page's request. Only one host decision dialog is shown at a time.

The Activity itself is wiring: UI state lives in `BrowserUiState` (a plain holder, not a ViewModel,
so the WebView is never retained across Activity recreation), `BrowserWebViewFactory` creates the
WebView and installs its clients, and the Compose UI is split into `BrowserScreen`,
`BrowserToolbar` and `BrowserMenus`. Toolbar show/hide on scroll is `BrowserChromeGesture`.

The browser also keeps its own cookie store, UA modes, media layout recovery, fullscreen
player and optional content filtering.

## Verification scope

JVM tests cover URL classification, SSL queue/navigation lifecycle, capability request identity,
origin display, file selection policy and toolbar scroll thresholds. Android tests exercise real WebView network, DOM,
touch, cookie POST, image loading and generated-document navigation. `BrowserLiveSitesTest`
loads real third-party pages and runs only with the `liveSites=true` instrumentation argument.
Passing emulator tests does not establish performance or compatibility on a particular phone.
