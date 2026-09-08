#!/usr/bin/env bash
set -euo pipefail
package_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
output_path="${1:-${package_dir}/../packages/kegel-trainer-v1.0.2.tbx}"
python3 - "${package_dir}" "${output_path}" <<'PY'
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

source = Path(sys.argv[1])
output = Path(sys.argv[2]).absolute()
entries = ["manifest.json", "index.html", "style.css", "app.js", "icon.png",
           "audio/prepare.ogg", "audio/contract.ogg", "audio/relax.ogg", "audio/complete.ogg"]
with tempfile.TemporaryDirectory(prefix="toolbox-kegel-package-") as temporary:
    stage = Path(temporary)
    for name in entries:
        target = stage / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, target)
    subprocess.run([sys.executable, str(source.parent.parent / "scripts/package-tool.py"),
                    str(stage), str(output)], check=True)
PY
