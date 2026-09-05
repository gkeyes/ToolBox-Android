# Host performance baseline protocol

> P1 status: **NOT_MEASURED**. The capture entry point and offline contract tests
> are not physical-device evidence. No startup, scrolling, memory or UI improvement
> has been measured. Debug is for diagnosis only; compare optimized, non-debuggable
> builds with the same profiling/instrumentation configuration.

## P0 identity and evidence

The plan reviewed `0eda7dd355cd28353cab30a7cc8c5b4b9546ef98`. Current P0 is
`3b9747601c389694c1a8622fcacd97de7c35d960` on `codex/refactor-lightweight-v2`.
Only the invalid historical-palette control was removed; current screenshot
validation, production UI, dependencies and security boundaries were retained.

- [Android CI 33977011655, attempt 1](https://github.com/gkeyes/ToolBox-Android/actions/runs/33977011655):
  public Actions API reports all three jobs successful: verify `101335381679`,
  optimized candidate `101335381524`, release delivery `101335747293`.
- Release artifact `9972692069` (`toolbox-v0.3.8-release-<full SHA>`), version
  `0.3.8 (12)`: downloaded APK is **4,968,798 bytes**, SHA-256
  `c145211ab39fae232ae1f197693fe8fb9ca5262ddfa93b204fa7079c2ca70d1d`.
  This is the existing release, not a newly built P1 APK or a candidate relabelled
  as release. The receipt reports R8/resource shrinking and screenshot validation
  PASS; device/instrumentation execution and minified runtime remain NOT_RUN.
- Local evidence at continuation: `/workspace/.ci-diagnostics/release-33977011655/`
  and `/workspace/uploads/toolbox-v0.3.8-release-3b97476.apk` (hash independently
  recomputed). Actions artifact digests identify ZIPs, not the APK inside them.
- P1 measurement-preparation commit `1646d7f14608d2dcca0b044b9c105171a37c82f4`
  subsequently passed all three jobs in
  [Android CI 33981424797](https://github.com/gkeyes/ToolBox-Android/actions/runs/33981424797),
  verified through the Actions API. This is P1 build/test evidence, not a device
  performance result or approval of later UI changes. P1 retained the original
  reference images; the separate catalog UI sample requires its own render review.

A for subsequent optimization comparisons must be the **P1 instrumented candidate
before performance/UI changes**, B the individual optimization. Rebuild both
with the same candidate profileable overlay, trace labels, R8 and compilation
mode; the uninstrumented P0 release cannot satisfy the new trace-coverage checks.
Record exact A/B SHAs and APK hashes; retain the P0 identity above for provenance.

## Environment and safety

Use an explicitly dedicated test device/environment, not the user's current
installation, a system clone with unknown contents, or a real database export.
The existing candidate retains `io.toolbox.host` and may have a diagnostic signing
key. This protocol does **not** change applicationId/signing or install, uninstall,
clear data, force-stop, change animation scales, compilation mode or refresh rate.
Any setup/replay action with side effects needs separate operator authorization
on the dedicated device. Never install the candidate over the user's app.

The candidate-only manifest enables shell profiling, leaving release unchanged.
Before measurement, verify the built/merged candidate manifest is profileable,
non-debuggable, R8/resource optimized; verify release did not gain profileability.
Independent merged-manifest/APK profiling verification is still **NOT_RUN**;
the subsequent P1 CI compilation result alone does not establish profiling access.

Prerequisites: Bash, Python 3 (standard library), `timeout`, `sha256sum`, ADB,
a connected dedicated Android device, a current `trace_processor_shell`, the exact
installed single-base APK (splits currently rejected), and an operator-reviewed
semantic replay script. The entry point checks the local/installed APK SHA and
installed profileable/non-debuggable flags before replay.

At this continuation the Alpine runtime has no ADB, JDK, Android SDK or trace
processor. No device was queried or changed. A tested device-specific semantic
replay and deterministic 20/50/100 fixtures have **not** been supplied; these are
explicit prerequisites, not a claim that end-to-end A–F automation already exists.
No benchmark module is introduced just to fill this gap.

## Capture entry point

```bash
SERIAL=<dedicated-adb-serial> \
APK=/absolute/path/app-candidate.apk \
BUILD_COMMIT=<full-instrumented-commit-sha> BUILD_VARIANT=candidate \
TEST_ENVIRONMENT=dedicated-device \
FIXTURE_ID=icons-v1 FIXTURE_COUNT=50 \
REFRESH_HZ=120 COMPILATION_MODE=speed-profile RUN_STATE=warm-icons \
FLOW=B OUT_DIR=artifacts/perf/run-a-b-01 \
TRACE_PROCESSOR=/absolute/path/trace_processor_shell \
CAPTURE_TIMEOUT=90 \
bash scripts/perf/capture-host.sh --replay /absolute/path/scroll-fixture.sh
```

`BUILD_COMMIT`, ART mode and observed refresh rate are operator declarations, not
inferred from APK bytes or max-refresh settings. Record the build receipt, device
navigation mode and controlled setup alongside the run. Never use real tool
names, query text or content in fixture IDs/trace labels. Use the real importer
for labelled PNG/SVG/no-icon fixtures; no hidden release fixture entry point.

Replay contract:

1. Verify the expected initial screen and fixture state with semantic selectors.
   No guessed fixed-coordinate taps. For repeatable scrolling, resolve the
   verified list bounds and apply the same trajectory; fail if bounds/state differ.
2. Execute only the requested A–F scene. Verify final screen/actions and exit
   nonzero on missing selectors, lost focus, assertion failure or interruption.
   A script that just returns 0 is **not** an acceptable replay.
3. Inherit `SERIAL`, `PACKAGE`, `ADB`, `FLOW`. The capture wrapper runs the script
   with a finite timeout and discards stdout/stderr to avoid saving private UI or
   console content. Never spawn unattended child/device test jobs; replay must
   clean up any jobs it owns on cancellation.
4. Keep recording/inspector disabled during performance samples. Take visual
   videos/screenshots in separate runs. The wrapper never captures logcat,
   clipboard, web bodies or screenshots, and never uploads artifacts.

Capture sequence: identity/thermal/memory metadata → supported atrace categories
and target `atrace_apps` → Perfetto `--background-wait` readiness acknowledgement
→ scenario gfxinfo reset → monotonic start → replay → monotonic end → gfxinfo,
memory and thermal snapshots → graceful trace stop/flush → pull → SQL validation.
Host monotonic timestamps bound replay orchestration only: **do not subtract them
from device trace timestamps**. Use trace timestamps for spans on the device.

Each new output directory contains `capture-summary.txt`, `capture-status.txt`,
`webview-provider.txt`, `refresh-settings.txt`, `thermal-before/after.txt`,
`meminfo-before/after.txt`, and `gfxinfo-framestats.txt`. With default `PERFETTO=1`,
also expect `perfetto-config.pbtxt`, `perfetto.pftrace`, `business-slices.sql`,
`business-slices.csv`, `trace-summary.json`. Keep everything local under ignored
`artifacts/perf/`; system traces/dumps can still reveal device activity and must
be reviewed before sharing. The serial is used for transport but not recorded.

Status is append-only; **the last `status=` value is authoritative**, together
with process exit code. Nonzero exit or final INVALID invalidates the entire run,
even if an earlier summary/file looks successful. A nonempty trace is insufficient:
SQL requires complete allowlisted business slices from the exact package or its
`:process` children, plus scene-specific labels, and rejects reported trace loss
or parser errors. Missing/unsupported data is unavailable, never zero.

`CAPTURED_NOT_EVALUATED` means capture/coverage checks passed, **not** that actions,
input readiness, performance thresholds or visuals passed. Review the semantic
replay and raw trace against the scene. `PERFETTO=0` is explicitly
`SNAPSHOT_ONLY_NOT_MEASURED`, never a substitute for a frame/latency comparison.
Errors/timeouts/lost transport must be rerun into a new directory, not patched
into PASS. No test auto-updates screenshots or suppresses critical failures.

## Trace interpretation

Reuse `HostTrace`: synchronous non-suspending blocks stay on one thread; suspending
operations use unique async cookies with `finally` pairing on success, exception
and cancellation. Labels contain no tool identifiers or content. Instrumentation
does not move IO/WebView work, alter caches, navigation or background semantics.

| Label | Meaning / limitations |
|---|---|
| `coreData.create` | Existing bootstrap dependency acquisition; may include cached acquisition, not all startup work or TTFD |
| `host.catalog.publish` | Catalog mapping/publication, not proof the screen is drawn/interactive |
| `tool.recordOpened` | Awaited recent-open persistence; success/error branch remains unchanged |
| `icon.catalog.lookup/recheck` | Initial catalog lookup and post-decode version recheck, including suspension |
| `icon.cache.hit/miss/evict` | Count markers, not user-action durations; only actual LRU eviction counts as evict |
| `icon.decode` | Read/decode inside the granted decode slot; not queue/lock wait |
| `tool.shell.enter` | Runtime shell's frame wait/entry animation; not tap-to-shell latency |
| `tool.prepare` | Runtime catalog read + preparation, not permit acquisition/WebView readiness |
| `runtime.attach` | Foreground-open processing including existing-runtime path, not a visual/input guarantee |
| `runtime.detach` / `runtime.release` | Foreground detach count / actual host WebView release respectively; a background detach need not release |
| `nav.enter/return` | Retained secondary-page transition spans; not primary Tab latency or tap feedback |
| `webView.create` / `webView.firstMainFrame` | Existing runtime labels; firstMainFrame ends on callback/error/release paths, **not necessarily a successful visual frame** |

The summarizer exports only fixed names, IDs, timestamps and durations. It retains
all complete slices and reports count/total/P50/P90/max using **nearest rank**
(`ceil(p*n)`, 1-based). Durations include suspension, can nest/overlap, and cannot
be added to infer CPU time. Small-sample quantiles are descriptive, not stable
performance estimates. Counts are not necessarily gestures/successful opens.
TTID/TTFD, input readiness and FrameTimeline metrics are **NOT_DERIVED** here;
they still require validated device measurement, not renamed page callbacks.

## A–F protocol (replaces the old flow lettering)

Use the same device, resolution, observed refresh rate, WebView provider/version,
data, build optimization and ART mode for A/B. Alternate runs and record thermal
state before/after. Retain all samples with invalid exclusions and reasons;
separate install/provider first-start and warm-up instead of silently dropping
slow results. Do not mix Debug recomposition counts with optimized frame times.

| Flow | Replay / sample requirement | Measurements and behavior |
|---|---|---|
| A — startup | Stopped process → launch; ≥20 valid samples; provider/installation first-start separately | TTID, real catalog/empty-state TTFD, icons, P50/P90; no dark-start flash; current script does not derive TTID/TTFD |
| B — list | 20/50/100 fixtures; ≥5 equal down/up trajectories each; cold/warm icons separate | FrameTimeline jank/overrun, P95/P99, lookup/hit/decode/eviction and GC |
| C — tool open | First open, repeated ordinary open, legal background reentry separately | Tap feedback/shell, prepare/permit/WebView, visual frame, deterministic test-input response |
| D — navigation | Tools↔Settings and tools→detail→permission→return; 20 rounds | Tap feedback, target first frame, animation completion separately; query/scroll/focus/permissions preserved |
| E — mixed | Real background demo + notification updates while doing B/C | UI frame deadlines, RPC queue, main thread, independent notification correctness |
| F — lifecycle | Ordinary open/close 30 times; background reentry separately; recreate Activity; separate process recovery | PSS/Java/native/graphics trend, WebView/Activity/Job/VM counts, no illegal reuse or stopped background work |

Search is a supplementary trace in B/D with deterministic fixture-only queries;
no unconditional debounce, pagination or profile generation is justified yet.
Memory before/after dumps alone do **not** prove object release or no leaks.

## Initial acceptance targets (freeze after valid baseline)

Correctness comes first: no crash/ANR, navigation/query/scroll/grant loss, or stopped
legal background sessions. Initial tap-feedback target P95≤100ms is not the
160/180ms transition duration. List targets: FrameTimeline jank≤3% and
frameOverrunMs P95≤0, also inspect P99/repeated long frames. At 120Hz a nominal
frame is 8.33ms, but use the actual presentation deadline, not CPU time alone.

For clearly slow paths aim for ≥20% P50/P90 improvement; already-fast paths may
remain unchanged within noise. Investigate sustained regressions around >5%; do
not trade one core path for another or change statistics to manufacture gains.
Ordinary sessions must release bounded objects without linear memory growth;
legal retained-background memory is accounted separately. Unsupported metrics
are NOT_MEASURED, not zero. Record sample count and quantile method for every P95.

## Investigation order — hypotheses, not measured bottleneck ranking

| Item | Current source evidence | Next measurement / possible change / risk |
|---|---|---|
| F1 | `catalog/CatalogViewModel.kt:openInstalled` still awaits recordOpened before navigation | C span vs tap/shell; if material, decouple statistics only; preserve launch validation, duplicate-open and failure semantics |
| F2 | `icons/ToolIconLoader.kt:load` queries before cache.get; 4MiB/256px, two decode slots | B lookup/hit/evict/decode plus allocations; use full immutable version key only with safe invalidation; no blind capacity increase |
| F5 | `navigation/ToolBoxNavigation.kt` enables runtime after entry animation; `runtime/RuntimeSessionManager.kt:ensureRuntime` then prepares | C shell/prepare/create gaps; evaluate safe IO overlap, not off-main WebView or another pool; cancellation/update/permit risks |
| F4 | Permission VM uses passed Activity store; retained route composition survives hiding | D/F offscreen collectors, object counts and updates; route ownership only for UI, never suspend authoritative grants/background sessions |

F1/F2 first, F5 next and F4 lifecycle review are a **source-led investigation
order**, not timing evidence. Raw trace ranges, measured cost and attribution are
NOT_MEASURED for all four. No performance implementation or UI redesign is
included in P1. If device access remains blocked, a later low-risk sample could
remove the always-visible saved hint and redundant subtitles, with unchanged
behavior and real before/after screenshots; that UI work has not started.

## Results / remaining gates

| Scene / metric | A | B | Samples / variation | Change | Conclusion |
|---|---|---|---|---|---|
| Host usable P50/P90 | — | — | — | — | NOT_MEASURED |
| Warm-icon jank / P95 overrun | — | — | — | — | NOT_MEASURED |
| Tool interactive P50/P90 | — | — | — | — | NOT_MEASURED |
| Return / Tab switching | — | — | — | — | NOT_MEASURED |
| Repeated open/close objects / PSS | — | — | — | — | NOT_MEASURED |

P1 Android CI build/test is now successful (run above). Still required: independent
merged-manifest/APK profiling verification; same-config comparison APKs; dedicated
device/provider/thermal context; real fixtures
and semantic replay; A–F captures with verified trace coverage; TTID/TTFD/input
and FrameTimeline extraction; object lifecycle analysis; native screenshots and
separate visual video. Offline capture tests protect failure/status/filtering
contracts only, never substitute for these gates. See `TESTING.md` for commands
and their actual PASS/FAIL/NOT_RUN status.
