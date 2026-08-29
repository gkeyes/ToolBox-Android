# V2 Phase 0 current-state inventory

> Snapshot: `2026-08-29` on `codex/v2-phase0-4` at
> `5d5fb62ff4e296a158e2684ed1b3fe681fad1592`. This is an implementation
> inventory, not a claim that every planned API is available to imported tools.

## Baseline relation

- Phase-0 reference tag: `v2-phase0-baseline-20260829`
  (`c0742c46de95d132b4591864dfbc429b9cd84015`, also `main` and
  `origin/main` when inspected).
- Current branch is one commit ahead of that reference and zero commits behind:
  `5d5fb62 feat: optimize V2 host responsiveness`.
- The reference-to-current diff contains 37 tracked files. It covers compact
  host UI/insets, catalog projection, deferred startup maintenance, runtime
  cleanup leasing, their focused tests, and screenshot references. This Phase-0
  documentation does not alter host behavior.
- The only pre-existing untracked file observed while making this inventory was
  `.DS_Store`; it is intentionally not part of this work.

## Modules and dependency direction

| Module | Current responsibility | Key dependencies |
|---|---|---|
| `:app` | Compose host, Navigation 3 routes, import-review, catalog, permissions, settings and runtime shell | `:core-data`, `:core-ui`, `:tool-package`, `:tool-runtime` |
| `:core-ui` | ToolBox theme/tokens and Miuix adaptation components | Compose BOM, Miuix UI/preference/icons/squircle |
| `:core-data` | Room/DataStore models, repositories and catalog transactions | Room, DataStore, coroutines |
| `:tool-package` | `.tbx` inspection, manifest/integrity/signature evidence, resumable inspection and transactional lifecycle | `:core-data`, coroutines |
| `:tool-runtime` | exact-origin asset loading, hardened WebView, profile/state cleanup and runtime preparation | `:core-data`, `:tool-package`, AndroidX WebKit |
| `:tool-api` | Present Gradle module only; no production source or published JS bridge contract | none |

`settings.gradle.kts` includes all six modules. There is no `vendor-hyperx`
module and no dependency injection framework. The JS declaration named by
`AGENTS.md`, `sdk/toolbox-api.d.ts`, is absent. `sdk/PROVENANCE.md` records it
as blocked pending the authoritative file with SHA-256
`7792a14e810d77d2e8c1368fc4cb38e2b4d304d8b4d701bfc082e2ef6dfb4421`; its
absence is a release boundary for native JS capabilities.

## Pinned build baseline

| Item | Pinned value |
|---|---:|
| Android Gradle Plugin | 9.1.1 |
| Kotlin / Compose compiler plugin | 2.4.10 |
| Compile / target / minimum SDK | 37 / 37 / 33 |
| Java and Kotlin JVM target | 21 |
| Compose BOM | 2026.08.00 |
| Miuix | 0.9.2 |
| Room | 2.8.4 |
| DataStore | 1.2.1 |
| AndroidX WebKit | 1.17.0 |
| Navigation 3 | 1.1.6 |

## Implemented host behavior

- Native Compose host provides Home, Tools and Settings top-level destinations,
  plus typed routes for import review, tool detail, permission centre and
  runtime. Compact chrome owns system insets once, keeps the search target at
  48 dp and the bottom visual bar at 56 dp; at 200% font scale its navigation
  items switch to a horizontal icon-label layout instead of enlarging the bar.
- The catalog persists tools, version history, grants, publisher records, audit
  metadata and runtime session metadata. Its Room projection observes each
  tool with its active version through one catalog flow; pinning, categories,
  last-opened state, rollback and confirmed uninstall are implemented through
  repositories/lifecycle code.
- Import uses `ACTION_OPEN_DOCUMENT` for `.tbx`, does not persist the external
  URI, and inspects the one-shot stream in app-private storage. Inspection
  enforces package limits and rejects unsafe paths, links, nested archives,
  native/dynamic-code payloads, manifest/integrity/signature failures and
  blocked risk conditions. The review UI displays findings and creates only the
  initial install grants allowed by the review state. Install, rollback,
  uninstall and recovery have transactional lifecycle results.
- The repository includes one actual example, `examples/position-calculator.tbx`.
  Its internal HTML may feature-detect `ToolBox` APIs, but it does not make
  those APIs available.
- Runtime preparation rechecks the installed manifest, private bundle locator
  and entry before load. The WebView uses a unique HTTPS
  `*.toolbox.invalid` origin through `WebViewAssetLoader`, disallows file and
  content access, mixed content, popup windows, file chooser and WebView
  permission prompts, blocks non-exact navigation, disables cookies, applies
  CSP response headers, and handles renderer loss. Dedicated WebView profiles
  are used when the provider supports the required APIs; otherwise the runtime
  selects an origin-only stateless mode or rejects unsafe cleanup/creation.
- Permission centre reads installed grant records and can revoke one existing
  record. Settings persists only host theme and audit-retention selections.

## Deliberately unimplemented or incomplete capabilities

| Area | Current boundary |
|---|---|
| ToolBox JS API / RPC | No `WebViewCompat.addWebMessageListener`, document-start shim, session nonce validation or native ToolBox handler exists. The runtime shell correctly labels the native API as unavailable. |
| Runtime grants | There is no runtime capability prompt or execution path. Permission centre cannot add grants; it only revokes stored install-review records. |
| Native capabilities | No storage, secure storage, clipboard, share, files, network proxy, device, haptics, notifications, shortcuts, camera, location or crypto handler is exposed to imported content. |
| Network | Imported pages have `connect-src 'none'`; there is no native HTTPS proxy, domain allowlist executor, redirect revalidation or SSRF implementation. |
| Signature trust | Inspector produces signature evidence, but owner-key onboarding/trusted publisher management UI and personal-mode automation are not implemented. Invalid signatures remain blocking. |
| Settings | DataStore contains additional safety/developer/quota values, but the current Settings screen intentionally exposes only theme and audit retention. No fake controls are shown. |
| Tool management extras | No batch operations, code export, update discovery, desktop shortcut creation, backup/import, developer console or audit-log browser is exposed. |
| Device performance facts | No physical-device A–E numbers are recorded yet. Use `scripts/perf/capture-host.sh` and `docs/performance/BASELINE.md`; do not infer smoothness from source or emulator screenshots. |

## Database schema

- Room database: `io.toolbox.core.data.db.ToolBoxDatabase`, schema version `1`,
  `exportSchema = true`.
- Exported schema: `core-data/schemas/io.toolbox.core.data.db.ToolBoxDatabase/1.json`.
- Tables: `tools`, `tool_versions`, `permission_grants`, `tool_kv`,
  `publishers`, `audit_logs`, `runtime_sessions`.
- Host preferences are stored separately in DataStore. This inventory records
  schema shape only; it does not read, copy or print any user database values,
  tool KV values, audit bodies, clipboard values or installed bundle files.

## Security status retained by this phase

The historical and current branches retain the repository guard against
`addJavascriptInterface`, broad storage/package visibility permissions, and
enabled WebView file/content/universal-file access. This document does not
reclassify the secondary runtime process as a UID sandbox and does not loosen
any security invariant in `AGENTS.md`.
