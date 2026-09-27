#!/usr/bin/env bash
set -euo pipefail

source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${source_dir}/../.." && pwd)"
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["version"])' "${source_dir}/manifest.json")"
output_path="${1:-${repo_root}/build/rss-scout/rss-scout-v${version}.tbx}"
stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/toolbox-rss-scout.XXXXXX")"
trap 'rm -rf -- "${stage_dir}"' EXIT

node "${source_dir}/test-private-rules.mjs"
node "${source_dir}/test-rule-sync.mjs"

entries=(
  manifest.json
  index.html
  style.css
  app.js
  core.js
  network.js
  miniflux.js
  rules.js
)
for entry in "${entries[@]}"; do
  cp -- "${source_dir}/${entry}" "${stage_dir}/${entry}"
done

python3 "${repo_root}/scripts/package-tool.py" "${stage_dir}" "${output_path}" --overwrite
unzip -tqq "${output_path}"
printf 'Built %s\n' "${output_path}"
