"""Offline capture-contract tests; fake ADB/CSV are never device evidence."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('trace_summary', ROOT / 'scripts/perf/summarize-trace.py')
SUMMARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUMMARY)

FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[3:]
root = pathlib.Path(os.environ['FAKE_ROOT'])
mode = os.environ.get('FAKE_MODE', '')
with (root / 'commands.jsonl').open('a') as f: f.write(json.dumps(args) + '\n')
if args == ['get-state']: print('device')
elif args[:3] == ['shell', 'pm', 'path']: print('package:/data/app/fixture/base.apk')
elif args[:2] == ['shell', 'sha256sum']: print(os.environ['FAKE_APK_SHA'] + '  /data/app/fixture/base.apk')
elif args[:3] == ['shell', 'dumpsys', 'package']:
    print('PROFILEABLE_BY_SHELL' + (' DEBUGGABLE' if mode == 'debug' else ''))
elif args[:2] == ['shell', 'getprop']: print('fixture')
elif args[:3] == ['shell', 'settings', 'get']: print('120')
elif args[:3] == ['shell', 'atrace', '--list_categories']:
    print('gfx - Graphics\nview - View System\nam - Activity Manager')
elif args[:2] == ['shell', 'perfetto']:
    config = sys.stdin.read()
    assert 'atrace_apps: "io.toolbox.host"' in config
    assert 'atrace_categories: "gfx"' in config
    assert 'atrace_categories: "webview"' not in config
    if mode == 'not-ready': sys.exit(1)
    if mode == 'bad-pid': print('not-a-pid'); sys.exit(0)
    (root / 'recording').touch()
    print('12345')
elif args[:3] == ['shell', 'kill', '-0']: sys.exit(0 if (root / 'recording').exists() else 1)
elif args[:3] == ['shell', 'kill', '-TERM']:
    (root / 'recording').unlink(missing_ok=True)
elif args[:2] == ['shell', 'replay-semantic-fixture']:
    if os.environ.get('PERFETTO', '1') == '1': assert (root / 'recording').exists()
    print('PRIVATE_FAKE_CONTENT_MUST_NOT_BE_SAVED')
    if mode == 'replay-fail': sys.exit(7)
elif args and args[0] == 'pull': pathlib.Path(args[2]).write_bytes(b'FAKE_NOT_A_TRACE')
elif args[:3] == ['shell', 'rm', '-f']:
    if mode == 'cleanup-fail': sys.exit(1)
elif args[:2] == ['shell', 'dumpsys']: print('fixture system counters')
else: sys.exit(9)
'''

FAKE_PROCESSOR = r'''#!/usr/bin/env python3
import os, sys
assert sys.argv[1] == '-Q'
sql = sys.argv[2]
mode = os.environ.get('FAKE_MODE', '')
if 'FROM stats' in sql: print('losses\n' + ('1' if mode == 'data-loss' else '0'))
else:
    print('id,name,ts,dur')
    if mode != 'no-slices':
        print('1,icon.catalog.lookup,1000000,' + ('-1' if mode == 'incomplete' else '2000000'))
        print('2,icon.cache.hit,3000000,0')
'''


class TraceSummaryTest(unittest.TestCase):
    def test_query_filters_target_process_and_fixed_names_on_both_track_types(self):
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.row_factory = sqlite3.Row
        db.executescript('''
            CREATE TABLE slice(id INTEGER, name TEXT, ts INTEGER, dur INTEGER, track_id INTEGER);
            CREATE TABLE thread_track(id INTEGER, utid INTEGER);
            CREATE TABLE thread(utid INTEGER, upid INTEGER);
            CREATE TABLE process_track(id INTEGER, upid INTEGER);
            CREATE TABLE process(upid INTEGER, name TEXT);
            INSERT INTO process VALUES (1, 'io.toolbox.host'), (2, 'other.app'),
                (3, 'io.toolbox.host:runtime'), (4, 'io.toolbox.hostile');
            INSERT INTO thread VALUES (1, 1), (2, 2), (3, 4);
            INSERT INTO thread_track VALUES (1, 1), (2, 2), (4, 3);
            INSERT INTO process_track VALUES (3, 3);
            INSERT INTO slice VALUES
                (1, 'icon.catalog.lookup', 0, 1000000, 1),
                (2, 'icon.catalog.lookup', 1, 2000000, 2),
                (3, 'icon.cache.hit', 2, 0, 3),
                (4, 'private web title', 3, 2000000, 1),
                (5, 'icon.catalog.lookup', 4, 2000000, 4);
        ''')
        rows = [dict(r) for r in db.execute(SUMMARY.slice_query('io.toolbox.host'))]
        self.assertEqual([1, 3], [r['id'] for r in rows])
        report = SUMMARY.analyze(rows, 'B')
        self.assertEqual('CAPTURED_NOT_EVALUATED', report['status'])
        self.assertEqual('NOT_VERIFIED', report['interaction_readiness'])
        self.assertEqual(1.0, report['slice_duration_ms']['icon.catalog.lookup']['p50_ms'])

    def test_incomplete_missing_unexpected_and_injection_fail_closed(self):
        for rows in [[], [{'name': 'icon.cache.hit', 'ts': 0, 'dur': 0}],
                     [{'name': 'icon.catalog.lookup', 'ts': 0, 'dur': -1}],
                     [{'name': 'private title', 'ts': 0, 'dur': 0}]]:
            with self.subTest(rows=rows), self.assertRaises(ValueError): SUMMARY.analyze(rows, 'B')
        with self.assertRaises(ValueError): SUMMARY.slice_query("io.toolbox.host' OR 1=1 --")

    def test_nearest_rank_keeps_all_samples(self):
        rows = [{'name': 'icon.catalog.lookup', 'ts': i, 'dur': i * 1000000} for i in range(1, 11)]
        metrics = SUMMARY.analyze(rows, 'B')['slice_duration_ms']['icon.catalog.lookup']
        self.assertEqual({'samples': 10, 'total_ms': 55.0, 'p50_ms': 5.0, 'p90_ms': 9.0, 'max_ms': 10.0}, metrics)


class CaptureContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='toolbox-perf-offline-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        adb = self.root / 'adb'
        adb.write_text(FAKE_ADB)
        adb.chmod(0o700)
        processor = self.root / 'processor'
        processor.write_text(FAKE_PROCESSOR)
        processor.chmod(0o700)
        replay = self.root / 'replay.sh'
        replay.write_text('set -euo pipefail\n"$ADB" -s "$SERIAL" shell replay-semantic-fixture\n')
        apk = self.root / 'fixture.apk'
        apk.write_bytes(b'FAKE_NOT_AN_APK')
        self.env = {**os.environ, 'SERIAL': 'private-fixture-serial', 'PACKAGE': 'io.toolbox.host',
                    'APK': str(apk), 'BUILD_COMMIT': 'a' * 40, 'BUILD_VARIANT': 'candidate',
                    'FLOW': 'B', 'OUT_DIR': str(self.root / 'capture'), 'ADB': str(adb),
                    'REPLAY_SCRIPT': str(replay), 'TRACE_PROCESSOR': str(processor), 'PERFETTO': '1',
                    'TEST_ENVIRONMENT': 'dedicated-device', 'FIXTURE_ID': 'icons-v1', 'FIXTURE_COUNT': '20',
                    'REFRESH_HZ': '120', 'COMPILATION_MODE': 'speed', 'RUN_STATE': 'warm-icons',
                    'CAPTURE_TIMEOUT': '5', 'FAKE_MODE': '', 'FAKE_ROOT': str(self.root),
                    'FAKE_APK_SHA': hashlib.sha256(apk.read_bytes()).hexdigest()}

    def run_capture(self, **patch):
        return subprocess.run(['bash', str(ROOT / 'scripts/perf/capture-host.sh')],
                              env={**self.env, **patch}, capture_output=True, text=True, timeout=25)

    def commands(self):
        p = self.root / 'commands.jsonl'
        return [json.loads(line) for line in p.read_text().splitlines()] if p.exists() else []

    def test_trace_ready_reset_replay_snapshot_stop_order_and_private_output(self):
        result = self.run_capture()
        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        ready = next(i for i, c in enumerate(commands) if c[:2] == ['shell', 'perfetto'])
        reset = commands.index(['shell', 'dumpsys', 'gfxinfo', 'io.toolbox.host', 'reset'])
        replay = commands.index(['shell', 'replay-semantic-fixture'])
        snapshot = commands.index(['shell', 'dumpsys', 'gfxinfo', 'io.toolbox.host', 'framestats'])
        stop = commands.index(['shell', 'kill', '-TERM', '12345'])
        self.assertTrue(ready < reset < replay < snapshot < stop)
        status = (self.root / 'capture/capture-status.txt').read_text()
        self.assertTrue(status.endswith('status=CAPTURED_NOT_EVALUATED\n'))
        report = json.loads((self.root / 'capture/trace-summary.json').read_text())
        self.assertEqual('NOT_DERIVED', report['frame_metrics'])
        all_output = b''.join(p.read_bytes() for p in (self.root / 'capture').iterdir())
        for private in [b'private-fixture-serial', b'PRIVATE_FAKE_CONTENT_MUST_NOT_BE_SAVED']:
            self.assertNotIn(private, all_output)
        self.assertFalse((self.root / 'recording').exists())

    def test_failures_never_report_success_and_clean_remote_trace(self):
        for mode in ['not-ready', 'bad-pid', 'replay-fail', 'no-slices', 'data-loss', 'incomplete', 'cleanup-fail']:
            with self.subTest(mode=mode):
                out = self.root / mode
                result = self.run_capture(FAKE_MODE=mode, OUT_DIR=str(out))
                self.assertNotEqual(0, result.returncode)
                self.assertIn('status=INVALID', (out / 'capture-status.txt').read_text().splitlines()[-2:])
                self.assertFalse((self.root / 'recording').exists())
        self.assertFalse(any(c[:3] == ['shell', 'kill', '-TERM'] and c[-1] != '12345' for c in self.commands()))

    def test_unsafe_environment_and_apk_identity_fail_before_replay(self):
        for i, patch in enumerate([{'TEST_ENVIRONMENT': 'user-device'}, {'BUILD_VARIANT': 'debug'},
                                   {'PACKAGE': 'bad;command'}, {'FAKE_APK_SHA': '0' * 64}, {'FAKE_MODE': 'debug'}]):
            with self.subTest(patch=patch):
                result = self.run_capture(OUT_DIR=str(self.root / f'rejected-{i}'), **patch)
                self.assertNotEqual(0, result.returncode)
        self.assertNotIn(['shell', 'replay-semantic-fixture'], self.commands())

    def test_existing_capture_is_not_overwritten(self):
        self.assertEqual(0, self.run_capture().returncode)
        summary = (self.root / 'capture/capture-summary.txt').read_bytes()
        self.assertNotEqual(0, self.run_capture().returncode)
        self.assertEqual(summary, (self.root / 'capture/capture-summary.txt').read_bytes())

    def test_snapshot_only_is_not_trace_or_measurement_success(self):
        result = self.run_capture(PERFETTO='0', TRACE_PROCESSOR='missing-processor')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.root / 'capture/capture-status.txt').read_text().endswith('status=SNAPSHOT_ONLY_NOT_MEASURED\n'))
        self.assertFalse((self.root / 'capture/trace-summary.json').exists())
        self.assertFalse(any(c[:2] == ['shell', 'perfetto'] for c in self.commands()))


if __name__ == '__main__':
    unittest.main()
