import test from 'node:test';
import assert from 'node:assert/strict';
import { loadScripts, plain } from './helpers.mjs';

const C = loadScripts(['core.js']).RSSScoutCore;
const rss = (prolog = '', items = '<item><title>One</title></item><item><title>Two</title></item>') =>
  prolog + '<rss version="2.0"><channel><title>Example</title><link>https://example.com/</link>' + items + '</channel></rss>';

test('parses RSS 2.0 with title, item count and preview', () => {
  assert.deepEqual(plain(C.parseFeed(rss())), { type: 'RSS', title: 'Example', count: 2, preview: ['One', 'Two'] });
});

test('parses namespaced Atom and RSS 1.0, ignoring a leading BOM', () => {
  const atom = '﻿  <feed xmlns="http://www.w3.org/2005/Atom"><title>A</title><entry><title>E1</title></entry></feed>';
  assert.deepEqual(plain(C.parseFeed(atom)), { type: 'Atom', title: 'A', count: 1, preview: ['E1'] });
  const rdf = '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/">' +
    '<channel rdf:about="x"><title>R</title></channel><item rdf:about="1"><title>I1</title></item></rdf:RDF>';
  assert.deepEqual(plain(C.parseFeed(rdf)), { type: 'RSS 1.0', title: 'R', count: 1, preview: ['I1'] });
});

test('rejects Atom without its namespace, HTML pages and malformed XML', () => {
  assert.equal(C.parseFeed('<feed><title>A</title></feed>'), null);
  assert.equal(C.parseFeed('<!doctype html><html><head><title>Page</title></head><body></body></html>'), null);
  assert.equal(C.parseFeed('<rss><channel><title>x</title></rss>'), null);
  assert.equal(C.parseFeed(''), null);
  assert.equal(C.parseFeed('plain text'), null);
});

test('parses JSON Feed 1 / 1.1 and rejects other JSON', () => {
  const feed = { version: 'https://jsonfeed.org/version/1.1', title: 'J', items: [{ title: 'x' }, { content_text: 'y' }, null] };
  assert.deepEqual(plain(C.parseFeed(JSON.stringify(feed))), { type: 'JSON Feed', title: 'J', count: 3, preview: ['x', 'y', '无标题条目'] });
  assert.equal(C.parseFeed(JSON.stringify({ ...feed, version: 'https://jsonfeed.org/version/2' })), null);
  assert.equal(C.parseFeed('{not json'), null);
});

test('accepts feeds that carry a plain DOCTYPE declaration', () => {
  const netscape = '<!DOCTYPE rss PUBLIC "-//Netscape Communications//DTD RSS 0.91//EN" "http://my.netscape.com/publish/formats/rss-0.91.dtd">\n';
  assert.equal(C.parseFeed(rss(netscape))?.title, 'Example');
  assert.equal(C.parseFeed(rss('<?xml version="1.0" encoding="utf-8"?>\n<!-- generator -->\n<!DOCTYPE rss>\n'))?.count, 2);
  assert.equal(C.parseFeed(rss("<!DOCTYPE rss SYSTEM 'rss.dtd'>"))?.type, 'RSS');
});

test('keeps feeds whose item content mentions a DOCTYPE', () => {
  const items = '<item><title>HTML</title><description><![CDATA[<!DOCTYPE html><p>x</p>]]></description></item>';
  assert.equal(C.parseFeed(rss('', items))?.count, 1);
});

test('still refuses internal DTD subsets and entity declarations', () => {
  assert.equal(C.parseFeed(rss('<!DOCTYPE rss [<!ENTITY a "aaaa">]>')), null);
  assert.equal(C.parseFeed(rss('<?xml version="1.0"?><!DOCTYPE rss [\n]>')), null);
  assert.equal(C.parseFeed('<!ENTITY a "x">' + rss()), null);
});
