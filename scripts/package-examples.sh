#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${TOOLBOX_EXAMPLE_OUTPUT_DIR:-${repo_root}/build/examples}"
# The shipped examples are the targets marked "bundled" in scripts/ci/tbx-targets.json.
# A read loop rather than mapfile keeps this working with the bash 3.2 that ships with macOS.
all_examples=()
while IFS= read -r name; do all_examples+=("${name}"); done < <(python3 -c '
import json, sys
registry = json.load(open(sys.argv[1], encoding="utf-8"))
print("\n".join(name for name, config in registry.items() if config.get("bundled")))
' "${repo_root}/scripts/ci/tbx-targets.json")
[[ ${#all_examples[@]} -gt 0 ]] || { printf 'No bundled examples in tbx-targets.json\n' >&2; exit 1; }
requested=("$@")
output_override=""

if [[ ${#requested[@]} -eq 2 && "${requested[1]}" == *.tbx ]]; then
  output_override="${requested[1]}"
  requested=("${requested[0]}")
fi

if [[ ${#requested[@]} -eq 0 ]]; then
  requested=("${all_examples[@]}")
fi

for name in "${requested[@]}"; do
  case " ${all_examples[*]} " in
    *" ${name} "*) ;;
    *) printf 'Unknown example: %s\n' "${name}" >&2; exit 2 ;;
  esac

  source_dir="${repo_root}/examples/${name}"
  output_path="${output_override:-${output_dir}/${name}.tbx}"
  stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/toolbox-${name}.XXXXXX")"
  entries=(manifest.json index.html style.css app.js icon.svg)
  trap 'rm -rf -- "${stage_dir}"' EXIT

  for entry in "${entries[@]}"; do
    [[ -f "${source_dir}/${entry}" ]] || { printf 'Missing %s in %s\n' "${entry}" "${source_dir}" >&2; exit 1; }
    cp -- "${source_dir}/${entry}" "${stage_dir}/${entry}"
  done

  python3 "${repo_root}/scripts/package-tool.py" "${stage_dir}" "${output_path}" --overwrite
  unzip -tqq "${output_path}"
  rm -rf -- "${stage_dir}"
  trap - EXIT
done
