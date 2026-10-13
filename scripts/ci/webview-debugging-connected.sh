#!/usr/bin/env bash
# Runs DeveloperWebViewDebuggingBehaviorTest on the connected emulator. The emulator image's own WebView
# (Trichrome 636771938) sometimes dies with a SIGSEGV on the app's main thread while the test creates and
# destroys WebViews; the same commit passes and fails, and the crashing code is WebView's native library
# (the app ships none). Only when every crash in the log is that one is the test run once more, and the
# retry must pass every scenario. Any other failure fails the job.
# Usage: webview-debugging-connected.sh <connected task> [gradle arguments...]
set -uo pipefail

task="$1"
shift
out=build/webview-debugging
mkdir -p "$out"

attempt() {
  adb logcat -b all -c || true
  ./gradlew --no-daemon ":app:$task" \
    -Pandroid.testInstrumentationRunnerArguments.class=io.toolbox.host.DeveloperWebViewDebuggingBehaviorTest \
    "$@" > "$out/connected-test.log" 2>&1
}

collect() {
  adb logcat -d -b crash > "$out/logcat-crash.txt"
  adb logcat -d -b all -v brief > "$out/logcat.txt"
}

# Every tombstone is the app process with a SIGSEGV whose top frame is in that WebView build's library.
known_crash() {
  local log="$1" count
  count() { grep -cE "^F/DEBUG .*$1" "$log"; }
  local tombstones
  tombstones="$(count '>>> .* <<<')"
  [ "$tombstones" -ge 1 ] \
    && [ "$(count '>>> io\.toolbox\.host <<<')" = "$tombstones" ] \
    && [ "$(count 'signal 11 \(SIGSEGV\)')" = "$tombstones" ] \
    && [ "$(count 'signal [0-9]+ ')" = "$tombstones" ] \
    && [ "$(count '#00 pc [0-9a-f]+ +/data/app/.*/com\.google\.android\.trichromelibrary_636771938-[^/]*/TrichromeLibrary\.apk!libmonochrome_64\.so .*BuildId: 2fdf3a989203643cc036b36c2d7975dfe53e8ca3')" = "$tombstones" ]
}

attempt "$@" && exit 0
collect
if known_crash "$out/logcat.txt"; then
  echo "::warning title=Known WebView crash::Trichrome 636771938 crashed in io.toolbox.host; running the test once more."
  mkdir -p "$out/first-attempt"
  mv "$out/connected-test.log" "$out/logcat-crash.txt" "$out/logcat.txt" "$out/first-attempt/"
  attempt "$@" && exit 0
  collect
fi
tail -n 120 "$out/connected-test.log"
exit 1
