#!/usr/bin/env python3
"""Decides which expensive pull request checks a change needs.

A pull request runs only the checks its own changes can affect (the owner's rule: test what changed,
not everything). The Gradle build and JVM tests run when anything that Gradle compiles or packages
changed; the WebView debugging emulator check runs when one of its watched files changed. A version
bump alone (versionCode / versionName in app/build.gradle.kts) needs neither: the version check
validates it. Pushes to main are not scoped; their workflows run everything.

Usage: pr_scope.py --base SHA --head SHA  (prints key=value lines for $GITHUB_OUTPUT)
"""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
APP_BUILD = "app/build.gradle.kts"
REGISTRY = "scripts/ci/tbx-targets.json"
WEBVIEW_PATHS = {
    ".github/workflows/webview-debugging.yml",
    APP_BUILD,
    "app/src/main/kotlin/io/toolbox/host/help/DeveloperHelpScreen.kt",
    "app/src/androidTest/kotlin/io/toolbox/host/DeveloperWebViewDebuggingBehaviorTest.kt",
    "tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/RuntimeWebViewDebugging.kt",
    "tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/HardenedRuntimeWebView.kt",
}
# Kotlin decimal literals have no leading zeros, and versionCode must be positive.
VERSION_LINE = re.compile(r'^\s*(versionCode\s*=\s*(?P<code>[1-9]\d{0,9})|versionName\s*=\s*"\d+\.\d+\.\d+")\s*$')
# Google Play's ceiling for versionCode, well inside the Int that Gradle assigns it to.
MAX_VERSION_CODE = 2_100_000_000


def version_only(base_text, head_text):
    """True when two build files differ only in their versionCode / versionName lines."""
    if base_text is None or head_text is None:
        return False
    def mask(text):
        kept = []
        for line in text.splitlines():
            match = VERSION_LINE.match(line)
            # An out-of-range code is not exempted: only a build would show the failure.
            if not match or (match["code"] and not 0 < int(match["code"]) <= MAX_VERSION_CODE):
                kept.append(line)
            else:
                # Keep the line's place with its value masked, so moving it out of defaultConfig counts.
                kept.append("versionCode" if match["code"] else "versionName")
        return kept
    # The head must still set each value exactly once, validly; a deleted or duplicated one needs the build.
    valid = [VERSION_LINE.match(line) for line in head_text.splitlines()]
    codes = [match for match in valid if match and match["code"] and 0 < int(match["code"]) <= MAX_VERSION_CODE]
    names = [match for match in valid if match and not match["code"]]
    if len(codes) != 1 or len(names) != 1:
        return False
    return mask(base_text) == mask(head_text)


def needs_gradle(path, bundled):
    """Whether a changed path can change what Gradle compiles, packages or tests."""
    if path.startswith("sdk/help/"):
        return True  # The whole directory is an APK asset source.
    # Markdown is documentation, except inside a source set, where it can be a packaged asset.
    is_doc = path.endswith(".md") and "/src/" not in f"/{path}"
    if is_doc or path.startswith("docs/") or path in {"LICENSE", ".gitignore"}:
        return False
    if path.startswith(".github/workflows/"):
        return path == ".github/workflows/android.yml"
    if path == REGISTRY:
        return True  # app/build.gradle.kts reads it to choose the bundled examples.
    if path.startswith(("scripts/ci/", "scripts/tests/")):
        return False
    if path.startswith("examples/"):
        parts = path.split("/")
        return len(parts) > 2 and parts[1] in bundled and parts[2] != "tests"
    return True


def scope(changed, read_base, read_head):
    registry = json.loads(read_head(REGISTRY) or "{}")
    bundled = {name for name, target in registry.items() if target.get("bundled")}
    version_bump_only = APP_BUILD in changed and version_only(read_base(APP_BUILD), read_head(APP_BUILD))
    relevant = [path for path in changed if not (path == APP_BUILD and version_bump_only)]
    return {
        "gradle": any(needs_gradle(path, bundled) for path in relevant),
        "webview": any(path in WEBVIEW_PATHS for path in relevant),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    args = parser.parse_args(argv)

    def git(*arguments):
        return subprocess.run(["git", *arguments], cwd=ROOT, check=True, capture_output=True).stdout.decode("utf-8")

    def reader(revision):
        def read(path):
            result = subprocess.run(["git", "show", f"{revision}:{path}"], cwd=ROOT, capture_output=True)
            return result.stdout.decode("utf-8") if result.returncode == 0 else None
        return read

    fork = git("merge-base", args.base, args.head).strip()
    changed = [path for path in git("diff", "--name-only", "-z", "--no-renames", f"{fork}..{args.head}", "--").split("\0")
               if path]
    result = scope(changed, reader(fork), reader(args.head))
    for key, value in result.items():
        print(f"{key}={'true' if value else 'false'}")
    print(f"{len(changed)} changed path(s): {', '.join(changed[:20])}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
