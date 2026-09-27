import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const ctx={URL,URLSearchParams,console};
ctx.globalThis=ctx;
ctx.window=ctx;
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(new URL('./core.js',import.meta.url),'utf8'),ctx);

const C=ctx.RSSScoutCore;
const payload=JSON.parse(fs.readFileSync(new URL('./private-rules.json',import.meta.url),'utf8'));
const imported=C.importScoutRules(payload);
assert.ok(imported.rules.length>=10);
assert.equal(imported.rules.some(rule=>Object.hasOwn(rule,'service')),false);

const hub='https://rsshub.example';
const worker='https://worker.example';
const route=(u,w=worker)=>C.radarCandidates(u,imported.rules,hub,w).map(x=>x.url);
const same=(actual,expected,message)=>assert.equal(JSON.stringify(actual),JSON.stringify(expected),message);

same(route('https://t.me/inside1024'),[
  'https://rsshub.example/telegram/channel/inside1024',
  'https://worker.example/rss/telegram/channel/inside1024'
]);
same(route('https://telega.io/c/readhub_cn'),[
  'https://rsshub.example/telegram/channel/readhub_cn',
  'https://worker.example/rss/telegram/channel/readhub_cn'
]);
same(route('https://space.bilibili.com/2267573/dynamic'),[
  'https://rsshub.example/bilibili/user/dynamic/2267573',
  'https://worker.example/rss/bilibili/user/dynamic/2267573'
]);
same(route('https://www.xiaohongshu.com/user/profile/abc123'),[
  'https://rsshub.example/xiaohongshu/user/abc123/notes',
  'https://worker.example/rss/xiaohongshu/user/abc123'
]);
same(route('https://github.com/ReChronoRain/HyperCeiler/actions/workflows/ci_build.yml'),[
  'https://worker.example/rss/github/actions/ReChronoRain/HyperCeiler/ci_build.yml'
]);
same(route('https://t.me/inside1024',''),[
  'https://rsshub.example/telegram/channel/inside1024'
]);

for(const bad of ['https://t.me/share','https://t.me/proxy','https://t.me/joinchat','https://t.me/c/123/456','https://t.me/+abcdef']) {
  same(route(bad),[],bad);
}

const legacy=C.importScoutRules({
  schemaVersion:1,
  rules:[{host:'example.com',source:'/:id',target:'/legacy/:id',service:'private'}]
});
same(C.radarCandidates('https://example.com/a',legacy.rules,hub,worker).map(x=>x.url),[
  'https://worker.example/legacy/a'
]);

console.log('private rules dual-service tests passed:', imported.rules.length);
