"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const model = require("./github-model.js");
const source = fs.readFileSync(path.join(__dirname, "app.js"), "utf8");

test("repository button preserves native quota, timeout and permission errors", async () => {
  for (const code of ["QUOTA_EXCEEDED", "NETWORK_TIMEOUT", "NETWORK_UNAVAILABLE", "NETWORK_BLOCKED", "PERMISSION_DENIED"]) {
    const nodes = new Map();
    const node = id => {
      if (!nodes.has(id)) nodes.set(id, {
        value: "", textContent: "", hidden: false, disabled: false, dataset: {},
        handlers: new Map(),
        addEventListener(type, callback) { this.handlers.set(type, callback); },
      });
      return nodes.get(id);
    };
    const requests = [];
    const api = {
      ready: async () => ({ hostVersion: "0.3.5", apiVersion: "1.0" }),
      storage: { get: async () => null, secure: { get: async () => null } },
      network: { request: async request => {
        requests.push(request);
        throw Object.assign(new Error("specific native failure"), { code });
      } },
    };
    vm.runInNewContext(source, {
      window: { ToolBox: api, GitHubWatcherModel: model, addEventListener() {} },
      document: {
        getElementById: node,
        querySelectorAll: () => [],
        querySelector: () => ({ value: "branch" }),
        addEventListener() {},
      },
      URL, Intl,
      setTimeout: () => 1, clearTimeout() {},
      setInterval: () => 1, clearInterval() {},
    });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(node("host-caption").textContent, "ToolBox 0.3.5 · API 1.0");
    node("repo-input").value = "https://github.com/gkeyes/ToolBox-Android";

    await node("load-repository").handlers.get("click")();

    assert.equal(requests.length, 1);
    assert.equal(node("toast").textContent, `${code}: specific native failure`);
    assert.equal(requests[0].maxResponseBytes, 4_194_304);
    assert.equal(node("loading-overlay").hidden, true);
    assert.equal(node("load-repository").disabled, false);
  }
});

const activeRun = {
  id: 58, run_attempt: 1, workflow_id: 7, name: "Android CI", head_branch: "main", event: "push",
  status: "in_progress", conclusion: null,
  created_at: "2026-09-03T08:01:44Z", run_started_at: "2026-09-03T08:01:44Z",
  updated_at: "2026-09-03T08:01:55Z",
};
const activeJobs = [{
  id: 9, name: "Build", status: "in_progress", conclusion: null,
  steps: [{ name: "Set up Android SDK 37", status: "in_progress", conclusion: null,
    started_at: "2026-09-03T08:01:55Z", completed_at: null }],
}];
const flush = () => new Promise(resolve => setImmediate(resolve));

function completedJobs(conclusion) {
  return activeJobs.map(job => ({
    ...job, status: "completed", conclusion,
    steps: job.steps.map(step => ({ ...step, status: "completed", conclusion, completed_at: "2026-09-03T08:02:09Z" })),
  }));
}

