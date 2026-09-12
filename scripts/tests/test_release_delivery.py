"""Regression tests for the release verifier; no signing secrets or SDK required."""

import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("verify_release", ROOT / "scripts/ci/verify_release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
CERT = "a" * 64
GRADLE = 'applicationId = "io.toolbox.host"\nversionName = "0.7.6"\nversionCode = 30\nisMinifyEnabled = true\nisShrinkResources = true\n'
BADGING = "package: name='io.toolbox.host' versionCode='30' versionName='0.7.6' platformBuildVersionName='16'\n"
SIGNATURE = f"Verifies\nNumber of signers: 1\nV2 Signer certificate SHA-256 digest: {CERT}\n"


class ReleaseDeliveryTest(unittest.TestCase):
    def verify(self, **changes):
        args = dict(signature=SIGNATURE, badging=BADGING, gradle=GRADLE, mapping=release.WORKER_MAPPING, expected=CERT)
        args.update(changes)
        return release.verify_release(**args)

    def test_accepts_current_and_legacy_apksigner_labels(self):
        for label in ("V2 Signer", "Signer #1"):
            with self.subTest(label=label):
                self.assertEqual("0.7.6", self.verify(signature=SIGNATURE.replace("V2 Signer", label))["versionName"])

    def test_rejects_missing_wrong_and_extra_certificates(self):
        cases = (SIGNATURE.replace(CERT, "b" * 64), "Verifies\nNumber of signers: 1\n", SIGNATURE + f"Signer #2 certificate SHA-256 digest: {'b' * 64}\n")
        for text in cases:
            with self.subTest(signature=text), self.assertRaises(ValueError):
                self.verify(signature=text)

    def test_rejects_multiple_signers_and_invalid_expected_fingerprint(self):
        with self.assertRaises(ValueError):
            self.verify(signature=SIGNATURE.replace("signers: 1", "signers: 2"))
        with self.assertRaises(ValueError):
            self.verify(expected="")

    def test_rejects_debug_package_and_mismatched_identity(self):
        for text in (BADGING + "application-debuggable\n", BADGING.replace("io.toolbox.host", "io.toolbox.host.debug"), BADGING.replace("'30'", "'29'")):
            with self.subTest(badging=text), self.assertRaises(ValueError):
                self.verify(badging=text)

    def test_version_comes_from_current_source_not_a_historical_artifact(self):
        changed = GRADLE.replace("0.7.6", "0.8.0").replace("= 30", "= 31")
        self.assertEqual("31", self.verify(gradle=changed, badging=BADGING.replace("0.7.6", "0.8.0").replace("'30'", "'31'"))["versionCode"])
        with self.assertRaises(ValueError):
            self.verify(gradle=changed)

    def test_rejects_missing_r8_mapping_or_disabled_optimization(self):
        with self.assertRaises(ValueError):
            self.verify(mapping="")
        with self.assertRaises(ValueError):
            self.verify(gradle=GRADLE.replace("isShrinkResources = true", "isShrinkResources = false"))


if __name__ == "__main__":
    unittest.main()
