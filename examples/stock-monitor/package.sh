#!/usr/bin/env bash
set -euo pipefail

source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${source_dir}/../.." && pwd)"
output_path="${1:-${repo_root}/build/examples/stock-monitor.tbx}"
stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/toolbox-stock-monitor.XXXXXX")"
entries=(manifest.json index.html style.css live-summary.js app.js icon.svg)
trap 'rm -rf -- "${stage_dir}"' EXIT

for entry in "${entries[@]}"; do
  [[ -f "${source_dir}/${entry}" ]] || { printf 'Missing %s\n' "${entry}" >&2; exit 1; }
  cp -- "${source_dir}/${entry}" "${stage_dir}/${entry}"
done

python3 "${repo_root}/scripts/package-tool.py" "${stage_dir}" "${output_path}" --overwrite
unzip -tqq "${output_path}"
