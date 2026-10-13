#!/usr/bin/env bash
# Installs the emulator and one system image before reactivecircus/android-emulator-runner starts, so its
# own `sdkmanager --install` finds them current and downloads nothing. The runner action gives up on the
# first failed download, and GitHub's runners regularly get a truncated archive from Google's SDK mirror
# ("Error on ZipFile unknown archive"); a fresh attempt gets a good copy.
# Usage: install-emulator.sh <api level> <target> <arch>
set -euo pipefail

api="$1"
target="$2"
arch="$3"
packages=(emulator "system-images;android-${api};${target};${arch}")

sdkmanager="$(command -v sdkmanager || true)"
test -n "$sdkmanager" || sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

for attempt in 1 2 3 4; do
  log="$(mktemp)"
  # sdkmanager reports a bad download as a warning and still exits 0, so its output decides. `yes` feeds
  # any licence prompt; it is not part of the pipeline, so its SIGPIPE can't fail the attempt.
  if "$sdkmanager" --install "${packages[@]}" --channel=0 >"$log" 2>&1 < <(yes) \
    && ! grep -q 'An error occurred while preparing SDK package' "$log"; then
    echo "Installed ${packages[*]} (attempt $attempt)."
    exit 0
  fi
  grep -E 'Warning|Error|error' "$log" | tail -n 5 >&2 || true
  [ "$attempt" -lt 4 ] || break
  echo "Emulator download failed (attempt $attempt); retrying." >&2
  sleep $((attempt * 15))
done
echo "Could not install ${packages[*]} after 4 attempts." >&2
exit 1
