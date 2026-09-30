#!/usr/bin/env bash
set -euo pipefail
health_package_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
health_version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["version"])' "${health_package_dir}/web/manifest.json")"
health_output="${1:-${health_package_dir}/../../output/health-records/health-records-v${health_version}.tbx}"
python3 "${health_package_dir}/../../scripts/package-tool.py" "${health_package_dir}/web" "${health_output}"
