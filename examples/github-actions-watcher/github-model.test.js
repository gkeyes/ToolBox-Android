"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const model = require("./github-model.js");

function iso(milliseconds) {
  return new Date(milliseconds).toISOString();
}

function successfulRun(id, workflowId, branch, event, started, duration, attempt = 1) {
  return {
    id,
    workflow_id: workflowId,
    head_branch: branch,
    event,
    status: "completed",
    conclusion: "success",
    run_attempt: attempt,
    run_started_at: iso(started),
    created_at: iso(started),
    updated_at: iso(started + duration)
  };
}

function job(name, steps) {
  return { name, status: "completed", steps };
}

function step(name, started, duration, status = "completed") {
  return { name, status, started_at: iso(started), completed_at: status === "completed" ? iso(started + duration) : null };
}

test("parses repository, Actions and workflow URLs", () => {
  const expected = { owner: "gkeyes", repo: "ToolBox-Android", fullName: "gkeyes/ToolBox-Android" };
  assert.deepEqual(model.parseRepositoryLink("gkeyes/ToolBox-Android"), expected);
  assert.deepEqual(model.parseRepositoryLink("https://github.com/gkeyes/ToolBox-Android/actions"), expected);
  assert.deepEqual(model.parseRepositoryLink("https://github.com/gkeyes/ToolBox-Android/actions/workflows/android.yml"), expected);
  assert.throws(() => model.parseRepositoryLink("https://example.com/a/b"), /github\.com/);
});

test("parses next pagination link", () => {
  const header = '<https://api.github.com/x?page=2>; rel="next", <https://api.github.com/x?page=8>; rel="last"';
  assert.equal(model.parseNextLink(header), "https://api.github.com/x?page=2");
  assert.equal(model.parseNextLink(""), null);
});

test("classifies invalid token, permission, missing repository and exhausted rate limit", () => {
  assert.equal(model.classifyApiStatus(401, 50), "invalid_token");
  assert.equal(model.classifyApiStatus(403, 0), "rate_limit");
  assert.equal(model.classifyApiStatus(403, 42, 60), "rate_limit");
  assert.equal(model.classifyApiStatus(403, 42), "permission");
  assert.equal(model.classifyApiStatus(403, Number.NaN), "permission");
  assert.equal(model.classifyApiStatus(404, 42), "not_found");
  assert.equal(model.classifyApiStatus(200, 42), null);
});

test("poll cadence follows authentication and active state", () => {
  assert.equal(model.pollInterval(true, true), 15_000);
  assert.equal(model.pollInterval(true, false), 60_000);
  assert.equal(model.pollInterval(false, true), 180_000);
  assert.equal(model.pollInterval(false, false), 300_000);
});

test("uses first nonempty history bucket and latest ten samples", () => {
  const target = { workflow_id: 9, head_branch: "main", event: "push" };
  const runs = [];
  for (let index = 0; index < 12; index += 1) {
    runs.push(successfulRun(index + 1, 9, "main", "push", index * 10_000, 5_000));
  }
  runs.push(successfulRun(30, 9, "dev", "push", 200_000, 5_000));
  runs.push(successfulRun(31, 9, "main", "schedule", 210_000, 5_000));
  const selected = model.selectHistoricalRuns(runs, target);
  assert.equal(selected.length, 10);
  assert.equal(selected[0].id, 12);
  assert.equal(selected.at(-1).id, 3);
});

test("falls back from branch and event to event, then workflow", () => {
  const eventFallback = successfulRun(1, 9, "dev", "push", 1_000, 2_000);
  assert.deepEqual(model.selectHistoricalRuns([eventFallback], { workflow_id: 9, head_branch: "main", event: "push" }).map((run) => run.id), [1]);
  const workflowFallback = successfulRun(2, 9, "dev", "schedule", 2_000, 2_000);
  assert.deepEqual(model.selectHistoricalRuns([workflowFallback], { workflow_id: 9, head_branch: "main", event: "push" }).map((run) => run.id), [2]);
});

test("arithmetic means use every valid sample from one through ten", () => {
  const samples = [
    successfulRun(1, 9, "main", "push", 0, 10_000),
    successfulRun(2, 9, "main", "push", 20_000, 30_000)
  ];
  const jobs = {
    "1:1": [job("build", [step("compile", 0, 2_000), step("test", 2_000, 4_000)])],
    "2:1": [job("build", [step("compile", 20_000, 6_000), step("test", 26_000, 8_000)])]
  };
  const timing = model.buildTimingModel(samples, jobs);
  assert.equal(timing.sampleCount, 2);
  assert.equal(timing.totalMeanMs, 20_000);
  assert.equal(timing.exactStepMs["build\u0000compile"], 4_000);
  assert.equal(timing.exactStepMs["build\u0000test"], 6_000);
});

test("invalid and zero-duration step timestamps do not affect averages", () => {
  const sampleRun = successfulRun(1, 9, "main", "push", 0, 10_000);
  const timing = model.buildTimingModel([sampleRun], {
    "1:1": [job("build", [
      { name: "zero", status: "completed", started_at: iso(0), completed_at: iso(0) },
      { name: "missing", status: "completed", started_at: null, completed_at: null },
      step("valid", 1_000, 2_000)
    ])]
  });
  assert.deepEqual(Object.keys(timing.exactStepMs), ["build\u0000valid"]);
});

