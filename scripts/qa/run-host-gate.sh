#!/usr/bin/env bash
set -euo pipefail

fail() {
    printf '%s\n' "$1" >&2
    exit 2
}

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd -- "$script_dir/../.." && pwd)"
attempt_dir=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --attempt)
            attempt_dir="${2:-}"
            shift 2
            ;;
        *)
            fail 'USAGE: run-host-gate.sh --attempt <absolute-dir>'
            ;;
    esac
done

[[ "$attempt_dir" = /* ]] || fail 'USAGE: run-host-gate.sh --attempt <absolute-dir>'
mkdir -p "$attempt_dir"
candidate_sha="$(git -C "$repository_root" rev-parse HEAD)"
timestamp_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'UTC=%s\nCANDIDATE_SHA=%s\nSTATE=HOST_GATE_STARTED\n' \
    "$timestamp_utc" "$candidate_sha" > "$attempt_dir/host-gate.bootstrap.txt"

resolve_java_home() {
    local candidate=""
    if [[ -n "${TOOLBOX_JAVA_HOME:-}" ]]; then
        candidate="$TOOLBOX_JAVA_HOME"
        [[ -x "$candidate/bin/java" ]] || fail 'TOOLBOX_JAVA_HOME must name a JDK 21 home'
        "$candidate/bin/java" -version 2>&1 | grep -q 'version "21\.' || fail 'TOOLBOX_JAVA_HOME must name a JDK 21 home'
        printf '%s\n' "$candidate"
        return
    fi

    if [[ -n "${JAVA_HOME:-}" ]]; then
        candidate="$JAVA_HOME"
        if [[ -x "$candidate/bin/java" ]] && "$candidate/bin/java" -version 2>&1 | grep -q 'version "21\.'; then
            printf '%s\n' "$candidate"
            return
        fi
    fi

    candidate='/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'
    if [[ -x "$candidate/bin/java" ]] && "$candidate/bin/java" -version 2>&1 | grep -q 'version "21\.'; then
        printf '%s\n' "$candidate"
        return
    fi

    if [[ -x /usr/libexec/java_home ]]; then
        candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        if [[ -n "$candidate" && -x "$candidate/bin/java" ]] && "$candidate/bin/java" -version 2>&1 | grep -q 'version "21\.'; then
            printf '%s\n' "$candidate"
            return
        fi
    fi
    fail 'JDK 21 not found: set TOOLBOX_JAVA_HOME to a JDK 21 home'
}

java_home="$(resolve_java_home)"
grep -Eq 'compileSdk[[:space:]]*=[[:space:]]*37' "$repository_root/app/build.gradle.kts" || fail 'SDK 37 baseline missing from app/build.gradle.kts'
action_log="$attempt_dir/host-gate.actions.txt"
surface_record="$attempt_dir/host-gate.ui-surface.txt"
summary="$attempt_dir/host-gate.summary.txt"
cleanup_receipt="$attempt_dir/host-gate.cleanup.txt"
receipt="$attempt_dir/host-gate.receipt"

printf 'UTC=%s\nCANDIDATE_SHA=%s\nJDK_HOME=%s\nSDK_BASELINE=37\n' \
    "$timestamp_utc" "$candidate_sha" "$java_home" > "$action_log"
printf '%s\n' \
    'scope=HOST_BUILD_GATE_ONLY' \
    'device_ui_tree=NOT_COLLECTED' \
    'reason=This gate does not operate a device and must not be used as release device evidence.' > "$surface_record"
printf 'UTC=%s\nCANDIDATE_SHA=%s\nSDK_BASELINE=37\n' \
    "$timestamp_utc" "$candidate_sha" > "$summary"

run_gate() {
    local name="$1"
    shift
    local log_name="host-gate.$name.log"
    local log_file="$attempt_dir/$log_name"
    local invocation="./gradlew --no-daemon $* --console=plain"
    printf 'UTC=%s\nCANDIDATE_SHA=%s\nGATE=%s\nINVOCATION=%s\n' \
        "$timestamp_utc" "$candidate_sha" "$name" "$invocation" > "$log_file"
    printf 'UTC=%s\nGATE=%s\nINVOCATION=%s\n' "$timestamp_utc" "$name" "$invocation" >> "$action_log"
    set +e
    (
        cd "$repository_root"
        JAVA_HOME="$java_home" ./gradlew --no-daemon "$@" --console=plain
    ) >> "$log_file" 2>&1
    local command_exit=$?
    set -e
    printf 'EXIT_CODE=%s\n' "$command_exit" >> "$log_file"
    printf 'EXIT_CODE=%s\nLOG=%s\n' "$command_exit" "$log_name" >> "$action_log"
    printf 'GATE=%s EXIT_CODE=%s LOG=%s\n' "$name" "$command_exit" "$log_name" >> "$summary"
    [[ "$command_exit" -eq 0 ]]
}

overall_exit=0
run_gate api-contract :tool-api:verifyToolBoxApiContract || overall_exit=1
run_gate security verifySecurityInvariants || overall_exit=1
run_gate compile \
    :app:compileDebugKotlin \
    :tool-runtime:compileDebugAndroidTestKotlin || overall_exit=1
run_gate admitted-unit \
    :app:testDebugUnitTest \
    :core-data:testDebugUnitTest \
    :tool-package:testDebugUnitTest \
    :tool-runtime:testDebugUnitTest \
    --tests 'io.toolbox.host.importflow.ToolBoxOpenDocumentTest' \
    --tests 'io.toolbox.host.permissions.PermissionCenterViewModelTest' \
    --tests 'io.toolbox.host.background.BackgroundTaskPolicyTest' \
    --tests 'io.toolbox.host.background.NetworkBoundaryTest' \
    --tests 'io.toolbox.host.background.ToolNetworkProxyTest' \
    --tests 'io.toolbox.host.background.BackgroundTasksPresentationTest' \
    --tests 'io.toolbox.host.runtime.LiveNotificationCoordinatorTest' \
    --tests 'io.toolbox.host.runtime.RuntimeNotificationRegressionTest' \
    --tests 'io.toolbox.host.runtime.RuntimeReminderPolicyTest' \
    --tests 'io.toolbox.core.data.BackgroundTaskRepositoryTest' \
    --tests 'io.toolbox.core.data.CatalogAndStorageRepositoryTest' \
    --tests 'io.toolbox.tool.packagekit.lifecycle.DirectPackageLifecycleTest' \
    --tests 'io.toolbox.tool.runtime.RuntimeRpcDispatcherTest' || overall_exit=1

printf '%s\n' \
    "timestamp_utc=$timestamp_utc" \
    'scope=HOST_BUILD_GATE_ONLY' \
    'device_mutation=NONE' \
    'cleanup_status=CLEAN' > "$cleanup_receipt"

{
    printf '%s\n' \
        'schema_version=2' \
        "timestamp_utc=$timestamp_utc" \
        "candidate_sha=$candidate_sha" \
        'invocation=bash scripts/qa/run-host-gate.sh --attempt <absolute-dir>' \
        "exit_code=$overall_exit" \
        'artifact_path=host-gate.summary.txt' \
        'action_log_path=host-gate.actions.txt' \
        'ui_tree_path=host-gate.ui-surface.txt' \
        'cleanup_receipt_path=host-gate.cleanup.txt' \
        'cleanup_status=CLEAN'
} > "$receipt"

"$script_dir/verify-receipt.sh" "$receipt" "$candidate_sha" >/dev/null
printf 'HOST_GATE_RECEIPT: %s\n' "$receipt"
exit "$overall_exit"
