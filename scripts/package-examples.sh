#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${TOOLBOX_EXAMPLE_OUTPUT_DIR:-${repo_root}/build/examples}"
# The shipped examples are the targets marked "bundled" in scripts/ci/tbx-targets.json; a run without names
# packages them. Explicit names may be any registered target whose package.sh delegates to this script, so an
# example keeps packaging on its own after it is un-bundled. Only such targets use the fixed file list below; a
# bundled target with its own packager would silently lose files, so it fails instead.
# A read loop rather than mapfile keeps this working with the bash 3.2 that ships with macOS.
all_examples=()
known_examples=()
other_examples=()
while IFS=' ' read -r kind name; do
  case "${kind}" in
    bundled) all_examples+=("${name}"); known_examples+=("${name}") ;;
    shared) known_examples+=("${name}") ;;
    other) other_examples+=("${name}") ;;
    unshared)
      printf 'Bundled example %s does not delegate its package.sh to scripts/package-examples.sh; its own files would be left out\n' "${name}" >&2
      exit 1 ;;
  esac
done < <(python3 -c '
import json, sys
from pathlib import Path
root = Path(sys.argv[1])
registry = json.loads((root / "scripts/ci/tbx-targets.json").read_text(encoding="utf-8"))
for name, config in registry.items():
    script = root / "examples" / name / "package.sh"
    shared = config.get("package", [])[:2] == ["bash", "package.sh"] and script.is_file() \
        and "scripts/package-examples.sh" in script.read_text(encoding="utf-8")
    if config.get("bundled"):
        print("bundled" if shared else "unshared", name)
    else:
        print("shared" if shared else "other", name)
' "${repo_root}")
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
  case " ${known_examples[*]} " in
    *" ${name} "*) ;;
    *)
      case " ${other_examples[*]-} " in
        *" ${name} "*) printf '%s has its own packager; run examples/%s/package.sh or scripts/ci/tbx.py build\n' "${name}" "${name}" >&2; exit 1 ;;
      esac
      printf 'Unknown example: %s\n' "${name}" >&2; exit 2 ;;
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
