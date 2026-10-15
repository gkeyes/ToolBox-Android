import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("app_version", ROOT / "scripts/ci/app_version.py")
app_version = importlib.util.module_from_spec(spec)
spec.loader.exec_module(app_version)
version_bump = app_version.version_bump

REPO = "owner/repo"
COMMIT = "c" * 40


def fragment(bump, line):
    return f"---\nbump: {bump}\n---\n{line}\n"


def receipt(name="0.13.14", code="90", commit=COMMIT):
    return f"BUILD_COMMIT={commit}\nVERSION_NAME={name}\nVERSION_CODE={code}\nBUILD_TYPE=release\n"


class FakeApi:
    """Answers `gh api` calls from a release (or an error per path), recording the calls."""

    def __init__(self, release=None, receipt_text=None, errors=None):
        self.release, self.receipt_text, self.errors, self.calls = release, receipt_text, errors or {}, []

    def __call__(self, path, accept=None):
        self.calls.append(path)
        failures = self.errors.get(path)
        if failures:
            return 1, "", failures.pop(0) if isinstance(failures, list) else failures
        if path.endswith("/releases/tags/dev"):
            return (0, json.dumps(self.release), "") if self.release is not None else (1, "", "gh: Not Found (HTTP 404)")
        if "/releases/assets/" in path and accept == "application/octet-stream":
            return 0, self.receipt_text, ""
        raise AssertionError(path)


def release(*names):
    return {"assets": [{"id": index, "name": name} for index, name in enumerate(names, 1)]}


class ComputeTest(unittest.TestCase):
    def test_no_fragments_keeps_the_published_version(self):
        self.assertEqual(("0.13.14", 90, []), app_version.compute("0.13.14", 90, [], []))

    def test_no_fragments_with_a_shipped_change_fails(self):
        with self.assertRaises(app_version.MissingFragment) as raised:
            app_version.compute("0.13.14", 90, [], ["app/src/main/kotlin/x.kt"])
        self.assertIn("app/src/main/kotlin/x.kt", str(raised.exception))

    def test_patch_only(self):
        fragments = [("changes/a.md", "patch", "修正甲。"), ("changes/b.md", "patch", "修正乙。")]
        self.assertEqual(("0.13.15", 91, ["修正甲。", "修正乙。"]),
                         app_version.compute("0.13.14", 90, fragments, ["app/x.kt"]))

    def test_minor_and_patch_take_the_minor_and_reset_patch(self):
        fragments = [("changes/a.md", "patch", "修正。"), ("changes/b.md", "minor", "新增。")]
        self.assertEqual(("0.14.0", 91, ["修正。", "新增。"]), app_version.compute("0.13.14", 90, fragments, []))

    def test_major_resets_minor_and_patch(self):
        fragments = [("changes/a.md", "minor", "新增。"), ("changes/b.md", "major", "重新设计。")]
        self.assertEqual(("1.0.0", 91, ["新增。", "重新设计。"]), app_version.compute("0.13.14", 90, fragments, []))

    def test_never_below_the_version_written_in_gradle(self):
        fragments = [("changes/a.md", "patch", "修正。")]
        self.assertEqual(("0.13.14", 90, ["修正。"]),
                         app_version.compute("0.13.10", 86, fragments, [], floor=("0.13.14", 90)))
        self.assertEqual(("0.13.15", 91, ["修正。"]),
                         app_version.compute("0.13.14", 90, fragments, [], floor=("0.13.14", 90)))
        # A minor bump can pass the floor's name while its code would still be lower: neither part goes below it.
        self.assertEqual(("0.14.0", 94, ["新增。"]), app_version.compute(
            "0.13.14", 90, [("changes/a.md", "minor", "新增。")], [], floor=("0.13.18", 94)))
        # Without a fragment the published version stands; the written one alone is not a new build.
        self.assertEqual(("0.13.10", 86, []), app_version.compute("0.13.10", 86, [], [], floor=("0.13.14", 90)))

    def test_versions_count_numerically(self):
        self.assertEqual("0.9.10", app_version.bump_version("0.9.9", ["patch"]))
        self.assertEqual("0.10.0", app_version.bump_version("0.9.9", ["minor", "patch"]))
        with self.assertRaises(ValueError):
            app_version.bump_version("0.9", ["patch"])

    def test_computed_version_from_the_environment(self):
        self.assertIsNone(app_version.computed_version({}))
        self.assertIsNone(app_version.computed_version({"TOOLBOX_VERSION_NAME": "", "TOOLBOX_VERSION_CODE": ""}))
        self.assertEqual(("0.14.0", "91"), app_version.computed_version(
            {"TOOLBOX_VERSION_NAME": "0.14.0", "TOOLBOX_VERSION_CODE": "91"}))
        for environ in ({"TOOLBOX_VERSION_NAME": "0.14.0"}, {"TOOLBOX_VERSION_CODE": "91"},
                        {"TOOLBOX_VERSION_NAME": "0.14", "TOOLBOX_VERSION_CODE": "91"},
                        {"TOOLBOX_VERSION_NAME": "0.14.0", "TOOLBOX_VERSION_CODE": "0"}):
            with self.subTest(environ=environ), self.assertRaises(ValueError):
                app_version.computed_version(environ)


