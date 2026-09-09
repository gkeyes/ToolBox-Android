import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("tbx_ci", ROOT / "scripts/ci/tbx.py")
CI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CI)


class TbxSelectionTest(unittest.TestCase):
    def setUp(self):
        self.registry = CI.targets()

    def test_single_multiple_and_all_targets(self):
        self.assertEqual(CI.select_targets(self.registry, "nextflux"), ["nextflux"])
        self.assertEqual(CI.select_targets(self.registry, " nextflux,github-actions-watcher,nextflux "),
                         ["github-actions-watcher", "nextflux"])
        self.assertEqual(CI.select_targets(self.registry, "all"), sorted(self.registry))

    def test_unregistered_or_shell_shaped_input_never_selects_an_executable(self):
        for value in ("", "nextflux,", "../nextflux", "all,nextflux", "nextflux;id", "$(id)"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                CI.select_targets(self.registry, value)

    def test_changed_tools_and_shared_dependencies(self):
        self.assertEqual(CI.select_targets(self.registry, "changed", ["examples/nextflux/src/App.jsx"]), ["nextflux"])
        self.assertEqual(CI.select_targets(self.registry, "changed", [
            "examples/nextflux/old.js", "examples/health-records/web/new.js",
        ]), ["health-records", "nextflux"])
        self.assertEqual(CI.select_targets(self.registry, "changed", ["examples/README.md", "README.md"]), [])
        for path in ("sdk/toolbox-api.d.ts", "tool-package/src/main/Importer.kt", "core-data/src/main/Store.kt",
                     "app/build.gradle.kts", ".github/workflows/tbx.yml", "scripts/package-tool.py"):
            with self.subTest(path=path):
                self.assertEqual(CI.select_targets(self.registry, "changed", [path]), sorted(self.registry))

    def test_git_diff_uses_both_commit_ids_without_rename_collapsing(self):
        before, after = "a" * 40, "b" * 40
        with patch.object(CI.subprocess, "check_output", return_value=b"examples/nextflux/old.js\0examples/health-records/web/new.js\0") as command:
            changed = CI.git_changes({"before": before, "after": after}, "push")
        self.assertEqual(command.call_args.args[0],
                         ["git", "diff", "--name-only", "-z", "--no-renames", before, after, "--"])
        self.assertEqual(CI.select_targets(self.registry, "changed", changed), ["health-records", "nextflux"])
        for event in ({"before": "0" * 40, "after": after},):
            self.assertEqual(CI.select_targets(self.registry, "changed", CI.git_changes(event, "push")), sorted(self.registry))
        with self.assertRaises(ValueError):
            CI.git_changes({"before": "--output=bad", "after": after}, "push")

    def test_pull_request_compares_base_and_head_and_missing_base_checks_all(self):
        event = {"pull_request": {"base": {"sha": "a" * 40}, "head": {"sha": "b" * 40}}}
        with patch.object(CI.subprocess, "check_output", return_value=b"examples/nextflux/src/App.jsx\0"):
            self.assertEqual(CI.git_changes(event, "pull_request"), ["examples/nextflux/src/App.jsx"])
        with patch.object(CI.subprocess, "check_output", side_effect=CI.subprocess.CalledProcessError(128, "git")):
            changed = CI.git_changes(event, "pull_request")
        self.assertEqual(CI.select_targets(self.registry, "changed", changed), sorted(self.registry))

    def test_registered_manifests_and_entrypoints_exist(self):
        for name, config in self.registry.items():
            with self.subTest(tool=name):
                source, manifest, host = CI.metadata(name, config)
                self.assertTrue((source / config["package"][1]).is_file())
                self.assertTrue(CI.VERSION.fullmatch(host))
                self.assertGreater(manifest["versionCode"], 0)
                if config.get("npm"):
                    package = json.loads((source / "package.json").read_text())
                    self.assertEqual(package["version"], manifest["version"])
                    self.assertTrue((source / "package-lock.json").is_file())
                CI.matched_files(source, config["javascript"])
                CI.matched_files(source, config["tests"])

    def test_receipt_requires_importer_and_configured_browser_success(self):
        with patch.dict(CI.os.environ, {"GITHUB_ACTIONS": "true", "TBX_IMPORTER_RESULT": "failure"}):
            with self.assertRaises(ValueError):
                CI.receipt("nextflux")
        with patch.dict(CI.os.environ, {"GITHUB_ACTIONS": "true", "TBX_IMPORTER_RESULT": "success", "TBX_BROWSER_RESULT": "skipped"}):
            with self.assertRaises(ValueError):
                CI.receipt("nextflux")


class TbxArchiveTest(unittest.TestCase):
    def test_manifest_and_integrity_are_bound_to_the_delivered_bytes(self):
        manifest = {"id": "io.toolbox.fixture", "version": "1.2.3", "versionCode": 4, "entry": "index.html"}
        content = {"manifest.json": json.dumps(manifest).encode(), "index.html": b"<!doctype html><p>Fixture</p>"}
        integrity = {"schemaVersion": 1, "algorithm": "SHA-256",
                     "files": {name: hashlib.sha256(data).hexdigest() for name, data in content.items()}}
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "fixture.tbx"
            for changed in (False, True):
                with zipfile.ZipFile(archive_path, "w") as archive:
                    for name, data in content.items():
                        archive.writestr(name, b"changed" if changed and name == "index.html" else data)
                    archive.writestr("integrity.json", json.dumps(integrity))
                if changed:
                    with self.assertRaises(ValueError):
                        CI.check_package(archive_path, manifest, {})
                else:
                    self.assertEqual(CI.check_package(archive_path, manifest, {}),
                                     hashlib.sha256(archive_path.read_bytes()).hexdigest())
                    with self.assertRaises(ValueError):
                        CI.check_package(archive_path, {**manifest, "versionCode": 5}, {})
                    with self.assertRaises(ValueError):
                        CI.check_package(archive_path, manifest, {"expected_files": ["manifest.json"]})


if __name__ == "__main__":
    unittest.main()
