#!/usr/bin/env bash
set -euo pipefail

source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${source_dir}/../.." && pwd)"
output_dir="${repo_root}/build/examples"
output_path="${output_dir}/notification-lab.tbx"
stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/toolbox-notification-lab.XXXXXX")"
entries=(manifest.json index.html style.css app.js icon.png)
trap 'rm -rf -- "${stage_dir}"' EXIT

for entry in "${entries[@]}"; do
  [[ -f "${source_dir}/${entry}" ]] || { printf 'Missing %s\n' "${entry}" >&2; exit 1; }
  cp -- "${source_dir}/${entry}" "${stage_dir}/${entry}"
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
mkdir -p -- "${output_dir}"
rm -f -- "${output_path}"
(
  cd -- "${stage_dir}"
  zip -X -q "${output_path}" "${entries[@]}" integrity.json
)
unzip -tqq "${output_path}"
expected_entries="$(printf '%s\n' "${entries[@]}" integrity.json | LC_ALL=C sort)"
actual_entries="$(unzip -Z1 "${output_path}" | LC_ALL=C sort)"
[[ "${actual_entries}" == "${expected_entries}" ]] || { printf 'Unexpected package contents\n' >&2; exit 1; }
printf 'Built %s  %s\n' "$(shasum -a 256 "${output_path}" | awk '{print $1}')" "${output_path}"
