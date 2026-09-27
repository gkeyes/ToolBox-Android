#!/usr/bin/env bash
set -euo pipefail

source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${source_dir}/../.." && pwd)"
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["version"])' "${source_dir}/manifest.json")"
output_path="${1:-${repo_root}/build/rss-scout/rss-scout-v${version}.tbx}"
stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/toolbox-rss-scout.XXXXXX")"
entries=(manifest.json index.html style.css core.js network.js miniflux.js rules.js app.js)
trap 'rm -rf -- "${stage_dir}"' EXIT

node "${source_dir}/test-private-rules.mjs"
node "${source_dir}/test-rule-sync.mjs"

for entry in "${entries[@]}"; do
  [[ -f "${source_dir}/${entry}" ]] || { printf 'Missing %s in %s\n' "${entry}" "${source_dir}" >&2; exit 1; }
  cp -- "${source_dir}/${entry}" "${stage_dir}/${entry}"
done

mkdir -p -- "$(dirname -- "${output_path}")"
python3 "${repo_root}/scripts/package-tool.py" "${stage_dir}" "${output_path}" --overwrite
