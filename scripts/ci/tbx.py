#!/usr/bin/env python3
"""Select registered TBX targets and reuse their existing build/package entrypoints."""

import argparse
from fnmatch import fnmatchcase
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "scripts/ci/tbx-targets.json"
# tbx.yml has a browser-test step only for these tools; registering browser tests elsewhere would be silently skipped.
BROWSER_TEST_RUNNERS = {"github-actions-watcher", "nextflux", "socialcoach"}
SHARED_PATHS = (
    ".github/workflows/tbx.yml", "scripts/ci/tbx.py", "scripts/ci/tbx-targets.json",
    "scripts/package-tool.py", "scripts/package-examples.sh", "sdk/*",
    "tool-api/*", "tool-package/*", "tool-runtime/*", "core-*/*",
    "app/build.gradle.kts", "app/src/main/kotlin/io/toolbox/host/runtime/*",
    "app/src/main/kotlin/io/toolbox/host/permissions/*", "gradle/*", "gradlew*",
    "gradle.properties", "build.gradle.kts", "settings.gradle.kts",
)
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?")


def available_tests(name, kind, registry=None):
    """Discover a tool's tests from the globs registered in tbx-targets.json.

    Paths under scripts/ are repository-relative host tests shared by several tools; every other pattern is
    relative to the tool directory.
    """
    config = (registry or targets())[name]
    tool = ROOT / "examples" / name
    found = set()
    for pattern in config.get("tests", {}).get(kind, []):
        if pattern.startswith("scripts/"):
            if (ROOT / pattern).is_file():
                found.add(pattern)
        else:
            found.update(path.relative_to(tool).as_posix() for path in tool.glob(pattern) if path.is_file())
    return found


def shared_tests(name, registry):
    """Registered host tests under scripts/ that also cover this tool; a change to one selects the tool."""
    tests = registry[name].get("tests", {})
    return {path for kind in ("unit", "browser") for path in tests.get(kind, []) if path.startswith("scripts/")}


def targets():
    return json.loads(REGISTRY.read_text(encoding="utf-8"))


def select_targets(registry, selection, changed=()):
    if selection == "all":
        return sorted(registry)
    if selection == "changed":
        if any(fnmatchcase(path, pattern) for path in changed for pattern in SHARED_PATHS):
            return sorted(registry)
        return sorted(name for name in registry
                      if any(path.startswith(f"examples/{name}/") for path in changed)
                      or any(path in changed for path in shared_tests(name, registry)))
    requested = selection.split(",")
    requested = [name.strip() for name in requested]
    unknown = [name for name in requested if name not in registry]
    if unknown:
        raise ValueError(f"Unknown TBX target(s): {', '.join(unknown)}. Choose {', '.join(sorted(registry))}, all, or changed.")
    return sorted(set(requested))


def git_changes(event, event_name):
    if event_name == "pull_request":
        base = event["pull_request"]["base"]["sha"]
        head = event["pull_request"]["head"]["sha"]
    elif event_name == "push":
        base, head = event["before"], event["after"]
    else:
        raise ValueError("'changed' needs a push or pull_request event; manually select a tool or all.")
    if not all(re.fullmatch(r"[0-9a-f]{40}", value) for value in (base, head)):
        raise ValueError("Expected immutable Git commit IDs in the event")
    # A new branch or unavailable pre-force-push commit must not silently skip tools.
    if base == "0" * 40:
        return ["scripts/ci/tbx-targets.json"]
    # A pull request is compared from where it branched off: diffing against the base branch's
    # current tip would also list everything merged since, and test tools the PR never touched.
    revisions = [f"{base}...{head}"] if event_name == "pull_request" else [base, head]
    try:
        result = subprocess.check_output(
            ["git", "diff", "--name-only", "-z", "--no-renames", *revisions, "--"], cwd=ROOT,
        )
    except subprocess.CalledProcessError:
        print("Before/after diff unavailable; selecting all registered TBX targets.")
        return ["scripts/ci/tbx-targets.json"]
    return [name for name in result.decode("utf-8").split("\0") if name]


