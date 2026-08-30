# Host performance baseline protocol

> Status: awaiting physical-device evidence. This file intentionally contains
> no performance numbers. Do not replace the placeholders with emulator output
> or inferred source-code conclusions.

## Capture contract

Use a debug build installed on the physical Android device. Create a separate
output directory for every capture; it is intentionally outside source control
by default because `gfxinfo`, `meminfo`, logcat and Perfetto can be device
specific.

```bash
SERIAL=<adb-serial> PACKAGE=io.toolbox.host FLOW=A \
  OUT_DIR=artifacts/perf/2026-08-29-flow-a \
  scripts/perf/capture-host.sh
```

Set `PERFETTO=1` when a ten-second trace is needed during a repeated action.
The script neither changes global animation scales nor clears application data.
It writes the supplied serial nowhere: `capture-summary.txt` deliberately has
no serial field. It also captures only package-associated system error/activity
log lines, not app console output or user-content bodies.

Required per-run artifacts:

- `device-info.txt` (model/system fields plus a hash of the build fingerprint),
- `webview-provider.txt`, `gfxinfo-framestats.txt`, `meminfo.txt`,
  `logcat-host-events.txt`, `flow-instructions.txt`, `capture-status.txt`, and
  `capture-summary.txt`,
- `perfetto.pftrace` only when `PERFETTO=1` succeeded.

Record the artifact directory and measurements below. Never commit a trace or
database export that could include personal data without a separate review.

## Device context to record

| Field | Baseline value |
|---|---|
| Run date/time (local) | `PENDING` |
| App commit / APK version | `PENDING` |
| Device model / Android release / SDK | `PENDING: see device-info.txt` |
| Refresh rate and navigation mode | `PENDING: record manually` |
| WebView provider/version | `PENDING: see webview-provider.txt` |
| Test data source | `PENDING: existing user catalog or isolated debug fixture` |

## Flow A — cold start

- **Reason:** The V2 plan moves orphan-profile maintenance off the visible
  startup path. This measures whether the first usable host screen is reached
  without waiting on nonessential cleanup.
- **Method:** From a stopped app, run `am force-stop io.toolbox.host`, launch
  `MainActivity`, and time until the first real Home item (or its truthful empty
  state) accepts input. Perform 10 runs; report runs 1–2 as warm-up only and
  calculate median/p95 over runs 3–10. Capture after the final measured run
  with `FLOW=A`.
- **Expected result:** A reproducible distribution is recorded with no crash or
  startup error. Final comparison target is median cold start ≤900 ms or at
  least 30% better than this physical-device baseline; this is not yet a pass.

| Metric | Value |
|---|---|
| Artifact directory | `PENDING` |
| Runs 1–2 (warm-up, ms) | `PENDING` |
| Runs 3–10 (ms) | `PENDING` |
| Median / p95 (ms) | `PENDING` |
| Jank / notable trace finding | `PENDING` |

## Flow B — top-level tab switching

- **Reason:** Home → Tools → Settings → Home formerly rebuilt navigation state
  and could contribute visible stalls; compact chrome must not mask a state
  loss or frame regression.
- **Method:** Start at a stable Home screen, perform that four-step sequence for
  20 rounds, and time tap-to-stable-first-frame for each transition. Capture
  `FLOW=B` after the final round; use Perfetto during one representative round
  if a stall is visible.
- **Expected result:** Every route remains usable and the recorded p95 is
  available for comparison. Final target is p95 ≤120 ms; this baseline makes no
  claim that the target already passes.

| Metric | Value |
|---|---|
| Artifact directory | `PENDING` |
| 20-round transition timings | `PENDING` |
| p95 (ms) | `PENDING` |
| State/scroll preservation observation | `PENDING` |

## Flow C — catalog scrolling

- **Reason:** Catalog projection replaces per-tool version observation, so the
  measured concern is scroll frame stability with realistic list sizes.
- **Method:** Use an isolated debug fixture database, never the user’s real
  catalog, with 20, 50 and 100 items. For each size scroll top→bottom and
  bottom→top five times, then capture `FLOW=C` for that size (one directory per
  size). Inspect `gfxinfo-framestats.txt`; use Perfetto only to diagnose a
  repeatable jank cluster.
- **Expected result:** Artifact sets identify the fixture size and direction
  without leaking tool data. Final target is janky frames ≤3% and no frame
  above 50 ms; numbers are pending.

| Fixture / direction | Artifact directory | Janky frames | Worst frame | Notes |
|---|---|---:|---:|---|
| 20 / down+up | `PENDING` | `PENDING` | `PENDING` | `PENDING` |
| 50 / down+up | `PENDING` | `PENDING` | `PENDING` | `PENDING` |
| 100 / down+up | `PENDING` | `PENDING` | `PENDING` | `PENDING` |

## Flow D — catalog search

- **Reason:** Search now consumes a precomputed catalog projection; this checks
  input responsiveness and result delivery rather than merely rendering a
  static list.
- **Method:** In Tools, enter `仓`, `仓位`, `position`, then
  `不存在的关键词`; record keypress-to-visible-result timing for each query.
  Repeat enough times to compute p95 and capture `FLOW=D` after the final
  query. Do not put query text into a log artifact; the manual table below is
  sufficient.
- **Expected result:** Each query yields a truthful result/empty state without a
  crash. Final target after the configured debounce is p95 ≤100 ms; baseline
  values remain pending.

| Query class | Artifact directory | Timing samples / p95 | Notes |
|---|---|---|---|
| CJK prefix | `PENDING` | `PENDING` | `PENDING` |
| Latin prefix | `PENDING` | `PENDING` | `PENDING` |
| no-match | `PENDING` | `PENDING` | `PENDING` |

## Flow E — open and close a tool

- **Reason:** Runtime profile isolation and cleanup must not create a sustained
  WebView/PSS leak or leave an active WebView after the user returns.
- **Method:** Install the shipped position-calculator package, open it, wait for
  its first main frame, return, and repeat 10 times. Capture `FLOW=E` before
  the first open, after the tenth return, and once while the WebView is open
  (three distinct output directories). Compare `meminfo.txt`; inspect the
  runtime outcome and cleanup state without copying web content.
- **Expected result:** The tool opens and returns ten times without crash;
  WebView/runtime cleanup remains bounded. Final target is no sustained linear
  PSS growth and a suggested net increase ≤10 MB; this is not measured yet.

| State | Artifact directory | PSS / runtime observation | Notes |
|---|---|---|---|
| Before first open | `PENDING` | `PENDING` | `PENDING` |
| During an open tool | `PENDING` | `PENDING` | `PENDING` |
| After tenth return | `PENDING` | `PENDING` | `PENDING` |

## Baseline conclusion

`PENDING_PHYSICAL_DEVICE_CAPTURE`. No claim about smoothness, startup speed or
memory behavior may cite this file until all relevant placeholder rows contain
the generated artifact directory and measured values.
