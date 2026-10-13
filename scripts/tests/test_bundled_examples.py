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


class BundledExamplesTest(unittest.TestCase):
    def test_android_ci_triggers_on_every_bundled_example(self):
        # The APK packages the bundled examples, but android.yml can only re-include them by name.
        registry = json.loads((ROOT / "scripts/ci/tbx-targets.json").read_text(encoding="utf-8"))
        bundled = {f"examples/{name}/**" for name, config in registry.items() if config.get("bundled")}
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