def metadata(name, config):
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name):
        raise ValueError("Invalid registered tool directory")
    source = ROOT / "examples" / name
    relative = PurePosixPath(config["manifest"])
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("Manifest must stay inside its registered tool directory")
    manifest = json.loads((source / relative).read_text(encoding="utf-8"))
    for field in ("version", "minHostVersion"):
        if not isinstance(manifest.get(field), str) or not VERSION.fullmatch(manifest[field]):
            raise ValueError(f"Invalid {field} in {name}")
    if not re.fullmatch(r"[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*){2,}", manifest.get("id", "")):
        raise ValueError(f"Invalid tool ID in {name}")
    if type(manifest.get("versionCode")) is not int or not 1 <= manifest["versionCode"] <= 2147483647:
        raise ValueError(f"Invalid versionCode in {name}")
    host = re.search(r'\bversionName\s*=\s*"([^"]+)"', (ROOT / "app/build.gradle.kts").read_text())
    if not host or not VERSION.fullmatch(host[1]):
        raise ValueError("Cannot read current host version")
    return source, manifest, host[1]


def append_values(environment_file, values):
    if environment_file:
        with Path(environment_file).open("a", encoding="utf-8") as handle:
            for key, value in values.items():
                if "\n" in str(value) or "\r" in str(value):
                    raise ValueError("Multiline workflow values are not accepted")
                handle.write(f"{key}={value}\n")


def selected_tests(name, changed, requested_filter, full, registry=None):
    registry = registry or targets()
    result = {"full_tests": full, "unit_test_filter": "", "browser_test_filter": "", "watcher_layout": False}
    if full or "tests" not in registry[name]:
        return result
    # Small plain-JS tools: a script, page or style edit reruns all of their tests; a media edit runs none.
    plain = registry[name].get("bundled", False) or name in {"stock-monitor", "kegel-trainer"}
    unit_files, browser_files = available_tests(name, "unit", registry), available_tests(name, "browser", registry)
    allowed = unit_files | browser_files
    if requested_filter:
        requested = {value.strip() for value in requested_filter.split(",") if value.strip()}
        selected = requested & allowed
    else:
        local = [path for path in changed if path.startswith(f"examples/{name}/")]
        shared = any(fnmatchcase(path, pattern) for path in changed for pattern in SHARED_PATHS)
        selected = set()
        for path in local:
            relative = path.removeprefix(f"examples/{name}/")
            if relative in allowed:
                selected.add(relative)
            elif name == "rss-scout" and relative in {"package.json", "package-lock.json"}:
                # RSS Scout's npm dependencies are test-only, so a dependency change must install and run them.
                selected |= unit_files
            elif registry[name].get("npm") and relative in {"package.json", "package-lock.json"}:
                # A built tool's dependencies ship in its .tbx and can break it at runtime without failing
                # the build, so a dependency change runs every registered test (npm test and the browser suite).
                return {**result, "full_tests": True}
            elif relative in {"manifest.json", "package.json", "package-lock.json", "README.md", "package.sh", "THIRD_PARTY_NOTICES.txt"}:
                continue
            elif name == "nextflux" and (relative.startswith("src/") or relative.startswith("test/browser/fixtures/")
                                         or relative.startswith("public/")
                                         or relative in {"index.html", "tailwind.config.js", "postcss.config.js", "vite.config.js"}):
                selected |= allowed
            elif name == "nextflux" and relative in {"test/browser/playwright.config.js", "test/browser/vite.config.js"}:
                selected |= browser_files
            elif name == "socialcoach" and (relative.startswith("platform/") or relative.startswith("src/")):
                selected |= allowed
            elif name == "health-records" and relative.startswith("web/"):
                selected |= allowed
            elif name == "github-actions-watcher" and relative in {"app.js", "style.css", "github-model.js", "reliability.js", "index.html"}:
                selected |= allowed
            elif plain and relative.endswith((".js", ".html", ".css")):
                selected |= allowed
            elif plain and relative.endswith((".png", ".svg", ".ogg")):
                continue
            elif name == "game-2048" and relative.endswith((".js", ".html", ".css")):
                selected |= allowed
            elif name == "game-2048" and relative.endswith(".svg"):
                continue
            else:
                # A path without a narrower mapping runs every registered test for this tool.
                return {**result, "full_tests": True}
        if shared:
            selected |= allowed
        selected |= {path for path in shared_tests(name, registry) if path in changed}
    result["unit_test_filter"] = ",".join(sorted(selected & unit_files))
    if name == "github-actions-watcher":
        # The watcher's browser check is a Python Playwright script with its own workflow step.
        result["watcher_layout"] = bool(selected & browser_files)
    else:
        result["browser_test_filter"] = ",".join(sorted(selected & browser_files))
    return result


