#!/usr/bin/env python3
"""Real watcher UI, fixture data and a mocked native bridge; run only in Actions.

Browser plugin not available in CI: Playwright Chromium checks the actual app.
Only the startup entrypoint is replaced in the served copy, never in the TBX.
No real tokens, GitHub requests or native notifications are used by this test.
"""
import json
import mimetypes
from pathlib import Path
from urllib.parse import urlsplit
from playwright.sync_api import expect, sync_playwright

SOURCE = Path(__file__).resolve().parents[1]
OUTPUT = SOURCE.parents[1] / "build/watcher-layout"
ENTRY = "  startForegroundClock();\n  boot();\n})();"
TEST_ENTRY = "  window.__watcherLayout = {state, model, viewState, renderDashboard, renderAll, chooseDisplayedRun, friendlyName};\n})();"
ASSETS = {"index.html", "style.css", "github-model.js", "app.js", "icon.png"}
CASES = [(width, 100, "light") for width in (280, 320, 360, 393, 420, 480, 699, 700, 820, 1280)]
CASES += [(320, 150, "light"), (393, 150, "dark"), (700, 150, "dark"), (393, 100, "dark")]

BRIDGE = r"""() => {
  window.__calls = [];
  window.ToolBox = {
    browser: {open: async url => {
      if (window.__denyBrowser) throw {code: 'PERMISSION_DENIED'};
      window.__calls.push(['browser', url]);
    }},
    storage: {set: async () => {}, secure: {get: async () => null}},
    background: {
      cancelTimer: async key => window.__calls.push(['cancelTimer', key]),
      setTimer: async (key, interval) => window.__calls.push(['setTimer', key, interval]),
      stop: async id => window.__calls.push(['stop', id])
    },
    notifications: {live: {start: async () => {}, update: async () => {}, end: async () => {}}, post: async () => {}},
    network: {request: async request => {
      window.__calls.push(['request', request.method, request.url]);
      const {state, model} = window.__watcherLayout;
      const path = new URL(request.url).pathname;
      let data;
      if (path.endsWith('/actions/runs')) data = {workflow_runs: state.runs};
      else if (path.endsWith('/jobs')) data = {jobs: state.jobsByRun[model.runKey(state.runs[0])]};
      else throw new Error('Unexpected API path: ' + path);
      return {status: 200, headers: {'x-ratelimit-remaining': '4900'}, body: JSON.stringify(data)};
    }}
  };
}"""

FIXTURE = r"""(longNames) => {
  const {state, model, viewState, renderAll} = window.__watcherLayout;
  const now = Date.now();
  const sha = 'ea165f8d65b6e75b540449e92b4886f43607fa02';
  const branch = longNames ? 'codex/' + 'longbranchwithoutbreakpoints'.repeat(6) : 'codex/refactor-lightweight-v2';
  const name = longNames ? 'Android CI ' + 'LongWorkflowName'.repeat(6) : 'Android CI';
  const run = {
    id: 42, run_attempt: 1, workflow_id: 7, run_number: 128,
    name, status: 'in_progress', conclusion: null, event: 'push',
    head_branch: branch, head_sha: sha,
    created_at: new Date(now - 120000).toISOString(),
    run_started_at: new Date(now - 120000).toISOString()
  };
  const names = [
    'Enable accelerated behavior test emulator',
    'Verify catalog, backup, execution identity, dialogs and Wasm on Android',
    'Retain host unit test reports',
    'Retain Android behavior and WebView evidence',
    'Run actions/upload-artifact@' + sha,
    'Post Run gradle/actions/setup-gradle@4733eaac7c1b0da527e4206b7671e0061de1ce37',
    'Post Run actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961',
    'Post Run actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803',
    longNames ? 'UnbrokenToken'.repeat(20) : 'Complete job'
  ];
  const job = {
    id: 99, name: longNames ? 'Android verification ' + 'LongJobName'.repeat(10) : 'Android 行为测试',
    status: 'in_progress', conclusion: null, started_at: run.run_started_at,
    steps: names.map((name, index) => ({
      name, number: index + 1,
      status: index === 0 ? 'completed' : index === 1 ? 'in_progress' : 'pending',
      conclusion: index === 0 ? 'success' : null,
      started_at: index < 2 ? new Date(now - 60000).toISOString() : null,
      completed_at: index === 0 ? new Date(now - 24000).toISOString() : null
    }))
  };
  const recent = Array.from({length: 10}, (_, i) => ({
    ...run, id: 30 - i, run_number: 127 - i, status: 'completed',
    conclusion: i === 1 ? 'failure' : 'success',
    created_at: new Date(now - (i + 1) * 3600000).toISOString(),
    run_started_at: new Date(now - (i + 1) * 3600000).toISOString(),
    updated_at: new Date(now - (i + 1) * 3600000 + 185000).toISOString()
  }));
  Object.assign(viewState, {selectedRunKey: null, detailsRunKey: null, jobsSignature: null, activeSignature: null, recentSignature: null, historyExpanded: false});
  Object.assign(state, {
    ready: true, monitoring: true, sessionId: 'fixture-session', busy: false,
    config: {owner: 'gkeyes', repo: 'ToolBox-Android', fullName: longNames ? 'owner/' + 'LongRepositoryName'.repeat(6) : 'gkeyes/ToolBox-Android', branchMode: 'branch', branch, selectedWorkflowIds: [7]},
    runs: [run, ...recent], jobsByRun: {[model.runKey(run)]: [job]},
    timingModels: {[`7:${branch}:push`]: {sampleCount: 0}}, progressByRun: {},
    trackedRunKeys: [model.runKey(run)], terminalStates: {}, watchStartedAt: now - 180000,
    lastPollAt: now, nextPollAt: now + 15000,
    warning: null, warningMessage: ''
  });
  document.getElementById('raw-names').checked = false;
  renderAll();
  return names;
}"""

