# 外部组件与版本说明

- Miuix repository: `https://github.com/compose-miuix-ui/miuix`
- Miuix docs: `https://compose-miuix-ui.github.io/miuix/`
- HyperX Compose source: `https://github.com/HowieHChen/hyperx-compose`
- AndroidX WebKit release notes: `https://developer.android.com/jetpack/androidx/releases/webkit`
- Android WebViewAssetLoader: `https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader`
- Android native API JavaScript bridge guide: `https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge`

Package baseline prepared on 2026-08-30:

- Miuix: `0.9.4-rc01`, pinned through the version catalog. The host uses its
  `miuix-nav` navigation stack; do not reintroduce AndroidX Navigation3 as a
  second navigation/animation system.
- HyperX Compose: source-only/pinned-commit strategy; its current build uses minSdk 33, compileSdk 37, JVM 21 and Miuix 0.9.0.
- AndroidX WebKit: 1.17.0.
- WorkManager: `2.11.2`, pinned for native background work only.
- OkHttp: `5.3.0`, pinned for the capability-gated HTTPS proxy only.

Before a production release, rerun dependency verification and lock hashes/versions. Never use dynamic `+` versions.
