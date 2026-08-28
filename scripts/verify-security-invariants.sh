#!/usr/bin/env bash
set -euo pipefail

repository_root="${1:?repository root is required}"
violations=0

needles=(
  'addJavascriptInterface('
  'android.permission.MANAGE_EXTERNAL_STORAGE'
  'android.permission.QUERY_ALL_PACKAGES'
  'allowFileAccess = true'
  'allowContentAccess = true'
  'allowUniversalAccessFromFileURLs = true'
)

reasons=(
  'Imported content must use an origin-bound WebMessage bridge.'
  'Broad storage access is forbidden; use SAF.'
  'Broad package visibility is forbidden.'
  'WebView file access must remain disabled.'
  'WebView content access must remain disabled.'
  'Universal file URL access must remain disabled.'
)

while IFS= read -r -d '' source_file; do
  for index in "${!needles[@]}"; do
    if grep -Fq "${needles[$index]}" "$source_file"; then
      relative_path="${source_file#"$repository_root"/}"
      printf '%s: %s\n' "$relative_path" "${reasons[$index]}" >&2
      violations=1
    fi
  done
done < <(
  find "$repository_root" -type f \
    \( -name '*.kt' -o -name '*.java' -o -name '*.xml' \) \
    -path '*/src/main/*' -print0
)

workflow_file="$repository_root/.github/workflows/android.yml"
if [[ -f "$workflow_file" ]]; then
  while IFS= read -r action_reference; do
    if [[ "$action_reference" == ./* ]]; then
      continue
    fi
    if [[ ! "$action_reference" =~ ^[^[:space:]#]+@[0-9a-f]{40}$ ]]; then
      printf '.github/workflows/android.yml: Action reference must use an immutable 40-character commit SHA: %s\n' \
        "$action_reference" >&2
      violations=1
    fi
  done < <(
    sed -nE 's/^[[:space:]]*uses:[[:space:]]*([^[:space:]#]+).*$/\1/p' "$workflow_file"
  )
fi

if [[ "$violations" -ne 0 ]]; then
  printf 'Security invariant verification failed.\n' >&2
  exit 1
fi

printf 'Security invariants verified.\n'
