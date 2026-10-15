#!/usr/bin/env python3
"""Computes the ToolBox APK version for a build on main from the changelog fragments in changes/.

Pull requests do not edit versionCode / versionName (every open PR would conflict on those two lines).
A PR that changes what ships in the APK adds one fragment, changes/<unique name>.md (format in
changes/README.md, parsed by version_bump.parse_fragment), naming its bump and its changelog line.

The build on main starts from the last build published to the test channel: VERSION_NAME, VERSION_CODE
and BUILD_COMMIT in the `dev` release's BUILD_RECEIPT.txt (written by verify_release.py, uploaded by
publish-test-release.sh together with the APK and the tag move). It collects the fragments added between
that commit and HEAD and raises the version by the largest bump among them (major > minor > patch; the
lower parts reset to 0) with versionCode + 1, never below the version written in app/build.gradle.kts
(which only matters for a bump written there before this scheme and not yet published). Without a new fragment the version stays the published one,
which the publisher skips as already published; but a change that ships in the APK without a fragment
fails. Only a missing `dev` release (HTTP 404), or one without an app build yet, falls back to the values
written in app/build.gradle.kts, counted from the commit that last set them; any other lookup failure is
retried and then fails closed.

Usage:
  app_version.py compute --head SHA
      Prints and appends to $GITHUB_OUTPUT: name, code, base_name, base_code, base_commit and changes (a
      JSON list of changelog lines). Needs GH_REPO and the gh CLI, and the full git history.
  app_version.py check-dev --base-commit SHA --head SHA
      Before publishing: fails when another run published to `dev` after `compute` read it, unless the
      newer build already contains HEAD (the publisher then skips this one as older).
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import version_bump  # noqa: E402  (shares the fragment format and what counts as shipped)

ROOT = Path(__file__).resolve().parents[2]
RECEIPT = "BUILD_RECEIPT.txt"
DEV_APK = re.compile(r"^toolbox-v(.+)-dev\.apk$")
APP_APK = re.compile(r"^toolbox-v.+\.apk$")
# Lines in app/build.gradle.kts that set the fallback version; the commit that last changed one is where
# fragments are counted from when `dev` has no app build.
VERSION_LINE_PATTERN = r"^\s*version(Code|Name)\s*="
RETRIES = 3


class LookupFailed(Exception):
    """The test channel's state could not be read or does not add up; nothing may be built from a guess."""


class MissingFragment(Exception):
    """A change ships in the APK, but no fragment was added since the last published build."""


def computed_version(environ):
    """(versionName, versionCode) that CI passes to Gradle as TOOLBOX_VERSION_NAME / TOOLBOX_VERSION_CODE, or
    None when neither is set (pull requests and local builds use the values in app/build.gradle.kts)."""
    name, code = environ.get("TOOLBOX_VERSION_NAME", ""), environ.get("TOOLBOX_VERSION_CODE", "")
    if not name and not code:
        return None
    if version_bump.parse_semver(name) is None or not re.fullmatch(r"[1-9][0-9]{0,9}", code):
        raise ValueError(f"TOOLBOX_VERSION_NAME / TOOLBOX_VERSION_CODE must be set together and valid: {name!r} {code!r}")
    return name, code


def bump_version(name, bumps):
    """name raised by the largest of bumps (patch, minor, major); unchanged when bumps is empty."""
    parts = version_bump.parse_semver(name)
    if parts is None:
        raise ValueError(f"Version {name!r} is not MAJOR.MINOR.PATCH")
    if not bumps:
        return name
    major, minor, patch = parts
    highest = max(bumps, key=version_bump.BUMPS.index)
    if highest == "major":
        return f"{major + 1}.0.0"
    if highest == "minor":
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def compute(base_name, base_code, fragments, shipped, floor=None):
    """(name, code, changelog lines) for a build whose last published version is base_name (base_code).

    fragments: [(path, bump, line)] added since that build, oldest first; shipped: paths changed since then
    that go into the APK. floor: (name, code) written in app/build.gradle.kts; a new version is never below
    it, so a bump written there before versions were computed (and not yet published) is not undone.
    """
    if not fragments:
        if shipped:
            listed = "\n".join(f"    {path}" for path in shipped[:10]) + (
                f"\n    ... and {len(shipped) - 10} more" if len(shipped) > 10 else "")
            raise MissingFragment(
                f"These changes since the last published build ({base_name}, {base_code}) ship in the APK, but no "
                f"changelog fragment was added under {version_bump.FRAGMENTS} for them:\n{listed}\n"
                f"Add one in a follow-up PR (see {version_bump.FRAGMENTS_README}); this build is not published.")
        return base_name, base_code, []
    name, code = bump_version(base_name, [bump for _, bump, _ in fragments]), base_code + 1
    if floor:
        # Each part separately: a minor bump over an old dev build can pass the floor's name but not its code.
        if version_bump.parse_semver(floor[0]) > version_bump.parse_semver(name):
            name = floor[0]
        code = max(code, floor[1])
    return name, code, [line for _, _, line in fragments]