async function monitoringApp(remote, restored, options = {}) {
  const nodes = new Map();
  const element = () => ({
    value: "", ownText: "", children: [], hidden: false, disabled: false, dataset: {},
    handlers: new Map(), style: { setProperty() {} },
    set textContent(value) { this.ownText = String(value); this.children = []; },
    get textContent() { return this.ownText + this.children.map(child => child.textContent).join(" "); },
    addEventListener(type, callback) { this.handlers.set(type, callback); },
    append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.ownText = ""; this.children = children; },
    setAttribute() {},
  });
  const node = id => {
    if (!nodes.has(id)) nodes.set(id, element());
    return nodes.get(id);
  };
  let now = Date.parse("2026-09-03T08:06:00Z");
  class Clock extends Date {
    constructor(...args) { super(...(args.length ? args : [now])); }
    static now() { return now; }
  }
  let saved = restored || {
    config: { owner: "fixture", repo: "repo", fullName: "fixture/repo", selectedWorkflowIds: [7], branchMode: "branch", branch: "main" },
    monitoring: true, sessionId: "fixture-session", runs: [activeRun], trackedRunKeys: [model.runKey(activeRun)],
    timingModels: { "7:main:push": model.buildTimingModel([], {}) },
  };
  const requests = [];
  const secure = options.secure || new Map([["github-actions-watcher-token", "fixture-token"]]);
  const timers = new Map();
  let timerListener;
  let foregroundTick;
  const api = {
    ready: async () => ({ hostVersion: "0.3.5", apiVersion: "1.0" }),
    storage: {
      get: async () => saved,
      set: async (_key, value) => { saved = structuredClone(value); },
      secure: {
        get: async key => {
          if (options.secureReadError) throw options.secureReadError;
          return secure.get(key) ?? null;
        },
        set: async (key, value) => {
          if (options.secureWriteError) throw options.secureWriteError;
          secure.set(key, value);
        },
        remove: async key => { secure.delete(key); },
      },
    },
    network: { request: async request => {
      const pathname = new URL(request.url).pathname;
      requests.push({ pathname, authorization: request.headers.Authorization });
      if (remote.wait) await remote.wait;
      if (remote.requestError) throw remote.requestError;
      let data;
      if (pathname === "/repos/fixture/repo") data = { full_name: "fixture/repo", default_branch: "main" };
      else if (pathname === "/repos/fixture/repo/actions/workflows") data = { workflows: [{ id: 7, state: "active", name: "Android CI" }] };
      else if (pathname === "/repos/fixture/repo/branches") data = [{ name: "main" }];
      else if (pathname === "/repos/fixture/repo/actions/runs") data = { workflow_runs: [remote.run] };
      else if (pathname === "/repos/fixture/repo/actions/runs/58/attempts/1/jobs") {
        if (remote.error) throw remote.error;
        data = { jobs: remote.jobs };
      } else throw new Error(`Unexpected request: ${pathname}`);
      return { status: 200, headers: {}, body: JSON.stringify(data) };
    } },
    background: {
      onTimer: listener => { timerListener = listener; },
      listSessions: async () => [{ sessionId: "fixture-session" }],
      status: async () => ({ sessionId: "fixture-session" }),
      setTimer: async (key, interval) => { timers.set(key, interval); },
    },
    notifications: { post: async () => {}, live: { start: async () => {}, update: async () => {}, end: async () => {} } },
  };
  vm.runInNewContext(source, {
    window: { ToolBox: api, GitHubWatcherModel: model, addEventListener() {} },
    document: {
      getElementById: node, createElement: element, querySelectorAll: () => [],
      querySelector: () => ({ value: "branch" }), addEventListener() {},
    },
    URL, Intl, Date: Clock,
    setTimeout: () => 1, clearTimeout() {},
    setInterval: callback => { foregroundTick = callback; return 1; }, clearInterval() {},
  });
  await flush();
  return {
    node, snapshot: () => structuredClone(saved),
    jobRequests: () => requests.filter(request => request.pathname.endsWith("/jobs")).length,
    requestCount: () => requests.length,
    usedToken: token => requests.length > 0 && requests.every(request => request.authorization === `Bearer ${token}`),
    timerInterval: () => timers.get("github-actions-watcher-poll"),
    tick: async milliseconds => { now += milliseconds; await foregroundTick(); },
    poll: async () => { timerListener({ key: "github-actions-watcher-poll" }); await flush(); },
    refresh: () => node("refresh-now").handlers.get("click")(),
  };
}

test("terminal polls synchronize final jobs and steps without repeatedly fetching settled details", async t => {
  for (const scenario of [
    { name: "success", conclusion: "success" },
    { name: "failure", conclusion: "failure" },
    { name: "cancelled", conclusion: "cancelled" },
    { name: "later job after earlier jobs completed", conclusion: "success", earlierJobsCompleted: true },
    { name: "cancelled before any job was created", conclusion: "cancelled", empty: true },
    { name: "restored terminal snapshot with stale jobs", conclusion: "success", restore: true },
  ]) {
    await t.test(scenario.name, async () => {
      const remote = {
        run: scenario.empty ? { ...activeRun, status: "queued" } : activeRun,
        jobs: scenario.empty ? [] : scenario.earlierJobsCompleted ? completedJobs("success") : activeJobs,
      };
      let app = await monitoringApp(remote);
      const cached = app.snapshot();
      const requestsBeforeCompletion = app.jobRequests();
      const jobs = scenario.empty ? [] : [
        ...completedJobs(scenario.conclusion),
        { id: 10, name: "Package delivery", status: "completed", conclusion: "skipped", steps: [] },
      ];
      remote.run = { ...activeRun, status: "completed", conclusion: scenario.conclusion, updated_at: "2026-09-03T08:05:07Z" };
      remote.jobs = jobs;

      if (scenario.restore) {
        cached.runs = [remote.run];
        cached.terminalStates = { [model.runKey(activeRun)]: { posted: false, holdUntil: Date.parse("2026-09-03T08:08:00Z") } };
        app = await monitoringApp(remote, cached);
      } else await app.poll();

      assert.equal(app.node("progress-value").textContent, "100%");
      assert.equal(app.node("active-count").textContent, "0 个");
      assert.equal(app.jobRequests(), scenario.restore ? 1 : requestsBeforeCompletion + 1);
      assert.deepEqual(app.snapshot().jobsByRun[model.runKey(activeRun)], jobs);
      assert.doesNotMatch(app.node("job-list").textContent, /运行中|等待中/);
      assert.doesNotMatch(app.node("current-step").textContent, /Set up Android SDK/);
      if (jobs.length) {
        assert.equal(app.node("job-list").children[0].children[0].children[2].dataset.result, scenario.conclusion);
        assert.match(app.node("job-list").textContent, /Package delivery/);
      }
      const requestsAfterCompletion = app.jobRequests();
      await app.refresh();
      assert.equal(app.jobRequests(), requestsAfterCompletion);
    });
  }
});

