#!/usr/bin/env python3
"""Checks for .github/workflows/release.yml (publishing a stable release).

check-tag:   the new tag must be strictly higher, by MAJOR.MINOR.PATCH, than the current latest release, so a
             wrong run can never make an older build the "latest" one the stable channel offers.
collect-tbx: puts every registered mini-tool's .tbx, at exactly the version its manifest has at the released
             commit, into the release folder. Each package comes from the first source folder that has it
             (the `dev` test channel first, then an earlier release named by the person publishing); a
             source's SHA256SUMS.txt must list and match it, and the package's own manifest must name the same
             tool and version. A tool no source has at that version fails the release instead of shipping an
             old package.
"""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = "scripts/ci/tbx-targets.json"
TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")


def parse_tag(tag):
    match = TAG.fullmatch(tag or "")
    return tuple(int(part) for part in match.groups()) if match else None


def tag_problem(tag, latest):
    """None when tag may become the latest release; latest is the current latest tag or empty for none."""
    new = parse_tag(tag)
    if new is None:
        return f"Unexpected tag {tag!r}; use vMAJOR.MINOR.PATCH"
    if not latest:
        return None
    current = parse_tag(latest)
    if current is None:
        return f"The latest release has tag {latest!r}, which is not vMAJOR.MINOR.PATCH; compare the versions by hand"
    if new <= current:
        return f"{tag} is not higher than the latest release {latest}; a stable release may only move forward"
    return None


def read_sums(folder):
    """{file name: sha256} from folder/SHA256SUMS.txt, or None when the folder has none."""
    path = folder / "SHA256SUMS.txt"
    if not path.is_file():
        return None
    sums = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64}) [ *]?(.+)", line.strip())
        if match:
            sums[match[2]] = match[1]
    return sums


def package_identity(path):
    with zipfile.ZipFile(path) as archive:
        manifest = json.loads(archive.read("manifest.json"))
    return manifest.get("id"), manifest.get("version")


def collect(registry, manifests, sources, out):
    """Copies each tool's package into out. Returns (problems, {tool: (file, source)})."""
    problems, chosen = [], {}
    for name in sorted(registry):
        manifest = manifests[name]
        wanted = f"{name}-v{manifest['version']}.tbx"
        offered = []
        for source in sources:
            candidate = source / wanted
            offered += sorted(path.name for path in source.glob(f"{name}-v*.tbx"))
            if not candidate.is_file():
                continue
            sums = read_sums(source)
            if sums is not None:
                digest = hashlib.sha256(candidate.read_bytes()).hexdigest()
                if sums.get(wanted) != digest:
                    problems.append(f"{wanted} from {source.name} does not match its SHA256SUMS.txt entry")
                    break
            try:
                identity = package_identity(candidate)
            except (zipfile.BadZipFile, KeyError, ValueError) as failure:
                problems.append(f"{wanted} from {source.name} is not a readable package: {failure}")
                break
            if identity != (manifest.get("id"), manifest["version"]):
                problems.append(f"{wanted} from {source.name} contains {identity[0]} {identity[1]}, "
                                f"not {manifest.get('id')} {manifest['version']}")
                break
            shutil.copyfile(candidate, out / wanted)
            chosen[name] = (wanted, source.name)
            break
        else:
            have = ", ".join(sorted(set(offered))) or "nothing"
            problems.append(f"{name}: the released commit has version {manifest['version']}, but no source has "
                            f"{wanted} (found: {have}). Publish from an Android CI run whose commit matches the test "
                            "channel's tools, or name an earlier release that has this version in reuse_tbx_from.")
    return problems, chosen


def git_reader(revision):
    def read(path):
        result = subprocess.run(["git", "show", f"{revision}:{path}"], cwd=ROOT, capture_output=True)
        if result.returncode != 0:
            raise ValueError(f"Cannot read {path} at {revision}: {result.stderr.decode('utf-8', 'replace').strip()}")
        return result.stdout.decode("utf-8")
    return read


def registered_manifests(read):
    registry = json.loads(read(REGISTRY))
    manifests = {}
    for name, config in registry.items():
        relative = PurePosixPath(config.get("manifest", "manifest.json"))
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) or relative.is_absolute() or ".." in relative.parts:
            raise ValueError(f"Unexpected registry entry {name}")
        manifest = json.loads(read(f"examples/{name}/{relative.as_posix()}"))
        if not isinstance(manifest.get("version"), str) or not re.fullmatch(r"[0-9A-Za-z.+-]+", manifest["version"]):
            raise ValueError(f"Invalid version in {name}'s manifest")
        manifests[name] = manifest
    return registry, manifests


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    tag = commands.add_parser("check-tag", help="Refuse a tag not strictly higher than the latest release")
    tag.add_argument("--tag", required=True)
    tag.add_argument("--latest", default="", help="Current latest release tag; empty when there is none")
    tbx = commands.add_parser("collect-tbx", help="Copy every registered tool's package at the released versions")
    tbx.add_argument("--revision", required=True, help="The commit being released")
    tbx.add_argument("--out", required=True, type=Path)
    tbx.add_argument("sources", nargs="+", type=Path, help="Folders of .tbx files, most preferred first")
    args = parser.parse_args(argv)

    if args.command == "check-tag":
        problem = tag_problem(args.tag, args.latest.strip())
        if problem:
            print(problem, file=sys.stderr)
            return 1
        print(f"{args.tag} is higher than the latest release {args.latest or '(none)'}.")
        return 0

    try:
        registry, manifests = registered_manifests(git_reader(args.revision))
    except (ValueError, json.JSONDecodeError) as failure:
        print(f"Cannot read the registered tools at {args.revision}: {failure}", file=sys.stderr)
        return 2
    args.out.mkdir(parents=True, exist_ok=True)
    problems, chosen = collect(registry, manifests, [source for source in args.sources if source.is_dir()], args.out)
    for name, (file, source) in sorted(chosen.items()):
        print(f"{file} from {source}")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
