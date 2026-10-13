#!/usr/bin/env python3
"""Fail unless every selected Android test entry ran and passed at least one case.

Usage: check-android-results.py "module=Class#method,Class,..."
A bare class belongs to app. Results come from each module's connected-test XML.
"""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def passed_cases(module):
    folder = Path(module) / "build/outputs/androidTest-results/connected"
    return [case for path in folder.rglob("*.xml") for case in ET.parse(path).iter("testcase")
            if not any(child.tag in {"skipped", "failure", "error"} for child in case)]


def main(filters):
    cases, missing = {}, []
    for entry in (part.strip() for part in filters.split(",")):
        if not entry:
            continue
        module, _, target = entry.rpartition("=")
        module = module or "app"
        classname, _, method = target.partition("#")
        if module not in cases:
            cases[module] = passed_cases(module)
        if not any(case.get("classname") == classname and (not method or case.get("name") == method)
                   for case in cases[module]):
            missing.append(entry)
    if missing:
        sys.exit("No passing, non-skipped Android test matched: " + ", ".join(missing))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "")
