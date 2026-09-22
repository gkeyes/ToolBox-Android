const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../examples/github-actions-watcher');
const reliability = require(path.join(root, 'reliability.js'));
const model = require(path.join(root, 'github-model.js'));
const defer = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const tick = () => new Promise(setImmediate);
const run = (id = 17, status = 'in_progress', attempt = 1) => ({
  id, workflow_id: 9, run_attempt: attempt, status, conclusion: status === 'completed' ? 'success' : null,
  head_branch: 'main', head_sha: 'abc123', name: 'CI', created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
});
const response = (body, headers = {}) => ({ status: 200, body: JSON.stringify(body), headers });

function harness(request) {
  const calls = [], posts = [], lives = [], timers = [];
  const element = () => ({
    value: '', hidden: false, dataset: {}, style: {}, disabled: false,
    addEventListener() {}, setAttribute() {}, focus() {}, replaceChildren() {}, append() {},
    contains() { return true; }, querySelector() { return element(); }, querySelectorAll() { return []; },
  });
  const nodes = new Map();
  const document = { hidden: false, addEventListener() {}, createElement: element,
    querySelector: element, querySelectorAll: () => [],
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); },
  };
  const toolbox = {
    storage: { set: async () => {}, get: async () => null, secure: { get: async () => null } },
    background: { onTimer() {}, onRestore() {}, setTimer: async (...args) => { timers.push(args); }, cancelTimer: async () => {}, stop: async () => {} },
    network: { request: async (options) => {
      calls.push(options);
      return request ? request(options, calls) : response(options.url.includes('/jobs?') ? { jobs: [] } : options.url.includes('/actions/runs/17') ? run() : { workflow_runs: [] });
    } },
    notifications: {
      post: async (...args) => { posts.push(args); },
      live: { start: async (r) => { lives.push(r); }, update: async (r) => { lives.push(r); }, end: async () => { lives.push('end'); } },
    },
  };
  const window = { ToolBox: toolbox, GitHubWatcherModel: model, GitHubWatcherReliability: reliability, addEventListener() {} };
  const context = vm.createContext({ window, document, URL, Intl, Date, console, setTimeout, clearTimeout, setInterval, clearInterval });
  let source = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
  const end = '  startForegroundClock();\n  bootPromise = boot();';
  assert.ok(source.includes(end));
  source = source.replace(end, `
    renderDashboard = function () {};
    renderAll = function () {};
    showToast = function () {};
    window.testApi = { state, pollGitHub, retireWork, processTerminalResults, liveRequestFor,
      expire: () => activePoll?.invalidate(reliability.timeoutError()),
      pendingPages: () => discoveryQueue.length };
  `);
  vm.runInContext(source, context, { filename: 'watcher/app.js' });
  const api = window.testApi;
  Object.assign(api.state, { ready: true, monitoring: true, sessionId: 's', hasToken: false,
    config: { owner: 'fixture', repo: 'fixture', fullName: 'fixture/fixture', selectedWorkflowIds: [9], branchMode: 'branch', branch: 'main' },
    watchStartedAt: Date.now() - 1_000, runs: [run()], trackedRunKeys: ['17:1'],
  });
  // Use the production model's exact key format.
  api.state.trackedRunKeys = [model.runKey(api.state.runs[0])];
  return { ...api, calls, posts, lives, timers, toolbox, close() { api.state.monitoring = false; api.retireWork(); } };
}

test('bridge deadline expires and a late value cannot become a successful result', async () => {
  const old = defer(); const lease = new reliability.Lease(15);
  await assert.rejects(lease.wait(old.promise), { code: 'NETWORK_TIMEOUT' });
  lease.invalidate(); old.resolve('late');
  await assert.rejects(lease.wait(Promise.resolve('late')), { code: 'CANCELLED' });
});

test('invalidation releases pending waiters immediately', async () => {
  const lease = new reliability.Lease(60_000); const waiting = lease.wait(new Promise(() => {}));
  lease.invalidate(); await assert.rejects(waiting, { code: 'CANCELLED' });
  assert.equal(lease.pending.size, 0);
});

test('poll scheduling subtracts work duration and respects rate-reset time', () => {
  assert.equal(reliability.nextDelay(1_000, 15_000, 9_000), 7_000);
  assert.equal(reliability.nextDelay(1_000, 15_000, 30_000), 1_000);
  assert.equal(reliability.nextDelay(1_000, 15_000, 30_000, 90_000), 60_000);
});

test('discovery omission cannot drop an active run and stale pages cannot undo completion', () => {
  const active = run();
  assert.deepEqual(reliability.mergeRuns([active], [run(18)]).map((r) => r.id).sort(), [17, 18]);
  assert.equal(reliability.mergeRuns([run(17, 'completed')], [active])[0].status, 'completed');
  assert.equal(reliability.mergeRuns([run(17, 'completed', 1)], [run(17, 'queued', 2)])[0].run_attempt, 2);
});

