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
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "scripts/ci/tbx-targets.json"
SHARED_PATHS = (
    ".github/workflows/tbx.yml", "scripts/ci/*",
    "scripts/package-tool.py", "scripts/package-examples.sh", "sdk/*",
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
    matrix = {"include": [{"tool": name, "npm": registry[name].get("npm", False)} for name in selected]}
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
        substitutions = {"output": str(output), "version": manifest["version"]}
        execute([argument.format(**substitutions) for argument in config["package"]], source)
        if config.get("built_path"):
            shutil.copyfile(ROOT / config["built_path"].format(**substitutions), output)
        digest = check_package(output, manifest, config)
        delivery = directory / "delivery"
        delivery.mkdir(exist_ok=True)
        os.replace(output, delivery / filename)
        (delivery / "SHA256SUMS.txt").write_text(f"{digest}  {filename}\n")
    print(f"{digest}  {delivery / filename}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("plan", "build"))
    parser.add_argument("--tool", default="all")
    args = parser.parse_args()
    try:
        if args.command == "plan":
            plan(args.tool)
        else:
            for name in select_targets(targets(), args.tool):
                build(name)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as failure:
        parser.exit(1, f"TBX build: {failure}\n")


if __name__ == "__main__":
    main()
