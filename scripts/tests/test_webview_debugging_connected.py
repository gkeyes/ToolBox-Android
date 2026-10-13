import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/ci/webview-debugging-connected.sh"

KNOWN = """F/DEBUG   ( 4571): *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
F/DEBUG   ( 4571): pid: 4329, tid: 4329, name: io.toolbox.host  >>> io.toolbox.host <<<
F/DEBUG   ( 4571): signal 11 (SIGSEGV), code 128 (SI_KERNEL), fault addr 0x0000000000000000
F/DEBUG   ( 4571):       #00 pc 0000000004bdb0c0  /data/app/~~Rswy2TNVt__PfDxMMZLlqQ==/com.google.android.trichromelibrary_636771938-1JAUN_EPnhCZo8fn1_vJwA==/TrichromeLibrary.apk!libmonochrome_64.so (offset 0x8cc000) (BuildId: 2fdf3a989203643cc036b36c2d7975dfe53e8ca3)
F/DEBUG   ( 4571):       #01 pc 0000000004bdc199  /data/app/~~Rswy2TNVt__PfDxMMZLlqQ==/com.google.android.trichromelibrary_636771938-1JAUN_EPnhCZo8fn1_vJwA==/TrichromeLibrary.apk!libmonochrome_64.so (offset 0x8cc000) (BuildId: 2fdf3a989203643cc036b36c2d7975dfe53e8ca3)
"""
APP_CODE = KNOWN.replace("4bdb0c0  /data/app/~~Rswy2TNVt__PfDxMMZLlqQ==/com.google.android.trichromelibrary_636771938-1JAUN_EPnhCZo8fn1_vJwA==/TrichromeLibrary.apk!libmonochrome_64.so",
                         "4bdb0c0  /data/app/~~x==/io.toolbox.host-y==/lib/x86_64/libtoolbox.so", 1)
ABORT = KNOWN.replace("signal 11 (SIGSEGV), code 128 (SI_KERNEL)", "signal 6 (SIGABRT), code -1 (SI_QUEUE)")
OTHER_PROCESS = KNOWN + KNOWN.replace(">>> io.toolbox.host <<<", ">>> com.android.systemui <<<")

# ./gradlew fails while FAKE_FAILURES says so; adb's logcat dump prints FAKE_LOGCAT.
FAKE_GRADLEW = """#!/usr/bin/env bash
n=$(( $(cat "$FAKE_DIR/runs" 2>/dev/null || echo 0) + 1 )); echo $n > "$FAKE_DIR/runs"
echo "gradle run $n $*"
[ "$n" -gt "$FAKE_FAILURES" ]
"""
FAKE_ADB = """#!/usr/bin/env bash
case "$*" in *" -d -b all "*) printf '%s' "$FAKE_LOGCAT" ;; esac
exit 0
"""


class WebViewDebuggingConnectedTest(unittest.TestCase):
    def run_script(self, failures, logcat=""):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            (work / "bin").mkdir()
            for path, body in {work / "gradlew": FAKE_GRADLEW, work / "bin/adb": FAKE_ADB}.items():
                path.write_text(body)
                path.chmod(0o755)
            environment = os.environ | {"PATH": f"{work / 'bin'}:{os.environ['PATH']}", "FAKE_DIR": str(work),
                                        "FAKE_FAILURES": str(failures), "FAKE_LOGCAT": logcat}
            result = subprocess.run(["bash", str(SCRIPT), "connectedDebugAndroidTest", "-Pextra=1"], cwd=work,
                                    env=environment, capture_output=True, text=True)
            runs = int((work / "runs").read_text())
            first = (work / "build/webview-debugging/first-attempt/logcat.txt").exists()
            return result, runs, first

    def test_pass_runs_once(self):
        result, runs, _ = self.run_script(failures=0)
        self.assertEqual((0, 1), (result.returncode, runs), result.stdout)

    def test_known_webview_crash_is_retried_once_and_keeps_first_evidence(self):
        result, runs, first = self.run_script(failures=1, logcat=KNOWN)
        self.assertEqual((0, 2, True), (result.returncode, runs, first), result.stdout)
        self.assertIn("Known WebView crash", result.stdout)

    def test_known_crash_twice_fails(self):
        result, runs, _ = self.run_script(failures=2, logcat=KNOWN)
        self.assertEqual((1, 2), (result.returncode, runs))

    def test_failure_without_crash_is_not_retried(self):
        result, runs, _ = self.run_script(failures=1)
        self.assertEqual((1, 1), (result.returncode, runs))

    def test_other_crashes_are_not_retried(self):
        for logcat in (APP_CODE, ABORT, OTHER_PROCESS):
            result, runs, _ = self.run_script(failures=1, logcat=logcat)
            self.assertEqual((1, 1), (result.returncode, runs), logcat)


if __name__ == "__main__":
    unittest.main()
