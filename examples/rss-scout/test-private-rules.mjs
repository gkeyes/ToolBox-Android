import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const ctx={URL,URLSearchParams,console};ctx.globalThis=ctx;ctx.window=ctx;vm.createContext(ctx);
vm.runInContext(fs.readFileSync(new URL('./core.js',import.meta.url),'utf8'),ctx);
const C=ctx.RSSScoutCore;
const payload=JSON.parse(fs.readFileSync(new URL('./private-rules.json',import.meta.url),'utf8'));
const imported=C.importScoutRules(payload);
assert.ok(imported.rules.length>=10);
assert.equal(imported.rules.some(rule=>Object.hasOwn(rule,'service')),false);
const hub='https://rsshub.example';
const worker='https://worker.example';
const route=(u,w=worker)=>C.radarCandidates(u,imported.rules,hub,w).map(x=>x.url);
assert.equal(JSON.stringify(route('https://t.me/inside1024'),[
  'https://rsshub.example/telegram/channel/inside1024'),JSON.stringify('https://worker.example/rss/telegram/channel/inside1024'
]));
assert.equal(JSON.stringify(route('https://telega.io/c/readhub_cn'),[
  'https://rsshub.example/telegram/channel/readhub_cn'),JSON.stringify('https://worker.example/rss/telegram/channel/readhub_cn'
]));
assert.equal(JSON.stringify(route('https://space.bilibili.com/2267573/dynamic'),[
  'https://rsshub.example/bilibili/user/dynamic/2267573'),JSON.stringify('https://worker.example/rss/bilibili/user/dynamic/2267573'
]));
assert.equal(JSON.stringify(route('https://www.xiaohongshu.com/user/profile/abc123'),[
  'https://rsshub.example/xiaohongshu/user/abc123/notes'),JSON.stringify('https://worker.example/rss/xiaohongshu/user/abc123'
]));
assert.equal(JSON.stringify(route('https://github.com/ReChronoRain/HyperCeiler/actions/workflows/ci_build.yml')),JSON.stringify([
  'https://worker.example/rss/github/actions/ReChronoRain/HyperCeiler/ci_build.yml'
]));
assert.equal(JSON.stringify(route('https://t.me/inside1024','')),JSON.stringify([
  'https://rsshub.example/telegram/channel/inside1024'
]));
for(const bad of ['https://t.me/share','https://t.me/proxy','https://t.me/joinchat','https://t.me/c/123/456','https://t.me/+abcdef']) assert.equal(JSON.stringify(route(bad),[]),JSON.stringify(bad));
const legacy=C.importScoutRules({schemaVersion:1,rules:[{host:'example.com',source:'/:id',target:'/legacy/:id',service:'private'}]});
assert.equal(JSON.stringify(C.radarCandidates('https://example.com/a',legacy.rules,hub,worker).map(x=>x.url)),JSON.stringify(['https://worker.example/legacy/a']));
console.log('private rules dual-service tests passed:', imported.rules.length);