class ReadDevTest(unittest.TestCase):
    def read(self, api):
        return app_version.read_dev(api, REPO, sleep=lambda seconds: None)

    def test_published_build_comes_from_the_receipt(self):
        api = FakeApi(release("toolbox-v0.13.14-dev.apk", "BUILD_RECEIPT.txt", "game-2048-v1.0.4.tbx"), receipt())
        self.assertEqual({"name": "0.13.14", "code": 90, "commit": COMMIT}, self.read(api))

    def test_this_runs_own_half_published_apk_is_not_a_mismatch(self):
        # An earlier attempt of this run swapped in its APK and stopped before the receipt.
        published = release("toolbox-v0.13.15-dev.apk", "BUILD_RECEIPT.txt")
        published["assets"][0]["digest"] = "sha256:" + "a" * 64
        api = FakeApi(published, receipt())
        with self.assertRaises(app_version.LookupFailed):
            self.read(api)
        self.assertEqual({"name": "0.13.14", "code": 90, "commit": COMMIT},
                         app_version.read_dev(api, REPO, sleep=lambda seconds: None, own_apk=("0.13.15", "a" * 64)))
        # Another build of the same version (other bytes), or a newer one, is still a mismatch.
        for own in (("0.13.15", "b" * 64), ("0.13.16", "a" * 64)):
            with self.subTest(own=own), self.assertRaises(app_version.LookupFailed):
                app_version.read_dev(api, REPO, sleep=lambda seconds: None, own_apk=own)

    def test_missing_release_is_no_build(self):
        self.assertIsNone(self.read(FakeApi()))

    def test_release_with_only_tools_is_no_build(self):
        self.assertIsNone(self.read(FakeApi(release("game-2048-v1.0.4.tbx"))))

    def test_other_lookup_errors_retry_then_fail_closed(self):
        path = f"repos/{REPO}/releases/tags/dev"
        api = FakeApi(release("toolbox-v0.13.14-dev.apk", "BUILD_RECEIPT.txt"), receipt(),
                      errors={path: ["HTTP 502", "HTTP 502"]})
        self.assertEqual(90, self.read(api)["code"])
        self.assertEqual(3, api.calls.count(path))
        api = FakeApi(release(), errors={path: "gh: Bad credentials (HTTP 401)"})
        with self.assertRaises(app_version.LookupFailed):
            self.read(api)
        # A missing receipt download is not "no build": it was listed a moment ago.
        api = FakeApi(release("toolbox-v0.13.14-dev.apk", "BUILD_RECEIPT.txt"), receipt(),
                      errors={f"repos/{REPO}/releases/assets/2": "HTTP 404"})
        with self.assertRaises(app_version.LookupFailed):
            self.read(api)

    def test_half_finished_publish_fails_closed(self):
        with self.assertRaises(app_version.LookupFailed):
            self.read(FakeApi(release("toolbox-v0.13.15-dev.apk")))
        with self.assertRaises(app_version.LookupFailed):
            self.read(FakeApi(release("toolbox-v0.13.15-dev.apk", "BUILD_RECEIPT.txt"), receipt()))

    def test_invalid_receipt_fails_closed(self):
        for text in (receipt(code="x"), receipt(name="0.13"), receipt(commit="abc"), "nothing"):
            with self.subTest(text=text), self.assertRaises(app_version.LookupFailed):
                self.read(FakeApi(release("toolbox-v0.13.14-dev.apk", "BUILD_RECEIPT.txt"), text))


