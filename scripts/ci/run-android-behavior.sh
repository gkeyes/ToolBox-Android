#!/usr/bin/env bash
# Run Android behavior tests on the shared emulator and keep going after a failing module.
# TOOLBOX_ANDROID_TEST_FILTER optionally selects module=Class[#method] entries (bare class means app).
set -uo pipefail
result=0
evidence_dir=build/emulator-evidence
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

run_module() {
  local module="$1" classes="${2:-}"
  prepare_visible_emulator "$module" || return 1
  if [ -n "$classes" ]; then
    # Naming a class is an explicit request, so opt-in suites such as live-site checks run too.
    ./gradlew --no-daemon ":$module:connectedDebugAndroidTest" \
      "-Pandroid.testInstrumentationRunnerArguments.class=$classes" \
      "-Pandroid.testInstrumentationRunnerArguments.liveSites=true"
  else
    ./gradlew --no-daemon ":$module:connectedDebugAndroidTest"
  fi
}

if [ -z "${TOOLBOX_ANDROID_TEST_FILTER:-}" ]; then
  run_module app || result=1
  run_module tool-runtime || result=1
  run_module core-data || result=1
  python3 scripts/ci/print-android-failures.py
  exit "$result"
fi

app_classes=()
runtime_classes=()
data_classes=()
IFS=',' read -ra entries <<< "$TOOLBOX_ANDROID_TEST_FILTER"
for entry in "${entries[@]}"; do
  entry="${entry// /}"
  case "$entry" in
    tool-runtime=*) runtime_classes+=("${entry#tool-runtime=}") ;;
    core-data=*) data_classes+=("${entry#core-data=}") ;;
    app=*) app_classes+=("${entry#app=}") ;;
    ?*) app_classes+=("$entry") ;;
  esac
done
join() { local IFS=','; echo "$*"; }
if [ "${#app_classes[@]}" -gt 0 ]; then run_module app "$(join "${app_classes[@]}")" || result=1; fi
if [ "${#runtime_classes[@]}" -gt 0 ]; then run_module tool-runtime "$(join "${runtime_classes[@]}")" || result=1; fi
if [ "${#data_classes[@]}" -gt 0 ]; then run_module core-data "$(join "${data_classes[@]}")" || result=1; fi
python3 scripts/ci/check-android-results.py "$TOOLBOX_ANDROID_TEST_FILTER" || result=1
python3 scripts/ci/print-android-failures.py
exit "$result"
