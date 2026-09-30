#!/usr/bin/env bash
set -euo pipefail

source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${source_dir}/../.." && pwd)"
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["version"])' "${source_dir}/manifest.json")"
[[ "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { printf 'Invalid tool version\n' >&2; exit 1; }
output_path="${1:-${repo_root}/build/github-actions-watcher/github-actions-watcher-v${version}.tbx}"
stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/toolbox-github-actions-watcher.XXXXXX")"
entries=(manifest.json index.html style.css github-model.js reliability.js app.js icon.png)
trap 'rm -rf -- "${stage_dir}"' EXIT

for entry in "${entries[@]}"; do
  [[ -f "${source_dir}/${entry}" ]] || { printf 'Missing %s\n' "${entry}" >&2; exit 1; }
  cp -- "${source_dir}/${entry}" "${stage_dir}/${entry}"
done

python3 "${repo_root}/scripts/package-tool.py" "${stage_dir}" "${output_path}" --overwrite
unzip -tqq "${output_path}"
