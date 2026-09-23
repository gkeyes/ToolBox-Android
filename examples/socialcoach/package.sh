#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
version="$(python3 -c 'import json; print(json.load(open("manifest.json"))["version"])')"
output="${1:-../../build/socialcoach-v${version}.tbx}"
python3 ../../scripts/package-tool.py dist "$output"
