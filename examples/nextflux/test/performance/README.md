# Production performance comparison

Run **NextFlux performance comparison** using GitHub Actions `workflow_dispatch`.
The selected workflow ref supplies the candidate and the shared harness; the
`baseline_ref` input defaults to `8d4e9bac9ecc73840e1b8fc66557e35eb5f2bb32`.
The workflow separately installs the locked dependencies and builds each
production `dist`, then serves them at exact loopback origins on ports 4173/4174.
The harness refuses execution outside GitHub Actions.

The same fixture drives both builds. Only the native ToolBox storage/network
boundary is synthetic; the production cache Worker, content pipeline, state
operations and UI run normally. Each scenario has three paired repetitions,
ordered baseline/candidate, candidate/baseline, baseline/candidate. Every pair
uses the same HTML, timestamp, viewport, browser binary and settings.

- Long prose: 720 content paragraphs plus start/end markers.
- Dense code: 32 JavaScript blocks, 40 lines each, with accompanying prose.
- Continuous reads: 24 individual UI actions in a 96-article cache. The separate
  18,518-article storage unit fixture does not establish browser performance at
  that scale.

Reading scenarios measure the first body visit after app/cache readiness, then
a warm reopen in the same context. Both builds select **All** through the UI.
Before each timed visit, the article's context menu performs **Mark as Read**;
the harness requires a successful persisted patch and the menu changing to
**Mark as Unread**, then closes it. Both timed visits therefore begin already
read, and the measured toolbar action returns the article to unread. The raw
preparation phase is retained separately; automatic read-on-open cost is excluded.
The cold/warm definition concerns article/cache/module reuse, not OS disk-cache
purging or an Android application cold start.

Baseline `8d4e9ba` showed an existing issue in
[GitHub run 34321452271](https://github.com/gkeyes/ToolBox-Android/actions/runs/34321452271):
opening unread article 96 completed its native `PUT read` and cache `patchState`,
but the toolbar retained `Read` and `lucide-circle-dot`. The required `Unread`
button assertion failed. That failed run remains evidence of the defect. The
comparison uses identical real UI preparation for both builds; it neither changes
the baseline product nor relaxes the full-text or read-to-unread assertions.

First-readable and complete-body times run from trusted primary pointerdown on
the card to two
animation-frame callbacks after the relevant DOM content is present. They are
paint opportunity proxies, **not FCP or pixel/device evidence**. Every paragraph
and code line must survive, in order. Complete-body time does not require
offscreen syntax highlighting. Scrolls visit 35%, 75%, the end and the top, with
750 ms observation at each location to cover the old 600 ms font debounce; this
observation interval is not a speed threshold.

State-action timing also starts at primary pointerdown, so React Aria's
pointerup-based `onPress` cannot complete work before instrumentation starts.

Raw JSON retains each repetition, phase timestamps, long-task entries and
count/total/maximum, font text-node visits, state acknowledgement times,
`storage.apply`/`storage.getMany` calls, Worker request counts, input hashes,
build commits and runtime environment. Markdown shows paired medians. The state
acknowledgement follows the real cache operation and synthetic native commit;
it does not measure Android Room or bridge transport throughput.

Functional integrity, successful persistence, production Workers, zero errors
and zero external requests are required. Performance numbers have no hard pass
gate. Failed or partial runs remain `FAIL` with partial measurements preserved.
Screenshot validation was removed; screenshots, video and traces are disabled.
