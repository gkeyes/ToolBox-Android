#!/usr/bin/env python3
"""Select registered TBX targets and reuse their existing build/package entrypoints."""

import argparse
from fnmatch import fnmatchcase
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "scripts/ci/tbx-targets.json"
SHARED_PATHS = (
    ".github/workflows/tbx.yml", "scripts/ci/*", "scripts/tests/test_tbx_ci.py",
    "scripts/package-tool.py", "scripts/package-examples.sh", "schema/*", "sdk/*",
    "tool-api/*", "tool-package/*", "tool-runtime/*", "core-*/*",
    "app/build.gradle.kts", "app/src/main/kotlin/io/toolbox/host/runtime/*",
    "app/src/main/kotlin/io/toolbox/host/permissions/*", "gradle/*", "gradlew*",
    "gradle.properties", "build.gradle.kts", "settings.gradle.kts",
)
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?")


def targets():
    return json.loads(REGISTRY.read_text(encoding="utf-8"))


def select_targets(registry, selection, changed=()):
    if selection == "all":
        return sorted(registry)
    if selection == "changed":
        if any(fnmatchcase(path, pattern) for path in changed for pattern in SHARED_PATHS):
            return sorted(registry)
        return sorted(name for name in registry
                      if any(path.startswith(f"examples/{name}/") for path in changed))
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
    try:
        result = subprocess.check_output(
            ["git", "diff", "--name-only", "-z", "--no-renames", base, head, "--"], cwd=ROOT,
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
    for path, expected in config.get("manifest_fields", {}).items():
        value = manifest
        for key in path.split("."):
            value = value[key]
        if value != expected:
            raise ValueError(f"{name}: manifest contract differs at {path}")
    if "permissions" in config and [item["name"] for item in manifest["permissions"]] != config["permissions"]:
        raise ValueError(f"{name}: permission contract differs")
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


def plan(selection):
    registry = targets()
    changed = ()
    if selection == "changed":
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        changed = git_changes(event, os.environ["GITHUB_EVENT_NAME"])
    selected = select_targets(registry, selection, changed)
    for name in selected:
        metadata(name, registry[name])
    matrix = {"include": [{"tool": name, "npm": registry[name].get("npm", False),
                           "browser": registry[name].get("browser", False)} for name in selected]}
    append_values(os.environ.get("GITHUB_OUTPUT"), {
        "matrix": json.dumps(matrix, separators=(",", ":")), "count": len(selected),
    })
    print(json.dumps(matrix, indent=2))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as handle:
            handle.write("TBX targets: " + (", ".join(selected) or "none (no registered tool affected)") + "\n")


def execute(command, source):
    subprocess.run(command, cwd=source, check=True)


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
        # Retain the existing importer HTML-prefix check; actual import follows in Gradle.
        archive.read(manifest["entry"])[:4096].decode("utf-8")
        integrity = json.loads(archive.read("integrity.json"))
        if integrity["schemaVersion"] != 1 or integrity["algorithm"] != "SHA-256":
            raise ValueError("Unsupported integrity format")
        if set(integrity["files"]) != set(names) - {"integrity.json"}:
            raise ValueError("Integrity inventory differs from package")
        for name, expected in integrity["files"].items():
            if hashlib.sha256(archive.read(name)).hexdigest() != expected:
                raise ValueError(f"Integrity mismatch: {name}")
    return hashlib.sha256(package.read_bytes()).hexdigest()


def build(name):
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise ValueError("TBX builds run only in GitHub Actions")
    registry = targets()
    if name not in registry:
        raise ValueError("Select one registered tool to build")
    config = registry[name]
    source, manifest, host = metadata(name, config)
    directory = ROOT / "build/tbx" / name
    directory.mkdir(parents=True, exist_ok=False)
    if config.get("npm"):
        for command in (["npm", "ci", "--ignore-scripts"], ["npm", "test"], ["npm", "run", "build"]):
            execute(command, source)
    javascript = matched_files(source, config["javascript"])
    for path in javascript:
        execute(["node", "--check", str(path)], source)
    tests = matched_files(source, config["tests"])
    if tests:
        execute(["node", "--test", *map(str, tests)], source)
    for forbidden in config.get("forbidden_text", []):
        for path in source.rglob("*"):
            if path.is_file() and forbidden.encode("utf-8") in path.read_bytes():
                raise ValueError(f"Forbidden private API in {path.name}: {forbidden}")
    filename = f"{name}-v{manifest['version']}.tbx"
    hashes = []
    for attempt in ("first", "second"):
        output = directory / attempt / filename
        output.parent.mkdir()
        substitutions = {"output": str(output), "version": manifest["version"]}
        execute([argument.format(**substitutions) for argument in config["package"]], source)
        if config.get("built_path"):
            built = ROOT / config["built_path"].format(**substitutions)
            shutil.copyfile(built, output)
        hashes.append(check_package(output, manifest, config))
    if hashes[0] != hashes[1]:
        raise ValueError("Repeated packaging did not reproduce the same bytes")
    delivery = directory / "delivery"
    delivery.mkdir()
    package = delivery / filename
    shutil.copyfile(directory / "first" / filename, package)
    append_values(os.environ.get("GITHUB_ENV"), {
        "TOOLBOX_PACKAGE_UNDER_TEST": package,
        "TOOLBOX_PACKAGE_EXPECTED_VERSION_CODE": manifest["versionCode"],
        "TOOLBOX_PACKAGE_EXPECTED_ID": manifest["id"],
        "TOOLBOX_PACKAGE_HOST_VERSION": host,
        "TBX_VERSION": manifest["version"],
    })
    checks = {
        "TOOL_ID": manifest["id"], "TOOL_VERSION": manifest["version"],
        "VERSION_CODE": manifest["versionCode"], "MIN_HOST_VERSION": manifest["minHostVersion"],
        "IMPORT_HOST_VERSION": host,
        "JS_CHECK": "PASS" if javascript else "COVERED_BY_PRODUCTION_BUILD",
        "NODE_TESTS": "PASS" if tests or config.get("npm") else "NOT_AVAILABLE",
        "REPRODUCIBLE_PACKAGE": "PASS", "INTEGRITY_CHECK": "PASS", "CURRENT_HOST_HTML_PREFIX": "PASS",
        "PACKAGE_SHA256": hashes[0],
    }
    (directory / "checks.json").write_text(json.dumps(checks, indent=2) + "\n")


def receipt(name):
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("TBX_IMPORTER_RESULT") != "success":
        raise ValueError("A successful production importer CI step is required")
    registry = targets()
    if name not in registry:
        raise ValueError("Select one registered tool")
    browser = registry[name].get("browser", False)
    if browser and os.environ.get("TBX_BROWSER_RESULT") != "success":
        raise ValueError("Configured browser checks must succeed before delivery")
    directory = ROOT / "build/tbx" / name
    checks = json.loads((directory / "checks.json").read_text())
    values = {
        "BUILD_COMMIT": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "BUILD_REF": os.environ["GITHUB_REF"], **checks, "PRODUCTION_IMPORTER": "PASS",
        "BROWSER_INTERACTIONS": "PASS_SYNTHETIC_DATA_PRODUCTION_WORKER" if browser else "NOT_CONFIGURED",
        "SCREENSHOT_VALIDATION": "REMOVED_BY_USER_REQUEST", "APK_BUILD": "SEPARATE_ANDROID_CI",
        "DEVICE_TEST_RESULT": "NOT_RUN_USER_OWNED", "REAL_ACCOUNT_AND_AI_VALIDATION": "NOT_RUN",
    }
    delivery = directory / "delivery"
    filename = f"{name}-v{checks['TOOL_VERSION']}.tbx"
    if hashlib.sha256((delivery / filename).read_bytes()).hexdigest() != checks["PACKAGE_SHA256"]:
        raise ValueError("Package changed after validation")
    append_values(delivery / "BUILD_AND_TEST_RECEIPT.txt", values)
    sums = "".join(f"{hashlib.sha256((delivery / item).read_bytes()).hexdigest()}  {item}\n"
                   for item in (filename, "BUILD_AND_TEST_RECEIPT.txt"))
    (delivery / "SHA256SUMS.txt").write_text(sums)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("plan", "build", "receipt"))
    parser.add_argument("--tool", default="all")
    args = parser.parse_args()
    try:
        {"plan": plan, "build": build, "receipt": receipt}[args.command](args.tool)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as failure:
        parser.exit(1, f"TBX CI: {failure}\n")


if __name__ == "__main__":
    main()
