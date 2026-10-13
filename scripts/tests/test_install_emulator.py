import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/ci/install-emulator.sh"

# Answers like sdkmanager (it reads only the licence prompts it needs, so it never drains `yes`). Fails the first FAKE_FAILURES calls the way Google's mirror does: a warning about a bad archive, sometimes
# with exit status 0, so the script has to read the output.
FAKE_SDKMANAGER = r"""#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_LOG"
calls=$(wc -l < "$FAKE_LOG")
if [ "$calls" -le "$FAKE_FAILURES" ]; then
  echo "Warning: An error occurred while preparing SDK package Android Emulator: Error on ZipFile unknown archive."
  exit "$FAKE_STATUS"
fi
exit 0
"""


class InstallEmulatorTest(unittest.TestCase):
    def install(self, failures, status=0):
        with tempfile.TemporaryDirectory() as temporary:
            temporary = Path(temporary)
            bin_dir = temporary / "bin"
            bin_dir.mkdir()
            for name, body in {"sdkmanager": FAKE_SDKMANAGER, "sleep": "#!/bin/sh\nexit 0\n"}.items():
                (bin_dir / name).write_text(body)
                (bin_dir / name).chmod(0o755)
            log = temporary / "calls.log"
            log.write_text("")
            environment = os.environ | {"PATH": f"{bin_dir}:{os.environ['PATH']}", "FAKE_LOG": str(log),
                                        "FAKE_FAILURES": str(failures), "FAKE_STATUS": str(status)}
            result = subprocess.run(["bash", str(SCRIPT), "35", "google_apis_playstore", "x86_64"],
                                    env=environment, capture_output=True, text=True)
            return result, log.read_text().splitlines()

    def test_installs_emulator_and_the_requested_image(self):
        result, calls = self.install(failures=0)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--install emulator system-images;android-35;google_apis_playstore;x86_64 --channel=0"], calls)

    def test_bad_archive_reported_as_warning_is_retried(self):
        result, calls = self.install(failures=2, status=0)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(3, len(calls))

    def test_failed_exit_is_retried(self):
        result, calls = self.install(failures=1, status=1)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(calls))

    def test_gives_up_after_four_attempts(self):
        result, calls = self.install(failures=9)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(4, len(calls))
        self.assertIn("after 4 attempts", result.stderr)


if __name__ == "__main__":
    unittest.main()
