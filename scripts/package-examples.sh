#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${TOOLBOX_EXAMPLE_OUTPUT_DIR:-${repo_root}/build/examples}"
all_examples=(position-calculator quick-notes background-task-demo)
requested=("$@")

if [[ ${#requested[@]} -eq 0 ]]; then
  requested=("${all_examples[@]}")
fi

for name in "${requested[@]}"; do
  case " ${all_examples[*]} " in
    *" ${name} "*) ;;
    *) printf 'Unknown example: %s\n' "${name}" >&2; exit 2 ;;
  esac

  source_dir="${repo_root}/examples/${name}"
  output_path="${output_dir}/${name}.tbx"
  stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/toolbox-${name}.XXXXXX")"
  entries=(manifest.json index.html style.css app.js icon.svg)
  trap 'rm -rf -- "${stage_dir}"' EXIT

  for entry in "${entries[@]}"; do
    [[ -f "${source_dir}/${entry}" ]] || { printf 'Missing %s in %s\n' "${entry}" "${source_dir}" >&2; exit 1; }
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
  [[ "${actual_entries}" == "${expected_entries}" ]] || { printf 'Unexpected package contents: %s\n' "${output_path}" >&2; exit 1; }
  printf 'Built %s  %s\n' "$(shasum -a 256 "${output_path}" | awk '{print $1}')" "${output_path}"
  rm -rf -- "${stage_dir}"
  trap - EXIT
done
