# 外部组件与版本说明

- Miuix repository: `https://github.com/compose-miuix-ui/miuix`
- Miuix docs: `https://compose-miuix-ui.github.io/miuix/`
- HyperX Compose source: `https://github.com/HowieHChen/hyperx-compose`
- AndroidX WebKit release notes: `https://developer.android.com/jetpack/androidx/releases/webkit`
- Android WebViewAssetLoader: `https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader`
- Android native API JavaScript bridge guide: `https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge`

Package baseline prepared on 2026-08-26:

- Miuix: 0.9.2 in this proposal; treat as experimental and pin it.
- HyperX Compose: source-only/pinned-commit strategy; its current build uses minSdk 33, compileSdk 37, JVM 21 and Miuix 0.9.0.
- AndroidX WebKit: 1.17.0.

Before a production release, rerun dependency verification and lock hashes/versions. Never use dynamic `+` versions.
