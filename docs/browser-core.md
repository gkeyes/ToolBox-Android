# Browser core

The built-in browser is a separate Activity for ordinary web pages, opened through a tool's
browser capability (`BrowserLauncher` → `BrowserActivity`). It uses Android System WebView.
Tool runtimes and their capability/network boundaries are separate and unaffected by it.
Content filtering is described in [Browser filtering](browser-filtering.md).

## Behaviour

- **Navigation.** HTTP(S) navigation keeps its original scheme, host and encoding; no host or
  protocol rewriting is performed. Generated `blob:`, `data:` and `about:blank` documents
  remain engine-owned. App links are offered explicitly, with a validated HTTP(S) fallback
  when an Android intent link supplies one.
- **TLS.** Recoverable TLS errors, including those from cross-origin page resources, are queued
  for a visible continue/cancel choice. WebView may reuse accepted certificate decisions during
  that document session; host navigation, reload, history traversal and disposal clear them.
  Non-recoverable TLS/network failures remain subject to the platform network stack.
- **Safe Browsing.** Warnings offer a continue/return choice rather than unconditional rejection.
- **Website permissions.** Camera, microphone, location and MIDI requests name the requesting
  website. Pure HTTPS protected-media playback keeps its existing behaviour.
- **Files and downloads.** File inputs open Android's document picker with MIME and
  single/multiple selection support; capture-directly-to-camera is not implemented. HTTP(S)
  downloads go to DownloadManager after confirmation. Generated blob/data downloads are not
  currently transferable to the system downloader.
- **Other.** The browser keeps its own cookie store, UA modes, media layout recovery,
  fullscreen player and optional content filtering.

## Key files

All under `app/src/main/kotlin/io/toolbox/host/browser/` unless noted.

| File | Role |
| --- | --- |
| `BrowserActivity` | Owns the WebView; otherwise wiring between the pieces below |
| `BrowserSslController` | TLS error queue and per-document certificate decisions |
| `BrowserCapabilityController` | Website permission requests and file selection |
| `BrowserNavigationController` | Link handling, app links, Safe Browsing choices and downloads |
| `BrowserUiState` | UI state holder (a plain holder, not a ViewModel, so the WebView is never retained across Activity recreation) |
| `BrowserWebViewFactory` | Creates the WebView and installs its clients |
| `BrowserScreen`, `BrowserToolbar`, `BrowserMenus` | Compose UI |
| `BrowserChromeGesture` | Toolbar show/hide on scroll |
| `app/src/main/kotlin/io/toolbox/host/runtime/BrowserLauncher.kt` | Entry point from the tool runtime |

## Invariants

- A webpage never receives the tool-native RPC bridge.
- Android app permission alone is not website consent. Website grants are scoped to the pending
  page request; no website grant is persisted.
- Only selected, readable external content URIs are returned to a file input.
- Navigation invalidates stale requests. Delayed Android activity results carry one-shot request
  identities so they cannot fulfil another page's request.
- Only one host decision dialog is shown at a time.

## Testing

- JVM tests (`app/src/test/kotlin/io/toolbox/host/browser/`) cover URL classification, the SSL
  queue and navigation lifecycle, capability request identity, origin display, file selection
  policy and toolbar scroll thresholds.
- Android tests (`app/src/androidTest/kotlin/io/toolbox/host/browser/`) exercise real WebView
  network, DOM, touch, cookie POST, image loading and generated-document navigation.
- `BrowserLiveSitesTest` loads real third-party pages and runs only with the `liveSites=true`
  instrumentation argument.
- Passing emulator tests does not establish performance or compatibility on a particular phone.
