import test from 'node:test';
import assert from 'node:assert/strict';
import { loadScripts, plain } from './helpers.mjs';

const ctx = loadScripts(['core.js', 'miniflux.js']);
const BASE = 'https://reader.example.com/miniflux';
const TOKEN = 'secret-token-123';

// Fake RSSScoutNetwork: routes "METHOD /v1/path" to handlers and records every request.
function client(routes, account = {}) {
  const calls = [];
  const network = {
    async request(url, options) {
      const path = url.slice(BASE.length);
      calls.push({ url, path, method: options.method, body: options.body, headers: options.headers });
      const handler = routes[options.method + ' ' + path];
      if (!handler) return { status: 404, url, text: '{"error_message":"not found"}' };
      const reply = await handler(options.body, calls.length);
      return { url: reply.url || url, status: reply.status || 200, text: typeof reply.text === 'string' ? reply.text : JSON.stringify(reply.json) };
    }
  };
  return { mf: new ctx.RSSScoutMiniflux(network, { base: BASE, token: TOKEN, ...account }), calls };
}
const feedList = (...urls) => () => ({ json: urls.map((feed_url, i) => ({ id: i + 1, feed_url, title: 'F' + i })) });

test('connect sends the token to the configured sub-path and keeps only valid categories', async () => {
  const { mf, calls } = client({
    'GET /v1/me': () => ({ json: { id: 7, username: 'alice' } }),
    'GET /v1/categories': () => ({ json: [{ id: 1, title: 'News' }, { id: 0, title: 'bad' }, null, { id: 3, title: '' }] })
  });
  assert.deepEqual(plain(await mf.connect()), { username: 'alice', categories: [{ id: 1, title: 'News' }, { id: 3, title: '分类 3' }] });
  assert.equal(calls[0].url, BASE + '/v1/me');
  assert.equal(calls[0].headers['X-Auth-Token'], TOKEN);
});

test('API errors carry the status, a hint, and never echo the token', async () => {
  const { mf } = client({
    'GET /v1/me': () => ({ status: 401, json: { error_message: 'bad token ' + TOKEN } }),
    'GET /v1/categories': () => ({ status: 403, text: '<html>denied</html>' })
  });
  await assert.rejects(mf.connect(), error => error.status === 401 && error.message.includes('[已隐藏 Token]') && !error.message.includes(TOKEN));
  await assert.rejects(mf.categories(), error => error.status === 403 && /拒绝访问/.test(error.message));
});

test('rejects non-JSON success bodies, redirects and malformed paths', async () => {
  await assert.rejects(client({ 'GET /v1/me': () => ({ text: '<html>login</html>' }) }).mf.connect(), /未返回 JSON/);
  await assert.rejects(client({ 'GET /v1/me': () => ({ url: 'https://reader.example.com/login', json: {} }) }).mf.connect(), /重定向/);
  await assert.rejects(client({}).mf.api('/v1/../admin'), /无效 API 路径/);
  await assert.rejects(new ctx.RSSScoutMiniflux({}, null).api('/v1/me'), /先在设置中填写/);
});

test('subscribe reuses an existing feed matched through an alias without creating a new one', async () => {
  const { mf, calls } = client({ 'GET /v1/feeds': feedList('https://example.com/feed.xml') });
  const result = await mf.subscribe({ url: 'https://example.com/new-feed', aliases: ['https://example.com/feed.xml'] });
  assert.deepEqual(plain(result), { id: 1, existing: true });
  assert.equal(calls.some(c => c.method === 'POST'), false);
});

test('subscribe creates once with the saved category, even when tapped twice', async () => {
  const { mf, calls } = client(
    { 'GET /v1/feeds': feedList(), 'POST /v1/feeds': () => ({ status: 201, json: { feed_id: 42 } }) },
    { category: 5 }
  );
  const candidate = { url: 'https://example.com/rss', title: 'T' };
  const [a, b] = await Promise.all([mf.subscribe(candidate), mf.subscribe(candidate)]);
  assert.deepEqual(plain(a), { id: 42, existing: false });
  assert.equal(a, b);
  const posts = calls.filter(c => c.method === 'POST');
  assert.equal(posts.length, 1);
  assert.deepEqual(plain(posts[0].body), { feed_url: 'https://example.com/rss', category_id: 5 });
  // The new feed is cached, so a later subscribe of the same URL is recognised without a POST.
  assert.equal((await mf.subscribe(candidate)).existing, true);
});

test('a failed create is reconciled against the server and marked uncertain only for unknown outcomes', async () => {
  let created = false;
  const reconciled = client({
    'GET /v1/feeds': () => feedList(...(created ? ['https://example.com/rss'] : []))(),
    'POST /v1/feeds': () => { created = true; return { status: 502, text: 'bad gateway' }; }
  });
  assert.deepEqual(plain(await reconciled.mf.subscribe({ url: 'https://example.com/rss' })), { id: 1, existing: true, reconciled: true });

  const lost = client({ 'GET /v1/feeds': feedList(), 'POST /v1/feeds': () => ({ status: 500, json: { error_message: 'boom' } }) });
  await assert.rejects(lost.mf.subscribe({ url: 'https://example.com/rss' }), error => error.uncertain === true);

  const rejected = client({ 'GET /v1/feeds': feedList(), 'POST /v1/feeds': () => ({ status: 400, json: { error_message: 'invalid feed' } }) });
  await assert.rejects(rejected.mf.subscribe({ url: 'https://example.com/rss' }), error => error.status === 400 && !error.uncertain);

  const noId = client({ 'GET /v1/feeds': feedList(), 'POST /v1/feeds': () => ({ status: 201, json: {} }) });
  await assert.rejects(noId.mf.subscribe({ url: 'https://example.com/rss' }), /feed_id/);
});

test('discover keeps only valid URLs and labels them as Miniflux results', async () => {
  const { mf, calls } = client({
    'POST /v1/discover': () => ({ json: [{ url: 'https://example.com/a.xml', title: 'A', type: 'rss' }, { url: 'ftp://x' }, { url: 'https://example.com/b' }] })
  });
  const found = plain(await mf.discover('https://example.com/'));
  assert.deepEqual(found.map(f => [f.url, f.title]), [['https://example.com/a.xml', 'A'], ['https://example.com/b', 'Miniflux 发现的源']]);
  assert.deepEqual(plain(calls[0].body), { url: 'https://example.com/' });
});
