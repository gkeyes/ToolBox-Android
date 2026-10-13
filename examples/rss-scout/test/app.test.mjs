import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { source } from './helpers.mjs';

const FEED = '<rss version="2.0"><channel><title>Routed</title><item><title>x</title></item></channel></rss>';
const MINIFLUX = 'https://reader.example.com';

// Boots index.html + the tool's scripts in jsdom against a fake ToolBox host.
async function boot({ storage = {}, secure = {}, responses = {} } = {}) {
  const dom = new JSDOM(source('index.html'), { url: 'https://tool.invalid/index.html', runScripts: 'outside-only', pretendToBeVisual: true });
  const { window } = dom;
  const log = { get: [], removed: [], requests: [] };
  const streams = new Map();
  let nextStream = 1;
  window.structuredClone = structuredClone;
  window.TextDecoder = TextDecoder;
  window.ToolBox = {
    ready: async () => {},
    storage: {
      get: async key => { log.get.push(key); return storage[key] ?? null; },
      set: async (key, value) => { storage[key] = value; },
      remove: async key => { log.removed.push(key); delete storage[key]; },
      secure: {
        get: async key => secure[key] ?? null,
        set: async (key, value) => { secure[key] = value; },
        remove: async key => { delete secure[key]; }
      }
    },
    network: {
      async openStream(request) {
        log.requests.push(request);
        const reply = responses[request.method + ' ' + request.url] || { status: 404, body: '' };
        const id = 'stream-' + nextStream++;
        streams.set(id, Buffer.from(typeof reply.body === 'string' ? reply.body : JSON.stringify(reply.body)));
        return { streamId: id, status: reply.status || 200, headers: { 'X-ToolBox-Final-URL': request.url } };
      },
      async readStream(id) { return { data: Array.from(streams.get(id)), done: true }; },
      async cancelStream() {}
    }
  };
  for (const name of ['core.js', 'network.js', 'miniflux.js', 'rules.js', 'app.js']) window.eval(source(name));
  await settle(window);
  return { window, $: id => window.document.getElementById(id), log, storage };
}
async function settle(window, rounds = 20) {
  for (let i = 0; i < rounds; i++) {
    await new Promise(resolve => setTimeout(resolve, 0));
    await new Promise(resolve => window.requestAnimationFrame(resolve));
  }
}

test('startup deletes the obsolete Radar v1 cache key without reading it', async () => {
  const { log, storage } = await boot({ storage: { 'rss-scout.radar.v1': { base: 'https://rsshub.app', rules: [{ host: 'a.com', source: '/', target: '/a' }] } } });
  assert.deepEqual(log.removed, ['rss-scout.radar.v1']);
  assert.equal(log.get.includes('rss-scout.radar.v1'), false);
  assert.equal('rss-scout.radar.v1' in storage, false);
});

test('subscribing a URL that carries the RSSHub key asks first and only then sends it to Miniflux', async () => {
  const feedUrl = 'https://rsshub.example/routed/1?key=hub-secret';
  const { window, $, log } = await boot({
    storage: { 'rss-scout.settings.v1': { hubBase: 'https://rsshub.example', useRadar: false, checkHome: false } },
    secure: {
      'rss-scout.miniflux.v1': { base: MINIFLUX, token: 'tok' },
      'rss-scout.hub-auth.v1': { base: 'https://rsshub.example', key: 'hub-secret' }
    },
    responses: {
      ['GET ' + feedUrl]: { body: FEED },
      ['GET ' + MINIFLUX + '/v1/feeds']: { body: [] },
      ['POST ' + MINIFLUX + '/v1/feeds']: { status: 201, body: { feed_id: 9 } }
    }
  });
  $('url').value = feedUrl;
  $('search-form').dispatchEvent(new window.Event('submit', { cancelable: true }));
  await settle(window);
  const subscribe = $('results').querySelector('button.subscribe');
  assert.equal(subscribe.textContent, '订阅 Miniflux');
  subscribe.click();
  await settle(window);
  assert.equal($('confirm-modal').hidden, false);
  assert.match($('confirm-message').textContent, /Miniflux 服务器/);
  assert.equal(log.requests.some(r => r.url.startsWith(MINIFLUX)), false);

  $('confirm-action').click();
  await settle(window);
  const post = log.requests.find(r => r.method === 'POST');
  assert.equal(JSON.parse(post.body).feed_url, feedUrl);
  assert.equal($('results').querySelector('button.subscribe').textContent, '已订阅');
});

test('subscribing a URL without credentials does not ask', async () => {
  const feedUrl = 'https://example.com/feed.xml';
  const { window, $, log } = await boot({
    storage: { 'rss-scout.settings.v1': { useRadar: false, checkHome: false } },
    secure: { 'rss-scout.miniflux.v1': { base: MINIFLUX, token: 'tok' } },
    responses: {
      ['GET ' + feedUrl]: { body: FEED },
      ['GET ' + MINIFLUX + '/v1/feeds']: { body: [] },
      ['POST ' + MINIFLUX + '/v1/feeds']: { status: 201, body: { feed_id: 3 } }
    }
  });
  $('url').value = feedUrl;
  $('search-form').dispatchEvent(new window.Event('submit', { cancelable: true }));
  await settle(window);
  $('results').querySelector('button.subscribe').click();
  await settle(window);
  assert.equal($('confirm-modal').hidden, true);
  assert.equal(log.requests.filter(r => r.method === 'POST').length, 1);
});

for (const name of ['api_key', 'password', 'secret']) {
  test(`subscribing a URL with a ${name} parameter asks first`, async () => {
    const feedUrl = `https://example.com/feed.xml?${name}=s3cret`;
    const { window, $, log } = await boot({
      storage: { 'rss-scout.settings.v1': { useRadar: false, checkHome: false } },
      secure: { 'rss-scout.miniflux.v1': { base: MINIFLUX, token: 'tok' } },
      responses: {
        ['GET ' + feedUrl]: { body: FEED },
        ['GET ' + MINIFLUX + '/v1/feeds']: { body: [] },
        ['POST ' + MINIFLUX + '/v1/feeds']: { status: 201, body: { feed_id: 4 } }
      }
    });
    $('url').value = feedUrl;
    $('search-form').dispatchEvent(new window.Event('submit', { cancelable: true }));
    await settle(window);
    $('results').querySelector('button.subscribe').click();
    await settle(window);
    assert.equal($('confirm-modal').hidden, false);
    assert.equal(log.requests.some(r => r.url.startsWith(MINIFLUX)), false);
  });
}

test('index.html declares a CSP that its own markup satisfies', () => {
  const html = source('index.html');
  const csp = html.match(/<meta http-equiv="Content-Security-Policy" content="([^"]+)">/)?.[1] || '';
  for (const directive of ["default-src 'self'", "script-src 'self'", "style-src 'self'", "object-src 'none'", "base-uri 'none'"]) {
    assert.ok(csp.split(/;\s*/).includes(directive), directive);
  }
  assert.equal(/'unsafe-(?:inline|eval)'/.test(csp), false);
  // No inline script, inline style block or style attribute that the policy would block.
  assert.equal(/<script(?![^>]*\ssrc=)[^>]*>/i.test(html), false);
  assert.equal(/<style\b|\sstyle=/i.test(html), false);
});
