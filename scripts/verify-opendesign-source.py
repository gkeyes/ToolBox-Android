#!/usr/bin/env python3
"""Verify the original OpenDesign reference has not drifted during implementation."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parent.parent / "design" / "opendesign"
manifest = json.loads((root / "source.json").read_text())
for name, expected in manifest["files"].items():
    actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f"Source changed: {name}")
print(f'PASS: {len(manifest["files"])} original OpenDesign files match their SHA-256 hashes.')
