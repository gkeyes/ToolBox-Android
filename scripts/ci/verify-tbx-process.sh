#!/usr/bin/env bash
set -euo pipefail
# Only invoked by the isolated GitHub emulator job. Never targets a user's device.
: "${GITHUB_ACTIONS:?This harness is restricted to GitHub Actions}"
[[ "$GITHUB_ACTIONS" == true ]]
mkdir -p build/tbx-upgrade-process
for phase in seed restore; do
  if [[ "$phase" == restore ]]; then
    adb shell am force-stop io.toolbox.host
  fi
  output="build/tbx-upgrade-process/$phase.txt"
  adb shell am instrument -w -r \
    -e class io.toolbox.host.runtime.TbxUpgradeProcessTest \
    -e tbxPhase "$phase" \
    io.toolbox.host.test/androidx.test.runner.AndroidJUnitRunner | tee "$output"
  grep -Eq 'OK \(1 test\)' "$output"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "$output"; then exit 1; fi
done
