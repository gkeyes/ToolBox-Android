#!/usr/bin/env python3
"""Offline rendering regression for the real watcher HTML/CSS and JS renderers.

Run in GitHub Actions, not as an Android build. Browser plugin not available;
Playwright Chromium provides viewport checks and screenshots. Only the startup
entrypoint is replaced in the served test copy of app.js: no production hooks,
network calls, tokens, native APIs or fixture code enter the packaged tool.
"""

import json
import mimetypes
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright


SOURCE = Path(__file__).resolve().parents[1]
OUTPUT = SOURCE.parents[1] / "build/watcher-layout"
ENTRY = "  startForegroundClock();\n  boot();\n})();"
TEST_ENTRY = "  window.__watcherLayout = {state, model, renderDashboard, stopForegroundClock};\n})();"
ASSETS = {"index.html", "style.css", "github-model.js", "app.js", "icon.png"}
CASES = [(width, 100, "light") for width in (280, 320, 360, 393, 420, 480, 699, 700, 820, 1280)]
CASES += [(320, 150, "light"), (393, 150, "dark"), (700, 150, "dark"), (393, 100, "dark")]

FIXTURE = r"""() => {
  const {state, model, renderDashboard, stopForegroundClock} = window.__watcherLayout;
  stopForegroundClock();
  const now = Date.now();
  const sha = 'ea165f8d65b6e75b540449e92b4886f43607fa02';
  const branch = 'codex/' + 'longbranchwithoutbreakpoints'.repeat(6);
  const run = {
    id: 42, run_attempt: 1, workflow_id: 7, run_number: 10,
    name: 'Android CI ' + 'LongWorkflowName'.repeat(6),
    status: 'in_progress', conclusion: null, event: 'push',
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
    'UnbrokenToken'.repeat(20)
  ];
  const job = {
    id: 99, name: 'Android verification ' + 'LongJobName'.repeat(10),
    status: 'in_progress', conclusion: null,
    started_at: run.run_started_at,
    steps: names.map((name, index) => ({
      name, number: index + 1,
      status: index === 0 ? 'completed' : index === 1 ? 'in_progress' : 'pending',
      conclusion: index === 0 ? 'success' : null,
      started_at: index < 2 ? new Date(now - 60000).toISOString() : null,
      completed_at: index === 0 ? new Date(now - 24000).toISOString() : null
    }))
  };
  Object.assign(state, {
    ready: true, monitoring: true,
    config: {fullName: 'owner/' + 'LongRepositoryName'.repeat(6), branchMode: 'branch', branch},
    runs: [run], jobsByRun: {[model.runKey(run)]: [job]},
    trackedRunKeys: [model.runKey(run)], watchStartedAt: now - 180000,
    lastPollAt: now, nextPollAt: now + 60000,
    warningMessage: 'Layout fixture: https://example.invalid/' + 'longpath'.repeat(20)
  });
  document.getElementById('setup-screen').hidden = true;
  document.getElementById('watch-screen').hidden = false;
  document.getElementById('host-caption').textContent = 'Offline layout regression fixture';
  renderDashboard();
  return names;
}"""

