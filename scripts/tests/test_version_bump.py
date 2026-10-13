import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("version_bump", ROOT / "scripts/ci/version_bump.py")
version_bump = importlib.util.module_from_spec(spec)
spec.loader.exec_module(version_bump)

SETTINGS = 'include(\n    ":app",\n    ":core-ui",\n    ":core-data",\n    ":tool-runtime",\n)\n'
REGISTRY = {
    "quick-notes": {"manifest": "manifest.json", "bundled": True, "package": ["bash", "package.sh", "{output}"],
                    "expected_files": ["manifest.json", "index.html", "app.js", "icon.svg", "integrity.json"]},
    "game-2048": {"manifest": "manifest.json", "package": ["bash", "package.sh", "{output}"],
                  "expected_files": ["manifest.json", "index.html", "game.js", "integrity.json"]},
    "health-records": {"manifest": "web/manifest.json", "package": ["bash", "package.sh", "{output}"]},
    "nextflux": {"manifest": "manifest.json", "npm": True, "package": ["python3", "package.py", "--output", "{output}"]},
}


def gradle(code, name):
    return f'android {{\n    defaultConfig {{\n        versionCode = {code}\n        versionName = "{name}"\n    }}\n}}\n'


def manifest(version, code):
    return json.dumps({"id": "io.toolbox.x", "version": version, "versionCode": code})


def tree(app=(65, "0.8.32"), tools=None, registry=None):
    files = {"settings.gradle.kts": SETTINGS, "app/build.gradle.kts": gradle(*app),
             "scripts/ci/tbx-targets.json": json.dumps(registry or REGISTRY)}
    versions = {"quick-notes": ("1.0.3", 4), "game-2048": ("1.0.4", 5), "health-records": ("1.0.15", 16),
                "nextflux": ("1.0.54", 75)} | (tools or {})
    for name, (version, code) in versions.items():
        path = (registry or REGISTRY)[name]["manifest"]
        files[f"examples/{name}/{path}"] = manifest(version, code)
    return files


def run(changed, base=None, head=None):
    base, head = base or tree(), head or tree()
    return version_bump.check(changed, base.get, head.get)