test("refresh feedback counts down, shows in-flight time and reschedules after a failure", async () => {
  const remote = { run: activeRun, jobs: activeJobs };
  const app = await monitoringApp(remote);
  assert.match(app.node("poll-countdown").textContent, /下次刷新.*15秒/);
  const initialRequests = app.requestCount();
  await app.tick(1000);
  assert.match(app.node("poll-countdown").textContent, /下次刷新.*14秒/);
  assert.equal(app.requestCount(), initialRequests, "the local countdown must not call GitHub");

  let release;
  remote.wait = new Promise(resolve => { release = resolve; });
  const refresh = app.refresh();
  await app.tick(2000);
  assert.match(app.node("poll-countdown").textContent, /正在同步.*2秒/);
  release();
  await refresh;
  remote.wait = null;
  assert.match(app.node("poll-countdown").textContent, /下次刷新.*15秒/);

  remote.requestError = Object.assign(new Error("fixture timeout"), { code: "NETWORK_TIMEOUT" });
  await app.tick(15_000);
  await app.poll();
  assert.match(app.node("poll-countdown").textContent, /下次重试.*15秒/);
  await app.tick(16_000);
  assert.match(app.node("poll-countdown").textContent, /刷新已延迟.*立即同步/);
});

test("an entered token survives repository failure and is reused on reopening without plaintext state", async () => {
  const secure = new Map();
  const options = { secure };
  const remote = { run: activeRun, jobs: activeJobs, requestError: new Error("fixture offline") };
  const app = await monitoringApp(remote, { monitoring: false }, options);
  app.node("repo-input").value = "https://github.com/fixture/repo";
  app.node("token-input").value = "fixture-saved-token";
  await app.node("load-repository").handlers.get("click")();
  assert.equal(secure.get("github-actions-watcher-token"), "fixture-saved-token");
  assert.equal(app.node("token-input").value, "");
  assert.doesNotMatch(JSON.stringify(app.snapshot()), /fixture-saved-token/);

  remote.requestError = null;
  const reopened = await monitoringApp(remote, { monitoring: false }, options);
  assert.match(reopened.node("token-state").textContent, /已.*保存.*Token/);
  reopened.node("repo-input").value = "https://github.com/fixture/repo";
  await reopened.node("load-repository").handlers.get("click")();
  assert.equal(reopened.usedToken("fixture-saved-token"), true);
  assert.equal(reopened.node("selection-surface").hidden, false);
});

