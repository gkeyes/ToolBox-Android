# Host performance capture protocol

Usage and interpretation for `scripts/perf/capture-host.sh` and
`scripts/perf/summarize-trace.py`. This document does not record a current baseline
or measured improvement. Keep exact A/B commit SHAs, APK hashes, build receipts
and results with each authorized comparison; old delivery records remain in Git
and the corresponding CI artifacts.

Automated screenshot tests and their CI gate are retired; receipts report
`HOST_SCREENSHOT_VALIDATION=REMOVED_BY_USER_REQUEST`, never PASS. IDE-only debug
previews and capture-contract tests are not device performance evidence.

## Environment and inputs

Use a dedicated test device with synthetic fixtures and an operator-reviewed
semantic replay for the selected scene. The wrapper does not install, uninstall,
clear data, force-stop or change device settings. Device setup and replay must
stay within the operator's authorization.

Compare optimized, non-debuggable `candidate` builds with identical profiling,
trace labels, R8 and ART configuration. Verify the merged/APK manifest is
shell-profileable and release remains unchanged. The wrapper checks the local
and installed single-base APK SHA and installed profiling/debug flags; it rejects
split APKs. Debug measurements cannot stand in for optimized frame times.

Prerequisites: Bash, Python 3 (standard library), `timeout`, `sha256sum`, ADB,
a connected dedicated device, a current `trace_processor_shell`, the exact
installed APK and the reviewed replay. The repository does not supply a generic
device-specific replay or a prepopulated fixture installation.

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
| `tool.recordOpened` | Recent-open persistence; runs after queuing navigation and includes statistics serialization wait. Not click-to-shell or a readiness metric; completion never triggers another navigation |
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

## A–F protocol

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

Search is a supplementary trace in B/D with deterministic fixture-only queries.
Memory before/after dumps alone do **not** prove object release or no leaks.

## Proposed targets for an authorized comparison

Freeze targets against a valid baseline before comparing; these are not CI gates.
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
