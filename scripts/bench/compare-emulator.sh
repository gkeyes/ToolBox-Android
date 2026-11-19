#!/usr/bin/env bash
# Compare the two compiled releases on the dedicated CI device.
set -euo pipefail
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]]
root=build/comparison
mkdir -p "$root/evidence"
rounds=$(jq -r .rounds scripts/bench/host-comparison.json)
for edition in baseline candidate; do
  adb install -r "$root/$edition.apk"
  adb shell dumpsys package io.toolbox.host | tr -d '\r' | sed -n 's/^ *firstInstallTime=//p' > "$root/evidence/$edition-installed.txt"
  test -s "$root/evidence/$edition-installed.txt"
  python3 scripts/bench/android-startup.py --cold --rounds "$rounds" --memory --output "$root/evidence/$edition.json"
done
cmp "$root/evidence/baseline-installed.txt" "$root/evidence/candidate-installed.txt"
python3 - <<'PY'
import json, statistics
from pathlib import Path
for version in ["baseline", "candidate"]:
    report = json.loads(Path(f"build/comparison/evidence/{version}.json").read_text())
    for metric in ["launchTotalMs", "homeFullyDrawnMs", "hostHomePssKiB"]:
        values = [s[metric] for s in report["samples"] if s[metric] is not None]
        print(version, report["hostVersion"], metric, len(values), statistics.median(values) if values else "unavailable")
PY