class ComputeFromHistoryTest(unittest.TestCase):
    """Runs `compute` against a small git repository with a fake test channel."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.git("init", "-q", "-b", "main")
        self.write({"settings.gradle.kts": 'include(":app")\n', "scripts/ci/tbx-targets.json": "{}",
                    "app/build.gradle.kts": 'android {\n    defaultConfig {\n        versionCode = 90\n'
                                            '        versionName = "0.13.14"\n    }\n}\n',
                    "changes/README.md": "说明\n", "changes/old.md": fragment("major", "早已发布。")})
        self.published = self.commit("published build")
        for module in (app_version, version_bump):
            patcher = patch.object(module, "ROOT", self.root)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.addCleanup(self.directory.cleanup)

    def git(self, *arguments):
        return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *arguments], cwd=self.root,
                              check=True, capture_output=True, text=True).stdout.strip()

    def write(self, files):
        for path, text in files.items():
            (self.root / path).parent.mkdir(parents=True, exist_ok=True)
            (self.root / path).write_text(text, encoding="utf-8")

    def commit(self, message, files=None):
        self.write(files or {})
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD")

    def compute(self, dev, released=()):
        output = Path(self.directory.name) / "output.txt"
        output.write_text("")
        stderr = io.StringIO()
        with patch.object(app_version, "read_dev", lambda api, repo: dev), \
                patch.object(app_version, "released_apps", lambda api, repo: list(released)), \
                patch.dict(os.environ, {"GH_REPO": REPO, "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": ""}), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(stderr):
            code = app_version.main(["compute", "--head", self.git("rev-parse", "HEAD")])
        values = dict(line.split("=", 1) for line in output.read_text().splitlines())
        return code, values, stderr.getvalue()

    def dev(self, name="0.13.14", code=90):
        return {"name": name, "code": code, "commit": self.published}

    def test_fragments_since_the_published_build_raise_the_version_in_order(self):
        self.commit("fix", {"app/src/main/a.kt": "a", "changes/fix.md": fragment("patch", "修正甲。")})
        self.commit("docs", {"README.md": "x"})
        self.commit("feature", {"app/src/main/b.kt": "b", "changes/feature.md": fragment("minor", "新增乙。")})
        code, values, _ = self.compute(self.dev())
        self.assertEqual(0, code)
        self.assertEqual(("0.14.0", "91", "0.13.14", "90", self.published),
                         (values["name"], values["code"], values["base_name"], values["base_code"], values["base_commit"]))
        self.assertEqual(["修正甲。", "新增乙。"], json.loads(values["changes"]))

    def test_nothing_new_keeps_the_published_version(self):
        self.commit("docs", {"README.md": "x", ".github/workflows/x.yml": "y"})
        code, values, _ = self.compute(self.dev())
        self.assertEqual((0, "0.13.14", "90", []), (code, values["name"], values["code"], json.loads(values["changes"])))

    def test_shipped_change_without_a_fragment_fails(self):
        self.commit("fix", {"app/src/main/a.kt": "a"})
        code, _, stderr = self.compute(self.dev())
        self.assertEqual(1, code)
        self.assertIn("app/src/main/a.kt", stderr)

    def test_missing_dev_build_falls_back_to_the_gradle_values(self):
        self.commit("fix", {"app/src/main/a.kt": "a", "changes/fix.md": fragment("patch", "修正甲。")})
        code, values, _ = self.compute(None)
        self.assertEqual(0, code)
        # Counted from the commit that last set the version lines, so the old fragment there is not counted.
        self.assertEqual(("0.13.15", "91", "", ["修正甲。"]),
                         (values["name"], values["code"], values["base_commit"], json.loads(values["changes"])))

    def test_missing_dev_build_after_a_release_fails_closed(self):
        # dev was deleted or recreated by the TBX workflow: the Gradle literal would reuse a shipped number.
        self.commit("fix", {"app/src/main/a.kt": "a", "changes/fix.md": fragment("patch", "修正甲。")})
        code, _, stderr = self.compute(None, released=["v0.8.32"])
        self.assertEqual(2, code)
        self.assertIn("dev has no app build, but ToolBox has already shipped (v0.8.32)", stderr)

    def test_released_apps_ignores_dev_and_tool_only_releases(self):
        releases = [{"tag_name": "dev", "assets": [{"name": "toolbox-v0.14.1-dev.apk"}]},
                    {"tag_name": "ui-screenshots", "assets": [{"name": "home.png"}]},
                    {"tag_name": "v0.8.32", "assets": [{"name": "toolbox-v0.8.32-release.apk"}, {"name": "x.tbx"}]}]
        self.assertEqual(["v0.8.32"], app_version.released_apps(lambda path, accept=None: (0, json.dumps(releases), ""), REPO))
        with self.assertRaises(app_version.LookupFailed):
            app_version.released_apps(lambda path, accept=None: (1, "", "HTTP 500"), REPO)

    def test_published_commit_missing_from_history_fails_closed(self):
        code, _, stderr = self.compute({"name": "0.13.14", "code": 90, "commit": "d" * 40})
        self.assertEqual(2, code)
        self.assertIn("is not in the history of", stderr)

    def test_published_commit_outside_the_current_history_fails_closed(self):
        # main was rewritten: the old dev commit still exists but is no longer an ancestor of the head.
        self.git("checkout", "-q", "-b", "old")
        old = self.commit("old published build", {"app/src/main/gone.kt": "g"})
        self.git("checkout", "-q", "main")
        self.commit("fix", {"app/src/main/a.kt": "a", "changes/fix.md": fragment("patch", "修正甲。")})
        code, _, stderr = self.compute({"name": "0.13.15", "code": 91, "commit": old})
        self.assertEqual(2, code)
        self.assertIn("is not in the history of", stderr)

    def test_check_dev(self):
        head = self.commit("fix", {"app/src/main/a.kt": "a", "changes/fix.md": fragment("patch", "修正甲。")})

        def check(dev, base_commit):
            with patch.object(app_version, "read_dev", lambda api, repo, **_: dev), patch.dict(os.environ, {"GH_REPO": REPO}), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return app_version.main(["check-dev", "--base-commit", base_commit, "--head", head])

        self.assertEqual(0, check(self.dev(), self.published))
        self.assertEqual(0, check(None, ""))
        # Another build reached dev meanwhile: one that contains this commit makes this one older (the publisher
        # skips it); any other means this run's version may not be new.
        newer = self.commit("later")
        self.assertEqual(0, check({"name": "0.13.15", "code": 91, "commit": newer}, self.published))
        self.git("checkout", "-q", "-b", "side", self.published)
        side = self.commit("side")
        self.assertEqual(1, check({"name": "0.13.15", "code": 91, "commit": side}, self.published))
        self.assertEqual(1, check(self.dev(), ""))


if __name__ == "__main__":
    unittest.main()
