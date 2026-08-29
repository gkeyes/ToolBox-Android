#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf '%s\n' \
        'USAGE: SERIAL=<adb-serial> PACKAGE=<package> FLOW=<A|B|C|D|E> OUT_DIR=<directory> scripts/perf/capture-host.sh [--perfetto]' \
        '   or: capture-host.sh --serial <adb-serial> --package <package> --flow <A|B|C|D|E> --out <directory> [--perfetto]' \
        'Optional: ADB=/absolute/path/to/adb PERFETTO=1'
}

fail() {
    printf 'capture-host: %s\n' "$1" >&2
    exit 2
}

serial="${SERIAL:-}"
package_name="${PACKAGE:-io.toolbox.host}"
flow="${FLOW:-}"
out_dir="${OUT_DIR:-}"
adb_bin="${ADB:-adb}"
capture_perfetto="${PERFETTO:-0}"
perfetto_config=""
perfetto_pid=""
required_failures=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial)
            serial="${2:-}"
            shift 2
            ;;
        --package)
            package_name="${2:-}"
            shift 2
            ;;
        --flow)
            flow="${2:-}"
            shift 2
            ;;
        --out)
            out_dir="${2:-}"
            shift 2
            ;;
        --perfetto)
            capture_perfetto=1
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            usage >&2
            fail "unknown argument"
            ;;
    esac
done

[[ -n "$serial" ]] || fail 'SERIAL/--serial is required'
[[ "$package_name" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]] || fail 'PACKAGE/--package is not a package name'
flow="${flow^^}"
[[ "$flow" =~ ^[ABCDE]$ ]] || fail 'FLOW/--flow must be A, B, C, D, or E'
[[ -n "$out_dir" ]] || fail 'OUT_DIR/--out is required'
[[ "$capture_perfetto" == 0 || "$capture_perfetto" == 1 ]] || fail 'PERFETTO must be 0 or 1'
command -v "$adb_bin" >/dev/null 2>&1 || fail 'ADB executable was not found'

mkdir -p "$out_dir"
out_dir="$(cd -- "$out_dir" && pwd)"

cleanup() {
    if [[ -n "$perfetto_pid" ]] && kill -0 "$perfetto_pid" >/dev/null 2>&1; then
        kill "$perfetto_pid" >/dev/null 2>&1 || true
        wait "$perfetto_pid" >/dev/null 2>&1 || true
    fi
    [[ -z "$perfetto_config" ]] || rm -f -- "$perfetto_config"
}
trap cleanup EXIT INT TERM

timestamp_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
status_file="$out_dir/capture-status.txt"
summary_file="$out_dir/capture-summary.txt"
printf 'schema_version=1\ntimestamp_utc=%s\nflow=%s\npackage=%s\n' \
    "$timestamp_utc" "$flow" "$package_name" > "$status_file"

"$adb_bin" -s "$serial" get-state >/dev/null 2>&1 || fail 'the requested ADB transport is not ready'

record_status() {
    printf 'artifact=%s status=%s\n' "$1" "$2" >> "$status_file"
}

capture_shell() {
    local artifact="$1"
    shift
    local target="$out_dir/$artifact"
    if "$adb_bin" -s "$serial" shell "$@" > "$target" 2>/dev/null; then
        record_status "$artifact" 'captured'
    else
        : > "$target"
        record_status "$artifact" 'failed'
        required_failures=1
    fi
}

read_prop() {
    "$adb_bin" -s "$serial" shell getprop "$1" 2>/dev/null | tr -d '\r\n'
}

write_device_info() {
    local target="$out_dir/device-info.txt"
    local manufacturer model release sdk fingerprint fingerprint_hash
    manufacturer="$(read_prop ro.product.manufacturer || true)"
    model="$(read_prop ro.product.model || true)"
    release="$(read_prop ro.build.version.release || true)"
    sdk="$(read_prop ro.build.version.sdk || true)"
    fingerprint="$(read_prop ro.build.fingerprint || true)"
    if command -v shasum >/dev/null 2>&1; then
        fingerprint_hash="$(printf '%s' "$fingerprint" | shasum -a 256 | awk '{print $1}')"
    else
        fingerprint_hash="$(printf '%s' "$fingerprint" | sha256sum | awk '{print $1}')"
    fi
    printf 'manufacturer=%s\nmodel=%s\nandroid_release=%s\nsdk=%s\nbuild_fingerprint_sha256=%s\n' \
        "$manufacturer" "$model" "$release" "$sdk" "$fingerprint_hash" > "$target"
    record_status 'device-info.txt' 'captured'
}

