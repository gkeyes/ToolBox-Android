#!/usr/bin/env bash
# P1 capture only: never installs, clears, force-stops or changes device settings.
set -euo pipefail
umask 077

usage() {
    printf '%s\n' \
        'USAGE: SERIAL=... APK=... BUILD_COMMIT=... BUILD_VARIANT=candidate FLOW=A..F OUT_DIR=... capture-host.sh --replay /absolute/script.sh' \
        'Required: TEST_ENVIRONMENT=dedicated-device FIXTURE_ID=... FIXTURE_COUNT=... REFRESH_HZ=... COMPILATION_MODE=... RUN_STATE=...' \
        'Optional: ADB=adb PERFETTO=1 TRACE_PROCESSOR=/path/to/trace_processor_shell CAPTURE_TIMEOUT=90' \
        'Replay must use semantic selectors and return nonzero on incomplete actions; see docs/performance/BASELINE.md.'
}
fail() { printf 'capture-host: %s\n' "$1" >&2; exit 2; }
serial="${SERIAL:-}"
package_name="${PACKAGE:-io.toolbox.host}"
flow="${FLOW:-}"
out_dir="${OUT_DIR:-}"
adb_bin="${ADB:-adb}"
replay="${REPLAY_SCRIPT:-}"
capture_perfetto="${PERFETTO:-1}"
processor="${TRACE_PROCESSOR:-trace_processor_shell}"
limit="${CAPTURE_TIMEOUT:-90}"
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h) usage; exit 0 ;;
        --perfetto) capture_perfetto=1; shift ;;
        --serial|--package|--flow|--out|--replay)
            [[ $# -ge 2 ]] || fail 'missing option value'
            case "$1" in
                --serial) serial="$2" ;; --package) package_name="$2" ;; --flow) flow="$2" ;;
                --out) out_dir="$2" ;; --replay) replay="$2" ;;
            esac
            shift 2 ;;
        *) usage >&2; fail 'unknown argument' ;;
    esac
