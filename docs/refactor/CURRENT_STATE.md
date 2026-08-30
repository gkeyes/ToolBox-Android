# ToolBox V2 current state

> Updated 2026-08-31 for `codex/refactor-lightweight-v2`.
> Resolve the exact delivery revision with `git rev-parse HEAD` rather than
> treating this document as a commit marker.

## Product boundary

ToolBox is a lightweight host for `.tbx` HTML/CSS/JavaScript tools. A mini-app
owns its own interface and business logic; the native host supplies a small,
per-tool capability surface where Android integration matters:

- direct import, update and uninstall;
- per-tool capability toggles;
- independently owned foreground/background Web runtimes, timers and notifications;
- system-mediated file, share, camera, location watch and exact-alarm operations.

It is **not** an installation review, risk-scoring, publisher-trust or audit-log
product. Package validation and optional package-local signature verification
run automatically before installation. A successful import appears in the tool
list; an invalid package reports a simple failure and leaves no partial tool.

## Modules and data

| Module | Current responsibility |
|---|---|
| `:app` | Native Miuix/Compose host, direct import, catalog/detail/permission/background/settings/help screens and Android capability adapters. |
| `:core-ui` | ToolBox Miuix adaptation components, theme and layout tokens. |
| `:core-data` | Room v1 repositories for installed tools/current version, grants, regular KV, install transactions, background tasks and task results; DataStore for theme and background master switch. |
| `:tool-package` | One-shot package inspection and transactional install/update/uninstall lifecycle. |
| `:tool-runtime` | Exact-origin `WebViewAssetLoader` runtime, hardened WebView and WebMessage RPC bridge. |
| `:tool-api` | Canonical ToolBox API 1.0 source, generated Kotlin metadata, JS shim and TypeScript declaration check. |

The Room schema intentionally has no migration or compatibility layer. It has
no audit, publisher-trust or runtime-session tables. Existing pre-V2 app data
is not a supported upgrade path.

## Implemented user flow

1. The Tools screen opens a `.tbx` with the system document picker and installs
   it immediately after internal validation.
2. A tool detail screen offers real actions: open, permissions, background
   tasks and delete. The overflow action is a native Miuix menu.
3. Each declared capability is a real per-tool Miuix toggle. A grant never
   overrides the package manifest, Android system permission, origin/session,
   user-gesture, rate or quota checks.
4. The runtime uses a compact return/title/refresh/overflow bar and gives the
   remaining screen to the tool; it has no host bottom navigation or security
   status strip.
5. Settings contains only theme, Background Safeguards, tool permissions and
   offline developer help. Background Safeguards owns the global switch,
   active sessions and real Android/HyperOS settings entry points.

## ToolBox API 1.0

The canonical machine-readable source is
`tool-api/src/main/resources/toolbox-api-v1.json`. The contract task keeps it
in sync with Kotlin descriptors, the WebMessage shim and `sdk/toolbox-api.d.ts`.

The implemented capability families are deliberately limited:

- local and secure storage, device basics, toast, SHA-256, haptics and writing
  the clipboard;
- manifest-allowlisted public HTTPS requests, notifications and compatible native background tasks;
- explicit clipboard read, Android Sharesheet, SAF file open/save, shortcuts,
  system-camera capture, location watch and exact alarms;
- a retained runtime session with restore/timer/location/alarm events and one
  `specialUse` foreground service with a persistent stop notification.

There is no contacts, SMS, accessibility, root, broad filesystem or shell
capability. A retained WebView exists only after the page explicitly starts a
`background.runtime` session; otherwise leaving the runtime still destroys it.

## Package and runtime safety boundary

- Import rejects unsafe paths, path collisions, symbolic links, nested
  archives, compressed-size abuse, native/dynamic-code payloads, invalid
  manifests, bad integrity data and invalid optional signatures.
- Tools run through a unique HTTPS AssetLoader origin. They do not use
  `file://`, a local web server or `addJavascriptInterface`.
- Every WebMessage call validates exact origin, main frame, session nonce,
  installed version, manifest declaration, tool grant, Android permission,
  gesture requirement, rate limit and quota.
- Network is off by default. Once granted, the native proxy permits public
  HTTPS hosts and legal HTTPS ports; an optional manifest allowlist can narrow
  that surface. Every redirect and DNS result is rechecked, private/reserved
  addresses are blocked, and cache, system proxy use and automatic retry remain disabled.
- Background tasks are quota-limited, do not keep a WebView alive, retry
  transient failures at most three times, recover orphaned interrupted runs and
  retain only the most recent task result for seven days.

The authoritative non-negotiable rules remain in `AGENTS.md`.

## Shipped examples and developer help

`scripts/package-examples.sh` produces the three importable API 1.0 examples:

1. `position-calculator.tbx` — persisted inputs, result copy and haptics.
2. `quick-notes.tbx` — create/edit/delete/persist/copy notes.
3. `background-task-demo.tbx` — GitHub HTTP and notification tasks, task list,
   cancellation and latest result.

The in-app offline Developer Help is generated from this API contract and the
examples. It documents package layout, manifest declarations, permissions,
JavaScript calls, packaging, import, background limits and errors.

## Remaining delivery boundary

The 0.3 workflow runs protocol, security, compile and admitted-unit gates, then
uploads only the APK, SHA-256 sums and same-commit receipt. The three unchanged
examples remain embedded in the APK. It does not start an emulator or claim
device validation in the delivery receipt.

Real Android system surfaces remain device-specific: SAF, camera, Sharesheet,
notification delivery, runtime recovery, background location, exact alarms,
HyperOS focus/island enhancement and system bars must be
observed on the intended phone during the final clean installation journey.