write_flow_instructions() {
    local target="$out_dir/flow-instructions.txt"
    case "$flow" in
        A) printf '%s\n' 'A: record the tenth cold launch after two warm-up runs; first truthful Home state must accept input.' ;;
        B) printf '%s\n' 'B: perform Home -> Tools -> Settings -> Home for 20 rounds; capture after the final round.' ;;
        C) printf '%s\n' 'C: use an isolated debug fixture only; record item count and scroll top-to-bottom then bottom-to-top five times.' ;;
        D) printf '%s\n' 'D: measure Tools search responsiveness; do not place typed queries in logs or filenames.' ;;
        E) printf '%s\n' 'E: label this snapshot as before-first-open, during-open, or after-tenth-return in the report; do not copy web content.' ;;
    esac > "$target"
    record_status 'flow-instructions.txt' 'captured'
}

capture_system_log() {
    local target="$out_dir/logcat-host-events.txt"
    set +e
    "$adb_bin" -s "$serial" shell logcat -d -v threadtime \
        ActivityTaskManager:W AndroidRuntime:E DEBUG:E libc:E '*:S' 2>/dev/null |
        grep -F -- "$package_name" > "$target"
    local pipe_status=("${PIPESTATUS[@]}")
    set -e
    if [[ "${pipe_status[0]}" -eq 0 ]]; then
        record_status 'logcat-host-events.txt' 'captured'
    else
        : > "$target"
        record_status 'logcat-host-events.txt' 'failed'
        required_failures=1
    fi
}

capture_perfetto_trace() {
    [[ "$capture_perfetto" == 1 ]] || return
    perfetto_config="$(mktemp "${TMPDIR:-/tmp}/toolbox-perfetto.XXXXXX")"
    printf '%s\n' \
        'buffers: { size_kb: 32768 fill_policy: RING_BUFFER }' \
        'duration_ms: 10000' \
        'data_sources: { config { name: "linux.ftrace" ftrace_config { ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_wakeup" ftrace_events: "power/suspend_resume" } } }' \
        'data_sources: { config { name: "android.surfaceflinger.frametimeline" } }' \
        'data_sources: { config { name: "track_event" } }' > "$perfetto_config"
    local target="$out_dir/perfetto.pftrace"
    set +e
    "$adb_bin" -s "$serial" exec-out perfetto --txt -c - -o - < "$perfetto_config" > "$target" 2>/dev/null &
    perfetto_pid="$!"
    wait "$perfetto_pid"
    local perfetto_exit=$?
    perfetto_pid=""
    set -e
    if [[ "$perfetto_exit" -eq 0 && -s "$target" ]]; then
        record_status 'perfetto.pftrace' 'captured'
    else
        rm -f -- "$target"
        record_status 'perfetto.pftrace' 'failed'
        required_failures=1
    fi
}

write_device_info
capture_shell 'webview-provider.txt' dumpsys webviewupdate
capture_shell 'gfxinfo-framestats.txt' dumpsys gfxinfo "$package_name" framestats
capture_shell 'meminfo.txt' dumpsys meminfo "$package_name"
capture_system_log
write_flow_instructions
capture_perfetto_trace

checksum() {
    local file="$1"
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{print $1}'
    else
        sha256sum "$file" | awk '{print $1}'
    fi
}

{
    printf 'schema_version=1\n'
    printf 'timestamp_utc=%s\n' "$timestamp_utc"
    printf 'flow=%s\n' "$flow"
    printf 'package=%s\n' "$package_name"
    printf 'device_serial=REDACTED_NOT_RECORDED\n'
    printf 'global_animation_settings=UNCHANGED\n'
    printf 'application_data=UNCHANGED\n'
    printf 'log_scope=SYSTEM_EVENTS_MATCHING_PACKAGE_ONLY\n'
    while IFS= read -r artifact; do
        printf 'artifact=%s sha256=%s\n' "$artifact" "$(checksum "$out_dir/$artifact")"
    done < <(find "$out_dir" -maxdepth 1 -type f -not -name 'capture-summary.txt' -exec basename {} \; | sort)
} > "$summary_file"

if [[ "$required_failures" -ne 0 ]]; then
    printf 'capture-host: one or more required artifacts could not be collected; inspect capture-status.txt\n' >&2
    exit 1
fi

printf 'capture-host: captured Flow %s artifacts in %s\n' "$flow" "$out_dir"