test('hung request unlocks after cycle expiry; later replies cannot overwrite a new cycle', async () => {
  const old = defer();
  const h = harness((o, calls) => calls.length === 1 ? old.promise : response(o.url.includes('/jobs?') ? { jobs: [] } : o.url.includes('/actions/runs/17') ? run(17, 'completed') : { workflow_runs: [] }));
  try {
    const first = h.pollGitHub(false); await tick();
    for (let i = 0; i < 20; i++) await h.pollGitHub(false);
    assert.equal(h.calls.length, 1);
    assert.equal(h.calls[0].timeoutMs, 15_000);
    h.expire(); await first;
    assert.equal(h.state.pollInFlight, false);
    await h.pollGitHub(true);
    assert.equal(h.state.runs[0].status, 'completed');
    old.resolve(response(run(17, 'in_progress'))); await tick();
    assert.equal(h.state.runs[0].status, 'completed');
    assert.equal(h.state.pollInFlight, false);
  } finally { h.close(); }
});

test('current tracked run is read before any history/discovery request', async () => {
  const h = harness();
  try { await h.pollGitHub(false); assert.ok(h.calls[0].url.endsWith('/actions/runs/17')); }
  finally { h.close(); }
});

test('unbounded pagination is carried over rather than blocking one poll', async () => {
  const h = harness((o) => response({ workflow_runs: [] }, { link: `<${o.url}&page=2>; rel="next"` }));
  Object.assign(h.state, { runs: [], hasToken: true });
  try { await h.pollGitHub(false); assert.equal(h.calls.length, 4); assert.ok(h.pendingPages() > 0); }
  finally { h.close(); }
});

test('stop invalidates a pending request without restarting timers or accepting its result', async () => {
  const old = defer(); const h = harness(() => old.promise);
  const first = h.pollGitHub(false); await tick();
  h.close(); await first;
  old.resolve(response(run(17, 'completed'))); await tick();
  assert.equal(h.state.monitoring, false);
  assert.equal(h.state.runs[0].status, 'in_progress');
  assert.equal(h.timers.length, 0);
});

test('result notification is immediate and deduplicated while live result can be retained', async () => {
  const h = harness();
  h.state.runs = [run(17, 'completed')];
  h.state.terminalStates[model.runKey(h.state.runs[0])] = { posted: false, jobsSynced: true, holdUntil: Date.now() + 120_000 };
  try {
    await h.processTerminalResults(); await h.processTerminalResults();
    assert.equal(h.posts.length, 1);
    assert.ok(!h.lives.includes('end'));
  } finally { h.close(); }
});

test('notification timestamp represents successful sync, not a fresh-looking clock tick', () => {
  const h = harness();
  h.state.lastPollAt = Date.now() - 900_000;
  try { const request = h.liveRequestFor(h.state.runs[0]); assert.equal(request.updatedAt, h.state.lastPollAt); assert.match(request.primaryText, /同步已延迟/); }
  finally { h.close(); }
});


test('clock and poll cannot post the same terminal notice concurrently', async () => {
  const h = harness(); const pending = defer();
  h.state.runs = [run(17, 'completed')];
  h.state.terminalStates[model.runKey(h.state.runs[0])] = { posted: false, holdUntil: Date.now() + 120_000 };
  h.toolbox.notifications.post = async (...args) => { h.posts.push(args); await pending.promise; };
  try {
    const first = h.processTerminalResults(); await tick();
    await h.processTerminalResults(); assert.equal(h.posts.length, 1);
    pending.resolve(); await first;
    assert.equal(h.state.terminalStates[model.runKey(h.state.runs[0])].posted, true);
  } finally { h.close(); }
});

test('secondary rate limiting defers the next cycle instead of retrying each tick', async () => {
  const h = harness(() => ({ status: 403, body: JSON.stringify({ message: 'secondary rate limit' }), headers: { 'retry-after': '120' } }));
  try {
    await h.pollGitHub(false); const count = h.calls.length;
    await h.pollGitHub(true);
    assert.equal(h.calls.length, count); assert.equal(h.state.warning, 'rate_limit');
    assert.ok(h.state.nextPollAt > Date.now() + 60_000);
  } finally { h.close(); }
});

test('deleted run does not permanently block discovery or polling other runs', async () => {
  const h = harness((o) => o.url.endsWith('/actions/runs/17')
    ? { status: 404, body: '{}', headers: {} }
    : response(o.url.includes('/jobs?') ? { jobs: [] } : { workflow_runs: [] }));
  try {
    await h.pollGitHub(false); await h.pollGitHub(false);
    assert.equal(h.calls.filter((o) => o.url.endsWith('/actions/runs/17')).length, 1);
    assert.ok(h.calls.some((o) => o.url.includes('per_page=')));
    assert.equal(h.state.pollInFlight, false);
  } finally { h.close(); }
});
