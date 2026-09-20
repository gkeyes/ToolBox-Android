import copy
import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("reuse", Path(__file__).resolve().parents[1] / "ci/reuse-host-verification.py")
reuse = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reuse)


class ReuseHostVerificationTest(unittest.TestCase):
    def fixture(self):
        return {
            "repository": {"full_name": "owner/host"}, "head_repository": {"full_name": "owner/host"},
            "head_branch": "main", "path": ".github/workflows/android.yml", "event": "push",
            "status": "completed", "conclusion": "failure", "head_sha": "a" * 40,
        }, [{
            "name": "Build APK and verify host behavior", "conclusion": "success",
            "steps": [{"name": name, "conclusion": "success"} for name in reuse.FULL_CHECKS],
        }]

    def test_failed_delivery_can_reuse_independently_passed_full_host_job(self):
        run, jobs = self.fixture()
        self.assertEqual("a" * 40, reuse.validate_prior_run(run, jobs, "owner/host", "main"))

    def test_other_repository_branch_workflow_or_incomplete_run_is_rejected(self):
        run, jobs = self.fixture()
        for key, value in (
            ("repository", {"full_name": "fork/host"}), ("head_repository", {"full_name": "fork/host"}),
            ("head_branch", "feature"), ("event", "pull_request"),
            ("path", ".github/workflows/other.yml"), ("status", "in_progress"), ("head_sha", "bad"),
        ):
            with self.subTest(key=key), self.assertRaises(ValueError):
                reuse.validate_prior_run(run | {key: value}, jobs, "owner/host", "main")

    def test_skipped_failed_or_missing_checks_cannot_be_promoted_to_pass(self):
        run, jobs = self.fixture()
        for conclusion in ("skipped", "failure", "cancelled", None):
            changed = copy.deepcopy(jobs)
            changed[0]["steps"][0]["conclusion"] = conclusion
            with self.subTest(conclusion=conclusion), self.assertRaises(ValueError):
                reuse.validate_prior_run(run, changed, "owner/host", "main")
        for changed in ([], [jobs[0] | {"conclusion": "cancelled"}], [jobs[0] | {"steps": []}]):
            with self.subTest(jobs=changed), self.assertRaises(ValueError):
                reuse.validate_prior_run(run, changed, "owner/host", "main")

    def test_any_application_build_resource_or_behavior_test_change_requires_fresh_checks(self):
        evidence = ["scripts/ci/release-startup-smoke.py", "scripts/tests/test_release_startup.py"]
        reuse.validate_changed_files(evidence)
        for changed in ("app/build.gradle.kts", "app/src/main/Foo.kt", "sdk/help/manual.md", "gradle/libs.versions.toml", "scripts/ci/run-android-behavior.sh", "app/src/androidTest/Foo.kt"):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                reuse.validate_changed_files(evidence + [changed])