done
[[ -n "$serial" ]] || fail 'SERIAL is required'
[[ "$package_name" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]] || fail 'invalid PACKAGE'
flow="${flow^^}"
[[ "$flow" =~ ^[ABCDEF]$ ]] || fail 'FLOW must be A..F'
[[ -n "$out_dir" && "$replay" = /* && -f "$replay" ]] || fail 'OUT_DIR and an absolute replay script are required'
[[ "${TEST_ENVIRONMENT:-}" == dedicated-device ]] || fail 'use an explicitly dedicated test device; never the user installation'
[[ "${APK:-}" = /* && -f "$APK" ]] || fail 'APK must name the exact local installed APK'
[[ "${BUILD_COMMIT:-}" =~ ^[0-9a-f]{40}$ ]] || fail 'BUILD_COMMIT must be a full commit SHA'
[[ "${BUILD_VARIANT:-}" == candidate ]] || fail 'use the optimized profileable candidate, not debug/release'
[[ "${FIXTURE_ID:-}" =~ ^[A-Za-z0-9_-]{1,64}$ && "${FIXTURE_COUNT:-}" =~ ^[0-9]{1,4}$ ]] || fail 'record a non-private fixture identifier and count'
[[ "${REFRESH_HZ:-}" =~ ^[0-9]{2,3}$ ]] || fail 'record the observed refresh rate (not just the peak setting)'
[[ "${COMPILATION_MODE:-}" =~ ^(none|speed|speed-profile)$ ]] || fail 'record the controlled ART compilation mode'
[[ "${RUN_STATE:-}" =~ ^(cold-start|warm-start|cold-icons|warm-icons|first-open|repeat-open|background-reentry|navigation|mixed|ordinary-lifecycle|background-lifecycle)$ ]] || fail 'record the scenario state'
[[ "$capture_perfetto" =~ ^[01]$ && "$limit" =~ ^[0-9]{1,3}$ ]] || fail 'invalid PERFETTO or CAPTURE_TIMEOUT'
(( limit >= 5 && limit <= 300 )) || fail 'CAPTURE_TIMEOUT must be 5..300 seconds'
for executable in "$adb_bin" python3 timeout sha256sum; do
    command -v "$executable" >/dev/null 2>&1 || fail 'a required capture executable is unavailable'
done
if [[ "$capture_perfetto" == 1 ]]; then
    command -v "$processor" >/dev/null 2>&1 || fail 'TRACE_PROCESSOR is required to check real target-package slices'
fi
# Never overwrite a previous capture or silently collect into an existing run directory.
mkdir -p -- "$(dirname -- "$out_dir")"
mkdir -- "$out_dir" || fail 'OUT_DIR must be new'
out_dir="$(cd -- "$out_dir" && pwd)"
status="$out_dir/capture-status.txt"
printf 'schema_version=2\nstatus=INVALID\nreason=CAPTURE_INCOMPLETE\n' > "$status"
trace_pid=""
remote_trace="/data/local/tmp/toolbox-perf-$(python3 -c 'import secrets; print(secrets.token_hex(8))').pftrace"
finished=0
adb_call() { timeout 45 "$adb_bin" -s "$serial" "$@" 2>/dev/null; }
cleanup() {
    local rc=$?
    trap - EXIT
    if [[ -n "$trace_pid" ]]; then
        if ! adb_call shell kill -TERM "$trace_pid" >/dev/null; then
            printf 'cleanup_trace_stop=FAILED\n' >> "$status"
            rc=1
        fi
    fi
    if ! adb_call shell rm -f "$remote_trace" >/dev/null; then
        printf 'cleanup_remote_file=FAILED\n' >> "$status"
        rc=1
    fi
    if [[ "$finished" != 1 || "$rc" != 0 ]]; then
        printf 'status=INVALID\nreason=CAPTURE_OR_CLEANUP_FAILED\n' >> "$status"
        rc=1
    fi
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
monotonic() { python3 -c 'import time; print(time.monotonic_ns())'; }
[[ "$(adb_call get-state | tr -d '\r\n')" == device ]] || fail 'ADB transport is not ready'
apk_sha="$(sha256sum "$APK" | awk '{print $1}')"
installed="$(adb_call shell pm path "$package_name" | tr -d '\r')"
[[ "$installed" =~ ^package:(/data/app/[A-Za-z0-9_./=+~-]+/base.apk)$ ]] || fail 'expected one installed base APK (no splits)'
installed_path="${BASH_REMATCH[1]}"
installed_sha="$(adb_call shell sha256sum "$installed_path" | awk '{print $1}')"
[[ "$installed_sha" == "$apk_sha" ]] || fail 'local/installed APK hashes differ'
package_info="$(adb_call shell dumpsys package "$package_name")"
[[ "$package_info" == *PROFILEABLE_BY_SHELL* && "$package_info" != *DEBUGGABLE* ]] || fail 'installed package must be shell-profileable and non-debuggable'
# Record build/fixture declarations separately from the byte-verified installed APK identity.
{
    printf 'schema_version=2\nflow=%s\npackage=%s\napk_sha256=%s\n' "$flow" "$package_name" "$apk_sha"
    printf 'capture_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'capture_script_sha256=%s\n' "$(sha256sum "${BASH_SOURCE[0]}" | awk '{print $1}')"
    printf 'build_commit_declared=%s\nbuild_variant=%s\nfixture_id=%s\nfixture_count=%s\n' "$BUILD_COMMIT" "$BUILD_VARIANT" "$FIXTURE_ID" "$FIXTURE_COUNT"
    printf 'refresh_hz_observed=%s\ncompilation_mode_declared=%s\nrun_state=%s\n' "$REFRESH_HZ" "$COMPILATION_MODE" "$RUN_STATE"
    printf 'replay_sha256=%s\n' "$(sha256sum "$replay" | awk '{print $1}')"
    printf 'device_serial=NOT_RECORDED\napplication_data=UNCHANGED_BY_CAPTURE\nsettings=UNCHANGED_BY_CAPTURE\n'
    for prop in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.version.sdk; do
        printf '%s=%s\n' "$prop" "$(adb_call shell getprop "$prop" | tr -d '\r\n')"
    done
    fingerprint="$(adb_call shell getprop ro.build.fingerprint)"
    printf 'build_fingerprint_sha256=%s\n' "$(printf '%s' "$fingerprint" | sha256sum | awk '{print $1}')"
} > "$out_dir/capture-summary.txt"
adb_call shell dumpsys webviewupdate > "$out_dir/webview-provider.txt"
adb_call shell dumpsys thermalservice > "$out_dir/thermal-before.txt"
adb_call shell dumpsys meminfo "$package_name" > "$out_dir/meminfo-before.txt"
for setting in peak_refresh_rate min_refresh_rate; do
    printf '%s=%s\n' "$setting" "$(adb_call shell settings get system "$setting" | tr -d '\r\n')"
done > "$out_dir/refresh-settings.txt"
if [[ "$capture_perfetto" == 1 ]]; then
    categories="$(adb_call shell atrace --list_categories)"
    config="$out_dir/perfetto-config.pbtxt"
    {
        printf 'buffers { size_kb: 32768 fill_policy: RING_BUFFER }\nduration_ms: %s\n' "$(((limit + 60) * 1000))"
        printf 'data_sources { config { name: "linux.ftrace" ftrace_config {\n'
        printf 'ftrace_events: "sched/sched_switch"\nftrace_events: "sched/sched_wakeup"\natrace_apps: "%s"\n' "$package_name"
        found=0
        for category in am wm gfx view webview; do
            if grep -Eq "^[[:space:]]*${category}[[:space:]]+-" <<< "$categories"; then
                printf 'atrace_categories: "%s"\n' "$category"
                found=$((found + 1))
            fi
        done
        (( found > 0 )) || fail 'no supported Android trace categories'
        printf '} } }\ndata_sources { config { name: "android.surfaceflinger.frametimeline" } }\n'
    } > "$config"
    # -D acknowledges data-source readiness; PID output alone is insufficient.
    acknowledged_pid="$(adb_call shell perfetto --background-wait --txt -c - -o "$remote_trace" < "$config" | tr -d '\r\n')"
    [[ "$acknowledged_pid" =~ ^[1-9][0-9]*$ ]] || fail 'Perfetto did not acknowledge a valid background PID'
    trace_pid="$acknowledged_pid"
    adb_call shell kill -0 "$trace_pid" >/dev/null || fail 'trace exited before replay'
fi
adb_call shell dumpsys gfxinfo "$package_name" reset >/dev/null
printf 'phase=READY\n' >> "$status"
start="$(monotonic)"
printf 'replay_start_host_monotonic_ns=%s\n' "$start" >> "$out_dir/capture-summary.txt"
# Only an operator-supplied, reviewed semantic replay is executed. No implicit taps/force-stop.
# Its stdout/stderr may contain UI content: discard rather than save console logs.
if ! SERIAL="$serial" PACKAGE="$package_name" ADB="$adb_bin" FLOW="$flow" \
    timeout "$limit" bash "$replay" >/dev/null 2>&1; then
    fail 'replay failed or timed out; capture is INVALID'
fi
printf 'replay_end_host_monotonic_ns=%s\n' "$(monotonic)" >> "$out_dir/capture-summary.txt"
printf 'phase=REPLAY_COMPLETED\n' >> "$status"
adb_call shell dumpsys gfxinfo "$package_name" framestats > "$out_dir/gfxinfo-framestats.txt"
adb_call shell dumpsys meminfo "$package_name" > "$out_dir/meminfo-after.txt"
adb_call shell dumpsys thermalservice > "$out_dir/thermal-after.txt"
if [[ "$capture_perfetto" == 1 ]]; then
    adb_call shell kill -TERM "$trace_pid" >/dev/null || fail 'trace did not cover the complete replay'
    stopped=0
    for ((i=0; i<60; i++)); do
        if ! adb_call shell kill -0 "$trace_pid" >/dev/null; then stopped=1; break; fi
        sleep 0.25
    done
    [[ "$stopped" == 1 ]] || fail 'trace did not finish flushing'
    trace_pid=""
    [[ "$(adb_call get-state | tr -d '\r\n')" == device ]] || fail 'transport lost while stopping trace'
    adb_call pull "$remote_trace" "$out_dir/perfetto.pftrace" >/dev/null
    [[ -s "$out_dir/perfetto.pftrace" ]] || fail 'trace is empty'
    python3 "$root/scripts/perf/summarize-trace.py" --processor "$processor" \
        --trace "$out_dir/perfetto.pftrace" --package "$package_name" --flow "$flow" --out "$out_dir"
fi
# Valid capture is NOT a performance PASS, interaction proof, or visual approval.
if [[ "$capture_perfetto" == 1 ]]; then
    printf 'status=CAPTURED_NOT_EVALUATED\n' >> "$status"
else
    printf 'status=SNAPSHOT_ONLY_NOT_MEASURED\n' >> "$status"
fi
finished=1
printf 'capture-host: Flow %s capture finished; performance thresholds NOT_EVALUATED\n' "$flow"