class VersionBumpTest(unittest.TestCase):
    def assertNeeds(self, changed, label, head=None):
        problems, _ = run(changed, head=head)
        self.assertTrue(any(problem.startswith(label) for problem in problems), problems)
        return problems

    def assertPasses(self, changed, head=None, base=None):
        problems, _ = run(changed, base=base, head=head)
        self.assertEqual([], problems)

    def test_app_sources_and_build_inputs_need_an_app_bump(self):
        for path in ("app/src/main/kotlin/io/toolbox/host/MainActivity.kt", "core-ui/src/main/kotlin/x/Theme.kt",
                     "tool-runtime/build.gradle.kts", "core-data/src/main/AndroidManifest.xml",
                     "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties", "settings.gradle.kts",
                     "build.gradle.kts", "gradle.properties", "sdk/help/manual.md", "app/proguard-rules.pro"):
            with self.subTest(path=path):
                self.assertNeeds([path], "ToolBox app (APK)")

    def test_tests_docs_ci_and_scripts_need_no_bump(self):
        self.assertPasses(["app/src/test/kotlin/x/FooTest.kt", "app/src/androidTest/kotlin/x/FooTest.kt",
                           "core-data/src/testFixtures/x.kt", "core-data/schemas/x/1.json", "CHANGELOG.md",
                           "README.md", "CONTRIBUTING.md", "docs/design.md", ".github/workflows/android.yml",
                           "scripts/ci/tbx.py", "scripts/package-tool.py", "scripts/package-examples.sh",
                           "scripts/tests/bundled-tools.test.cjs", "gradlew", "examples/game-2048/tests/a.test.cjs",
                           "examples/health-records/test/model.test.mjs", "examples/nextflux/test/browser/a.spec.js",
                           "examples/nextflux/README.md", "examples/unregistered/app.js", "core-ui/README.md"])

    def test_app_bump_needs_both_numbers_strictly_higher(self):
        changed = ["app/src/main/kotlin/x.kt"]
        self.assertPasses(changed, head=tree(app=(66, "0.8.33")))
        self.assertPasses(changed, head=tree(app=(66, "0.9.0")))
        self.assertPasses(changed, head=tree(app=(70, "1.0.0")))
        for app in ((66, "0.8.32"), (65, "0.8.33"), (66, "0.8.31"), (66, "0.9"), (66, "0.9.0-beta"), (66, "v0.9.0")):
            with self.subTest(app=app):
                self.assertNeeds(changed, "ToolBox app (APK)", head=tree(app=app))

    def test_semver_is_compared_numerically(self):
        self.assertPasses(["app/src/main/x.kt"], base=tree(app=(65, "0.8.9")), head=tree(app=(66, "0.8.10")))
        problems, _ = run(["app/src/main/x.kt"], base=tree(app=(65, "0.10.0")), head=tree(app=(66, "0.9.9")))
        self.assertTrue(problems)

    def test_failure_names_the_file_numbers_and_semver_rule(self):
        problems = self.assertNeeds(["core-ui/src/main/kotlin/x.kt"], "ToolBox app (APK)")
        message = problems[0]
        self.assertIn("app/build.gradle.kts", message)
        self.assertIn("core-ui/src/main/kotlin/x.kt", message)
        self.assertIn("versionCode to at least 66", message)
        self.assertIn("bug fix -> 0.8.33", message)
        self.assertIn("noticeable UI change -> 0.9.0", message)
        self.assertIn("major redesign -> 1.0.0", message)

    def test_tool_with_fixed_file_list_ships_exactly_those_files(self):
        self.assertNeeds(["examples/game-2048/game.js"], "Mini-tool game-2048")
        self.assertNeeds(["examples/game-2048/package.sh"], "Mini-tool game-2048")
        self.assertPasses(["examples/game-2048/README.md", "examples/game-2048/notes.txt"])
        problems = self.assertNeeds(["examples/game-2048/game.js"], "Mini-tool game-2048")
        self.assertIn("examples/game-2048/manifest.json", problems[0])
        self.assertIn("bug fix -> 1.0.5", problems[0])
        self.assertPasses(["examples/game-2048/game.js"], head=tree(tools={"game-2048": ("1.0.5", 6)}))
        self.assertPasses(["examples/game-2048/game.js"], head=tree(tools={"game-2048": ("1.1.0", 6)}))
        for version in (("1.0.5", 5), ("1.0.4", 6), ("1.0.3", 6), ("1.0", 6)):
            with self.subTest(version=version):
                self.assertNeeds(["examples/game-2048/game.js"], "Mini-tool game-2048",
                                 head=tree(tools={"game-2048": version}))

    def test_changing_a_tools_file_list_is_a_package_change(self):
        registry = json.loads(json.dumps(REGISTRY))
        registry["game-2048"]["expected_files"].append("style.css")
        problems, _ = run(["scripts/ci/tbx-targets.json"], head=tree(registry=registry))
        self.assertTrue(any(problem.startswith("Mini-tool game-2048") for problem in problems), problems)
        self.assertFalse(any(problem.startswith("ToolBox app") for problem in problems), problems)

    def test_packaging_command_change_needs_a_tool_bump(self):
        registry = json.loads(json.dumps(REGISTRY))
        registry["game-2048"]["package"] = ["bash", "package.sh", "--minify", "{output}"]
        head = tree(registry=registry)
        head["scripts/ci/tbx-targets.json"] = json.dumps(registry)
        problems, _ = run(["scripts/ci/tbx-targets.json"], head=head)
        self.assertTrue(any(problem.startswith("Mini-tool game-2048") for problem in problems), problems)

    def test_bundled_tool_packaging_change_also_needs_an_app_bump(self):
        registry = json.loads(json.dumps(REGISTRY))
        registry["quick-notes"]["expected_files"].append("style.css")
        head = tree(tools={"quick-notes": ("1.0.4", 5)}, registry=registry)
        problems, _ = run(["scripts/ci/tbx-targets.json"], head=head)
        self.assertTrue(any(problem.startswith("ToolBox app") for problem in problems), problems)
        self.assertFalse(any(problem.startswith("Mini-tool quick-notes") for problem in problems), problems)

    def test_bundled_tool_change_also_ships_in_the_apk(self):
        problems = self.assertNeeds(["examples/quick-notes/app.js"], "ToolBox app (APK)")
        self.assertTrue(any(problem.startswith("Mini-tool quick-notes") for problem in problems))
        self.assertPasses(["examples/quick-notes/app.js"],
                          head=tree(app=(66, "0.8.33"), tools={"quick-notes": ("1.0.4", 5)}))

    def test_changing_which_examples_are_bundled_ships_in_the_apk(self):
        registry = json.loads(json.dumps(REGISTRY))
        registry["game-2048"]["bundled"] = True
        problems, _ = run(["scripts/ci/tbx-targets.json"], head=tree(registry=registry))
        self.assertTrue(any(problem.startswith("ToolBox app (APK)") for problem in problems), problems)
        changed_tests = json.loads(json.dumps(REGISTRY))
        changed_tests["game-2048"]["tests"] = {"unit": ["tests/*.test.cjs"]}
        self.assertPasses(["scripts/ci/tbx-targets.json"], head=tree(registry=changed_tests))

    def test_built_tools_ship_their_build_inputs_not_their_tests(self):
        for path in ("examples/nextflux/src/App.jsx", "examples/nextflux/public/icon.svg", "examples/nextflux/package-lock.json",
                     "examples/nextflux/vite.config.js", "examples/nextflux/THIRD_PARTY_NOTICES.txt",
                     "examples/nextflux/package.py", "examples/nextflux/index.html"):
            with self.subTest(path=path):
                self.assertNeeds([path], "Mini-tool nextflux")
        self.assertNeeds(["examples/health-records/web/app.mjs"], "Mini-tool health-records")
        self.assertNeeds(["examples/health-records/web/vendor/lib.js"], "Mini-tool health-records")
        self.assertPasses(["examples/health-records/web/.DS_Store", "examples/nextflux/test/cache-core.test.mjs",
                           "examples/nextflux/.gitignore"])

    def test_version_may_change_without_content_but_only_upwards(self):
        self.assertPasses(["app/build.gradle.kts"], head=tree(app=(66, "0.8.33")))
        problems, _ = run(["CHANGELOG.md"], head=tree(app=(64, "0.8.31")))
        self.assertTrue(problems)
        problems, _ = run(["examples/game-2048/manifest.json"], head=tree(tools={"game-2048": ("1.0.5", 5)}))
        self.assertTrue(problems)

    def test_a_bump_that_landed_on_main_does_not_fail_an_untouched_pr(self):
        fork = tree()
        base = tree(tools={"nextflux": ("1.0.55", 76)})
        problems, _ = version_bump.check(["CHANGELOG.md"], base.get, fork.get, fork.get)
        self.assertEqual([], problems)
        older = tree(tools={"nextflux": ("1.0.53", 74)})
        problems, _ = version_bump.check(["CHANGELOG.md"], base.get, older.get, fork.get)
        self.assertTrue(problems)

    def test_new_or_removed_tools_have_nothing_to_compare(self):
        base = tree()
        del base["examples/game-2048/manifest.json"]
        self.assertPasses(["examples/game-2048/game.js", "examples/game-2048/manifest.json"], base=base)

    def test_every_registered_tool_has_a_known_package_inventory(self):
        registry = json.loads((ROOT / "scripts/ci/tbx-targets.json").read_text(encoding="utf-8"))
        built = {name for name, config in registry.items() if "expected_files" not in config}
        self.assertEqual(built, set(version_bump.BUILT_TOOL_INPUTS))
        for name, config in registry.items():
            source = ROOT / "examples" / name
            files = [path.relative_to(source).as_posix() for path in source.rglob("*")
                     if path.is_file() and "node_modules" not in path.parts and "dist" not in path.parts]
            shipped = {path for path in files if version_bump.tool_shipped(name, path, config)}
            with self.subTest(tool=name):
                self.assertIn(config["manifest"], shipped)
                self.assertFalse({path for path in shipped if path.startswith(("test/", "tests/"))})
                for expected in set(config.get("expected_files", [])) - {"integrity.json"}:
                    self.assertIn(expected, shipped)

    def test_reads_modules_from_settings(self):
        modules = version_bump.gradle_modules((ROOT / "settings.gradle.kts").read_text(encoding="utf-8"))
        self.assertIn("app", modules)
        self.assertIn("tool-runtime", modules)
        self.assertTrue(all((ROOT / module / "build.gradle.kts").is_file() for module in modules))


if __name__ == "__main__":
    unittest.main()
