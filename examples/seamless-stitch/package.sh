#!/usr/bin/env bash
set -euo pipefail
package_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${package_dir}/../.." && pwd)"
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["version"])' "${package_dir}/manifest.json")"
output_path="${1:-${repo_root}/build/examples/seamless-stitch-v${version}.tbx}"
stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/toolbox-seamless-stitch.XXXXXX")"
trap 'rm -rf -- "${stage_dir}"' EXIT
entries=(manifest.json index.html style.css app.js engine.js runtime.js worker.js icon.svg README.md THIRD_PARTY_NOTICES.md licenses/ToolBox-MIT.txt licenses/screenStitch-MIT.txt licenses/screenshot-stitcher-MIT.txt)
for entry in "${entries[@]}"; do
  mkdir -p -- "${stage_dir}/$(dirname -- "${entry}")"
  cp -- "${package_dir}/${entry}" "${stage_dir}/${entry}"
done
python3 "${repo_root}/scripts/package-tool.py" "${stage_dir}" "${output_path}" --overwrite
unzip -tqq "${output_path}"
