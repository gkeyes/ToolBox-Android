#!/usr/bin/env python3
"""Validate target-package trace coverage. Never equate slices with input readiness."""
import argparse
import csv
import io
import json
import math
from pathlib import Path
import re
import subprocess

# Fixed labels only: never export arbitrary WebView/user slice names.
EVENTS = (
    'coreData.create', 'runtimeProfile.cleanup', 'host.catalog.publish',
    'tool.recordOpened', 'icon.catalog.lookup', 'icon.cache.hit', 'icon.cache.miss',
    'icon.cache.evict', 'icon.catalog.recheck', 'icon.decode', 'tool.prepare', 'runtime.attach',
    'runtime.detach', 'runtime.release', 'nav.enter', 'nav.return', 'tool.shell.enter',
    'webView.create', 'webView.firstMainFrame',
)
REQUIRED = {
    'A': {'coreData.create', 'host.catalog.publish'},
    'B': {'icon.catalog.lookup'},
    'C': {'runtime.attach'},
    'D': {'nav.enter', 'nav.return'},
    'E': {'icon.catalog.lookup', 'runtime.attach'},
    'F': {'runtime.attach', 'runtime.detach'},
}


def slice_query(package):
    if not re.fullmatch(r'[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+', package):
        raise ValueError('Invalid target package')
    names = ','.join("'" + name + "'" for name in EVENTS)
    return f"""SELECT s.id, s.name, s.ts, s.dur
FROM slice s
LEFT JOIN thread_track tt ON tt.id = s.track_id
LEFT JOIN thread th ON th.utid = tt.utid
LEFT JOIN process_track pt ON pt.id = s.track_id
JOIN process p ON p.upid = COALESCE(th.upid, pt.upid)
WHERE (p.name = '{package}' OR p.name GLOB '{package}:*')
AND s.name IN ({names}) ORDER BY s.ts;"""


def analyze(rows, flow):
    if not rows:
        raise ValueError('NO_TARGET_BUSINESS_SLICES: nonempty trace is insufficient')
    seen = set()
    durations = {}
    for row in rows:
        name = row['name']
        if name not in EVENTS:
            raise ValueError('Unexpected exported slice name')
        ts, dur = int(row['ts']), int(row['dur'])
        if ts < 0 or dur < 0:
            raise ValueError('INCOMPLETE_SLICE: capture did not cover a complete operation')
        seen.add(name)
        durations.setdefault(name, []).append(dur)
    if not REQUIRED[flow] <= seen:
        raise ValueError('MISSING_SCENARIO_SLICES: ' + ','.join(sorted(REQUIRED[flow] - seen)))
    metrics = {}
    for name, values in sorted(durations.items()):
        ordered = sorted(values)
        metrics[name] = {
            'samples': len(values), 'total_ms': sum(values) / 1e6,
            'p50_ms': ordered[math.ceil(len(values) * 0.5) - 1] / 1e6,
            'p90_ms': ordered[math.ceil(len(values) * 0.9) - 1] / 1e6,
            'max_ms': ordered[-1] / 1e6,
        }
    return {'status': 'CAPTURED_NOT_EVALUATED', 'flow': flow,
            'slice_counts': {name: len(values) for name, values in durations.items()},
            'slice_duration_ms': metrics, 'quantile_method': 'nearest-rank; all complete slices retained',
            'scenario_actions': 'REQUIRES_SEMANTIC_REPLAY_REVIEW',
            'interaction_readiness': 'NOT_VERIFIED', 'ttid_ttfd': 'NOT_DERIVED',
            'frame_metrics': 'NOT_DERIVED', 'clock': 'TRACE_TIMESTAMP_NS',
            'note': 'Durations include suspension where applicable and nested slices overlap. They are not CPU time, per-gesture samples, input readiness or a performance PASS. Small-sample quantiles are descriptive only.'}


def query(processor, trace, sql):
    # Classic -Q produces CSV in non-interactive mode; do not invent a --csv flag.
    result = subprocess.run([processor, '-Q', sql, str(trace)], capture_output=True,
                            text=True, timeout=60, check=False)
    if result.returncode:
        raise ValueError('Trace processor query failed; inspect trace locally (stderr not exported)')
    return list(csv.DictReader(io.StringIO(result.stdout)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--processor', required=True)
    parser.add_argument('--trace', type=Path, required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--flow', choices=REQUIRED, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    sql = slice_query(args.package)
    # Losing trace data invalidates comparative measurements, even if some slices survived.
    loss = query(args.processor, args.trace,
                 "SELECT COALESCE(SUM(value),0) AS losses FROM stats WHERE severity IN ('error','data_loss') AND value > 0;")
    if len(loss) != 1 or int(loss[0]['losses']) != 0:
        raise ValueError('TRACE_DATA_LOSS_OR_PARSE_ERROR')
    rows = query(args.processor, args.trace, sql)
    summary = analyze(rows, args.flow)
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / 'business-slices.sql').write_text(sql + '\n')
    with (args.out / 'business-slices.csv').open('w', newline='') as output:
        writer = csv.DictWriter(output, fieldnames=['id', 'name', 'ts', 'dur'])
        writer.writeheader()
        writer.writerows(rows)
    (args.out / 'trace-summary.json').write_text(json.dumps(summary, indent=2) + '\n')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.TimeoutExpired) as error:
        raise SystemExit('summarize-trace: ' + str(error)) from None