test("matrix jobs fall back to normalized job and new steps use job average", () => {
  const sampleRun = successfulRun(1, 9, "main", "push", 0, 20_000);
  const timing = model.buildTimingModel([sampleRun], {
    "1:1": [job("test (ubuntu, node 20)", [step("checkout", 0, 2_000), step("tests", 2_000, 8_000)])]
  });
  const run = { status: "in_progress", created_at: iso(100_000), run_started_at: iso(100_000) };
  const jobs = [{
    name: "test (macos, node 22)",
    status: "in_progress",
    steps: [
      { name: "checkout", status: "completed", started_at: iso(100_000), completed_at: iso(102_000) },
      { name: "new analysis", status: "in_progress", started_at: iso(102_000), completed_at: null }
    ]
  }];
  const estimate = model.estimateRunProgress(run, jobs, timing, 104_500, 0);
  assert.ok(estimate.progress > 50 && estimate.progress < 98);
  assert.equal(estimate.job, "test (macos, node 22)");
  assert.equal(estimate.step, "new analysis");
});

test("parallel jobs are aggregated without exceeding the 98 percent running ceiling", () => {
  const run = { status: "in_progress", created_at: iso(0), run_started_at: iso(0) };
  const jobs = [
    { name: "linux", status: "completed", steps: [{ name: "test", status: "completed" }] },
    { name: "windows", status: "in_progress", steps: [{ name: "test", status: "in_progress", started_at: iso(0) }] }
  ];
  const estimate = model.estimateRunProgress(run, jobs, null, 600_000, 0);
  assert.equal(estimate.progress, 95);
  assert.ok(estimate.progress <= 98);
});

test("progress is monotonic per attempt, capped at 98, terminal is 100 and rerun can reset", () => {
  const running = { id: 7, run_attempt: 1, status: "in_progress", created_at: iso(0), run_started_at: iso(0) };
  const jobs = [{ name: "build", status: "in_progress", steps: [{ name: "compile", status: "in_progress", started_at: iso(0) }] }];
  const timing = { sampleCount: 1, totalMeanMs: 10_000, exactStepMs: { "build\u0000compile": 1_000 }, normalizedStepMs: {}, jobStepMeanMs: {}, globalStepMeanMs: 1_000 };
  const high = model.estimateRunProgress(running, jobs, timing, 9_000, 0);
  assert.equal(high.progress, 90);
  const monotonic = model.estimateRunProgress(running, jobs, timing, 100, high.progress);
  assert.equal(monotonic.progress, 90);
  const terminal = model.estimateRunProgress({ ...running, status: "completed", conclusion: "success" }, jobs, timing, 10_000, monotonic.progress);
  assert.equal(terminal.progress, 100);
  const rerun = model.estimateRunProgress({ ...running, run_attempt: 2 }, jobs, timing, 100, 0);
  assert.ok(rerun.progress < high.progress);
});

test("terminal elapsed time stops at GitHub updated timestamp", () => {
  const terminal = model.estimateRunProgress({
    status: "completed",
    conclusion: "success",
    run_started_at: iso(1_000),
    updated_at: iso(21_000)
  }, [], null, 90_000, 98);
  assert.equal(terminal.elapsedMs, 20_000);
  assert.equal(terminal.progress, 100);
});

test("ETA reports remaining time or overrun from even one sample", () => {
  const timing = { sampleCount: 1, totalMeanMs: 60_000, exactStepMs: {}, normalizedStepMs: {}, jobStepMeanMs: {}, globalStepMeanMs: 60_000 };
  const run = { status: "in_progress", created_at: iso(0), run_started_at: iso(0) };
  const early = model.estimateRunProgress(run, [], timing, 20_000, 0);
  assert.equal(early.remainingMs, 40_000);
  assert.equal(early.sampleCount, 1);
  const late = model.estimateRunProgress(run, [], timing, 90_000, early.progress);
  assert.equal(late.remainingMs, null);
  assert.equal(late.overrunMs, 30_000);
});

test("run filtering and primary priority handle multiple workflows and branches", () => {
  const runs = [
    { id: 1, workflow_id: 1, head_branch: "dev", status: "in_progress", created_at: iso(1_000) },
    { id: 2, workflow_id: 2, head_branch: "main", status: "queued", created_at: iso(2_000) },
    { id: 3, workflow_id: 1, head_branch: "main", status: "in_progress", created_at: iso(3_000) }
  ];
  const filtered = model.selectedRuns(runs, [1, 2], "branch", "main");
  assert.deepEqual(filtered.map((run) => run.id), [2, 3]);
  assert.equal(model.choosePrimaryRun(filtered).id, 3);
});

test("notification summary has one coherent job and step line", () => {
  const run = { name: "Android CI", status: "in_progress", head_branch: "main", head_sha: "abcdef123456" };
  const summary = model.buildNotificationSummary("owner/repo", run, { progress: 42, job: "verify", step: "unit tests" });
  assert.equal(summary.primaryText, "42%");
  assert.equal(summary.secondaryText, "verify · unit tests");
  assert.equal((summary.body.match(/verify/g) || []).length, 1);
  assert.match(summary.body, /main · abcdef1/);
});

test("failure, cancellation, rate limit and offline map to stable colors", () => {
  assert.equal(model.statePresentation({ status: "completed", conclusion: "failure" }).color, "#E94949");
  assert.equal(model.statePresentation({ status: "completed", conclusion: "cancelled" }).color, "#7A7F87");
  assert.equal(model.statePresentation(null, "rate_limit").color, "#F59E0B");
  assert.equal(model.statePresentation(null, "offline").tone, "warning");
});
