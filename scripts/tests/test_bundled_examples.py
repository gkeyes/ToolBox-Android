import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


def workflow_paths(text):
    """The paths filter of each top-level trigger in a workflow, read without a YAML dependency."""
    paths, event, in_paths = {}, None, False
    for line in text.splitlines():
        if line and not line.startswith(" ") and not line.startswith("on:"):
            event = None
        trigger = re.fullmatch(r"  ([a-z_]+):\s*", line)
        if trigger:
            event, in_paths = trigger[1], False
            continue
        if event and re.fullmatch(r"    paths:\s*", line):
            in_paths = True
            paths[event] = []
            continue
        item = re.fullmatch(r'      - "([^"]+)"\s*', line)
        if event and in_paths and item:
            paths[event].append(item[1])
        elif in_paths and line.strip():
            in_paths = False
    return paths


def workflow_includes(patterns, path):
    """GitHub paths-filter semantics: the last matching pattern wins and a leading ! excludes."""
    included = False
    for pattern in patterns:
        negated = pattern.startswith("!")
        glob = pattern[1:] if negated else pattern
        expression = re.escape(glob).replace(r"\*\*", "\0").replace(r"\*", "[^/]*").replace("\0", ".*")
        if re.fullmatch(expression, path):
            included = not negated
    return included


def registry():
    return json.loads((ROOT / "scripts/ci/tbx-targets.json").read_text(encoding="utf-8"))


class BundledExamplesTest(unittest.TestCase):
    def test_bundled_examples_use_the_shared_packager(self):
        # package-examples.sh copies a fixed file list, so a bundled tool with other files must not be bundled.
        script = (ROOT / "scripts/package-examples.sh").read_text(encoding="utf-8")
        entries = re.search(r"^  entries=\(([^)]*)\)$", script, re.MULTILINE)[1].split()
        for name, config in registry().items():
            if not config.get("bundled"):
                continue
            with self.subTest(name=name):
                self.assertEqual(["bash", "package.sh"], config["package"][:2])
                self.assertIn("scripts/package-examples.sh",
                              (ROOT / "examples" / name / "package.sh").read_text(encoding="utf-8"))
                self.assertEqual(set(entries) | {"integrity.json"}, set(config.get("expected_files", [])))

    def test_tbx_ci_triggers_on_every_registered_shared_test(self):
        shared = {path for config in registry().values() for kind in ("unit", "browser")
                  for path in config.get("tests", {}).get(kind, []) if path.startswith("scripts/")}
        self.assertTrue(shared)
        filters = workflow_paths((ROOT / ".github/workflows/tbx.yml").read_text(encoding="utf-8"))
        for event in ("push", "pull_request"):
            for path in sorted(shared):
                with self.subTest(event=event, path=path):
                    self.assertIn(event, filters)
                    self.assertTrue(workflow_includes(filters[event], path), f"tbx.yml {event} paths miss {path}")

    def test_android_ci_triggers_on_every_bundled_example(self):
        # The APK packages the bundled examples, but android.yml can only re-include them by name.
        bundled = {f"examples/{name}/**" for name, config in registry().items() if config.get("bundled")}
        self.assertTrue(bundled)
        filters = workflow_paths((ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8"))
        for event in ("push", "pull_request"):
            with self.subTest(event=event):
                self.assertIn(event, filters)
                included = {path for path in filters[event] if re.fullmatch(r"examples/[^/*]+/\*\*", path)}
                self.assertEqual(bundled, included,
                                 f"android.yml {event} paths must re-include exactly the bundled examples")


if __name__ == "__main__":
    unittest.main()
