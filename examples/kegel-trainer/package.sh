#!/usr/bin/env bash
set -euo pipefail
package_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${package_dir}/../.." && pwd)"
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "${package_dir}/manifest.json")"
output_path="${1:-${repo_root}/build/examples/kegel-trainer-v${version}.tbx}"
python3 - "${package_dir}" "${output_path}" <<'PY'
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

source = Path(sys.argv[1])
output = Path(sys.argv[2]).absolute()
entries = ["manifest.json", "index.html", "style.css", "app.js", "icon.svg",
           "audio/prepare.ogg", "audio/contract.ogg", "audio/relax.ogg", "audio/complete.ogg"]
with tempfile.TemporaryDirectory(prefix="toolbox-kegel-package-") as temporary:
    stage = Path(temporary)
    for name in entries:
        target = stage / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, target)
    subprocess.run([sys.executable, str(source.parent.parent / "scripts/package-tool.py"),
                    str(stage), str(output), "--overwrite"], check=True)
PY
