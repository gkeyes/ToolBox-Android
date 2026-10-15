const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
const reliability = require(path.join(root, 'reliability.js'));
const model = require(path.join(root, 'github-model.js'));
const defer = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const tick = () => new Promise(setImmediate);
const run = (id = 17, status = 'in_progress', attempt = 1) => ({
  id, workflow_id: 9, run_attempt: attempt, status, conclusion: status === 'completed' ? 'success' : null,
  head_branch: 'main', head_sha: 'abc123', name: 'CI', created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
});
const response = (body, headers = {}) => ({ status: 200, body: JSON.stringify(body), headers });

function harness(request, extraStubs = {}) {
  const calls = [], posts = [], lives = [], timers = [], writes = [], secureWrites = [];
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
    storage: { set: async (key, value) => { writes.push([key, JSON.parse(JSON.stringify(value))]); }, get: async () => null,
      secure: { get: async () => null, set: async (key, value) => { secureWrites.push([key, value]); } } },
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
  let clockPaints = 0;
  const hook = { boot: false, stubs: {
    renderDashboard() {}, renderDashboardClock() { clockPaints += 1; }, renderAll() {}, showToast() {}, ...extraStubs,
  } };
  const window = { ToolBox: toolbox, GitHubWatcherModel: model, GitHubWatcherReliability: reliability, __TOOLBOX_TEST__: hook, addEventListener() {} };
  const context = vm.createContext({ window, document, URL, Intl, Date, console, setTimeout, clearTimeout, setInterval, clearInterval });
  vm.runInContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), context, { filename: 'watcher/app.js' });
  const api = { ...hook.api, clockPaints: () => clockPaints };
  Object.assign(api.state, { ready: true, monitoring: true, sessionId: 's', hasToken: false,
    config: { owner: 'fixture', repo: 'fixture', fullName: 'fixture/fixture', selectedWorkflowIds: [9], branchMode: 'branch', branch: 'main' },
    watchStartedAt: Date.now() - 1_000, runs: [run()], trackedRunKeys: ['17:1'],
  });
  // Use the production model's exact key format.
  api.state.trackedRunKeys = [model.runKey(api.state.runs[0])];
  return { ...api, calls, posts, lives, timers, writes, secureWrites, toolbox, node: (id) => document.getElementById(id), close() { api.state.monitoring = false; api.retireWork(); } };
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

test('foreground seconds only repaint clocks and never poll, notify or persist', async () => {
  const h = harness();
  try {
    h.setForeground(true);
    await h.clockTick(false);
    await h.clockTick(false);
    assert.equal(h.clockPaints(), 2);
    assert.equal(h.calls.length, 0);
    assert.equal(h.posts.length, 0);
    assert.equal(h.lives.length, 0);
    assert.equal(h.timers.length, 0);
  } finally { h.close(); }
});

