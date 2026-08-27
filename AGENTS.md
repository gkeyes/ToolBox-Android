# AGENTS.md — ToolBox Android

## Mission

Build an Android host app that imports `.tbx` ZIP packages containing HTML/CSS/JavaScript, reviews their permissions and risk, installs them atomically, and runs them in a hardened WebView with a capability-gated ToolBox JS API.

## Read first

1. `docs/ToolBox_Android_技术方案.md`
2. `design/host_ui_light.png`
3. `schema/manifest.schema.json`
4. `sdk/toolbox-api.d.ts`
5. `references/component_mapping.md`
6. `TESTING.md`

## Non-negotiable security invariants

- Never use `addJavascriptInterface` for imported content.
- Never load mini-app files with `file://` or run a localhost/Ktor server.
- Use a unique exact HTTPS origin for every tool through `WebViewAssetLoader`.
- Use `WebViewCompat.addWebMessageListener`; validate exact `sourceOrigin`, `isMainFrame`, session nonce, manifest declaration, ToolBox grant, Android permission, user gesture, rate limit and quota.
- Disable file/content access, universal file URL access, mixed content, popup windows and arbitrary navigation.
- Remote networking is off by default. All network access goes through a native HTTPS proxy with manifest domain allowlists, redirect revalidation and SSRF/private-address blocking.
- Do not request `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, accessibility, SMS, contacts or root privileges.
- Installation is transactional and rollback-safe. Reject Zip Slip, zip bombs, path collisions, symlinks, nested archives and native/dynamic-code payloads.
- Invalid signatures are blocking. Unsigned tools use strict policy and cannot silently persist high-risk grants.
- Do not log clipboard contents, file contents, secure values or HTTP bodies.
- A secondary process is crash/memory isolation only; never describe it as a separate UID sandbox.

## UI rules

- Host UI is native Compose. Mini-app internal UI is HTML and is outside the host design system.
- Use Miuix as the primary design system; wrap it behind ToolBox components.
- HyperX Compose is optional and must be pinned as source/submodule; never track its moving `main` in production.
- Implement the light design board first. Preserve semantic colors, spacing and information hierarchy rather than drawing phone frames.
- Support font scaling, TalkBack, 48 dp touch targets and adaptive layouts.

## Delivery order

1. Scaffold modules, theme, navigation and static host screens.
2. Implement `.tbx` inspect/install/uninstall and the import-review UI.
3. Implement AssetLoader, unique origin/profile, CSP and hardened navigation.
4. Implement RPC bridge and `ready/ui/storage/haptics` vertical slice.
5. Add permissions, audit, clipboard/share/files/network/notification/shortcut APIs.
6. Add integrity/signature, update/rollback/export and hardening tests.

## Definition of done for each change

- Code compiles with warnings reviewed.
- Unit/instrumentation/UI tests relevant to the change pass.
- No main-thread file, zip, hash, database or network work.
- Errors are typed and user-visible messages are actionable.
- Security-sensitive branch has a test.
- Every retained or new automated test is admitted in `TESTING.md` with its reason, method and expected result; prefer the smallest non-duplicated test at the lowest faithful layer.
- Documentation and TypeScript API declaration stay in sync.