def parse_receipt(text):
    values = dict(line.split("=", 1) for line in text.splitlines() if "=" in line)
    name, code, commit = values.get("VERSION_NAME", ""), values.get("VERSION_CODE", ""), values.get("BUILD_COMMIT", "")
    if version_bump.parse_semver(name) is None or not re.fullmatch(r"[1-9][0-9]*", code) \
            or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise LookupFailed(f"The dev {RECEIPT} has no valid VERSION_NAME, VERSION_CODE and BUILD_COMMIT")
    return {"name": name, "code": int(code), "commit": commit}


def read_dev(api, repo, sleep=time.sleep, own_apk=None):
    """The last published app build on `dev` as {name, code, commit}, or None when there is none.

    own_apk: (version, sha256 hex) of the APK this run publishes. That exact APK on `dev`, left by an earlier
    attempt of this run that stopped before its receipt, is not an inconsistency: the publisher finishes it.

    api(path, accept) returns (returncode, stdout, stderr) of `gh api`. Only a missing release (HTTP 404)
    or a release without any app build counts as none; anything else is retried and then raises.
    """
    def call(path, accept=None, missing_ok=False):
        for attempt in range(1, RETRIES + 1):
            code, out, err = api(path, accept)
            if code == 0:
                return out
            if missing_ok and "HTTP 404" in err:
                return None
            if attempt == RETRIES:
                raise LookupFailed(f"Could not read {path}: {err.strip()}")
            sleep(attempt * 5)

    release = call(f"repos/{repo}/releases/tags/dev", missing_ok=True)
    if release is None:
        return None
    assets = json.loads(release).get("assets", [])
    receipt = next((asset for asset in assets if asset.get("name") == RECEIPT), None)
    apks = [match[1] for asset, match in ((asset, DEV_APK.match(asset.get("name", ""))) for asset in assets)
            if match and not (own_apk and match[1] == own_apk[0] and asset.get("digest") == f"sha256:{own_apk[1]}")]
    if receipt is None:
        if apks:
            raise LookupFailed(f"dev has an APK but no {RECEIPT}; an earlier publish stopped halfway. Re-run that "
                               "publish (it finishes the receipt and tag) before building a new version.")
        return None  # Created by the TBX workflow before any app build was published.
    build = parse_receipt(call(f"repos/{repo}/releases/assets/{receipt['id']}", "application/octet-stream"))
    highest = max(apks, key=lambda name: version_bump.parse_semver(name) or (-1,), default=None)
    if highest is not None and highest != build["name"] or highest is None and own_apk is None:
        raise LookupFailed(f"dev's APK ({highest}) and {RECEIPT} ({build['name']}) disagree; an earlier publish "
                           "stopped halfway. Re-run that publish before building a new version.")
    return build


def released_apps(api, repo):
    """Tags of releases other than `dev` that carry a ToolBox APK. Any failure to list them raises."""
    tags, page = [], 1
    while True:
        code, out, err = api(f"repos/{repo}/releases?per_page=100&page={page}")
        if code != 0:
            raise LookupFailed(f"Could not list releases: {err.strip()}")
        releases = json.loads(out)
        tags += [release["tag_name"] for release in releases if release.get("tag_name") != "dev"
                 and any(APP_APK.match(asset.get("name", "")) for asset in release.get("assets", []))]
        if len(releases) < 100:
            return tags
        page += 1


def gh_api(path, accept=None):
    command = ["gh", "api", path] + (["-H", f"Accept: {accept}"] if accept else [])
    result = subprocess.run(command, cwd=ROOT, capture_output=True)
    return result.returncode, result.stdout.decode("utf-8", "replace"), result.stderr.decode("utf-8", "replace")


def git(*arguments):
    return subprocess.run(["git", *arguments], cwd=ROOT, check=True, capture_output=True).stdout.decode("utf-8")


def has_commit(revision):
    return subprocess.run(["git", "cat-file", "-e", f"{revision}^{{commit}}"], cwd=ROOT, capture_output=True).returncode == 0


def fragments_since(base, head, read_head):
    """[(path, bump, line)] for fragments added after base up to head, in the order they were added.

    A fragment that does not parse raises ValueError (the PR check should have caught it).
    """
    added = set(git("diff", "--name-only", "-z", "--no-renames", "--diff-filter=A", f"{base}...{head}", "--",
                    version_bump.FRAGMENTS).split("\0")) - {""}
    ordered = []
    for path in git("log", "--reverse", "--diff-filter=A", "--name-only", "--format=", f"{base}..{head}", "--",
                    version_bump.FRAGMENTS).splitlines():
        if path in added and path not in ordered:
            ordered.append(path)
    ordered += sorted(added - set(ordered))
    result = []
    for path in ordered:
        if not version_bump.is_fragment(path):
            continue
        try:
            bump, line = version_bump.parse_fragment(read_head(path))
        except ValueError as problem:
            raise ValueError(f"{path}: {problem}") from None
        result.append((path, bump, line))
    return result


