import test from 'node:test';
import assert from 'node:assert/strict';
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
