import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("stable_release", ROOT / "scripts/ci/stable_release.py")
stable_release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stable_release)

REGISTRY = {"alpha": {"manifest": "manifest.json"}, "beta-tool": {"manifest": "web/manifest.json"}}
MANIFESTS = {"alpha": {"id": "io.toolbox.alpha", "version": "1.2.0"},
             "beta-tool": {"id": "io.toolbox.beta", "version": "2.0.1"}}


def package(folder, name, tool_id, version, sums=True):
    path = folder / name
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("manifest.json", json.dumps({"id": tool_id, "version": version}))
    if sums:
        with (folder / "SHA256SUMS.txt").open("a") as handle:
            handle.write(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {name}\n")
    return path


class TagTest(unittest.TestCase):
    def test_tag_must_be_strictly_higher_than_latest(self):
        self.assertIsNone(stable_release.tag_problem("v0.13.2", "v0.8.32"))
        self.assertIsNone(stable_release.tag_problem("v1.0.0", ""))
        self.assertIn("not higher", stable_release.tag_problem("v0.8.32", "v0.8.32"))
        self.assertIn("not higher", stable_release.tag_problem("v0.8.9", "v0.8.32"))
        self.assertIn("Unexpected tag", stable_release.tag_problem("v1.0", "v0.8.32"))
        self.assertIn("compare the versions by hand", stable_release.tag_problem("v1.0.0", "nightly"))

    def test_cli_exit_codes(self):
        self.assertEqual(0, stable_release.main(["check-tag", "--tag", "v0.9.0", "--latest", "v0.8.32"]))
        self.assertEqual(1, stable_release.main(["check-tag", "--tag", "v0.8.31", "--latest", "v0.8.32"]))


class CollectTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(self.temp.name)
        self.dev, self.reused, self.out = base / "dev", base / "reused", base / "out"
        for folder in (self.dev, self.reused, self.out):
            folder.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def collect(self):
        return stable_release.collect(REGISTRY, MANIFESTS, [self.dev, self.reused], self.out)

    def test_takes_each_tool_at_its_manifest_version_from_the_first_source(self):
        package(self.dev, "alpha-v1.2.0.tbx", "io.toolbox.alpha", "1.2.0")
        package(self.dev, "beta-tool-v2.0.2.tbx", "io.toolbox.beta", "2.0.2")  # dev moved past the commit
        package(self.reused, "beta-tool-v2.0.1.tbx", "io.toolbox.beta", "2.0.1")
        package(self.reused, "alpha-v1.1.0.tbx", "io.toolbox.alpha", "1.1.0")
        problems, chosen = self.collect()
        self.assertEqual([], problems)
        self.assertEqual({"alpha": ("alpha-v1.2.0.tbx", "dev"), "beta-tool": ("beta-tool-v2.0.1.tbx", "reused")}, chosen)
        self.assertEqual({"alpha-v1.2.0.tbx", "beta-tool-v2.0.1.tbx"}, {path.name for path in self.out.iterdir()})

    def test_an_old_carried_over_version_fails_instead_of_shipping(self):
        package(self.dev, "alpha-v1.2.0.tbx", "io.toolbox.alpha", "1.2.0")
        package(self.reused, "beta-tool-v1.0.6.tbx", "io.toolbox.beta", "1.0.6")
        problems, _ = self.collect()
        self.assertEqual(1, len(problems))
        self.assertIn("beta-tool: the released commit has version 2.0.1", problems[0])
        self.assertIn("beta-tool-v1.0.6.tbx", problems[0])
        self.assertFalse((self.out / "beta-tool-v1.0.6.tbx").exists())

    def test_checksums_and_inner_manifest_are_verified(self):
        path = package(self.dev, "alpha-v1.2.0.tbx", "io.toolbox.alpha", "1.2.0")
        path.write_bytes(path.read_bytes() + b"x")
        package(self.dev, "beta-tool-v2.0.1.tbx", "io.toolbox.beta", "2.0.0")
        problems, _ = self.collect()
        self.assertTrue(any("does not match its SHA256SUMS.txt" in problem for problem in problems), problems)
        self.assertTrue(any("contains io.toolbox.beta 2.0.0" in problem for problem in problems), problems)

    def test_a_package_missing_from_the_sums_file_is_refused(self):
        package(self.dev, "alpha-v1.2.0.tbx", "io.toolbox.alpha", "1.2.0")
        package(self.dev, "beta-tool-v2.0.1.tbx", "io.toolbox.beta", "2.0.1", sums=False)
        problems, _ = self.collect()
        self.assertEqual(1, len(problems))
        self.assertIn("beta-tool-v2.0.1.tbx", problems[0])

    def test_reads_the_real_registry_at_a_revision(self):
        registry, manifests = stable_release.registered_manifests(stable_release.git_reader("HEAD"))
        self.assertEqual(set(registry), set(manifests))
        self.assertTrue(all(manifest["version"] for manifest in manifests.values()))


if __name__ == "__main__":
    unittest.main()
