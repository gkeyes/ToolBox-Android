#!/usr/bin/env bash
set -euo pipefail
health_package_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
health_output="${1:-${health_package_dir}/../../output/health-records/health-records-v1.0.7.tbx}"
python3 "${health_package_dir}/../../scripts/package-tool.py" "${health_package_dir}/web" "${health_output}"