test('terminal jobs are not fetched again after their final steps are synchronized', async () => {
  const h = harness();
  const finished = run(17, 'completed');
  const key = model.runKey(finished);
  h.state.runs = [finished];
  h.state.jobsByRun[key] = [{ id: 1, status: 'completed', steps: [{ status: 'completed' }] }];
  h.state.terminalStates[key] = { posted: true, jobsSynced: true, holdUntil: Date.now() + 120_000 };
  try {
    await h.pollGitHub(false);
    assert.equal(h.calls.filter((call) => call.url.includes('/jobs?')).length, 0);
  } finally { h.close(); }
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


test('a clock continuation after stop must not send a result notification', async () => {
  const h = harness();
  h.state.runs = [run(17, 'completed')];
  h.state.terminalStates[model.runKey(h.state.runs[0])] = { posted: false, holdUntil: Date.now() + 120_000 };
  h.close();
  await h.processTerminalResults();
  assert.equal(h.posts.length, 0);
  assert.equal(h.state.terminalStates[model.runKey(h.state.runs[0])].posted, false);
});


test('a mistyped token is rejected by a test request and never replaces the saved token', async () => {
  const h = harness((o) => o.url.endsWith('/rate_limit')
    ? { status: 401, body: '{"message":"Bad credentials"}', headers: {} }
    : response({ workflow_runs: [] }));
  try {
    Object.assign(h.state, { token: 'good-token', hasToken: true, rateRemaining: 4000, rateResetAt: null });
    h.node('token-input').value = 'typo-token';
    await assert.rejects(h.saveEnteredToken(), { kind: 'invalid_token' });
    assert.equal(h.secureWrites.length, 0);
    assert.equal(h.state.token, 'good-token');
    assert.equal(h.state.rateRemaining, 4000);
    const probe = h.calls.find((o) => o.url.endsWith('/rate_limit'));
    assert.equal(probe.headers.Authorization, 'Bearer typo-token');
  } finally { h.close(); }
});

test('a token is saved only after GitHub accepts it', async () => {
  const h = harness((o) => response({ resources: {} }, { 'x-ratelimit-remaining': '4999', 'x-ratelimit-reset': '9999999999' }));
  try {
    h.node('token-input').value = ' new-token ';
    assert.equal(await h.saveEnteredToken(), true);
    assert.deepEqual(h.secureWrites, [['github-actions-watcher-token', 'new-token']]);
    assert.equal(h.state.token, 'new-token');
    assert.equal(h.state.rateRemaining, 4999);
  } finally { h.close(); }
});

test('HTTP 429 is rate limiting and waits for Retry-After before any further request', async () => {
  const h = harness(() => ({ status: 429, body: '{}', headers: { 'retry-after': '300' } }));
  try {
    await h.pollGitHub(false); const count = h.calls.length;
    assert.equal(h.state.warning, 'rate_limit');
    assert.ok(h.state.rateResetAt >= Date.now() + 290_000);
    await h.pollGitHub(true); await h.clockTick(true);
    assert.equal(h.calls.length, count);
    assert.ok(h.state.nextPollAt >= Date.now() + 290_000);
  } finally { h.close(); }
  assert.equal(model.classifyApiStatus(429, null, null), 'rate_limit');
  assert.equal(model.classifyApiStatus(403, 4000, null, 'You have exceeded a secondary rate limit. Please wait a few minutes before you try again.'), 'rate_limit');
  assert.equal(model.classifyApiStatus(403, 4000, null, 'Resource not accessible by integration'), 'permission');
  assert.equal(model.parseRetryAfter('120'), 120);
  assert.ok(Math.abs(model.parseRetryAfter(new Date(Date.now() + 60_000).toUTCString()) - 60) < 2);
  assert.equal(model.parseRetryAfter(null), null);
});

test('anonymous polling alternates full and run-only cycles to stay under 60 requests/hour', async () => {
  const h = harness();
  try {
    await h.pollGitHub(false);
    const full = h.calls.length;
    assert.ok(h.calls.some((o) => o.url.includes('/jobs?')));
    await h.pollGitHub(false);
    const light = h.calls.slice(full);
    assert.deepEqual(light.map((o) => new URL(o.url).pathname), ['/repos/fixture/fixture/actions/runs/17']);
    const before = h.calls.length;
    await h.pollGitHub(false);
    assert.ok(h.calls.slice(before).some((o) => o.url.includes('/jobs?')));
    // Worst case per hour at the active anonymous interval stays well under GitHub's 60.
    const pollsPerHour = 3_600_000 / model.pollInterval(false, true);
    assert.ok(pollsPerHour / 2 * full + pollsPerHour / 2 * light.length < 60);
  } finally { h.close(); }
});

test('per-run maps are pruned to listed runs and only compact fields are kept', () => {
  const h = harness();
  try {
    const raw = { ...run(17), actor: { login: 'x', avatar_url: 'https://example.invalid/a' }, repository: { full_name: 'a/b' }, html_url: 'u' };
    h.state.jobsByRun = { '17:1': [], '5:1': [{ id: 1 }] };
    h.state.progressByRun = { '17:1': 40, '5:1': 90 };
    h.state.terminalStates = { '5:1': { posted: true } };
    h.state.timingModels = { '9:main:': { sampleCount: 1 }, '3:old:push': { sampleCount: 2 } };
    h.state.trackedRunKeys = ['17:1', '5:1'];
    h.state.runs = [];
    h.acceptRuns([raw], new reliability.Lease(5_000));
    assert.equal(h.state.runs[0].actor, undefined);
    assert.equal(h.state.runs[0].repository, undefined);
    assert.equal(h.state.runs[0].head_sha, 'abc123');
    assert.deepEqual(Object.keys(h.state.progressByRun), ['17:1']);
    assert.deepEqual(Object.keys(h.state.jobsByRun), ['17:1']);
    assert.deepEqual(Object.keys(h.state.terminalStates), []);
    assert.deepEqual(Object.keys(h.state.timingModels), ['9:main:']);
    assert.deepEqual([...h.state.trackedRunKeys], ['17:1']);
    const job = model.slimJob({ id: 1, name: 'b', status: 'completed', runner_name: 'r', url: 'u', steps: [{ name: 's', number: 1, status: 'completed', extra: 1 }] });
    assert.deepEqual(job, { id: 1, name: 'b', status: 'completed', steps: [{ name: 's', number: 1, status: 'completed' }] });
  } finally { h.close(); }
});

test('unchanged state is not rewritten to storage', async () => {
  const h = harness();
  try {
    await h.persist(); await h.persist(); await h.persist();
    assert.equal(h.writes.length, 1);
    h.state.lastPollAt = Date.now();
    await h.persist();
    assert.equal(h.writes.length, 2);
  } finally { h.close(); }
});

test('User-Agent version comes from one constant that matches the manifest', () => {
  const h = harness();
  try {
    const manifest = JSON.parse(fs.readFileSync(path.join(root, 'manifest.json'), 'utf8'));
    assert.equal(h.userAgent, `ToolBox-GitHub-Actions-Watcher/${manifest.version}`);
  } finally { h.close(); }
});


test('a short Retry-After is not stretched to the hourly primary reset', async () => {
  const hourly = Math.floor(Date.now() / 1000) + 3_000;
  const h = harness(() => ({ status: 403, body: '{"message":"secondary rate limit"}',
    headers: { 'retry-after': '30', 'x-ratelimit-remaining': '40', 'x-ratelimit-reset': String(hourly) } }));
  try {
    await h.pollGitHub(false);
    assert.equal(h.state.warning, 'rate_limit');
    assert.ok(h.state.rateResetAt <= Date.now() + 31_000, `reset ${h.state.rateResetAt - Date.now()}ms away`);
    assert.ok(h.state.rateResetAt >= Date.now() + 29_000);
  } finally { h.close(); }
});

test('anonymous full polls alternate the due front page with queued discovery pages', async () => {
  const h = harness();
  const lists = (from) => h.calls.slice(from).map((o) => new URL(o.url)).filter((u) => !/\/runs\/\d+/.test(u.pathname))
    .map((u) => u.pathname.includes('/workflows/') || u.searchParams.has('status') ? 'discovery' : u.pathname.endsWith('/actions/runs') ? 'front' : 'other');
  try {
    const kinds = [];
    for (let cycle = 0; cycle < 4; cycle++) {
      h.ageFrontPage(); // simulate the 6-minute gap between anonymous full polls
      const full = h.calls.length; await h.pollGitHub(false); kinds.push(...lists(full));
      const light = h.calls.length; await h.pollGitHub(false);
      assert.equal(h.calls.length - light, 1, 'light polls only re-read the tracked run');
    }
    assert.deepEqual(kinds, ['front', 'discovery', 'front', 'discovery']);
    // Per hour: 10 full polls x (run + one list page + jobs) + 10 light polls x run.
    const fullCost = 3, pollsPerHour = 3_600_000 / model.pollInterval(false, true);
    assert.ok(pollsPerHour / 2 * fullCost + pollsPerHour / 2 < 60);
  } finally { h.close(); }
});

test('a secondary limit with quota remaining ignores the hourly reset and backs off from 1 minute', async () => {
  const hourly = Math.floor(Date.now() / 1000) + 3_000;
  const h = harness(() => ({ status: 429, body: '{"message":"secondary rate limit"}',
    headers: { 'x-ratelimit-remaining': '40', 'x-ratelimit-reset': String(hourly) } }));
  try {
    await h.pollGitHub(false);
    assert.equal(h.state.warning, 'rate_limit');
    let wait = h.state.rateResetAt - Date.now();
    assert.ok(wait >= 59_000 && wait <= 61_000, `first wait ${wait}ms`);
    h.state.rateResetAt = Date.now() - 1; // the minute passes; the limit is hit again
    await h.pollGitHub(false);
    wait = h.state.rateResetAt - Date.now();
    assert.ok(wait >= 119_000 && wait <= 121_000, `second wait ${wait}ms`);
  } finally { h.close(); }
  const exhausted = harness(() => ({ status: 403, body: '{}',
    headers: { 'x-ratelimit-remaining': '0', 'x-ratelimit-reset': String(hourly) } }));
  try {
    await exhausted.pollGitHub(false);
    assert.equal(exhausted.state.rateResetAt, hourly * 1000);
  } finally { exhausted.close(); }
});

test('anonymous poll interval adapts to runs whose jobs span several pages', async () => {
  const jobsPage = (o) => {
    const page = Number(new URL(o.url).searchParams.get('page') || 1);
    const next = page < 3 ? { link: `<${o.url.replace(/&page=\d+/, '')}&page=${page + 1}>; rel="next"` } : {};
    return response({ total_count: 250, jobs: Array.from({ length: page < 3 ? 100 : 50 }, (_, i) => ({ id: page * 1000 + i, status: 'in_progress', steps: [] })) }, next);
  };
  const h = harness((o) => o.url.includes('/jobs?') ? jobsPage(o)
    : response(o.url.includes('/actions/runs/17') ? run() : { workflow_runs: [] }));
  try {
    await h.pollGitHub(false);
    const full = h.calls.length;
    assert.equal(h.calls.filter((o) => o.url.includes('/jobs?')).length, 3);
    assert.equal(h.state.jobsByRun[model.runKey(run())].length, 250, 'every job is still fetched');
    await h.pollGitHub(false);
    const light = h.calls.length - full;
    const pollsPerHour = 3_600_000 / h.pollInterval();
    assert.ok(h.pollInterval() > model.pollInterval(false, true));
    assert.ok(pollsPerHour / 2 * (full + light) <= 50, `${pollsPerHour / 2 * (full + light)} requests/hour`);
  } finally { h.close(); }
});

test('a failing discovery page rotates out and the next full poll reads the front page', async () => {
  const h = harness((o) => o.url.includes('/workflows/9/runs')
    ? { status: 404, body: '{"message":"Not Found"}', headers: {} }
    : response(o.url.includes('/jobs?') ? { jobs: [] } : o.url.includes('/actions/runs/17') ? run() : { workflow_runs: [] }));
  const lists = (from) => h.calls.slice(from).map((o) => new URL(o.url)).filter((u) => !/\/runs\/\d+/.test(u.pathname))
    .map((u) => u.pathname.includes('/workflows/') ? 'workflow' : u.searchParams.has('status') ? 'status' : 'front');
  try {
    const kinds = [];
    for (let cycle = 0; cycle < 3; cycle++) {
      h.ageFrontPage();
      const full = h.calls.length; await h.pollGitHub(false); kinds.push(...lists(full));
      await h.pollGitHub(false);
    }
    // front, then the failing workflow page (dropped), then front again rather than a stuck head.
    assert.deepEqual(kinds.slice(0, 3), ['front', 'workflow', 'front']);
    assert.equal(h.calls.filter((o) => o.url.includes('/workflows/9/runs')).length, 1);
    assert.notEqual(h.state.warning, 'not_found');
  } finally { h.close(); }
});

test('a repository link typed without https:// reads the real owner, and dotted owners are refused', () => {
  for (const input of ['github.com/foo/bar', 'www.github.com/foo/bar', 'GitHub.com/foo/bar.git', 'https://github.com/foo/bar', 'foo/bar']) {
    const parsed = model.parseRepositoryLink(input);
    assert.deepEqual([parsed.owner, parsed.repo], ['foo', 'bar'], input);
  }
  assert.equal(model.parseRepositoryLink('foo/bar.js').repo, 'bar.js');
  assert.throws(() => model.parseRepositoryLink('github.com'), /owner\/repo/);
  assert.throws(() => model.parseRepositoryLink('example.com/foo/bar'), /owner/);
});

test('reading a different repository drops the previous watch setup', async () => {
  const h = harness((options) => {
    if (/\/repos\/other\/project$/.test(options.url)) return response({ full_name: 'other/project', default_branch: 'trunk' });
    if (options.url.includes('/actions/workflows')) return response({ workflows: [{ id: 5, name: 'Build', path: '.github/workflows/build.yml', state: 'active' }] });
    if (options.url.includes('/branches')) return response([{ name: 'trunk' }]);
    return response({ workflow_runs: [] });
  });
  try {
    h.state.monitoring = false;
    h.node('repo-input').value = 'https://github.com/other/project';
    await h.readRepository();
    assert.equal(h.state.repository.fullName, 'other/project');
    assert.equal(h.state.config, null);
  } finally { h.close(); }
});

test('a failed final save while stopping still leaves the watch screen', async () => {
  let renders = 0;
  const h = harness(undefined, { renderAll() { renders += 1; } });
  try {
    h.toolbox.storage.set = async () => { throw Object.assign(new Error('disk full'), { code: 'STORAGE_ERROR' }); };
    await h.stopWatching();
    assert.equal(h.state.monitoring, false);
    assert.ok(renders > 0, 'screen re-rendered after the failed save');
  } finally { h.close(); }
});
