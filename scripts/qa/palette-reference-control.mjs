#!/usr/bin/env node
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

export const RECORD = 'docs/qa/palette-reference-refresh.json';
export const THEME = 'core-ui/src/main/java/io/toolbox/core/ui/theme/ToolBoxTheme.kt';
export const REFERENCES = 'app/src/screenshotTestDebug/reference';

const colorExpression = /Color(?:\(0xFF([0-9A-Fa-f]{6})\)|\.(White|Black))/;

function colorBlock(source, mode) {
  const start = source.indexOf(`val ${mode}ToolBoxColors = ToolBoxColorScheme(`);
  const end = source.indexOf('\n)', start);
  if (start < 0 || end < start) throw new Error(`Missing ${mode} palette`);
  return { start, end, text: source.slice(start, end) };
}

function readColor(block, role) {
  const line = block.split('\n').find(line => new RegExp(`^\\s+${role} = `).test(line));
  const match = line?.match(colorExpression);
  if (!match) throw new Error(`Missing color ${role}`);
  return (match[1] ?? (match[2] === 'White' ? 'FFFFFF' : '000000')).toUpperCase();
}

// Restore only the recorded color roles. Keep all fixes, components and fixtures unchanged.
export function restoreControlPalette(current, baseline, record) {
  let result = current;
  for (const mode of ['Light', 'Dark']) {
    const old = colorBlock(baseline, mode);
    const roles = record.expectedColors?.[mode];
    if (!roles || Object.keys(roles).length === 0) throw new Error(`Missing ${mode} expected colors`);
    for (const [role, expected] of Object.entries(roles)) {
      if (!/^[A-Za-z][A-Za-z0-9]*$/.test(role) || !/^[0-9A-F]{6}$/.test(expected)) {
        throw new Error('Invalid color record');
      }
      const block = colorBlock(result, mode);
      if (readColor(block.text, role) !== expected) throw new Error(`Unexpected current ${mode}.${role}`);
      // Before onDanger existed, destructive buttons also used onPrimary.
      const oldRole = role === 'onDanger' ? 'onPrimary' : role;
      const replacement = `Color(0xFF${readColor(old.text, oldRole)})`;
      const lines = block.text.split('\n').map(line =>
        new RegExp(`^\\s+${role} = `).test(line) ? line.replace(colorExpression, replacement) : line);
      result = result.slice(0, block.start) + lines.join('\n') + result.slice(block.end);
    }
  }
  return result;
}

export function assertControlReport(html, expected) {
  const counts = {};
  for (const key of ['tests', 'errors', 'failures', 'skipped']) {
    const match = html.match(new RegExp(`id="${key}"[^>]*>\\s*<div class="counter">(\\d+)</div>`));
    if (!match) throw new Error(`Missing control report counter: ${key}`);
    counts[key] = Number(match[1]);
  }
  if (counts.tests !== expected || counts.errors || counts.failures || counts.skipped) {
    throw new Error(`Incomplete screenshot control: ${JSON.stringify(counts)}`);
  }
  return counts;
}

function verifyReferences(repo, record) {
  if (!Array.isArray(record.references) || !record.references.length) throw new Error('Missing reference hashes');
  const listed = git(repo, 'ls-files', REFERENCES).trim().split('\n').filter(Boolean).sort();
  const recorded = record.references.map(ref => `${REFERENCES}/${ref.file}`).sort();
  if (new Set(recorded).size !== recorded.length || JSON.stringify(listed) !== JSON.stringify(recorded)) {
    throw new Error('Reference matrix differs from the recorded CI render');
  }
  const digest = bytes => createHash('sha256').update(bytes).digest('hex');
  for (const ref of record.references) {
    if (path.posix.isAbsolute(ref.file) || ref.file.split('/').includes('..') || !ref.file.endsWith('.png')) {
      throw new Error('Invalid reference path');
    }
    const relative = `${REFERENCES}/${ref.file}`;
    const current = fs.readFileSync(path.join(repo, relative));
    const previous = command(repo, 'git', ['show', `${record.baselineCommit}:${relative}`], { encoding: null });
    if (digest(current) !== ref.renderedSha256 || digest(previous) !== ref.referenceSha256) {
      throw new Error(`Reference provenance mismatch: ${ref.file}`);
    }
  }
}

