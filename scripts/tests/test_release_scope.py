import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "ci/verify_release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseScopeTest(unittest.TestCase):
    def fixture(self):
        return dict(startup=None, config={"applicationId": "io.toolbox.host", "versionName": "0.8.5", "versionCode": "38"},
                    apk_sha256="b" * 64, allow_not_run=True, host_checks="build_and_unit_only",
                    host_scope="current_run", host_commit="a" * 40, build_commit="a" * 40)

    def startup(self):
        return {"status": "PASS", "scope": "cold_start_initial_home", "apk_sha256": "b" * 64,
                "application_id": "io.toolbox.host", "version_name": "0.8.5", "version_code": "38", "android_version": "15"}

    def test_current_build_waiver_reports_not_run_never_pass(self):
        result = release.verify_startup_scope(**self.fixture())
        self.assertEqual("NOT_RUN_USER_REQUEST", result["MINIFIED_STARTUP_SMOKE"])
        self.assertEqual("NOT_RUN", result["STARTUP_ANDROID_VERSION"])

    def test_waiver_cannot_reuse_or_mislabel_other_evidence(self):
        for key, value in (("host_checks", "full"), ("host_scope", "reused_run"),
                           ("host_commit", "c" * 40), ("build_commit", "invalid")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                release.verify_startup_scope(**(self.fixture() | {key: value}))

    def test_waiver_cannot_hide_failed_or_successful_attempted_check(self):
        for status in ("PASS", "FAIL", "NOT_RUN"):
            with self.subTest(status=status), self.assertRaises(ValueError):
                release.verify_startup_scope(**(self.fixture() | {"startup": self.startup() | {"status": status}}))

    def test_full_mode_requires_real_startup_evidence(self):
        with self.assertRaises(ValueError):
            release.verify_startup_scope(**(self.fixture() | {"allow_not_run": False}))
        result = release.verify_startup_scope(**(self.fixture() | {"allow_not_run": False, "startup": self.startup()}))
        self.assertEqual("PASS_API_35_EMULATOR_INITIAL_HOME_ONLY", result["MINIFIED_STARTUP_SMOKE"])

    def test_full_mode_rejects_failed_or_mismatched_apk_and_identity(self):
        for field, value in (("status", "FAIL"), ("scope", "other"), ("apk_sha256", "c" * 64),
                             ("application_id", "other"), ("version_name", "old"), ("version_code", "1")):
            with self.subTest(field=field), self.assertRaises(ValueError):
                release.verify_startup_scope(**(self.fixture() | {"allow_not_run": False, "startup": self.startup() | {field: value}}))


class ReleaseVersionTest(unittest.TestCase):
    GRADLE = ('applicationId = "io.toolbox.host"\nversionCode = 90\nversionName = "0.13.14"\n'
              "isMinifyEnabled = true\nisShrinkResources = true\n")
    MAPPING = release.WORKER_MAPPING + "\n"
    CERT = "a" * 64

    def verify(self, apk_name, apk_code, version=None):
        signature = f"Number of signers: 1\nSigner #1 certificate SHA-256 digest: {self.CERT}\n"
        badging = f"package: name='io.toolbox.host' versionCode='{apk_code}' versionName='{apk_name}'\n"
        return release.verify_release(signature, badging, self.GRADLE, self.MAPPING, self.CERT, version)

    def test_without_a_computed_version_the_gradle_values_apply(self):
        self.assertEqual("0.13.14", self.verify("0.13.14", "90")["versionName"])
        with self.assertRaises(ValueError):
            self.verify("0.14.0", "91")

    def test_the_computed_version_must_be_the_apks(self):
        config = self.verify("0.14.0", "91", ("0.14.0", "91"))
        self.assertEqual(("0.14.0", "91"), (config["versionName"], config["versionCode"]))
        for apk in (("0.13.14", "90"), ("0.14.0", "90"), ("0.13.15", "91")):
            with self.subTest(apk=apk), self.assertRaises(ValueError):
                self.verify(*apk, ("0.14.0", "91"))
