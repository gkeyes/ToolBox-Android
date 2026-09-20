#!/usr/bin/env bash
# Run both suites sequentially on the shared emulator and retain a failing result.
set -uo pipefail
result=0
evidence_dir=build/wasm-emulator
mkdir -p "$evidence_dir"

prepare_visible_emulator() {
  local suite="$1"
  adb shell dumpsys power > "$evidence_dir/$suite-power-before.txt" || return 1
  # The runner unlocks only at boot. Compilation between suites can outlast the
  # emulator's display timeout; a RESUMED Activity then has no focused window.
  adb shell svc power stayon true || return 1
  adb shell input keyevent KEYCODE_WAKEUP || return 1
  adb shell wm dismiss-keyguard || return 1
  adb shell dumpsys power > "$evidence_dir/$suite-power-ready.txt" || return 1
  adb shell dumpsys battery > "$evidence_dir/$suite-battery-ready.txt" || return 1
  adb shell dumpsys window windows > "$evidence_dir/$suite-windows-ready.txt" || return 1
}

prepare_visible_emulator app || exit 1
if [ "${TOOLBOX_BEHAVIOR_SCOPE:-full}" = performance ]; then
  ./gradlew --no-daemon :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.toolbox.host.CatalogNameSortTest,io.toolbox.host.icons.ToolIconCompatibilityTest,io.toolbox.host.icons.CatalogToolIconBehaviorTest || result=1
  exit "$result"
fi
test "${TOOLBOX_BEHAVIOR_SCOPE:-full}" = full || exit 2
./gradlew --no-daemon :app:connectedDebugAndroidTest || result=1
prepare_visible_emulator runtime || exit 1
./gradlew --no-daemon :tool-runtime:connectedDebugAndroidTest || result=1
exit "$result"
