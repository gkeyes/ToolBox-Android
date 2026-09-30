#!/usr/bin/env bash
set -euo pipefail

package_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "${package_dir}/manifest.json")"
output_path="${1:-${package_dir}/../packages/game-2048-v${version}.tbx}"
stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/toolbox-game-2048.XXXXXX")"
entries=(manifest.json index.html style.css game.js app.js icon.svg)
trap 'rm -rf -- "${stage_dir}"' EXIT

for entry in "${entries[@]}"; do
  [[ -f "${package_dir}/${entry}" ]] || { printf '缺少文件：%s\n' "${entry}" >&2; exit 1; }
  cp -- "${package_dir}/${entry}" "${stage_dir}/${entry}"
done

python3 "${package_dir}/../../scripts/package-tool.py" "${stage_dir}" "${output_path}"
unzip -tqq "${output_path}"
