import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import test from 'node:test';
import {
  RECORD, THEME, REFERENCES, restoreControlPalette, assertControlReport, runControl, controlRequested,
} from '../qa/palette-reference-control.mjs';

const oldTheme = `package fixture
private val LightToolBoxColors = ToolBoxColorScheme(
    primary = Color(0xFF1677E8),
    textSecondary = Color(0xFF77797E),
    danger = Color(0xFFE34B4B),
    onPrimary = Color.White,
)
private val DarkToolBoxColors = ToolBoxColorScheme(
    primary = Color(0xFF4A9BFF),
    onPrimary = Color.White,
)
val unchangedLayout = "preserve spacing, labels and callbacks"
`;
const newTheme = oldTheme
  .replace('1677E8', '1264CC').replace('77797E', '676A70').replace('E34B4B', 'BA252C')
  .replace('primary = Color(0xFF4A9BFF),\n    onPrimary = Color.White,',
    'primary = Color(0xFF4A9BFF),\n    onPrimary = Color(0xFF10243A),\n    onDanger = Color(0xFF30100F),');
const expectedColors = {
  Light: { primary: '1264CC', textSecondary: '676A70', danger: 'BA252C' },
  Dark: { onPrimary: '10243A', onDanger: '30100F' },
};
const report = (tests = 1, errors = 0, failures = 0, skipped = 0) =>
  Object.entries({ tests, errors, failures, skipped })
    .map(([key, value]) => `<div class="infoBox" id="${key}"><div class="counter">${value}</div></div>`).join('\n');

// These test the diagnostic transformation/orchestration, not Android rendering.
test('control restores only recorded roles and rejects stale palette inputs', () => {
  const restored = restoreControlPalette(newTheme, oldTheme, { expectedColors });
  assert.equal(restored, oldTheme
    .replace('primary = Color(0xFF4A9BFF),\n    onPrimary = Color.White,',
      'primary = Color(0xFF4A9BFF),\n    onPrimary = Color(0xFFFFFFFF),\n    onDanger = Color(0xFFFFFFFF),'));
  assert.throws(() => restoreControlPalette(newTheme.replace('1264CC', '000000'), oldTheme, { expectedColors }), /Unexpected current Light.primary/);
  assert.throws(() => restoreControlPalette(newTheme, oldTheme, { expectedColors: { Light: {} } }), /Missing Light/);
});

test('control requires every screenshot to execute without skips or failures', () => {
  assert.deepEqual(assertControlReport(report(17), 17), { tests: 17, errors: 0, failures: 0, skipped: 0 });
  for (const html of [report(0), report(16), report(17, 1), report(17, 0, 1), report(17, 0, 0, 1), '']) {
    assert.throws(() => assertControlReport(html, 17));
  }
});

function fixture(t, exitCode) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'palette-control-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const repo = path.join(root, 'repo'), evidence = path.join(root, 'evidence');
  fs.mkdirSync(repo);
  const git = (...args) => execFileSync('git', args, { cwd: repo, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  const write = (relative, text) => {
    const file = path.join(repo, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, text);
  };
  git('init', '--quiet'); git('config', 'user.name', 'Control fixture'); git('config', 'user.email', 'fixture@example.invalid');
  write(THEME, oldTheme); write(`${REFERENCES}/fixture.png`, 'old reference');
  git('add', '.'); git('commit', '--quiet', '-m', 'baseline');
  const baselineCommit = git('rev-parse', 'HEAD');
  write(THEME, newTheme); write(`${REFERENCES}/fixture.png`, 'new reference');
  const digest = s => createHash('sha256').update(s).digest('hex');
  write(RECORD, JSON.stringify({ schemaVersion: 1, baselineCommit, expectedColors, references: [{
    file: 'fixture.png', referenceSha256: digest('old reference'), renderedSha256: digest('new reference'),
  }] }));
  write('gradlew', `#!${process.execPath}
const fs = require('node:fs');
const assert = require('node:assert/strict');
assert.ok(fs.readFileSync(${JSON.stringify(THEME)}, 'utf8').includes('Color(0xFF1677E8)'));
assert.equal(fs.readFileSync(${JSON.stringify(`${REFERENCES}/fixture.png`)}, 'utf8'), 'old reference');
fs.mkdirSync('app/build/reports/screenshotTest/preview/debug', {recursive:true});
fs.writeFileSync('app/build/reports/screenshotTest/preview/debug/index.html', ${JSON.stringify(report())});
console.log('FAKE_GRADLE_ORCHESTRATION_ONLY');
process.exit(${exitCode});
`);
  fs.chmodSync(path.join(repo, 'gradlew'), 0o755);
  git('add', '.'); git('commit', '--quiet', '-m', 'request control');
  return { repo, evidence, git, write };
}

for (const exitCode of [0, 3]) test(`isolated control preserves primary checkout and cleans up after exit ${exitCode}`, t => {
  const { repo, evidence, git, write } = fixture(t, exitCode);
  assert.equal(controlRequested(repo, git('rev-parse', 'HEAD')), true);
  if (exitCode === 0) runControl(repo, evidence);
  else assert.throws(() => runControl(repo, evidence), /strict screenshot control failed/);
  assert.equal(fs.readFileSync(path.join(repo, THEME), 'utf8'), newTheme);
  assert.equal(fs.readFileSync(path.join(repo, REFERENCES, 'fixture.png'), 'utf8'), 'new reference');
  assert.equal(git('status', '--porcelain'), '');
  assert.equal(git('worktree', 'list', '--porcelain').split('\n').filter(l => l.startsWith('worktree ')).length, 1);
  const receipt = JSON.parse(fs.readFileSync(path.join(evidence, 'receipt.json'), 'utf8'));
  assert.equal(receipt.status, exitCode === 0 ? 'PASSED' : 'FAILED');
  assert.equal(receipt.controlExitCode, exitCode);
  assert.equal(receipt.cleanup, 'CLEAN');
  assert.equal(receipt.visualApproval, 'NOT_PERFORMED');
  write('README.md', 'later unrelated commit'); git('add', '.'); git('commit', '--quiet', '-m', 'later change');
  assert.equal(controlRequested(repo, git('rev-parse', 'HEAD')), false);
});

test('reference provenance mismatch prevents even starting the renderer', t => {
  const { repo, evidence, write, git } = fixture(t, 0);
  write(`${REFERENCES}/fixture.png`, 'unrecorded replacement');
  assert.throws(() => runControl(repo, evidence), /Reference provenance mismatch/);
  assert.equal(git('worktree', 'list', '--porcelain').split('\n').filter(l => l.startsWith('worktree ')).length, 1);
  assert.equal(fs.existsSync(path.join(evidence, 'control.log')), false);
});