def main_compute(args):
    repo = os.environ["GH_REPO"]
    read_head = version_bump.reader(args.head)
    written_code, written_name = version_bump.app_version(read_head(version_bump.APP_BUILD))
    published = read_dev(gh_api, repo)
    if published:
        if not has_commit(published["commit"]) or subprocess.run(
                ["git", "merge-base", "--is-ancestor", published["commit"], args.head], cwd=ROOT,
                capture_output=True).returncode != 0:
            raise LookupFailed(f"dev's build commit {published['commit']} is not in the history of {args.head}")
        base_name, base_code, base = published["name"], published["code"], published["commit"]
        print(f"Last published build: ToolBox {base_name} ({base_code}) from {base[:7]}.")
    else:
        # Counting from the Gradle literal is only right before the first publish. Once ToolBox has shipped,
        # a lost or recreated `dev` would compute a version that reuses or undercuts one already on phones.
        shipped_releases = released_apps(gh_api, repo)
        if shipped_releases:
            raise LookupFailed(f"dev has no app build, but ToolBox has already shipped ({', '.join(shipped_releases[:3])}). "
                               "Restore the last published APK and BUILD_RECEIPT.txt to the dev release (they are in the "
                               "last successful main run's artifacts) and re-run, so the version continues from it.")
        base_code, base_name = written_code, written_name
        base = git("log", "-1", "--format=%H", f"-G{VERSION_LINE_PATTERN}", args.head, "--",
                   version_bump.APP_BUILD).strip()
        print(f"dev has no app build yet; starting from {version_bump.APP_BUILD}: {base_name} ({base_code}), "
              f"set in {base[:7] or 'no commit'}.")
    if base:
        changed = [path for path in git("diff", "--name-only", "-z", "--no-renames", f"{base}...{args.head}", "--")
                   .split("\0") if path]
        fork = git("merge-base", base, args.head).strip()
        shipped = version_bump.app_shipped(changed, version_bump.reader(fork), read_head)
        fragments = fragments_since(base, args.head, read_head)
    else:
        shipped, fragments = [], []
    name, code, changes = compute(base_name, base_code, fragments, shipped, floor=(written_name, written_code))
    outputs = {"name": name, "code": str(code), "base_name": base_name, "base_code": str(base_code),
               "base_commit": base if published else "", "changes": json.dumps(changes, ensure_ascii=False)}
    for key, value in outputs.items():
        print(f"{key}={value}")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as handle:
            handle.writelines(f"{key}={value}\n" for key, value in outputs.items())
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as handle:
            handle.write(f"ToolBox version for this build: **{name} ({code})**, from {base_name} ({base_code})"
                         + "".join(f"\n- {line}" for line in changes) + "\n")
    return 0


def main_check_dev(args):
    """Fails when `dev` moved after compute read it: this build's version would then not be new."""
    own_apk = None
    if args.apk:
        with open(args.apk, "rb") as handle:
            own_apk = (args.version, hashlib.sha256(handle.read()).hexdigest())
    published = read_dev(gh_api, os.environ["GH_REPO"], own_apk=own_apk)
    current = published["commit"] if published else ""
    if current == args.base_commit:
        print("dev still has the build this version was computed from.")
        return 0
    if current and has_commit(current) and subprocess.run(
            ["git", "merge-base", "--is-ancestor", args.head, current], cwd=ROOT).returncode == 0:
        print(f"dev now has {current[:7]}, which already contains {args.head[:7]}; the publisher skips this build.")
        return 0
    print(f"dev moved from {args.base_commit[:7] or 'no app build'} to {current[:7] or 'no app build'} after this run "
          "computed its version, so the version may no longer be new. Not publishing; re-run all jobs of this "
          "workflow (not only the failed ones) to build it with a fresh version.", file=sys.stderr)
    return 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    compute_parser = commands.add_parser("compute")
    compute_parser.add_argument("--head", required=True)
    check_parser = commands.add_parser("check-dev")
    check_parser.add_argument("--base-commit", required=True, help="base_commit from compute (empty: no app build)")
    check_parser.add_argument("--head", required=True)
    check_parser.add_argument("--version", default="", help="the version this run publishes")
    check_parser.add_argument("--apk", default="", help="the APK this run publishes; that exact file may be on dev "
                              "from an attempt that stopped before the receipt")
    args = parser.parse_args(argv)
    try:
        return main_compute(args) if args.command == "compute" else main_check_dev(args)
    except MissingFragment as problem:
        print(f"Version check failed. {problem}", file=sys.stderr)
        return 1
    except (LookupFailed, ValueError, subprocess.CalledProcessError) as failure:
        detail = getattr(failure, "stderr", b"") or b""
        print(f"Could not compute the app version; not building: {failure} {detail.decode('utf-8', 'replace')}".strip(),
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