test("token save, replacement, clear and storage failures are explicit and never use plaintext storage", async () => {
  const secure = new Map();
  const options = { secure };
  const remote = { run: activeRun, jobs: activeJobs };
  const app = await monitoringApp(remote, {
    monitoring: false, rateRemaining: 0, rateResetAt: Date.parse("2026-09-03T09:06:00Z"),
  }, options);
  app.node("token-input").value = "fixture-original-token";
  await app.node("save-token").handlers.get("click")();
  assert.equal(app.requestCount(), 0, "saving does not require a repository or network request");
  assert.equal(secure.get("github-actions-watcher-token"), "fixture-original-token");
  assert.equal(app.node("token-input").value, "");
  assert.equal(app.snapshot().rateRemaining, null, "a saved new credential must not inherit the previous quota after reopening");

  options.secureWriteError = Object.assign(new Error("fixture secure failure"), { code: "PERMISSION_DENIED" });
  app.node("token-input").value = "fixture-replacement-token";
  await app.node("save-token").handlers.get("click")();
  assert.match(app.node("token-state").textContent, /Token 未保存.*安全存储/);
  assert.equal(secure.get("github-actions-watcher-token"), "fixture-original-token");
  assert.equal(app.node("token-input").value, "fixture-replacement-token");
  assert.doesNotMatch(JSON.stringify(app.snapshot()), /fixture-.*token/);

  options.secureWriteError = null;
  await app.node("save-token").handlers.get("click")();
  assert.equal(secure.get("github-actions-watcher-token"), "fixture-replacement-token");
  const restored = await monitoringApp(remote, app.snapshot(), options);
  assert.match(restored.node("token-state").textContent, /已安全保存/);
  await restored.node("clear-token").handlers.get("click")();
  assert.equal(secure.size, 0);
  const cleared = await monitoringApp(remote, { monitoring: false }, options);
  assert.equal(cleared.node("clear-token").hidden, true);

  options.secureReadError = Object.assign(new Error("fixture permission"), { code: "PERMISSION_DENIED" });
  const denied = await monitoringApp(remote, { monitoring: false }, options);
  assert.match(denied.node("token-state").textContent, /无法读取.*开启安全存储/);
  assert.match(denied.node("host-caption").textContent, /ToolBox/);
});

test("refresh countdown follows anonymous and quota-wait schedules without extra requests", async () => {
  const remote = { run: activeRun, jobs: activeJobs };
  const anonymous = await monitoringApp(remote, undefined, { secure: new Map() });
  assert.equal(anonymous.timerInterval(), 180_000);
  assert.match(anonymous.node("poll-countdown").textContent, /下次刷新.*3分0秒/);
  await anonymous.tick(1000);
  assert.match(anonymous.node("poll-countdown").textContent, /下次刷新.*2分59秒/);

  const snapshot = anonymous.snapshot();
  snapshot.rateRemaining = 0;
  snapshot.rateResetAt = Date.parse("2026-09-03T08:08:00Z");
  const limited = await monitoringApp(remote, snapshot);
  assert.equal(limited.requestCount(), 0);
  assert.equal(limited.timerInterval(), 120_000);
  assert.match(limited.node("poll-countdown").textContent, /额度恢复后刷新.*2分0秒/);
  await limited.tick(119_000);
  await limited.refresh();
  assert.equal(limited.requestCount(), 0);
  assert.match(limited.node("poll-countdown").textContent, /额度恢复后刷新.*1秒/);
  await limited.tick(1000);
  await limited.poll();
  assert.ok(limited.requestCount() > 0);
});

test("terminal details stay pending and retry when the final jobs response fails or is still active", async t => {
  for (const failure of ["timeout", "stale jobs", "stale steps"]) {
    await t.test(failure, async () => {
      const remote = { run: activeRun, jobs: activeJobs };
      const app = await monitoringApp(remote);
      remote.run = { ...activeRun, status: "completed", conclusion: "success", updated_at: "2026-09-03T08:05:07Z" };
      if (failure === "timeout") remote.error = Object.assign(new Error("fixture timeout"), { code: "NETWORK_TIMEOUT" });
      if (failure === "stale steps") remote.jobs = activeJobs.map(job => ({ ...job, status: "completed", conclusion: "success" }));

      await app.poll();

      assert.equal(app.node("progress-value").textContent, "100%");
      assert.doesNotMatch(app.node("job-list").textContent, /运行中|等待中/);
      assert.match(app.node("job-list").textContent, /同步/);
      assert.doesNotMatch(app.node("current-step").textContent, /Set up Android SDK/);
      remote.error = null;
      remote.jobs = completedJobs("success");
      const requestsBeforeRetry = app.jobRequests();

      await app.poll();

      assert.equal(app.jobRequests(), requestsBeforeRetry + 1);
      assert.deepEqual(app.snapshot().jobsByRun[model.runKey(activeRun)], remote.jobs);
      assert.equal(app.node("job-list").children[0].children[0].children[2].dataset.result, "success");
    });
  }
});
