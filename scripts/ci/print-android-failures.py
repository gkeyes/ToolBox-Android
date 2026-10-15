#!/usr/bin/env python3
"""Print each failed connected-test case with the top of its stack trace.

The uploaded reports need a blob download that some reviewers cannot reach, so the log carries the cause.
"""

from pathlib import Path
import xml.etree.ElementTree as ET

for module in ("app", "tool-runtime", "core-data"):
    for path in sorted((Path(module) / "build/outputs/androidTest-results/connected").rglob("*.xml")):
        for case in ET.parse(path).iter("testcase"):
            for child in case:
                if child.tag in {"failure", "error"}:
                    print(f"::group::{module} {case.get('classname')}#{case.get('name')} {child.tag}")
                    print("\n".join((child.text or child.get("message") or "").splitlines()[:40]))
                    print("::endgroup::")
