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


FRAGMENT = "---\nbump: patch\n---\n修正首页闪退。\n"


def tree(app=(65, "0.8.32"), tools=None, registry=None, fragments=None):
    files = {"settings.gradle.kts": SETTINGS, "app/build.gradle.kts": gradle(*app),
             "scripts/ci/tbx-targets.json": json.dumps(registry or REGISTRY)} | (fragments or {})
    versions = {"quick-notes": ("1.0.3", 4), "game-2048": ("1.0.4", 5), "health-records": ("1.0.15", 16),
                "nextflux": ("1.0.54", 75)} | (tools or {})
    for name, (version, code) in versions.items():
        path = (registry or REGISTRY)[name]["manifest"]
        files[f"examples/{name}/{path}"] = manifest(version, code)
    return files


def run(changed, base=None, head=None):
    base, head = base or tree(), head or tree()
    return version_bump.check(changed, base.get, head.get)


class FakeGit:
    """Answers the git commands push_base runs from a small commit graph."""

    def __init__(self, commits, ancestors, parents, merge_bases=None):
        self.commits, self.ancestors, self.parents = commits, ancestors, parents
        self.merge_bases = merge_bases or {}

    def __call__(self, *arguments):
        if arguments[:3] == ("rev-parse", "--verify", "--quiet"):
            revision = arguments[3].removesuffix("^{commit}")
            if revision.endswith("^"):
                revision = self.parents.get(revision[:-1])
            return (0, revision + "\n") if revision in self.commits else (1, "")
        if arguments[:2] == ("merge-base", "--is-ancestor"):
            return (0, "") if tuple(arguments[2:]) in self.ancestors else (1, "")
        if arguments[0] == "merge-base":
            found = self.merge_bases.get(tuple(arguments[1:]))
            return (0, found + "\n") if found else (1, "")
        raise AssertionError(arguments)


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

    def test_app_change_passes_with_an_added_fragment(self):
        for bump in ("patch", "minor", "major"):
            with self.subTest(bump=bump):
                fragment = {"changes/fix-home.md": f"---\nbump: {bump}\n---\n修正首页闪退。\n"}
                self.assertPasses(["app/src/main/kotlin/x.kt", "changes/fix-home.md"], head=tree(fragments=fragment))

    def test_app_change_without_an_added_fragment_fails(self):
        # Editing a fragment that was already there, or only the README, is not a new entry.
        old = {"changes/older.md": FRAGMENT}
        problems, _ = run(["app/src/main/kotlin/x.kt", "changes/older.md"], base=tree(fragments=old),
                          head=tree(fragments={"changes/older.md": FRAGMENT.replace("闪退", "卡顿")}))
        self.assertTrue(any(problem.startswith("ToolBox app (APK)") for problem in problems), problems)
        readme = {"changes/README.md": "说明"}
        problems, _ = run(["app/src/main/kotlin/x.kt", "changes/README.md"], head=tree(fragments=readme))
        self.assertTrue(problems)

    def test_editing_the_app_version_lines_fails(self):
        fragment = {"changes/x.md": FRAGMENT}
        for app in ((66, "0.8.33"), (65, "0.9.0"), (64, "0.8.31")):
            with self.subTest(app=app):
                problems = self.assertNeeds(["app/src/main/x.kt", "app/build.gradle.kts", "changes/x.md"],
                                            "ToolBox app (APK)", head=tree(app=app, fragments=fragment))
                self.assertIn("do not edit versionCode / versionName", problems[0])
        problems, _ = run(["app/build.gradle.kts", "CHANGELOG.md"], head=tree(app=(66, "0.8.33")))
        self.assertTrue(any("do not edit" in problem for problem in problems), problems)

    def test_bad_fragments_fail_even_without_an_app_change(self):
        bad = {
            "no front matter": "修正首页闪退。\n",
            "unclosed front matter": "---\nbump: patch\n修正首页闪退。\n",
            "unknown bump": "---\nbump: feature\n---\n新增分组。\n",
            "missing bump": "---\n---\n新增分组。\n",
            "other key": "---\nbump: minor\ntitle: x\n---\n新增分组。\n",
            "empty body": "---\nbump: minor\n---\n\n  \n",
            "version prefix": "---\nbump: patch\n---\n版本 0.13.15（91）：修正首页闪退。\n",
        }
        for case, text in bad.items():
            with self.subTest(case=case):
                problems, _ = run(["changes/x.md"], head=tree(fragments={"changes/x.md": text}))
                self.assertTrue(any("changelog fragment changes/x.md" in problem for problem in problems), problems)
        for path in ("changes/x.txt", "changes/sub/x.md"):
            with self.subTest(path=path):
                problems, _ = run([path], head=tree(fragments={path: FRAGMENT}))
                self.assertTrue(any("is not a fragment" in problem for problem in problems), problems)
        # Removing a fragment is allowed.
        self.assertPasses(["changes/x.md"], base=tree(fragments={"changes/x.md": FRAGMENT}))

    def test_parse_fragment(self):
        self.assertEqual(("minor", "新增分组排序。拖动即可。"),
                         version_bump.parse_fragment("---\r\nbump: minor\r\n---\r\n- 新增分组排序。\r\n拖动即可。\r\n"))
        self.assertEqual(("major", "首页重新设计。"), version_bump.parse_fragment("---\nbump:  major \n---\n首页重新设计。"))

    def test_app_failure_names_the_files_and_the_fragment_rule(self):
        problems = self.assertNeeds(["core-ui/src/main/kotlin/x.kt"], "ToolBox app (APK)")
        message = problems[0]
        self.assertIn("core-ui/src/main/kotlin/x.kt", message)
        self.assertIn("changes/", message)
        self.assertIn("bump: patch", message)
        self.assertIn("minor for a new feature or noticeable UI change", message)
        self.assertIn("major only for a major redesign", message)

    def test_tool_semver_is_compared_numerically(self):
        base = tree(tools={"game-2048": ("1.0.9", 5)})
        self.assertPasses(["examples/game-2048/game.js"], base=base, head=tree(tools={"game-2048": ("1.0.10", 6)}))
        problems, _ = run(["examples/game-2048/game.js"], base=tree(tools={"game-2048": ("1.10.0", 5)}),
                          head=tree(tools={"game-2048": ("1.9.9", 6)}))
        self.assertTrue(problems)

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
        self.assertPasses(["examples/quick-notes/app.js", "changes/notes.md"],
                          head=tree(tools={"quick-notes": ("1.0.4", 5)}, fragments={"changes/notes.md": FRAGMENT}))

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

    def test_tool_version_may_change_without_content_but_only_upwards(self):
        self.assertPasses(["examples/game-2048/manifest.json"], head=tree(tools={"game-2048": ("1.0.5", 6)}))
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

    def test_only_limits_problems_to_the_named_packages(self):
        changed = ["app/src/main/kotlin/io/toolbox/host/MainActivity.kt", "examples/game-2048/game.js"]
        problems, _ = version_bump.check(changed, tree().get, tree().get, only={"app"})
        self.assertEqual(["ToolBox app (APK)"], [problem.split(":")[0] for problem in problems])
        problems, _ = version_bump.check(changed, tree().get, tree().get, only={"game-2048"})
        self.assertEqual(["Mini-tool game-2048"], [problem.split(":")[0] for problem in problems])
        problems, _ = version_bump.check(changed, tree().get, tree().get, only={"nextflux"})
        self.assertEqual([], problems)

    def test_push_base_compares_a_normal_push_with_before(self):
        git = FakeGit(commits={"b" * 40, "h" * 40, "p" * 40}, ancestors={("b" * 40, "h" * 40)}, parents={"h" * 40: "p" * 40})
        self.assertEqual(("b" * 40, None), version_bump.push_base("b" * 40, "h" * 40, git))

    def test_push_base_falls_back_for_a_new_branch_or_a_force_push(self):
        head, parent, gone, rewritten, fork = "h" * 40, "p" * 40, "g" * 40, "r" * 40, "f" * 40
        git = FakeGit(commits={head, parent, rewritten, fork}, ancestors=set(), parents={head: parent},
                      merge_bases={(rewritten, head): fork})
        base, notice = version_bump.push_base(version_bump.ZERO_SHA, head, git)
        self.assertEqual(parent, base)
        self.assertIn("created the branch", notice)
        base, notice = version_bump.push_base(gone, head, git)
        self.assertEqual(parent, base)
        self.assertIn("not available", notice)
        base, notice = version_bump.push_base(rewritten, head, git)
        self.assertEqual(fork, base)
        self.assertIn("force push", notice)
        root = FakeGit(commits={head}, ancestors=set(), parents={})
        self.assertIsNone(version_bump.push_base(version_bump.ZERO_SHA, head, root)[0])

    def test_reads_modules_from_settings(self):
        modules = version_bump.gradle_modules((ROOT / "settings.gradle.kts").read_text(encoding="utf-8"))
        self.assertIn("app", modules)
        self.assertIn("tool-runtime", modules)
        self.assertTrue(all((ROOT / module / "build.gradle.kts").is_file() for module in modules))


if __name__ == "__main__":
    unittest.main()
