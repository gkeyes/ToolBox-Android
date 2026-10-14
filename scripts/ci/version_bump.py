#!/usr/bin/env python3
"""Fail a pull request that changes shipped content without declaring the version change it needs.

Rule: every update that ships carries a new version number; one version number never ships with two
different contents. The test channel (`dev`) is rebuilt from every merge to `main`, so a merge that
changes what goes into the APK or into a mini-tool's .tbx must raise that package's version.

Versions follow MAJOR.MINOR.PATCH, compared numerically:
  - a bug fix raises PATCH;
  - a new feature or a noticeable UI change raises MINOR and resets PATCH to 0;
  - only a major redesign raises MAJOR.
versionCode always goes up by at least 1.

The APK's version is not edited by pull requests (every open PR would conflict on the same two lines).
A PR that changes what ships in the APK adds one changelog fragment, changes/<unique name>.md, that names
the bump (patch, minor or major) and carries the changelog line; the build on main computes the version
from the fragments added since the last published build (scripts/ci/app_version.py). This check requires
such a PR to add a valid fragment, and refuses any PR that edits versionCode / versionName in
app/build.gradle.kts. Mini-tools still raise their own manifest version: this check cannot tell a fix from
a feature, so it only requires both numbers to be strictly higher than on the base branch, and states the
rule on failure.

What counts as shipped (everything else, e.g. tests, docs, CHANGELOG, CI workflows and repository
scripts, needs no bump or fragment):

APK ("app", needs a changelog fragment in changes/):
  - every file of a Gradle module included from settings.gradle.kts (app, core-*, tool-*), except its
    test source sets (src/test, src/androidTest, src/testFixtures), Markdown outside src/, and
    <module>/schemas/ (exported Room schemas, which core-data adds only to the androidTest APK);
  - the root Gradle build: settings.gradle.kts, build.gradle.kts, gradle.properties and gradle/**
    (version catalog and wrapper; they decide which compiler, libraries and plugins build the APK);
  - sdk/help/** (app/build.gradle.kts packages it as APK assets);
  - the shipped files of every example marked "bundled" in scripts/ci/tbx-targets.json (each one is
    packaged into the APK's assets), and a change to which examples are bundled.
  Not counted: gradlew/gradlew.bat (launcher scripts) and the shared packagers scripts/package-tool.py
  and scripts/package-examples.sh (repository scripts; the owner's rule exempts scripts, and
  publish-test-release.sh never republishes an unchanged version, so such a change ships with the
  next real bump).

Mini-tool ("examples/<tool>", version is "version"/"versionCode" in its registered manifest):
  - its manifest and its packaging recipe (package.sh / package.py: it decides what goes in);
  - for a tool with "expected_files" in tbx-targets.json: exactly those files (CI already checks the
    built .tbx contains exactly that list), plus a change to that list itself;
  - for a tool that is built first (no fixed list): the inputs listed in BUILT_TOOL_INPUTS below.
  Tests (tests/, test/), README and other docs, and test-only configs never count. An examples/
  directory that is not registered in tbx-targets.json is never built or published, so it is ignored.
"""

import argparse
from fnmatch import fnmatchcase
import json
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
APP_BUILD = "app/build.gradle.kts"
# Changelog fragments: one Markdown file per APK change, directly in this folder (README.md explains them).
FRAGMENTS = "changes/"
FRAGMENTS_README = "changes/README.md"
BUMPS = ("patch", "minor", "major")
REGISTRY = "scripts/ci/tbx-targets.json"
SEMVER = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")

# Root build files that change how the APK is compiled and what it links against.
APP_BUILD_FILES = ("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle/*")
# Folders the app packages as APK assets besides the bundled examples (see app/build.gradle.kts).
APP_ASSET_DIRS = ("sdk/help/",)
# Source sets that only build test APKs or JVM tests.
TEST_SOURCE_SETS = ("src/test/", "src/androidTest/", "src/testFixtures/")

