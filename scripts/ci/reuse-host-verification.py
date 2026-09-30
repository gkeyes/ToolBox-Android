#!/usr/bin/env python3
"""Reuse completed checks only for merged, unchanged host inputs; retain their scope."""

import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tempfile


EVIDENCE_FILES = set()
NON_HOST_FILES = {"README.md", ".github/workflows/tbx.yml", "scripts/ci/tbx.py", "scripts/ci/tbx-targets.json"}
BUNDLED_EXAMPLES = {"position-calculator", "quick-notes", "background-task-demo", "notification-lab"}
FULL_CHECKS = {
    "Lint the complete Android app",
    "Verify API contract, embedded SDK, and security entry points",
    "Verify Wasm binary packaging",
    "Build APKs and behavioral test APKs",
    "Verify installation, catalog, network, runtime and cancellation regressions",
    "Verify catalog, backup, execution identity, dialogs and Wasm on Android",
}
PERFORMANCE_CHECKS = {
    "Lint the complete Android app",
    "Verify API contract, embedded SDK, and security entry points",
    "Compile performance test APK",
    "Verify performance unit regressions",
    "Verify performance behavior on Android",
}
TARGETED_CHECKS = {
    "Lint the complete Android app",
    "Verify API contract, embedded SDK, and security entry points",
    "Build debug and release APKs for selected checks",
    "Verify targeted selection evidence",
    "Save targeted check evidence",
}
TARGETED_STEPS = {
    "unit": "Verify selected unit cases",
    "android": "Confirm selected Android cases actually ran",
    "node": "Verify selected browser Node cases",
}
TARGETED_ARTIFACT_PREFIX = "android-targeted-evidence"


def validate_prior_run(run, jobs, repository, default_branch, targeted_evidence=None):
    if (run.get("repository", {}).get("full_name") != repository
            or run.get("head_repository", {}).get("full_name") != repository
            or not run.get("head_branch")
            or (run.get("head_branch") != default_branch and run.get("event") not in ("workflow_dispatch", "pull_request"))
            or run.get("path") != ".github/workflows/android.yml"
            or run.get("event") not in ("push", "workflow_dispatch", "pull_request")
            or run.get("status") != "completed"):
        raise ValueError("Baseline must be a completed default-branch, manual, or same-repository PR Android workflow")
    commit = run.get("head_sha", "")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Baseline commit is invalid")
    for job in jobs:
        if job.get("name") not in ("Build APK and verify host behavior", "Verify selected Android test") or job.get("conclusion") != "success":
            continue
        passed = {step["name"] for step in job.get("steps", []) if step.get("conclusion") == "success"}
        if job.get("name") == "Build APK and verify host behavior" and FULL_CHECKS <= passed:
            return commit, "full"
        if job.get("name") == "Build APK and verify host behavior" and PERFORMANCE_CHECKS <= passed:
            return commit, "performance"
        if targeted_evidence is not None:
            validate_targeted_evidence(run, targeted_evidence)
            filters = targeted_evidence["filters"]
            required = TARGETED_CHECKS | {TARGETED_STEPS[kind] for kind, values in filters.items() if values}
            if required <= passed:
                return commit, "targeted"
    raise ValueError("Baseline must have actually passed a supported host check scope; skipped or reused checks do not qualify")


def validate_targeted_evidence(run, evidence):
    if (evidence.get("scope") != "targeted" or evidence.get("run_id") != run.get("id")
            or evidence.get("attempt") != run.get("run_attempt")
            or evidence.get("head_sha") != run.get("head_sha")):
        raise ValueError("Targeted evidence does not identify this exact run attempt and PR head")
    filters, executed = evidence.get("filters"), evidence.get("executed")
    if (not isinstance(filters, dict) or not isinstance(executed, dict)
            or set(filters) != set(TARGETED_STEPS) or set(executed) != set(TARGETED_STEPS)):
        raise ValueError("Targeted evidence is incomplete")
    if not filters["android"]:
        raise ValueError("A targeted release baseline must include actual Android behavior cases")
    for kind in TARGETED_STEPS:
        selected, counts = filters[kind], executed[kind]
        if (not isinstance(selected, list) or not isinstance(counts, dict)
                or any(not isinstance(value, str) or not value for value in selected)
                or len(selected) != len(set(selected)) or set(counts) != set(selected)
                or any(type(counts[value]) is not int or counts[value] <= 0 for value in selected)):
            raise ValueError(f"Targeted {kind} filters lack nonzero execution evidence")


