const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const repairSource = fs.readFileSync(
  path.resolve(__dirname, '../../app/src/main/assets/browser/media-layout-compat.js'),
  'utf8',
);

function chromeBinary() {
  for (const candidate of ['google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser']) {
    const probe = spawnSync('bash', ['-lc', 'command -v ' + candidate], { encoding: 'utf8' });
    if (probe.status === 0 && probe.stdout.trim()) return probe.stdout.trim();
  }
  throw new Error('No Chromium/Chrome binary available for real layout regression test');
}

function runFixture({ playerClass = 'dplayer', expectedRepair = true } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'toolbox-media-layout-'));
  const htmlPath = path.join(dir, 'fixture.html');

  const html = `<!doctype html>
<meta charset="utf-8">
<style>
  html, body { margin: 0; width: 384px; min-height: 800px; }
  #player { width: 384px; height: 0; display: block; }
  #wrap { width: 384px; height: 0; display: flex; }
  #video { width: 384px; height: 0; display: block; }
</style>
<div id="player" class="${playerClass}">
  <div id="wrap" class="${playerClass ? 'dplayer-video-wrap' : ''}">
    <video id="video" class="dplayer-video dplayer-video-current"></video>
  </div>
</div>
<script>
  const video = document.getElementById('video');
  video.src = URL.createObjectURL(new Blob(['not-real-media'], { type: 'video/mp4' }));
</script>
<script>
  window.__toolboxRepairCollapsedMediaLayout = ${repairSource};
</script>
<script>
  try {
    const before = document.getElementById('player').getBoundingClientRect();
    const repaired = window.__toolboxRepairCollapsedMediaLayout();
    const player = document.getElementById('player');
    const wrap = document.getElementById('wrap');
    const video = document.getElementById('video');
    const after = player.getBoundingClientRect();
    const wrapRect = wrap.getBoundingClientRect();
    const videoRect = video.getBoundingClientRect();
    const expected = ${expectedRepair ? 'true' : 'false'};
    const pass = expected
      ? before.height <= 2 && repaired === 1 && after.height > 200 && wrapRect.height > 200 && videoRect.height > 200
      : before.height <= 2 && repaired === 0 && after.height <= 2;
    document.body.setAttribute('data-result', pass ? 'PASS' : 'FAIL');
    document.body.setAttribute('data-before', Math.round(before.width) + 'x' + Math.round(before.height));
    document.body.setAttribute('data-after', Math.round(after.width) + 'x' + Math.round(after.height));
    document.body.setAttribute('data-wrap', Math.round(wrapRect.width) + 'x' + Math.round(wrapRect.height));
    document.body.setAttribute('data-video', Math.round(videoRect.width) + 'x' + Math.round(videoRect.height));
    document.body.setAttribute('data-repaired', String(repaired));
    document.body.setAttribute('data-marker', player.dataset.toolboxMediaLayoutRepair || 'none');
  } catch (error) {
    document.body.setAttribute('data-result', 'ERROR');
    document.body.setAttribute('data-error', String(error && (error.stack || error.message || error)));
  }
</script>`;

  fs.writeFileSync(htmlPath, html);
  const chrome = chromeBinary();
  const result = spawnSync(
    chrome,
    [
      '--headless=new',
      '--no-sandbox',
      '--disable-gpu',
      '--disable-dev-shm-usage',
      '--run-all-compositor-stages-before-draw',
      '--virtual-time-budget=1500',
      '--dump-dom',
      'file://' + htmlPath,
    ],
    { encoding: 'utf8', timeout: 30000 },
  );

  try {
    assert.equal(result.status, 0, result.stderr || result.stdout);
    return result.stdout;
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test('real Chromium layout recovers a 384x0 DPlayer to a visible player', () => {
  const dom = runFixture();
  assert.match(dom, /data-result="PASS"/);
  assert.match(dom, /data-before="384x0"/);
  assert.match(dom, /data-after="384x216"/);
  assert.match(dom, /data-repaired="1"/);
  assert.match(dom, /data-marker="fallback-16:9:1\.7778"/);
});

test('real Chromium layout leaves a generic zero-height media container untouched', () => {
  const dom = runFixture({ playerClass: '', expectedRepair: false });
  assert.match(dom, /data-result="PASS"/);
  assert.match(dom, /data-before="384x0"/);
  assert.match(dom, /data-after="384x0"/);
  assert.match(dom, /data-repaired="0"/);
});
