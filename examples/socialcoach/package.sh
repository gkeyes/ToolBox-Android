#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
output="${1:-../../build/socialcoach-v1.0.0.tbx}"
python3 ../../scripts/package-tool.py dist "$output"
