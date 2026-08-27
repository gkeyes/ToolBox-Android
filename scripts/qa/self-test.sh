#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
validator="$script_dir/verify-receipt.sh"
requested_case="all"
fixture_sha='13c59aed9c3fd53fcdcd70079335602531390930'

if [[ "${1:-}" == "--case" ]]; then
    requested_case="${2:-}"
    [[ -n "$requested_case" ]] || {
        printf '%s\n' 'USAGE: self-test.sh [--case valid|stale-sha|missing-action-log|missing-ui-tree|missing-cleanup-receipt]' >&2
        exit 2
    }
    shift 2
fi
[[ $# -eq 0 ]] || {
    printf '%s\n' 'USAGE: self-test.sh [--case valid|stale-sha|missing-action-log|missing-ui-tree|missing-cleanup-receipt]' >&2
    exit 2
}

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/toolbox-qa-self-test.XXXXXX")"
trap 'rm -rf -- "$scratch_dir"' EXIT

artifact="$scratch_dir/artifact.txt"
action_log="$scratch_dir/actions.txt"
ui_tree="$scratch_dir/ui.xml"
cleanup_receipt="$scratch_dir/cleanup.txt"
printf '%s\n' 'artifact' > "$artifact"
printf '%s\n' 'action-log' > "$action_log"
printf '%s\n' '<hierarchy />' > "$ui_tree"
printf '%s\n' 'cleanup_status=CLEAN' > "$cleanup_receipt"

write_receipt() {
    local destination="$1"
    local action_value="$2"
    local ui_value="$3"
    local cleanup_value="$4"
    {
        printf '%s\n' \
            'schema_version=2' \
            'timestamp_utc=2026-08-27T00:00:00Z' \
            "candidate_sha=$fixture_sha" \
            'invocation=bash scripts/qa/self-test.sh' \
            'exit_code=0' \
            'artifact_path=artifact.txt' \
            "action_log_path=$action_value" \
            "ui_tree_path=$ui_value" \
            "cleanup_receipt_path=$cleanup_value" \
            'cleanup_status=CLEAN'
    } > "$destination"
}

run_case() {
    local case_name="$1"
    local receipt="$scratch_dir/$case_name.receipt"
    case "$case_name" in
        valid)
            write_receipt "$receipt" 'actions.txt' 'ui.xml' 'cleanup.txt'
            "$validator" "$receipt" "$fixture_sha"
            ;;
        stale-sha)
            write_receipt "$receipt" 'actions.txt' 'ui.xml' 'cleanup.txt'
            "$validator" "$receipt" '0000000000000000000000000000000000000000'
            ;;
        missing-action-log)
            write_receipt "$receipt" 'missing-actions.txt' 'ui.xml' 'cleanup.txt'
            "$validator" "$receipt" "$fixture_sha"
            ;;
        missing-ui-tree)
            write_receipt "$receipt" 'actions.txt' 'missing-ui.xml' 'cleanup.txt'
            "$validator" "$receipt" "$fixture_sha"
            ;;
        missing-cleanup-receipt)
            write_receipt "$receipt" 'actions.txt' 'ui.xml' 'missing-cleanup.txt'
            "$validator" "$receipt" "$fixture_sha"
            ;;
        *)
            printf 'UNKNOWN_SELF_TEST_CASE: %s\n' "$case_name" >&2
            return 2
            ;;
    esac
}

if [[ "$requested_case" != "all" ]]; then
    run_case "$requested_case"
    exit $?
fi

run_case valid
for invalid_case in stale-sha missing-action-log missing-ui-tree missing-cleanup-receipt; do
    if run_case "$invalid_case"; then
        printf 'SELF_TEST_FAILURE: %s unexpectedly passed\n' "$invalid_case" >&2
        exit 1
    fi
done
printf '%s\n' 'SELF_TEST_VALID: receipt validator accepts complete evidence and rejects incomplete evidence'
