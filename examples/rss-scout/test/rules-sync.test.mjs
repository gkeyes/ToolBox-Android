import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { loadScripts } from './helpers.mjs';

const ctx = loadScripts(['core.js', 'rules.js']);
const radar = JSON.stringify({ 'example.com': { _name: 'Example', '.': [{ title: 'Feed', source: ['/:id'], target: '/example/:id' }] } });
const network = { request: async url => (url.includes('raw.githubusercontent.com/DIYgod') ? { status: 200, text: radar } : { status: 404, text: '' }) };
const settings = { hubBase: 'https://rsshub.example', ruleMode: 'auto', ruleUrl: '', usePrivateRules: true };

for (const [label, privateRuleUrl] of [['is empty', ''], ['is not HTTPS', 'http://rules.example/r.json'], ['carries credentials', 'https://rules.example/r.json?token=x']]) {
  test('a private rule URL that ' + label + ' does not break Radar sync', async () => {
    const cache = await ctx.RSSScoutRules.sync(network, { ...settings, privateRuleUrl }, '', new AbortController().signal);
    assert.equal(cache.rules.length, 1);
    assert.equal(cache.source, 'GitHub 官方构建');
    assert.notEqual(cache.privateWarning, '');
    assert.equal(cache.attempts.at(-1).source, '私人规则订阅');
  });
}

test('sync still fails when neither Radar nor private rules are available', async () => {
  const down = { request: async () => ({ status: 503, text: '' }) };
  await assert.rejects(ctx.RSSScoutRules.sync(down, { ...settings, privateRuleUrl: '' }, '', new AbortController().signal), /规则更新未完成/);
});

test('stopping during the private rule fetch is reported as an abort', async () => {
  const controller = new AbortController();
  const slow = {
    request: async url => {
      if (url.includes('rules.example')) { controller.abort(); throw new DOMException('已停止', 'AbortError'); }
      return network.request(url);
    }
  };
  await assert.rejects(
    ctx.RSSScoutRules.sync(slow, { ...settings, privateRuleUrl: 'https://rules.example/r.json' }, '', controller.signal),
    { name: 'AbortError' }
  );
});

test('a sync that loads only one half of the rules says which half is missing', async () => {
  const signal = new AbortController().signal;
  const failedPrivate = await ctx.RSSScoutRules.sync(network, { ...settings, privateRuleUrl: 'https://rules.example/r.json' }, '', signal);
  assert.equal(failedPrivate.partial, 'private');
  const noPrivate = await ctx.RSSScoutRules.sync(network, { ...settings, usePrivateRules: false }, '', signal);
  assert.equal(noPrivate.partial, '');
  const privateRules = fs.readFileSync(new URL('../private-rules.json', import.meta.url), 'utf8');
  const privateOnly = { request: async url => (url.includes('rules.example') ? { status: 200, text: privateRules } : { status: 503, text: '' }) };
  const cache = await ctx.RSSScoutRules.sync(privateOnly, { ...settings, privateRuleUrl: 'https://rules.example/r.json' }, '', signal);
  assert.ok(cache.rules.length > 0);
  assert.equal(cache.partial, 'base');
});

test('a partial sync keeps the half the previous cache already had', () => {
  const { keepCachedHalf } = ctx.RSSScoutRules;
  const radarOnly = { sourceKey: 'k', partial: 'private', privateCount: 0, rules: [{ id: 'radar' }] };
  const privateOnly = { sourceKey: 'k', partial: 'base', privateCount: 1, rules: [{ id: 'private' }] };
  const merged = keepCachedHalf(privateOnly, radarOnly);
  assert.deepEqual(Array.from(merged.rules, r => r.id), ['private', 'radar']);
  assert.equal(merged.privateCount, 1);
  assert.equal(merged.partial, 'base');
  const back = keepCachedHalf(radarOnly, merged);
  assert.deepEqual(Array.from(back.rules, r => r.id), ['private', 'radar']);
  assert.equal(back.privateCount, 1);
  assert.equal(keepCachedHalf(privateOnly, { ...radarOnly, sourceKey: 'other' }), privateOnly);
  assert.equal(keepCachedHalf(privateOnly, privateOnly), privateOnly);
  const again = keepCachedHalf(privateOnly, merged);
  assert.deepEqual(Array.from(again.rules, r => r.id), ['private', 'radar'], 'a second private-only retry keeps the cached Radar half');
  const complete = { ...privateOnly, partial: '' };
  assert.equal(keepCachedHalf(complete, radarOnly), complete);
});

test('a half that fails permanently stops scans from re-syncing until the settings change', async () => {
  const { sync, needsRetry, keepCachedHalf, sourceKey } = ctx.RSSScoutRules;
  const signal = new AbortController().signal;
  const privateSettings = { ...settings, privateRuleUrl: 'https://rules.example/r.json' };
  // The private rule URL answers 404 every time: Radar loads, the private half can never load.
  const gone = await sync(network, privateSettings, '', signal);
  assert.equal(gone.partial, 'private');
  assert.equal(gone.stuck, true);
  assert.equal(needsRetry(gone, privateSettings), false, 'a permanent 404 must not trigger a full sync on every scan');
  assert.equal(keepCachedHalf(gone, { ...gone, stuck: false }).stuck, true);
  // A changed rule setting is a new sourceKey, so the cache is no longer valid and the next scan syncs.
  const changed = { ...privateSettings, privateRuleUrl: 'https://rules.example/other.json' };
  assert.notEqual(sourceKey(changed), gone.sourceKey);
  assert.equal(needsRetry(gone, changed), true);
  // Clearing the flag (a new instance key) makes the partial cache retry again.
  assert.equal(needsRetry({ ...gone, stuck: false }, privateSettings), true);

  // A transient failure (5xx, rate limit, network error) keeps retrying on every scan.
  for (const failure of [{ status: 503, text: '' }, { status: 429, text: '' }, null]) {
    const flaky = {
      request: async url => {
        if (!url.includes('rules.example')) return network.request(url);
        if (!failure) throw new Error('network down');
        return failure;
      }
    };
    const cache = await sync(flaky, privateSettings, '', signal);
    assert.equal(cache.partial, 'private');
    assert.equal(cache.stuck, false);
    assert.equal(needsRetry(cache, privateSettings), true);
  }

  // Radar half: every Radar source must have failed permanently.
  const privateRules = fs.readFileSync(new URL('../private-rules.json', import.meta.url), 'utf8');
  const radarGone = { request: async url => (url.includes('rules.example') ? { status: 200, text: privateRules } : { status: 404, text: '' }) };
  const noRadar = await sync(radarGone, privateSettings, '', signal);
  assert.equal(noRadar.partial, 'base');
  assert.equal(noRadar.stuck, true);
  const oneMirrorDown = { request: async url => (url.includes('jsdelivr') ? { status: 502, text: '' } : radarGone.request(url)) };
  assert.equal((await sync(oneMirrorDown, privateSettings, '', signal)).stuck, false);
});