# Tools whose .tbx is built from sources rather than copied from a fixed file list. Patterns are
# relative to the tool directory; a path matching none of them (tests, README, test configs) is not
# shipped. Keep these in step with each tool's package script and bundler config.
BUILT_TOOL_INPUTS = {
    # package.sh packs web/ as a whole (package-tool.py skips only OS/VCS clutter).
    "health-records": ("web/*",),
    # package.py packs vite's dist/ plus manifest.json, LICENSE and THIRD_PARTY_NOTICES.txt; vite builds
    # index.html, src/ and public/ with the configs and the locked dependencies.
    "nextflux": ("index.html", "src/*", "public/*", "vite.config.js", "tailwind.config.js", "postcss.config.js",
                 "package.json", "package-lock.json", "LICENSE", "THIRD_PARTY_NOTICES.txt"),
    # package.sh packs dist/; vite builds index.html, src/, platform/ and public/ (tsconfig.json sets the
    # JSX and path options it compiles with) and copies manifest.json, LICENSE, NOTICE and UPSTREAM.json.
    "socialcoach": ("index.html", "src/*", "platform/*", "public/*", "vite.config.ts", "tsconfig.json",
                    "package.json", "package-lock.json", "LICENSE", "NOTICE", "UPSTREAM.json"),
}
# Names package-tool.py never packs.
PACKAGER_IGNORED = {".DS_Store", "__MACOSX", ".git", "__pycache__", "node_modules"}


def parse_semver(value):
    match = SEMVER.fullmatch(value or "")
    return tuple(int(part) for part in match.groups()) if match else None


def app_version(text):
    if text is None:
        return None
    code = re.search(r"^\s*versionCode\s*=\s*([0-9]+)\s*$", text, re.MULTILINE)
    name = re.search(r'^\s*versionName\s*=\s*"([^"]*)"\s*$', text, re.MULTILINE)
    if not code or not name:
        raise ValueError(f"Cannot read versionCode and versionName from {APP_BUILD}")
    return int(code[1]), name[1]


def tool_version(text):
    if text is None:
        return None
    manifest = json.loads(text)
    code = manifest.get("versionCode")
    return (code if type(code) is int else None), manifest.get("version")


def is_fragment(path):
    """A changelog fragment: a Markdown file directly in changes/, other than its README."""
    name = path[len(FRAGMENTS):] if path.startswith(FRAGMENTS) else None
    return bool(name) and "/" not in name and name.endswith(".md") and path != FRAGMENTS_README


def parse_fragment(text):
    """(bump, changelog line) from a fragment, or ValueError saying what is wrong.

    The format (see changes/README.md) is a front matter with only `bump: patch|minor|major`, then the
    changelog text in Simplified Chinese without a version prefix; its lines are joined into one entry.
    """
    lines = text.replace("\r\n", "\n").split("\n")
    if not lines or lines[0].strip() != "---":
        raise ValueError("must start with a '---' line opening the front matter")
    end = next((index for index, line in enumerate(lines[1:], 1) if line.strip() == "---"), None)
    if end is None:
        raise ValueError("the front matter has no closing '---' line")
    fields = {}
    for line in lines[1:end]:
        if not line.strip():
            continue
        key, colon, value = line.partition(":")
        if not colon or key.strip() != "bump" or "bump" in fields:
            raise ValueError(f"unexpected front matter line {line.strip()!r}; only one 'bump: patch|minor|major' line")
        fields["bump"] = value.strip()
    bump = fields.get("bump")
    if bump not in BUMPS:
        raise ValueError(f"bump must be one of {', '.join(BUMPS)}, but is {bump!r}")
    body = ""
    for line in (line.strip() for line in lines[end + 1:]):
        # Wrapped Chinese text joins without a space; a space only between two ASCII characters.
        if line:
            body += (" " if body and body[-1].isascii() and line[0].isascii() else "") + line
    body = re.sub(r"^[-*]\s+", "", body)
    if not body:
        raise ValueError("the changelog text after the front matter is empty")
    if re.match(r"版本\s*[0-9]|v?[0-9]+\.[0-9]+\.[0-9]+", body):
        raise ValueError("leave out the version prefix; the version is computed when the build is published")
    return bump, body


def gradle_modules(settings):
    """Module directories included from settings.gradle.kts, e.g. ':core-ui' -> 'core-ui'."""
    if not settings:
        return set()
    return {name.replace(":", "/") for name in re.findall(r'"\s*:([A-Za-z0-9_.:-]+)\s*"', settings)}


# Registry entries that decide what a tool's .tbx contains: its file list, manifest and packaging command.
PACKAGING_KEYS = ("expected_files", "manifest", "package")


def bundled(registry):
    return {name for name, config in registry.items() if config.get("bundled")}


def tool_shipped(name, relative, config):
    """Whether examples/<name>/<relative> ends up in the tool's .tbx (or decides what does)."""
    if relative == PurePosixPath(config.get("manifest", "manifest.json")).as_posix():
        return True
    package = config.get("package", [])
    if len(package) >= 2 and relative == package[1]:
        return True
    if any(part in PACKAGER_IGNORED for part in PurePosixPath(relative).parts):
        return False
    if "expected_files" in config:
        return relative in set(config["expected_files"]) - {"integrity.json"}
    patterns = BUILT_TOOL_INPUTS.get(name)
    if patterns is None:
        raise ValueError(f"{name} has no expected_files in {REGISTRY} and no BUILT_TOOL_INPUTS entry in "
                         "scripts/ci/version_bump.py, so its shipped files are unknown")
    return any(fnmatchcase(relative, pattern) for pattern in patterns)


