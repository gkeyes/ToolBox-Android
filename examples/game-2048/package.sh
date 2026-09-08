#!/usr/bin/env bash
set -euo pipefail

package_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
output_path="${1:-${package_dir}/../packages/game-2048-v1.0.0.tbx}"
mkdir -p -- "$(dirname -- "${output_path}")"
output_path="$(cd -- "$(dirname -- "${output_path}")" && pwd)/$(basename -- "${output_path}")"
[[ ! -e "${output_path}" ]] || { printf '输出已存在，请指定新路径：%s\n' "${output_path}" >&2; exit 1; }
stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/toolbox-game-2048.XXXXXX")"
entries=(manifest.json index.html style.css game.js app.js icon.svg)
trap 'rm -rf -- "${stage_dir}"' EXIT

for entry in "${entries[@]}"; do
  [[ -f "${package_dir}/${entry}" ]] || { printf '缺少文件：%s\n' "${entry}" >&2; exit 1; }
  cp -- "${package_dir}/${entry}" "${stage_dir}/${entry}"
done

python3 - "${stage_dir}" "${entries[@]}" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
files = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in sys.argv[2:]}
(root / "integrity.json").write_text(
    json.dumps({"schemaVersion": 1, "algorithm": "SHA-256", "files": files}, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY

touch -t 198001010000 "${stage_dir}"/*
(
  cd -- "${stage_dir}"
  zip -X -q "${output_path}" "${entries[@]}" integrity.json
)

unzip -tqq "${output_path}"
expected_entries="$(printf '%s\n' "${entries[@]}" integrity.json | LC_ALL=C sort)"
actual_entries="$(unzip -Z1 "${output_path}" | LC_ALL=C sort)"
[[ "${actual_entries}" == "${expected_entries}" ]] || { printf '打包内容不符合预期：%s\n' "${output_path}" >&2; exit 1; }

printf '已生成 %s  %s\n' "$(shasum -a 256 "${output_path}" | awk '{print $1}')" "${output_path}"
