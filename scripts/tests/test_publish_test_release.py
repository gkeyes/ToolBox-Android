import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/ci/publish-test-release.sh"

# A stand-in for the GitHub CLI: answers the reads publish-test-release.sh makes from FAKE_* variables
# and records every call, so a test can see whether anything was uploaded or the tag moved.
FAKE_GH = r"""#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_LOG"
args="$*"
case "$args" in
  "release view dev --json assets --jq .assets[].name") printf '%s' "$FAKE_ASSETS"; [ -n "$FAKE_ASSETS" ] && echo; exit 0 ;;
  "release view dev") exit 0 ;;
  *"git/ref/tags/dev"*) [ -n "$FAKE_CURRENT" ] || exit 1; echo "$FAKE_CURRENT"; exit 0 ;;
  *"/compare/"*) echo "$FAKE_ORDER"; exit 0 ;;
  *"releases/tags/dev --jq .id") echo 1; exit 0 ;;
  *"assets?per_page=100"*"toolbox-v"*) printf '%s\n' "$FAKE_ASSETS" | grep -E '^toolbox-v.*\.apk$' || true; exit 0 ;;
  *"assets?per_page=100"*) exit 0 ;;
esac
exit 0
"""


class PublishTestReleaseTest(unittest.TestCase):
    def publish(self, kind, files, assets, current="a" * 40, order="ahead"):
        with tempfile.TemporaryDirectory() as temporary:
            temporary = Path(temporary)
            bin_dir, delivery = temporary / "bin", temporary / "delivery"
            bin_dir.mkdir()
            delivery.mkdir()
            (bin_dir / "gh").write_text(FAKE_GH)
            (bin_dir / "gh").chmod(0o755)
            (bin_dir / "sleep").write_text("#!/bin/sh\nexit 0\n")
            (bin_dir / "sleep").chmod(0o755)
            for name in files:
                (delivery / name).write_text(name)
            log = temporary / "gh.log"
            log.write_text("")
            environment = os.environ | {
                "PATH": f"{bin_dir}:{os.environ['PATH']}", "FAKE_LOG": str(log), "FAKE_ASSETS": "\n".join(assets),
                "FAKE_CURRENT": current, "FAKE_ORDER": order, "GH_REPO": "owner/repo", "GITHUB_SHA": "b" * 40,
                "GITHUB_RUN_ID": "7",
            }
            result = subprocess.run(["bash", str(SCRIPT), kind, str(delivery)], env=environment,
                                    capture_output=True, text=True)
            return result, log.read_text().splitlines()

    @staticmethod
    def uploads(calls):
        return [call for call in calls if call.startswith("release upload") or "git/refs/tags/dev" in call]

    def test_same_app_version_is_not_published_again(self):
        result, calls = self.publish("app", ["toolbox-v0.9.0-release.apk", "BUILD_RECEIPT.txt"],
                                     ["toolbox-v0.9.0-dev.apk", "BUILD_RECEIPT.txt"])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("already has ToolBox 0.9.0", result.stdout)
        self.assertEqual([], self.uploads(calls))

    def test_higher_app_version_is_published_and_moves_the_tag(self):
        result, calls = self.publish("app", ["toolbox-v0.9.0-release.apk", "BUILD_RECEIPT.txt"],
                                     ["toolbox-v0.8.32-dev.apk", "BUILD_RECEIPT.txt"])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any("toolbox-v0.9.0-dev.apk" in call for call in calls if call.startswith("release upload")))
        self.assertTrue(any("git/refs/tags/dev" in call for call in calls))

    def test_lower_app_version_is_refused(self):
        result, calls = self.publish("app", ["toolbox-v0.8.33-release.apk", "BUILD_RECEIPT.txt"],
                                     ["toolbox-v0.9.0-dev.apk"])
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.uploads(calls))

    def test_order_guard_still_skips_an_older_commit(self):
        result, calls = self.publish("app", ["toolbox-v0.9.1-release.apk", "BUILD_RECEIPT.txt"],
                                     ["toolbox-v0.9.0-dev.apk"], order="behind")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("newer than", result.stdout)
        self.assertEqual([], self.uploads(calls))

    def test_unknown_channel_state_fails_closed(self):
        result, calls = self.publish("app", ["toolbox-v0.9.1-release.apk", "BUILD_RECEIPT.txt"],
                                     ["toolbox-v0.9.0-dev.apk"], current="")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.uploads(calls))

    def test_first_app_publish_on_a_tools_only_release(self):
        result, calls = self.publish("app", ["toolbox-v0.9.0-release.apk", "BUILD_RECEIPT.txt"], ["game-2048-v1.0.4.tbx"])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any("toolbox-v0.9.0-dev.apk" in call for call in calls if call.startswith("release upload")))

    def test_tool_versions_already_published_are_skipped(self):
        result, calls = self.publish("tools", ["game-2048-v1.0.4.tbx", "rss-scout-v1.4.7.tbx"],
                                     ["game-2048-v1.0.4.tbx", "rss-scout-v1.4.6.tbx"])
        self.assertEqual(0, result.returncode, result.stderr)
        uploads = [call for call in calls if call.startswith("release upload")]
        self.assertFalse(any("game-2048" in call for call in uploads))
        self.assertTrue(any("rss-scout-v1.4.7.tbx" in call for call in uploads))
        self.assertTrue(any("delete-asset dev rss-scout-v1.4.6.tbx" in call for call in calls))


if __name__ == "__main__":
    unittest.main()
