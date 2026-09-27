import fs from 'node:fs';import vm from 'node:vm';import assert from 'node:assert/strict';
const ctx={URL,URLSearchParams,console,DOMException};ctx.globalThis=ctx;ctx.window=ctx;vm.createContext(ctx);
vm.runInContext(fs.readFileSync(new URL('./core.js',import.meta.url),'utf8'),ctx);
vm.runInContext(fs.readFileSync(new URL('./rules.js',import.meta.url),'utf8'),ctx);
const privatePayload=fs.readFileSync(new URL('./private-rules.json',import.meta.url),'utf8');
const radarPayload=JSON.stringify({'example.com':{_name:'Example','.':[{title:'Example Feed',source:['/:id'],target:'/example/:id'}]}});
const privateURL='https://rules.example/rss-scout-rules.json';
const calls=[];
const network={request:async(url)=>{calls.push(url); if(url===privateURL)return {status:200,text:privatePayload}; if(url.includes('raw.githubusercontent.com/DIYgod/RSSHub'))return {status:200,text:radarPayload}; return {status:404,text:''};}};
const settings={hubBase:'https://rsshub.example',workerBase:'https://worker.example',ruleMode:'auto',ruleUrl:'',usePrivateRules:true,privateRuleUrl:privateURL};
const cache=await ctx.RSSScoutRules.sync(network,settings,'',new AbortController().signal);
assert.ok(cache.rules.length>=10);assert.ok(cache.source.includes('私人规则订阅'));assert.ok(cache.source.includes('GitHub 官方构建'));assert.equal(ctx.RSSScoutRules.cacheValid(cache,settings),true);
const urls=ctx.RSSScoutCore.radarCandidates('https://telega.io/c/readhub_cn',cache.rules,settings.hubBase,settings.workerBase).map(x=>x.url);
assert.deepEqual(urls,[
  'https://rsshub.example/telegram/channel/readhub_cn',
  'https://worker.example/rss/telegram/channel/readhub_cn'
]);
const actionUrls=ctx.RSSScoutCore.radarCandidates('https://github.com/ReChronoRain/HyperCeiler/actions/workflows/ci_build.yml',cache.rules,settings.hubBase,settings.workerBase).map(x=>x.url);
assert.deepEqual(actionUrls,['https://worker.example/rss/github/actions/ReChronoRain/HyperCeiler/ci_build.yml']);
const official=ctx.RSSScoutCore.radarCandidates('https://example.com/42',cache.rules,settings.hubBase,settings.workerBase).map(x=>x.url);
assert.deepEqual(official,['https://rsshub.example/example/42']);
console.log('combined dual-service rule sync passed:',cache.rules.length,'rules',calls.length,'requests');