function command(cwd, executable, args, options = {}) {
  const result = spawnSync(executable, args, {
    cwd, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, ...options,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${path.basename(executable)} failed (${result.status})`);
  return result.stdout;
}

function git(repo, ...args) { return command(repo, 'git', args); }

export function controlRequested(repo, head) {
  // This is an explicitly recorded one-off control, not a permanent old-layout requirement.
  // --root also handles shallow checkouts: unchanged parent/HEAD record bytes mean no request.
  const changed = git(repo, 'diff-tree', '--root', '--no-commit-id', '--name-only', '-r', head, '--', RECORD);
  return changed.trim() === RECORD;
}

export function runControl(repo, evidence, { force = false } = {}) {
  const head = git(repo, 'rev-parse', 'HEAD').trim();
  if (!force && !controlRequested(repo, head)) {
    console.log('PALETTE_REFERENCE_CONTROL: not requested by this commit; normal screenshot gate remains required.');
    return;
  }
  const record = JSON.parse(fs.readFileSync(path.join(repo, RECORD), 'utf8'));
  if (record.schemaVersion !== 1 || !/^[0-9a-f]{40}$/.test(record.baselineCommit)) {
    throw new Error('Invalid baseline record');
  }
  fs.mkdirSync(evidence, { recursive: true });
  if (spawnSync('git', ['cat-file', '-e', `${record.baselineCommit}^{commit}`], { cwd: repo, stdio: 'ignore' }).status !== 0) {
    git(repo, 'fetch', '--no-tags', '--depth=1', 'origin', record.baselineCommit);
  }
  verifyReferences(repo, record);
  const oldTheme = git(repo, 'show', `${record.baselineCommit}:${THEME}`);
  const controlled = restoreControlPalette(fs.readFileSync(path.join(repo, THEME), 'utf8'), oldTheme, record);
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'toolbox-palette-control-'));
  const worktree = path.join(scratch, 'source');
  const receipt = { schemaVersion: 1, candidateCommit: head, baselineCommit: record.baselineCommit,
    scope: 'OLD_PALETTE_OLD_REFERENCES_ONLY', deviceExecution: 'NOT_RUN', visualApproval: 'NOT_PERFORMED',
    controlExitCode: null, status: 'RUNNING', cleanup: 'PENDING' };
  let attached = false;
  let failure;
  try {
    git(repo, 'worktree', 'add', '--detach', worktree, head);
    attached = true;
    fs.writeFileSync(path.join(worktree, THEME), controlled);
    git(worktree, 'restore', '--source', record.baselineCommit, '--worktree', '--', REFERENCES);
    // Neither the primary checkout nor its new references are modified.
    const changedPaths = git(worktree, 'diff', '--name-only').trim().split('\n').filter(Boolean);
    if (changedPaths.some(p => p !== THEME && !p.startsWith(`${REFERENCES}/`))) {
      throw new Error('Control modified an unrelated source');
    }
    const log = fs.openSync(path.join(evidence, 'control.log'), 'w');
    let result;
    try {
      result = spawnSync('./gradlew', ['--no-daemon', ':app:validateDebugScreenshotTest', '--console=plain'], {
        cwd: worktree, stdio: ['ignore', log, log], timeout: 15 * 60 * 1000,
      });
    } finally { fs.closeSync(log); }
    receipt.controlExitCode = result.status;
    for (const relative of ['app/build/reports/screenshotTest', 'app/build/outputs/screenshotTest-results']) {
      const from = path.join(worktree, relative);
      if (fs.existsSync(from)) fs.cpSync(from, path.join(evidence, relative), { recursive: true });
    }
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`Old-palette strict screenshot control failed (${result.status}); see control.log`);
    receipt.screenshots = assertControlReport(fs.readFileSync(path.join(worktree,
      'app/build/reports/screenshotTest/preview/debug/index.html'), 'utf8'), record.references.length);
    console.log('PALETTE_REFERENCE_CONTROL: old palette + old references passed the unchanged screenshot gate.');
  } catch (error) { failure = error; }
  finally {
    try {
      if (attached) git(repo, 'worktree', 'remove', '--force', worktree);
      fs.rmSync(scratch, { recursive: true, force: true });
      receipt.cleanup = 'CLEAN';
    } catch (error) {
      receipt.cleanup = 'FAILED';
      failure ??= error;
    }
    receipt.status = failure ? 'FAILED' : 'PASSED';
    fs.writeFileSync(path.join(evidence, 'receipt.json'), JSON.stringify(receipt, null, 2) + '\n');
  }
  if (failure) throw failure;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
    runControl(repo, path.join(repo, '.omo/evidence/ci-host-gate/palette-control'));
  } catch (error) {
    console.error(`PALETTE_REFERENCE_CONTROL: ${error.message}`);
    process.exitCode = 1;
  }
}