def plan(selection, validation_scope="full", test_filter=""):
    registry = targets()
    changed = ()
    if selection == "changed":
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        changed = git_changes(event, os.environ["GITHUB_EVENT_NAME"])
    selected = select_targets(registry, selection, changed)
    if validation_scope not in {"full", "targeted", "build"}:
        raise ValueError("Unknown TBX validation scope")
    if test_filter and validation_scope != "targeted":
        raise ValueError("Choose targeted validation when selecting a test path")
    if validation_scope == "targeted" and selection != "changed" and not test_filter:
        raise ValueError("Targeted TBX dispatch needs a test path")
    if validation_scope == "build" and selection == "changed":
        raise ValueError("Build-only dispatch needs an explicitly selected tool")
    unsupported = sorted(name for name, config in registry.items()
                         if config.get("tests", {}).get("browser") and name not in BROWSER_TEST_RUNNERS)
    if unsupported:
        raise ValueError(f"Browser tests are registered for {', '.join(unsupported)}, but tbx.yml has no browser-test step for them")
    for name in selected:
        metadata(name, registry[name])
    matrix = {"include": [
        {"tool": name, "npm": registry[name].get("npm", False), "bundled": registry[name].get("bundled", False),
         "unit_tests": bool(registry[name].get("tests", {}).get("unit")),
         **selected_tests(name, changed, test_filter, full=selection != "changed" and validation_scope == "full", registry=registry)}
        for name in selected
    ]}
    if test_filter and not any(row["unit_test_filter"] or row["browser_test_filter"] or row["watcher_layout"] for row in matrix["include"]):
        raise ValueError("Selected TBX test path does not belong to a selected tool")
    append_values(os.environ.get("GITHUB_OUTPUT"), {
        "matrix": json.dumps(matrix, separators=(",", ":")), "count": len(selected),
    })
    print(json.dumps(matrix, indent=2))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as handle:
            handle.write(f"TBX scope: {validation_scope}; targets: " + (", ".join(selected) or "none (no registered tool affected)") + "\n")


def execute(command, source):
    subprocess.run(command, cwd=source, check=True)


def run_selected_tests(name, test_filter, browser=False):
    registry = targets()
    if "tests" not in registry.get(name, {}):
        raise ValueError(f"No registered tests for {name}")
    allowed = available_tests(name, "browser" if browser else "unit", registry)
    # "all" runs every test the registered globs discover, so full validation never misses a new file.
    selected = sorted(allowed) if test_filter.strip() == "all" else [value.strip() for value in test_filter.split(",") if value.strip()]
    name_patterns = registry[name]["tests"].get("name_patterns", {})
    if not selected or any(value not in allowed for value in selected):
        raise ValueError(f"Invalid selected {'browser' if browser else 'unit'} tests for {name}")
    if browser:
        source = ROOT / "examples" / name / ("test/browser" if name == "nextflux" else "")
        paths = [Path(value).name if name == "nextflux" else value for value in selected]
        for path in paths:
            listing = subprocess.run(["npx", "playwright", "test", "--list", path], cwd=source, text=True, capture_output=True, check=True)
            print(listing.stdout, end="")
            count = re.search(r"Total: (\d+) tests?", listing.stdout)
            if not count or int(count[1]) == 0:
                raise ValueError(f"No Playwright tests matched {path}")
        execute(["npx", "playwright", "test", *paths], source)
    else:
        for path in selected:
            source = ROOT if path.startswith("scripts/") else ROOT / "examples" / name
            command = ["node"] + (["--import", "tsx"] if name == "socialcoach" else []) + ["--test"]
            if path in name_patterns:
                command += ["--test-name-pattern", name_patterns[path]]
            command.append(path)
            completed = subprocess.run(command, cwd=source, text=True, capture_output=True)
            sys.stdout.write(completed.stdout)
            sys.stderr.write(completed.stderr)
            completed.check_returncode()
            count = re.findall(r"^# pass (\d+)\s*$", completed.stdout, re.MULTILINE)
            if not count or int(count[-1]) == 0:
                raise ValueError(f"No Node tests executed for {name}: {path}")


