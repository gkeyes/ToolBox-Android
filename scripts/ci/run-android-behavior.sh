#!/usr/bin/env bash
# Run both suites sequentially on the shared emulator and retain a failing result.
set -uo pipefail
result=0
./gradlew --no-daemon :app:connectedDebugAndroidTest || result=1
./gradlew --no-daemon :tool-runtime:connectedDebugAndroidTest || result=1
exit "$result"