def load_targeted_evidence(run, repository):
    name = f"{TARGETED_ARTIFACT_PREFIX}-{run['id']}-{run['run_attempt']}"
    listed = json.loads(subprocess.check_output([
        "gh", "api", f"repos/{repository}/actions/runs/{run['id']}/artifacts?per_page=100",
    ], text=True))["artifacts"]
    matches = [artifact for artifact in listed if artifact.get("name") == name and not artifact.get("expired")]
    if not matches:
        return None
    if len(matches) != 1:
        raise ValueError("Targeted evidence artifact is ambiguous")
    if not isinstance(matches[0].get("size_in_bytes"), int) or matches[0]["size_in_bytes"] > 64 * 1024:
        raise ValueError("Targeted evidence artifact is unexpectedly large")
    with tempfile.TemporaryDirectory() as directory:
        subprocess.run(["gh", "run", "download", str(run["id"]), "--repo", repository,
                        "--name", name, "--dir", directory], check=True)
        evidence_file = Path(directory) / "targeted-selection.json"
        return json.loads(evidence_file.read_text())


def validate_changed_files(paths):
    def standalone_example(path):
        parts = PurePosixPath(path).parts
        return len(parts) >= 3 and parts[0] == "examples" and parts[1] not in BUNDLED_EXAMPLES

    # Only four examples enter the APK. Standalone TBX inputs and their build
    # registry are validated separately; all Android/SDK/build inputs remain guarded.
    unexpected = {path for path in paths if path not in EVIDENCE_FILES | NON_HOST_FILES
                  and not path.startswith(("docs/", "core-data/schemas/"))
                  and not standalone_example(path)}
    if unexpected:
        raise ValueError("Host inputs changed; run affected host checks instead of reusing this baseline: " + ", ".join(sorted(unexpected)))


def main():
    run_id = os.environ.get("REUSE_VERIFIED_RUN", "")
    checks = os.environ.get("VALIDATION_SCOPE", "full")
    if checks not in ("full", "performance", "reuse"):
        raise ValueError("Unsupported host verification scope")
    if checks == "reuse" and not run_id:
        raise ValueError("Reuse scope needs a completed Android workflow run ID")
    outputs = {"scope": "current_run", "source_run": os.environ["GITHUB_RUN_ID"],
               "source_attempt": os.environ["GITHUB_RUN_ATTEMPT"],
               "base_commit": os.environ["GITHUB_SHA"], "checks": checks}
    if run_id:
        if os.environ["GITHUB_EVENT_NAME"] != "workflow_dispatch" or not re.fullmatch(r"[0-9]+", run_id):
            raise ValueError("Reusing evidence requires a manually selected numeric workflow run ID")
        repository = os.environ["GITHUB_REPOSITORY"]
        endpoint = f"repos/{repository}/actions/runs/{run_id}"

        def api(path):
            return json.loads(subprocess.check_output(["gh", "api", path], text=True))

        run = api(endpoint)
        if (run.get("repository", {}).get("full_name") != repository
                or run.get("head_repository", {}).get("full_name") != repository):
            raise ValueError("Reusable verification must come from the same repository")
        # Inspect the exact completed attempt, even if someone subsequently reruns the workflow.
        jobs = api(f"{endpoint}/attempts/{run['run_attempt']}/jobs?per_page=100")["jobs"]
        evidence = load_targeted_evidence(run, repository)
        commit, checks = validate_prior_run(run, jobs, repository, os.environ["DEFAULT_BRANCH"], evidence)
        if checks == "targeted" and os.environ["VALIDATION_SCOPE"] != "reuse":
            raise ValueError("Select reuse scope to preserve a targeted baseline")
        subprocess.run(["git", "merge-base", "--is-ancestor", commit, os.environ["GITHUB_SHA"]], check=True)
        changed = subprocess.check_output(["git", "diff", "--name-only", "--no-renames", "-z", commit, os.environ["GITHUB_SHA"]]).decode().split("\0")
        validate_changed_files(path for path in changed if path)
        filters = evidence["filters"] if checks == "targeted" else {kind: [] for kind in TARGETED_STEPS}
        outputs = {"scope": "reused_unchanged_host", "source_run": run_id,
                   "source_attempt": str(run["run_attempt"]), "base_commit": commit, "checks": checks,
                   "unit_filter": ",".join(filters["unit"]), "android_filter": ",".join(filters["android"]),
                   "node_filter": ",".join(filters["node"])}
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as handle:
            handle.write(f"Host inputs match merged commit `{commit}`. Reusing its completed `{checks}` checks from [run {run_id}](https://github.com/{repository}/actions/runs/{run_id}), attempt {run['run_attempt']}; selected unit {len(filters['unit'])}, Android {len(filters['android'])}, Node {len(filters['node'])}. This run builds and cold-starts a newly signed release.\n")
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as handle:
        handle.writelines(f"{key}={value}\n" for key, value in outputs.items())


if __name__ == "__main__":
    main()
