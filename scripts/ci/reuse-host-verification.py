#!/usr/bin/env python3
"""Reuse a completed host check only for explicitly requested CI-evidence repairs."""

import json
import os
from pathlib import Path
import re
import subprocess


EVIDENCE_FILES = {
    ".github/workflows/android.yml",
    "scripts/ci/release-startup-smoke.py",
    "scripts/ci/reuse-host-verification.py",
    "scripts/ci/verify_release.py",
    "scripts/tests/test_release_startup.py",
    "scripts/tests/test_reuse_host_verification.py",
}
FULL_CHECKS = {
    "Verify API contract, embedded SDK, and security entry points",
    "Verify Wasm binary packaging",
    "Build APKs and behavioral test APKs",
    "Verify installation, catalog, network, runtime and cancellation regressions",
    "Verify catalog, backup, execution identity, dialogs and Wasm on Android",
}


def validate_prior_run(run, jobs, repository, default_branch):
    if (run.get("repository", {}).get("full_name") != repository
            or run.get("head_repository", {}).get("full_name") != repository
            or run.get("head_branch") != default_branch
            or run.get("path") != ".github/workflows/android.yml"
            or run.get("event") not in ("push", "workflow_dispatch")
            or run.get("status") != "completed"):
        raise ValueError("Baseline must be a completed default-branch Android workflow in this repository")
    commit = run.get("head_sha", "")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Baseline commit is invalid")
    for job in jobs:
        if job.get("name") != "Build APK and verify host behavior" or job.get("conclusion") != "success":
            continue
        passed = {step["name"] for step in job.get("steps", []) if step.get("conclusion") == "success"}
        if FULL_CHECKS <= passed:
            return commit
    raise ValueError("Baseline must have actually passed the full host checks; skipped or reused checks do not qualify")


def validate_changed_files(paths):
    unexpected = set(paths) - EVIDENCE_FILES
    if unexpected:
        raise ValueError("Host inputs changed; run affected host checks instead of reusing this baseline: " + ", ".join(sorted(unexpected)))


def main():
    run_id = os.environ.get("REUSE_VERIFIED_RUN", "")
    outputs = {"scope": "current_run", "source_run": os.environ["GITHUB_RUN_ID"], "base_commit": os.environ["GITHUB_SHA"]}
    if run_id:
        if os.environ["GITHUB_EVENT_NAME"] != "workflow_dispatch" or not re.fullmatch(r"[0-9]+", run_id):
            raise ValueError("Reusing evidence requires a manually selected numeric workflow run ID")
        repository = os.environ["GITHUB_REPOSITORY"]
        endpoint = f"repos/{repository}/actions/runs/{run_id}"

        def api(path):
            return json.loads(subprocess.check_output(["gh", "api", path], text=True))

        run = api(endpoint)
        # Inspect the exact completed attempt, even if someone subsequently reruns the workflow.
        jobs = api(f"{endpoint}/attempts/{run['run_attempt']}/jobs?per_page=100")["jobs"]
        commit = validate_prior_run(run, jobs, repository, os.environ["DEFAULT_BRANCH"])
        subprocess.run(["git", "merge-base", "--is-ancestor", commit, os.environ["GITHUB_SHA"]], check=True)
        changed = subprocess.check_output(["git", "diff", "--name-only", "--no-renames", "-z", commit, os.environ["GITHUB_SHA"]]).decode().split("\0")
        validate_changed_files(path for path in changed if path)
        outputs = {"scope": "reused_unchanged_host", "source_run": run_id, "base_commit": commit}
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as handle:
            handle.write(f"Host inputs match `{commit}`. Reusing its completed full checks from [run {run_id}](https://github.com/{repository}/actions/runs/{run_id}); this run checks only CI evidence and signed release startup.\n")
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as handle:
        handle.writelines(f"{key}={value}\n" for key, value in outputs.items())


if __name__ == "__main__":
    main()
