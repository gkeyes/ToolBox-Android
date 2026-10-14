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
