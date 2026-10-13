import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("pr_scope", ROOT / "scripts/ci/pr_scope.py")
pr_scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pr_scope)

REGISTRY = json.dumps({"quick-notes": {"bundled": True}, "nextflux": {}})


def gradle(code, name, extra=""):
    return f'android {{\n    defaultConfig {{\n        versionCode = {code}\n        versionName = "{name}"\n{extra}    }}\n}}\n'


def run(changed, base_build=None, head_build=None):
    base = {"scripts/ci/tbx-targets.json": REGISTRY, "app/build.gradle.kts": base_build or gradle(66, "0.8.33")}
    head = {"scripts/ci/tbx-targets.json": REGISTRY, "app/build.gradle.kts": head_build or gradle(66, "0.8.33")}
    return pr_scope.scope(changed, base.get, head.get)


class PrScopeTest(unittest.TestCase):
    def test_ci_scripts_docs_and_unbundled_tools_skip_gradle(self):
        self.assertEqual({"gradle": False, "webview": False},
                         run([".github/workflows/version-bump.yml", "scripts/ci/version_bump.py",
                              "scripts/tests/test_version_bump.py", "CHANGELOG.md", "CONTRIBUTING.md",
                              "examples/nextflux/src/App.jsx", "examples/quick-notes/tests/app.test.cjs"]))

    def test_code_bundled_tools_manual_and_android_workflow_need_gradle(self):
        for path in ("app/src/main/kotlin/io/toolbox/host/MainActivity.kt", "core-ui/build.gradle.kts",
                     "gradle/libs.versions.toml", "examples/quick-notes/app.js", "sdk/help/manual.md",
                     ".github/workflows/android.yml", "scripts/package-examples.sh"):
            self.assertTrue(run([path])["gradle"], path)

    def test_a_version_bump_alone_needs_neither_build_nor_emulator(self):
        result = run(["app/build.gradle.kts", "CHANGELOG.md"], head_build=gradle(67, "0.9.0"))
        self.assertEqual({"gradle": False, "webview": False}, result)

    def test_other_build_file_changes_need_both(self):
        head = gradle(67, "0.9.0", '        minSdk = 30\n')
        self.assertEqual({"gradle": True, "webview": True}, run(["app/build.gradle.kts"], head_build=head))

    def test_webview_files_need_the_emulator_check(self):
        result = run(["tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/RuntimeWebViewDebugging.kt"])
        self.assertEqual({"gradle": True, "webview": True}, result)
        self.assertEqual({"gradle": False, "webview": True}, run([".github/workflows/webview-debugging.yml"]))


if __name__ == "__main__":
    unittest.main()