def classify(changed, base_registry, head_registry, modules):
    """Returns ({'app': [paths], '<tool>': [paths]}, {path: reason}) for the changed paths."""
    shipped, notes = {}, {}

    def ship(target, path, reason):
        shipped.setdefault(target, []).append(path)
        notes.setdefault(path, []).append(f"{target}: {reason}")

    bundled_any = bundled(base_registry) | bundled(head_registry)
    for path in changed:
        top, _, rest = path.partition("/")
        module = next((m for m in sorted(modules, key=len, reverse=True) if path.startswith(m + "/")), None)
        if module:
            inner = path[len(module) + 1:]
            if inner.startswith(TEST_SOURCE_SETS):
                notes[path] = ["test source set"]
            elif inner.startswith("schemas/"):
                notes[path] = ["Room schema export (androidTest assets only)"]
            elif inner.endswith(".md") and not inner.startswith("src/"):
                notes[path] = ["module documentation"]
            else:
                ship("app", path, f"part of Gradle module :{module}")
        elif any(fnmatchcase(path, pattern) for pattern in APP_BUILD_FILES):
            ship("app", path, "root Gradle build configuration")
        elif path.startswith(APP_ASSET_DIRS):
            ship("app", path, "packaged as APK assets")
        elif path == REGISTRY:
            if bundled(base_registry) != bundled(head_registry):
                ship("app", path, "changes which examples are bundled into the APK")
            for name in sorted(set(base_registry) & set(head_registry)):
                if any(base_registry[name].get(key) != head_registry[name].get(key) for key in PACKAGING_KEYS):
                    ship(name, path, "changes how its .tbx is packaged")
                    if name in bundled_any:
                        ship("app", path, f"bundled example {name} is packaged into the APK")
            notes.setdefault(path, ["tool registry (test or build settings only)"])
        elif top == "examples" and "/" in rest:
            name, relative = rest.split("/", 1)
            config = head_registry.get(name) or base_registry.get(name)
            if config is None:
                notes[path] = ["unregistered example (never published)"]
                continue
            # A file shipped at either end of the change (added, edited or removed) changes the package.
            in_base = name in base_registry and tool_shipped(name, relative, base_registry[name])
            in_head = name in head_registry and tool_shipped(name, relative, head_registry[name])
            if in_base or in_head:
                ship(name, path, "packaged into its .tbx")
                if name in bundled_any:
                    ship("app", path, f"bundled example {name} is packaged into the APK")
            else:
                notes[path] = [f"not packaged into {name}'s .tbx (tests, docs or tooling)"]
        else:
            notes[path] = ["not shipped (docs, CI, scripts or tests)"]
    return shipped, notes


def bump_problem(label, version_file, base, head, files, kind):
    """None when head's version is strictly above base's, else a message saying what to change."""
    base_code, base_name = base
    head_code, head_name = head
    base_parts, head_parts = parse_semver(base_name), parse_semver(head_name)
    problems = []
    if head_parts is None:
        problems.append(f"versionName must be MAJOR.MINOR.PATCH (numbers only), but is {head_name!r}")
    elif base_parts is not None and head_parts <= base_parts:
        problems.append(f"versionName {head_name} is not higher than {base_name} on the base branch")
    if head_code is None or base_code is None or head_code <= base_code:
        problems.append(f"versionCode {head_code} is not higher than {base_code} on the base branch")
    if not problems:
        return None
    if base_parts:
        major, minor, patch = base_parts
        options = (f"bug fix -> {major}.{minor}.{patch + 1}; new feature or noticeable UI change -> "
                   f"{major}.{minor + 1}.0; major redesign -> {major + 1}.0.0")
    else:
        options = "bug fix -> raise PATCH; new feature or UI change -> raise MINOR, PATCH 0; redesign -> raise MAJOR"
    listed = "\n".join(f"    {path}" for path in files[:10]) + (f"\n    ... and {len(files) - 10} more" if len(files) > 10 else "")
    field = "versionName" if kind == "app" else '"version"'
    return (f"{label}: this change modifies files that ship in it, so its version in {version_file} must go up.\n"
            + "".join(f"  - {problem}\n" for problem in problems)
            + f"  Shipped files changed:\n{listed}\n"
            + f"  Fix: set versionCode to at least {(base_code or 0) + 1} and raise {field} from {base_name} "
            + f"by semantic versioning ({options}).")


