#!/usr/bin/env bash
set -euo pipefail

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

receipt_path="${1:-}"
[[ -n "$receipt_path" ]] || fail "USAGE: verify-receipt.sh <receipt-path>"
[[ -f "$receipt_path" && -s "$receipt_path" ]] || fail "EVIDENCE_INCOMPLETE: receipt missing"

schema_version=""
timestamp_utc=""
candidate_sha=""
invocation=""
exit_code=""
artifact_path=""
action_log_path=""
ui_tree_path=""
cleanup_receipt_path=""
cleanup_status=""

while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" == *=* ]] || fail "EVIDENCE_INCOMPLETE: malformed receipt"
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
        schema_version|timestamp_utc|candidate_sha|invocation|exit_code|artifact_path|action_log_path|ui_tree_path|cleanup_receipt_path|cleanup_status)
            [[ -z "${!key}" ]] || fail "EVIDENCE_INCOMPLETE: duplicate receipt field $key"
            printf -v "$key" '%s' "$value"
            ;;
        *)
            fail "EVIDENCE_INCOMPLETE: unknown receipt field"
            ;;
    esac
done < "$receipt_path"

[[ "$schema_version" == "1" ]] || fail "EVIDENCE_INCOMPLETE: unsupported receipt schema"
[[ "$timestamp_utc" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || fail "EVIDENCE_INCOMPLETE: UTC timestamp missing"
[[ "$candidate_sha" =~ ^[0-9a-f]{40}$ ]] || fail "EVIDENCE_INCOMPLETE: full candidate SHA missing"
[[ -n "$invocation" ]] || fail "EVIDENCE_INCOMPLETE: invocation missing"
[[ "$exit_code" =~ ^[0-9]+$ ]] || fail "EVIDENCE_INCOMPLETE: exit code missing"
[[ "$artifact_path" = /* && -f "$artifact_path" && -s "$artifact_path" ]] || fail "EVIDENCE_INCOMPLETE: artifact missing"
[[ "$action_log_path" = /* && -f "$action_log_path" && -s "$action_log_path" ]] || fail "EVIDENCE_INCOMPLETE: action log missing"
[[ "$ui_tree_path" = /* && -f "$ui_tree_path" && -s "$ui_tree_path" ]] || fail "EVIDENCE_INCOMPLETE: UI tree missing"
[[ "$cleanup_receipt_path" = /* && -f "$cleanup_receipt_path" && -s "$cleanup_receipt_path" ]] || fail "EVIDENCE_INCOMPLETE: cleanup receipt missing"
[[ "$cleanup_status" == "CLEAN" ]] || fail "EVIDENCE_INCOMPLETE: cleanup status is not CLEAN"
grep -Fqx 'cleanup_status=CLEAN' "$cleanup_receipt_path" || fail "EVIDENCE_INCOMPLETE: cleanup receipt is not CLEAN"

printf 'EVIDENCE_VALID: %s\n' "$receipt_path"
