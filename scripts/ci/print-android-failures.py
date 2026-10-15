#!/usr/bin/env python3
"""Print each failed connected-test case with the top of its stack trace, and summarize what ran.

The uploaded reports need a blob download that some reviewers cannot reach, so the log carries the
cause, and the step summary records the filter and per-class counts so a green run can be checked
without reading the log.
"""

from collections import Counter
from pathlib import Path
import os
import xml.etree.ElementTree as ET

counts = {}
for module in ("app", "tool-runtime", "core-data"):
    for path in sorted((Path(module) / "build/outputs/androidTest-results/connected").rglob("*.xml")):
        for case in ET.parse(path).iter("testcase"):
            outcome = "passed"
            for child in case:
                if child.tag in {"failure", "error"}:
                    outcome = "failed"
                    print(f"::group::{module} {case.get('classname')}#{case.get('name')} {child.tag}")
                    print("\n".join((child.text or child.get("message") or "").splitlines()[:40]))
                    print("::endgroup::")
                elif child.tag == "skipped" and outcome == "passed":
                    outcome = "skipped"
            counts.setdefault((module, case.get("classname")), Counter())[outcome] += 1

summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    test_filter = os.environ.get("TOOLBOX_ANDROID_TEST_FILTER") or "(all Android behavior tests)"
    lines = ["### Android behavior tests", "", f"Filter: `{test_filter}`", "",
             "| Module | Class | Passed | Failed | Skipped |", "| --- | --- | --- | --- | --- |"]
    for (module, classname), tally in sorted(counts.items(), key=lambda item: (item[0][0], item[0][1] or "")):
        lines.append(f"| {module} | `{classname}` | {tally['passed']} | {tally['failed']} | {tally['skipped']} |")
    if not counts:
        lines.append("| - | no test results | 0 | 0 | 0 |")
    with open(summary, "a", encoding="utf-8") as out:
        out.write("\n".join(lines) + "\n")
