import test from 'node:test';
import assert from 'node:assert/strict';
import { loadScripts, plain } from './helpers.mjs';

const C = loadScripts(['core.js']).RSSScoutCore;
const page = (head, body = '') => '<!doctype html><html><head><title>Blog</title>' + head + '</head><body>' + body + '</body></html>';

test('finds declared alternate feeds and resolves them against <base href>', () => {
  const html = page(
    '<base href="https://cdn.example.com/site/">' +
      '<link rel="alternate" type="application/rss+xml" title="Posts" href="feed.xml">' +
      '<link rel="alternate" type="text/html" hreflang="en" href="/en/">' +
      '<link rel="stylesheet" href="style.css">'
  );
  const result = C.extractPage(html, 'https://example.com/post/1', {});
  assert.equal(result.title, 'Blog');
  assert.equal(result.base, 'https://cdn.example.com/site/');
  assert.deepEqual(plain(result.candidates), [
    { url: 'https://cdn.example.com/site/feed.xml', title: 'Posts', sources: ['网页声明'], kind: 'native', type: 'application/rss+xml' }
  ]);
});

test('reads HTTP Link headers, including quoted parameters and multiple links', () => {
  const headers = { Link: '<https://example.com/a,b>; rel="preload", </atom.xml>; rel="alternate"; type="application/atom+xml"' };
  const result = C.extractPage('not html', 'https://example.com/x', headers);
  assert.deepEqual(plain(result.candidates).map(c => [c.url, c.sources[0], c.type]), [
    ['https://example.com/atom.xml', 'HTTP Link', 'application/atom+xml']
  ]);
  assert.equal(result.title, '');
});

test('collects body links by feed-like path or label, merges duplicates and skips unsafe schemes', () => {
  const html = page(
    '<link rel="alternate" type="application/rss+xml" href="/rss.xml">',
    '<a href="/rss.xml">RSS</a><a href="/subscribe">Atom feed</a><a href="/about">About</a>' +
      '<a href="javascript:alert(1)">RSS</a><a href="https://user:pw@example.com/feed">feed</a>'
  );
  const found = plain(C.extractPage(html, 'https://example.com/', {}).candidates);
  assert.deepEqual(found.map(c => c.url), ['https://example.com/rss.xml', 'https://example.com/subscribe']);
  assert.deepEqual(found[0].sources, ['网页声明', '页面链接']);
});

test('derives a YouTube channel feed from channel metadata', () => {
  const id = 'UC' + 'a'.repeat(22);
  const html = page('<meta itemprop="channelId" content="' + id + '">');
  const [feed] = plain(C.extractPage(html, 'https://www.youtube.com/@someone', {}).candidates);
  assert.equal(feed.url, 'https://www.youtube.com/feeds/videos.xml?channel_id=' + id);
  assert.equal(feed.sources[0], '频道元数据');
  assert.equal(C.extractPage(page('<meta itemprop="channelId" content="bogus">'), 'https://www.youtube.com/x', {}).candidates.length, 0);
});
