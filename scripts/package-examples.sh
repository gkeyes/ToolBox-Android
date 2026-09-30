#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${TOOLBOX_EXAMPLE_OUTPUT_DIR:-${repo_root}/build/examples}"
all_examples=(position-calculator quick-notes background-task-demo notification-lab)
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
  entries=(manifest.json index.html style.css app.js icon.png)
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