BOUNDS = r"""() => {
  const failures = [];
  const root = document.documentElement;
  if (root.scrollWidth > root.clientWidth + 1) failures.push('document horizontal overflow');
  if (document.body.scrollWidth > document.body.clientWidth + 1) failures.push('body horizontal overflow');
  for (const element of document.querySelectorAll('#watch-screen > *, .step-row')) {
    if (!element.getClientRects().length) continue;
    const rect = element.getBoundingClientRect();
    if (rect.left < -1 || rect.right > root.clientWidth + 1) {
      failures.push('outside viewport: ' + (element.id || element.className));
    }
  }
  // Inspect the actual text line boxes too: overflow:hidden must not make a
  // visually clipped SHA pass merely because the document stops scrolling.
  const selectors = [
    '.step-row > span:nth-child(2)', '.job-list summary > strong',
    '.repo-label', '#run-branch', '.run-card-title span', '.run-card-meta',
    '.recent-main span', '#network-message'
  ];
  for (const element of document.querySelectorAll(selectors.join(','))) {
    if (!element.getClientRects().length) continue;
    const box = element.getBoundingClientRect();
    const card = element.closest('.surface, .hero, .network-banner');
    const cardBox = card.getBoundingClientRect();
    const style = getComputedStyle(card);
    const left = Math.max(box.left, cardBox.left + parseFloat(style.paddingLeft));
    const right = Math.min(box.right, cardBox.right - parseFloat(style.paddingRight));
    const range = document.createRange();
    range.selectNodeContents(element);
    if ([...range.getClientRects()].some(rect => rect.left < left - 1 || rect.right > right + 1)) {
      failures.push('text spills or clips: ' + element.textContent.slice(0, 90));
    }
  }
  return {viewport: root.clientWidth, scrollWidth: root.scrollWidth, failures};
}"""


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    app = (SOURCE / "app.js").read_text(encoding="utf-8")
    if app.count(ENTRY) != 1:
        raise RuntimeError("Watcher startup changed; update the isolated renderer harness")
    instrumented_app = app.replace(ENTRY, TEST_ENTRY)
    results = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        for width, font_percent, theme in CASES:
            name = f"{width}px-font{font_percent}-{theme}"
            page = browser.new_page(viewport={"width": width, "height": 844}, color_scheme=theme)
            errors = []
            page.on("pageerror", lambda error: errors.append(str(error)))
            page.on("console", lambda message: errors.append(message.text)
                    if message.type in ("error", "warning") else None)

            def serve(route):
                url = urlsplit(route.request.url)
                path = url.path.lstrip("/") or "index.html"
                if url.netloc != "watcher.test" or path not in ASSETS:
                    errors.append(f"Unexpected request: {route.request.url}")
                    route.abort()
                    return
                if path == "app.js":
                    route.fulfill(content_type="text/javascript", body=instrumented_app)
                else:
                    route.fulfill(content_type=mimetypes.guess_type(path)[0] or "application/octet-stream",
                                  body=(SOURCE / path).read_bytes())

            page.route("**/*", serve)
            result = {"case": name, "passed": False}
            try:
                page.goto("http://watcher.test/", wait_until="load")
                assert page.url == "http://watcher.test/"
                expect(page).to_have_title("GitHub 构建守望")
                names = page.evaluate(FIXTURE)
                page.evaluate("size => document.documentElement.style.fontSize = size + '%'", font_percent)
                expect(page.locator("#watch-screen")).to_be_visible()
                expect(page.locator(".step-row")).to_have_count(len(names))
                expect(page.locator(".step-row > span:nth-child(2)")).to_have_text(names)
                page.screenshot(path=str(OUTPUT / f"{name}-top.png"))
                result["bounds"] = page.evaluate(BOUNDS)
                assert not result["bounds"]["failures"], result["bounds"]

                # Real details click -> collapsed -> data render -> still collapsed -> open.
                details = page.locator("#job-list details").first
                expect(details).to_have_attribute("open", "")
                details.locator("summary").click()
                assert not details.evaluate("element => element.open")
                page.evaluate("window.__watcherLayout.renderDashboard()")
                assert not details.evaluate("element => element.open"), "Refresh lost collapsed state"
                details.locator("summary").click()
                expect(details).to_have_attribute("open", "")
                page.evaluate("window.__watcherLayout.renderDashboard()")
                expect(details).to_have_attribute("open", "")
                assert not page.evaluate(BOUNDS)["failures"]
                page.locator("#jobs-title").scroll_into_view_if_needed()
                page.screenshot(path=str(OUTPUT / f"{name}-jobs.png"))

                page.evaluate("window.scrollTo({left: 10000, top: document.documentElement.scrollHeight})")
                assert page.evaluate("window.scrollX") == 0, "Page can still pan horizontally"
                footer = page.evaluate("""() => {
                  const button = document.getElementById('stop-watching');
                  const rect = button.getBoundingClientRect();
                  const bar = button.parentElement.getBoundingClientRect();
                  const recent = document.getElementById('recent-runs').getBoundingClientRect();
                  return {
                    visible: rect.left >= 0 && rect.right <= innerWidth && rect.top >= 0 && rect.bottom <= innerHeight,
                    hit: button.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)),
                    historyClear: recent.bottom <= bar.top + 1
                  };
                }""")
                assert all(footer.values()), footer
                assert not errors, errors
                page.screenshot(path=str(OUTPUT / f"{name}-bottom.png"))
                result.update(passed=True, footer=footer)
            except Exception as error:
                result["error"] = str(error)
                result["console"] = errors
                page.screenshot(path=str(OUTPUT / f"{name}-failure.png"))
            finally:
                results.append(result)
                page.close()
                print(json.dumps(result, ensure_ascii=False), flush=True)
        browser.close()
    (OUTPUT / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if not all(result["passed"] for result in results):
        raise SystemExit("Watcher layout regression failed; inspect the retained screenshots and results.json")
    print(f"PASS: {len(results)} viewport/font/theme cases; wrapping, bounds, details refresh and footer hit tests")


if __name__ == "__main__":
    main()