def regression_problem(label, version_file, base, head):
    """A version that changes without a content change must still only go up."""
    if base == head:
        return None
    (base_code, base_name), (head_code, head_name) = base, head
    base_parts, head_parts = parse_semver(base_name), parse_semver(head_name)
    if head_parts is None:
        return f"{label}: versionName {head_name!r} in {version_file} is not MAJOR.MINOR.PATCH."
    if (base_parts and head_parts <= base_parts) or head_code is None or (base_code is not None and head_code <= base_code):
        return (f"{label}: the version in {version_file} changed from {base_name} ({base_code}) to {head_name} "
                f"({head_code}); both versionName and versionCode must go up together, never down or sideways.")
    return None


FRAGMENT_EXAMPLE = """---
bump: patch
---
修正……（一句话说明这次改动，简体中文，不写版本号）"""


def app_problems(changed, read_fork, read_head, shipped):
    """Problems with the APK side of a change: version lines edited, bad fragments, or a missing fragment.

    Compared with the commit the PR branched from, so only what the PR itself changed counts.
    """
    label = "ToolBox app (APK)"
    problems, added = [], []
    fork_app, head_app = app_version(read_fork(APP_BUILD)), app_version(read_head(APP_BUILD))
    if fork_app and head_app and fork_app != head_app:
        problems.append(f"{label}: do not edit versionCode / versionName in {APP_BUILD} (changed from "
                        f"{fork_app[1]} ({fork_app[0]}) to {head_app[1]} ({head_app[0]})). The build on main computes "
                        f"the version from the fragments in {FRAGMENTS}; restore both lines and add a fragment instead.")
    for path in sorted(set(changed)):
        if not path.startswith(FRAGMENTS) or path == FRAGMENTS_README:
            continue
        text = read_head(path)
        if text is None:
            continue  # Removing a fragment is allowed (e.g. one added by mistake before it was published).
        if not is_fragment(path):
            problems.append(f"{label}: {path} is not a fragment; fragments are Markdown files directly in {FRAGMENTS}")
            continue
        try:
            parse_fragment(text)
        except ValueError as problem:
            problems.append(f"{label}: changelog fragment {path}: {problem}. Expected:\n{FRAGMENT_EXAMPLE}")
            continue
        if read_fork(path) is None:
            added.append(path)
    if shipped and not added:
        listed = "\n".join(f"    {path}" for path in shipped[:10]) + (
            f"\n    ... and {len(shipped) - 10} more" if len(shipped) > 10 else "")
        problems.append(f"{label}: this change modifies files that ship in the APK, so it must add a changelog "
                        f"fragment {FRAGMENTS}<unique name>.md (see {FRAGMENTS_README}).\n"
                        f"  Shipped files changed:\n{listed}\n"
                        f"  Fix: add for example {FRAGMENTS}<branch name>.md containing\n{FRAGMENT_EXAMPLE}\n"
                        f"  with bump: patch for a bug fix, minor for a new feature or noticeable UI change, "
                        f"major only for a major redesign.")
    return problems


def app_shipped(changed, read_base, read_head):
    """The changed paths that ship in the APK (scripts/ci/app_version.py uses this on main)."""
    base_registry = json.loads(read_base(REGISTRY) or "{}")
    head_registry = json.loads(read_head(REGISTRY) or "{}")
    modules = gradle_modules(read_base("settings.gradle.kts")) | gradle_modules(read_head("settings.gradle.kts"))
    return classify(sorted(set(changed)), base_registry, head_registry, modules)[0].get("app", [])


def check(changed, read_base, read_head, read_fork=None, only=None):
    """Returns (problems, notes). read_base/read_head map a repository path to its text, or None if absent.

    read_fork reads the commit the PR branched from. A version the PR did not touch is compared with
    that commit, so a bump that landed on the base branch meanwhile does not fail a PR that never
    changed that app or tool; merging the base branch in brings the bump along.

    only, when given, limits the problems to those packages ("app" or a tool directory name).
    """
    read_fork = read_fork or read_base
    base_registry = json.loads(read_base(REGISTRY) or "{}")
    head_registry = json.loads(read_head(REGISTRY) or "{}")
    modules = gradle_modules(read_base("settings.gradle.kts")) | gradle_modules(read_head("settings.gradle.kts"))
    shipped, notes = classify(sorted(set(changed)), base_registry, head_registry, modules)
    problems = []

    if only is None or "app" in only:
        problems.extend(app_problems(changed, read_fork, read_head, shipped.get("app", [])))

    for name in sorted(set(base_registry) & set(head_registry)):
        if only is not None and name not in only:
            continue
        base_path = f"examples/{name}/{base_registry[name].get('manifest', 'manifest.json')}"
        head_path = f"examples/{name}/{head_registry[name].get('manifest', 'manifest.json')}"
        base_tool, head_tool = tool_version(read_base(base_path)), tool_version(read_head(head_path))
        if not base_tool or not head_tool:
            continue  # A new or removed tool has no earlier version to compare with.
        label = f"Mini-tool {name}"
        fork_tool = tool_version(read_fork(base_path))
        problem = (bump_problem(label, head_path, base_tool, head_tool, shipped[name], "tool")
                   if name in shipped else fork_tool and regression_problem(label, head_path, fork_tool, head_tool))
        if problem:
            problems.append(problem)
    return problems, notes


