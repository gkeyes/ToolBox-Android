import test from 'node:test';
import assert from 'node:assert/strict';
import { loadScripts } from './helpers.mjs';

const ctx = loadScripts(['core.js', 'network.js']);

// Fake ToolBox stream API: serves the given byte chunks one read at a time.
function toolbox(chunks, headers = {}, finalUrl = 'https://example.com/feed') {
  const calls = { open: [], cancelled: 0, reads: 0 };
  const api = {
    network: {
      async openStream(request) {
        calls.open.push(request);
        return { streamId: 's1', status: 200, headers: finalUrl ? { 'X-ToolBox-Final-URL': finalUrl, ...headers } : headers };
      },
      async readStream() {
        const index = calls.reads++;
        return index < chunks.length ? { data: Array.from(chunks[index]), done: index === chunks.length - 1 } : { done: true };
      },
      async cancelStream() { calls.cancelled++; }
    }
  };
  return { network: new ctx.RSSScoutNetwork(api, () => 1234), calls };
}
const bytes = (...parts) => Uint8Array.from(parts.flatMap(p => (typeof p === 'string' ? Array.from(Buffer.from(p, 'latin1')) : p)));
const GBK_ZHONGWEN = [0xd6, 0xd0, 0xce, 0xc4]; // "中文" in GBK
const BIG5_ZHONGWEN = [0xa4, 0xa4, 0xa4, 0xe5]; // "中文" in Big5

test('decodes with the charset named in Content-Type', async () => {
  const { network, calls } = toolbox([bytes('<p>', GBK_ZHONGWEN, '</p>')], { 'content-type': 'text/html; charset="GBK"' });
  const r = await network.request('https://example.com/feed');
  assert.equal(r.text, '<p>中文</p>');
  assert.equal(r.url, 'https://example.com/feed');
  assert.equal(calls.open[0].timeoutMs, 1234);
  assert.equal(calls.cancelled, 0);
});

test('falls back to the XML declaration, then <meta charset>, then UTF-8', async () => {
  let r = await toolbox([bytes('<?xml version="1.0" encoding="gb2312"?><t>', GBK_ZHONGWEN, '</t>')]).network.request('https://example.com/');
  assert.equal(r.text.endsWith('<t>中文</t>'), true);
  r = await toolbox([bytes('<html><head><meta charset=big5></head>', BIG5_ZHONGWEN)]).network.request('https://example.com/');
  assert.equal(r.text.endsWith('中文'), true);
  r = await toolbox([Buffer.from('<p>中文</p>')]).network.request('https://example.com/');
  assert.equal(r.text, '<p>中文</p>');
});

test('detects UTF-16 byte order marks', async () => {
  const le = Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from('<rss/>', 'utf16le')]);
  assert.equal((await toolbox([le]).network.request('https://example.com/')).text, '<rss/>');
  const be = Buffer.from(Buffer.from('<rss/>', 'utf16le')).swap16();
  assert.equal((await toolbox([Buffer.concat([Buffer.from([0xfe, 0xff]), be])]).network.request('https://example.com/')).text, '<rss/>');
});

test('an unknown charset label decodes as UTF-8 instead of failing', async () => {
  const r = await toolbox([Buffer.from('ok 中文')], { 'Content-Type': 'text/xml; charset=x-made-up' }).network.request('https://example.com/');
  assert.equal(r.text, 'ok 中文');
});

test('joins chunks before decoding so split multi-byte characters survive', async () => {
  const all = Buffer.from('标题');
  const r = await toolbox([all.subarray(0, 2), all.subarray(2, 4), all.subarray(4)]).network.request('https://example.com/');
  assert.equal(r.text, '标题');
});

test('refuses plain HTTP and responses without a final URL', async () => {
  await assert.rejects(toolbox([]).network.request('http://example.com/'), /HTTP/);
  await assert.rejects(toolbox([bytes('x')], {}, '').network.request('https://example.com/'), /最终响应网址/);
});

test('cancels the host stream when aborted mid-read', async () => {
  const controller = new AbortController();
  const { network, calls } = toolbox([bytes('a'), bytes('b'), bytes('c')]);
  const original = network.api.network.readStream;
  network.api.network.readStream = async id => { const part = await original(id); controller.abort(); return part; };
  await assert.rejects(network.request('https://example.com/', { signal: controller.signal }), { name: 'AbortError' });
  assert.equal(calls.cancelled, 1);
});