BOUNDS = r"""() => {
  const failures = [];
  const root = document.documentElement;
  if (root.scrollWidth > root.clientWidth + 1) failures.push('document horizontal overflow');
  if (document.body.scrollWidth > document.body.clientWidth + 1) failures.push('body horizontal overflow');
  for (const element of document.querySelectorAll('#watch-screen > *, .step-row')) {
    if (!element.checkVisibility()) continue;
    const rect = element.getBoundingClientRect();
    if (rect.left < -1 || rect.right > root.clientWidth + 1) failures.push('outside viewport: ' + (element.id || element.className));
  }
  // Overview titles intentionally use two-line summaries. Raw details must
  // preserve every original character, not just hide horizontal overflow.
  for (const element of document.querySelectorAll('.step-row > span:nth-child(2), .job-list summary > strong, .metadata dd, .recent-main > span, #network-message')) {
    if (!element.checkVisibility()) continue;
    const box = element.getBoundingClientRect();
    const range = document.createRange();
    range.selectNodeContents(element);
    if ([...range.getClientRects()].some(rect => rect.left < box.left - 1 || rect.right > box.right + 1)) failures.push('text spills: ' + element.textContent.slice(0, 90));
  }
  return {viewport: root.clientWidth, scrollWidth: root.scrollWidth, failures};
}"""


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    app = (SOURCE / 'app.js').read_text(encoding='utf-8')
    if app.count(ENTRY) != 1:
        raise RuntimeError('Watcher startup changed; update the isolated renderer harness')
    instrumented = app.replace(ENTRY, TEST_ENTRY)
    results = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        for width, font_percent, theme in CASES:
            name = f'{width}px-font{font_percent}-{theme}'
            page = browser.new_page(viewport={'width': width, 'height': 844}, color_scheme=theme)
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.on('console', lambda msg: errors.append(msg.text) if msg.type in ('error', 'warning') else None)
            page.add_init_script(f'({BRIDGE})()')

            def serve(route):
                url = urlsplit(route.request.url)
                path = url.path.lstrip('/') or 'index.html'
                if url.netloc != 'watcher.test' or path not in ASSETS:
                    errors.append(f'Unexpected request: {route.request.url}')
                    route.abort()
                elif path == 'app.js':
                    route.fulfill(content_type='text/javascript', body=instrumented)
                else:
                    route.fulfill(content_type=mimetypes.guess_type(path)[0] or 'application/octet-stream', body=(SOURCE / path).read_bytes())

            page.route('**/*', serve)
            result = {'case': name, 'passed': False}
            try:
                page.goto('http://watcher.test/', wait_until='load')
                assert page.url == 'http://watcher.test/'
                expect(page).to_have_title('GitHub 构建守望')
                names = page.evaluate(FIXTURE, True)
                page.evaluate("size => document.documentElement.style.fontSize = size + '%'", font_percent)
                expect(page.locator('#watch-screen')).to_be_visible()
                assert not page.locator('#build-details').evaluate('e => e.open')
                expect(page.locator('#job-list details')).to_have_count(1)
                assert not page.locator('#job-list details').first.evaluate('e => e.open')
                expect(page.locator('#active-section')).to_be_hidden()
                expect(page.locator('.recent-row')).to_have_count(3)
                expect(page.locator('#current-step')).to_have_text('验证 Android 行为')
                result['bounds'] = page.evaluate(BOUNDS)
                assert not result['bounds']['failures'], result['bounds']
                page.screenshot(path=str(OUTPUT / f'{name}-overview.png'))

                page.locator('#build-details > summary').click()
                job = page.locator('#job-list details').first
                job.locator('summary').click()
                expect(page.locator('.step-row')).to_have_count(len(names))
                expect(page.locator('.step-row > span:nth-child(2)').nth(4)).to_have_text('上传构建产物')
                page.locator('#raw-names').check()
                expect(page.locator('.step-row > span:nth-child(2)')).to_have_text(names)
                page.locator('#technical-details > summary').click()
                expect(page.locator('#run-sha')).to_have_text('ea165f8d65b6e75b540449e92b4886f43607fa02')
                assert not page.evaluate(BOUNDS)['failures'], page.evaluate(BOUNDS)
                # A clock render must not replace focused controls or reopen details.
                page.evaluate('window.__oldJob = document.querySelector("#job-list details")')
                page.evaluate('window.__watcherLayout.renderDashboard()')
                assert page.evaluate('window.__oldJob === document.querySelector("#job-list details")')
                assert job.evaluate('e => e.open')
                job.locator('summary').click()
                page.evaluate('window.__watcherLayout.renderDashboard()')
                assert not job.evaluate('e => e.open')
                job.locator('summary').click()
                page.locator('#jobs-title').scroll_into_view_if_needed()
                page.screenshot(path=str(OUTPUT / f'{name}-details.png'))
                page.locator('#build-details > summary').click()
                page.locator('#toggle-history').click()
                expect(page.locator('.recent-row')).to_have_count(10)
                page.evaluate('window.__watcherLayout.renderDashboard()')
                expect(page.locator('.recent-row')).to_have_count(10)
                page.locator('#toggle-history').click()
                expect(page.locator('.recent-row')).to_have_count(3)
                page.locator('#open-github').click()
                assert page.evaluate("window.__calls.some(c => c[0] === 'browser' && c[1] === 'https://github.com/gkeyes/ToolBox-Android/actions/runs/42')")

                # Multiple simultaneous builds can be selected without changing
                # the primary run used by background notifications.
                page.evaluate("""() => {
                  const {state, model, renderDashboard} = window.__watcherLayout;
                  const other = {...state.runs[0], id: 43, name: 'TBX Package', created_at: new Date(Date.now() - 180000).toISOString()};
                  state.runs.splice(1, 0, other);
                  state.trackedRunKeys.push(model.runKey(other));
                  renderDashboard();
                }""")
                page.locator('#active-section > summary').click()
                page.locator('#active-runs .run-card').click()
                expect(page.locator('#run-workflow')).to_have_text('TBX Package')
                assert page.evaluate('window.__watcherLayout.chooseDisplayedRun().id') == 42
                page.evaluate(FIXTURE, True)
                page.locator('#refresh-now').click()
                expect(page.locator('#refresh-now')).to_be_enabled()
                assert page.evaluate("window.__calls.filter(c => c[0] === 'request').length") > 0
                assert page.evaluate("window.__calls.filter(c => c[0] === 'request').every(c => c[1] === 'GET')")

                # Warning stays visible while the build state remains truthful.
                page.evaluate("""() => {
                  const {state, renderDashboard} = window.__watcherLayout;
                  state.warning = 'offline'; state.warningMessage = '网络暂时不可用 · https://example.invalid/' + 'longpath'.repeat(20);
                  renderDashboard();
                }""")
                expect(page.locator('#network-banner')).to_be_visible()
                expect(page.locator('#hero-state')).to_have_text('运行中')
                assert not page.evaluate(BOUNDS)['failures']

                page.evaluate('window.scrollTo({left: 10000, top: document.documentElement.scrollHeight})')
                assert page.evaluate('window.scrollX') == 0
                footer = page.evaluate("""() => {
                  const button = document.getElementById('stop-watching');
                  const rect = button.getBoundingClientRect();
                  const bar = document.querySelector('.action-bar').getBoundingClientRect();
                  const recent = document.getElementById('recent-runs').getBoundingClientRect();
                  return {
                    visible: rect.left >= 0 && rect.right <= innerWidth && rect.top >= 0 && rect.bottom <= innerHeight,
                    hit: button.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)),
                    historyClear: recent.bottom <= bar.top + 1,
                    touchTarget: rect.height >= 44
                  };
                }""")
                assert all(footer.values()), footer
                page.locator('#stop-watching').click()
                expect(page.locator('#setup-screen')).to_be_visible()
                assert page.evaluate("window.__calls.filter(c => c[0] === 'cancelTimer').length") == 2
                assert not errors, errors
                result.update(passed=True, footer=footer)

                # Readable product evidence, still explicitly fixture-only data.
                page.evaluate(FIXTURE, False)
                page.evaluate('window.scrollTo(0, 0)')
                page.screenshot(path=str(OUTPUT / f'{name}-compact.png'))
                result['overviewHeight'] = page.evaluate('document.documentElement.scrollHeight')
                if width == 393 and font_percent == 100:
                    assert result['overviewHeight'] < 1100, result
                    for conclusion, label in [('failure', '构建失败'), ('success', '构建成功'), ('cancelled', '已取消')]:
                        page.evaluate(FIXTURE, False)
                        page.evaluate("""conclusion => {
                          const {state, model, renderDashboard} = window.__watcherLayout;
                          const run = state.runs[0]; run.status = 'completed'; run.conclusion = conclusion; run.updated_at = new Date().toISOString();
                          state.terminalStates[model.runKey(run)] = {jobsSynced: true, holdUntil: Date.now() + 120000, posted: false};
                          const job = state.jobsByRun[model.runKey(run)][0]; job.status = 'completed'; job.conclusion = conclusion;
                          job.steps.forEach((s, i) => {s.status = 'completed'; s.conclusion = conclusion === 'failure' && i === 1 ? 'failure' : 'success';});
                          renderDashboard();
                        }""", conclusion)
                        expect(page.locator('#hero-state')).to_have_text(label)
                        expect(page.locator('#progress-caption')).to_have_text('本次已结束')
                        if conclusion == 'failure':
                            expect(page.locator('#current-step')).to_have_text('失败步骤：验证 Android 行为')
                        page.screenshot(path=str(OUTPUT / f'{name}-{conclusion}.png'))
                    page.evaluate(FIXTURE, False)
                    page.evaluate("""() => {
                      const {state, model, renderDashboard} = window.__watcherLayout;
                      state.runs[0].status = 'queued'; state.jobsByRun = {}; state.progressByRun = {};
                      renderDashboard();
                    }""")
                    expect(page.locator('#hero-state')).to_have_text('排队中')
                    expect(page.locator('#progress-value')).to_have_text('0%')
                    page.evaluate("""() => {
                      const {state, renderDashboard} = window.__watcherLayout;
                      state.runs = []; state.trackedRunKeys = [];
                      renderDashboard();
                    }""")
                    expect(page.locator('#progress-value')).to_have_text('--')
                    page.screenshot(path=str(OUTPUT / f'{name}-idle.png'))
                    page.evaluate('window.__denyBrowser = true')
                    page.locator('#open-github').click()
                    expect(page.locator('#toast')).to_contain_text('浏览器访问')
                    page.emulate_media(reduced_motion='reduce')
                    assert page.locator('#progress-fill').evaluate('e => getComputedStyle(e).transitionDuration') == '0s'
                    assert not errors, errors
            except Exception as error:
                result.update(passed=False, error=str(error), console=errors)
                page.screenshot(path=str(OUTPUT / f'{name}-failure.png'))
            finally:
                results.append(result)
                page.close()
                print(json.dumps(result, ensure_ascii=False), flush=True)
        browser.close()
    (OUTPUT / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if not all(result['passed'] for result in results):
        raise SystemExit('Watcher UI regression failed; inspect screenshots and results.json')
    print(f'PASS: {len(results)} viewport/font/theme cases; progressive disclosure, raw names, history, state presentation, bounds and controls')


if __name__ == '__main__':
    main()