def matched_files(source, patterns):
    result = set()
    for pattern in patterns:
        matches = [path for path in source.glob(pattern) if path.is_file()]
        if not matches:
            raise ValueError(f"No files match the registered check: {source.name}/{pattern}")
        result.update(matches)
    return sorted(result)


def check_package(package, manifest, config):
    with zipfile.ZipFile(package) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate archive entries")
        if "expected_files" in config and set(names) != set(config["expected_files"]):
            raise ValueError("Package contents differ from the registered file list")
        if json.loads(archive.read("manifest.json")) != manifest:
            raise ValueError("Packaged manifest differs from source")
        if manifest["entry"] not in names:
            raise ValueError("Missing HTML entry")
        integrity = json.loads(archive.read("integrity.json"))
        if integrity["schemaVersion"] != 1 or integrity["algorithm"] != "SHA-256":
            raise ValueError("Unsupported integrity format")
        if set(integrity["files"]) != set(names) - {"integrity.json"}:
            raise ValueError("Integrity inventory differs from package")
        for name, expected in integrity["files"].items():
            if stream_digest(archive.open(name)) != expected:
                raise ValueError(f"Integrity mismatch: {name}")
    return stream_digest(package.open("rb"))


def stream_digest(stream):
    with stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
        return digest.hexdigest()


def build(name):
    config = targets()[name]
    source, manifest, _ = metadata(name, config)
    if config.get("npm"):
        execute(["npm", "run", "build"], source)
    for path in matched_files(source, config["javascript"]):
        execute(["node", "--check", str(path)], source)
    directory = ROOT / "build/tbx" / name
    directory.mkdir(parents=True, exist_ok=True)
    filename = f"{name}-v{manifest['version']}.tbx"
    with tempfile.TemporaryDirectory(dir=directory) as temporary:
        output = Path(temporary) / filename
        substitutions = {"output": str(output)}
        execute([argument.format(**substitutions) for argument in config["package"]], source)
        digest = check_package(output, manifest, config)
        delivery = directory / "delivery"
        delivery.mkdir(exist_ok=True)
        os.replace(output, delivery / filename)
        (delivery / "SHA256SUMS.txt").write_text(f"{digest}  {filename}\n")
    print(f"{digest}  {delivery / filename}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("plan", "build", "test-unit", "test-browser", "superseded"))
    parser.add_argument("--tool", default="all")
    parser.add_argument("--validation-scope", default="full")
    parser.add_argument("--test-filter", default="")
    args = parser.parse_args()
    try:
        if args.command == "plan":
            plan(args.tool, args.validation_scope, args.test_filter)
        elif args.command == "superseded":
            # Paths changed on main after this run's commit, one per line on stdin; prints the tools a
            # newer push run rebuilds, so this run must not publish its older packages for them.
            changed = [line.strip() for line in sys.stdin if line.strip()]
            print("\n".join(select_targets(targets(), "changed", changed)))
        elif args.command.startswith("test-"):
            run_selected_tests(args.tool, args.test_filter, browser=args.command == "test-browser")
        else:
            for name in select_targets(targets(), args.tool):
                build(name)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as failure:
        parser.exit(1, f"TBX build: {failure}\n")


if __name__ == "__main__":
    main()