def git(*arguments):
    return subprocess.run(["git", *arguments], cwd=ROOT, check=True, capture_output=True).stdout


def reader(revision):
    def read(path):
        result = subprocess.run(["git", "show", f"{revision}:{path}"], cwd=ROOT, capture_output=True)
        return result.stdout.decode("utf-8") if result.returncode == 0 else None
    return read


ZERO_SHA = "0" * 40


def push_base(before, head, run_git):
    """(commit to compare a push with, or None when there is nothing to compare; notice or None).

    before is the push event's `before`: all zeros when the push created the branch, and possibly missing
    or not an ancestor of head after a force push. Those cases fall back to what can still be compared (the
    merge base of before and head, else head's first parent) instead of skipping the check.
    run_git(*arguments) returns (returncode, stdout).
    """
    def resolve(revision):
        code, out = run_git("rev-parse", "--verify", "--quiet", f"{revision}^{{commit}}")
        return out.strip() if code == 0 and out.strip() else None

    known = before and before != ZERO_SHA and resolve(before)
    if known:
        if run_git("merge-base", "--is-ancestor", known, head)[0] == 0:
            return known, None
        code, out = run_git("merge-base", known, head)
        if code == 0 and out.strip():
            return out.strip(), f"{before[:7]} is not an ancestor of {head[:7]} (force push); comparing with their merge base"
    reason = ("the push created the branch" if not before or before == ZERO_SHA else
              f"{before[:7]} is not available or shares no history with {head[:7]}")
    parent = resolve(f"{head}^")
    if parent:
        return parent, f"{reason}; comparing with {head[:7]}'s first parent only"
    return None, f"{reason}, and {head[:7]} has no parent; nothing to compare"


def run_git(*arguments):
    result = subprocess.run(["git", *arguments], cwd=ROOT, capture_output=True)
    return result.returncode, result.stdout.decode("utf-8", "replace")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True, help="Base branch commit (the PR's target)")
    parser.add_argument("--head", required=True, help="PR head commit")
    parser.add_argument("--quiet", action="store_true", help="Print only problems")
    parser.add_argument("--push", action="store_true",
                        help="--base is a push event's `before` (all zeros for a new branch, possibly gone after a "
                             "force push); see push_base()")
    parser.add_argument("--only", action="append", metavar="PACKAGE",
                        help="Report problems only for this package: app or a tool directory name (repeatable)")
    args = parser.parse_args(argv)
    if args.push:
        args.base, notice = push_base(args.base, args.head, run_git)
        if notice:
            print(f"Version check: {notice}.")
        if args.base is None:
            print("Version check passed.")
            return 0
    try:
        # Paths this PR changed since it branched (three dots); versions are compared with the base
        # branch itself, so a PR must also be above a bump that landed on main meanwhile.
        changed = [path for path in git("diff", "--name-only", "-z", "--no-renames", f"{args.base}...{args.head}", "--")
                   .decode("utf-8").split("\0") if path]
        fork = git("merge-base", args.base, args.head).decode("utf-8").strip()
        problems, notes = check(changed, reader(args.base), reader(args.head), reader(fork),
                                only=set(args.only) if args.only else None)
    except (ValueError, subprocess.CalledProcessError) as failure:
        detail = getattr(failure, "stderr", b"") or b""
        print(f"Version check could not run: {failure} {detail.decode('utf-8', 'replace')}".strip(), file=sys.stderr)
        return 2
    if not args.quiet:
        print(f"{len(changed)} changed path(s):")
        for path in sorted(notes):
            print(f"  {path}: {'; '.join(notes[path])}")
    if problems:
        print("\nVersion check failed. Every shipped change needs a new version (a changelog fragment for the "
              "APK, a manifest bump for a mini-tool); the same version must never ship twice with different "
              "content.\n", file=sys.stderr)
        print("\n\n".join(problems), file=sys.stderr)
        return 1
    print("Version check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
