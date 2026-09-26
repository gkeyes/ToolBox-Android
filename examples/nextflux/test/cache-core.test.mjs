import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createArticleCache, ROOT_KEY } from "../src/toolbox/cache.js";
import { LEGACY_MANIFEST_KEY, LEGACY_PREFIX } from "../src/toolbox/cache-legacy.js";

const ACCOUNT={serverUrl:"https://miniflux.example",userId:"7"};
const FEED={id:7,title:"测试订阅",url:"https://example.test/feed",site_url:"https://example.test",categoryId:2};
const CATEGORY={id:2,title:"测试分类"};
const SYNCED_AT="2026-09-26T03:30:00.000Z";

const digest=async(value)=>createHash("sha256").update(JSON.stringify(value)).digest("hex");
const clone=(value)=>value===undefined?undefined:structuredClone(value);

function memoryStorage(initial={}){
  const values=new Map(Object.entries(initial).map(([key,value])=>[key,clone(value)]));
  let loseNextRootReply=false;
  return {
    values,
    loseNextRootReply(){loseNextRootReply=true;},
    async getMany(keys){return keys.map(key=>values.has(key)?clone(values.get(key)):null);},
    async apply(change={}){
      for(const item of change.set||[])values.set(item.key,clone(item.value));
      for(const key of change.remove||[])values.delete(key);
      if(loseNextRootReply&&(change.set||[]).some(item=>item.key===ROOT_KEY)){
        loseNextRootReply=false;
        throw Object.assign(new Error("simulated lost native reply"),{code:"NETWORK_UNAVAILABLE"});
      }
    },
    async keys(){return [...values.keys()];},
  };
}

function article(overrides={}){
  return {
    id:101,feedId:7,title:"<b>缓存文章</b>",author:"作者",
    url:"https://example.test/article",content:"<p>正文第一段</p><p>正文第二段</p>",
    status:"unread",starred:0,published_at:SYNCED_AT,created_at:SYNCED_AT,
    reading_time:2,enclosures:[],changed_at:SYNCED_AT,...overrides,
  };
}

test("cache core persists article bodies and acknowledged state through a sync transaction",async()=>{
  const storage=memoryStorage();
  const cache=createArticleCache(storage,{autoGc:false,tokenPrefix:"cachecore",digest});
  await cache.initialize(ACCOUNT);
  await cache.updateCatalog({feeds:[FEED],categories:[CATEGORY]});

  const token=await cache.prepareSync({account:ACCOUNT,syncedAt:SYNCED_AT});
  await cache.applySyncBatch(token,[article()]);
  await cache.commitSync(token,{feeds:[FEED],categories:[CATEGORY]});

  const loaded=await cache.readArticle(101);
  assert.equal(loaded.content,"<p>正文第一段</p><p>正文第二段</p>");
  assert.equal(loaded.feed.id,7);
  assert.equal(loaded.titleText,"缓存文章");

  const patched=await cache.patchState([{id:101,status:"read",starred:1}]);
  assert.equal(patched.articles[0].status,"read");
  assert.equal(patched.articles[0].starred,1);
  const metadata=await cache.readMetadata(101);
  assert.equal(metadata.status,"read");
  assert.equal(metadata.starred,1);
  assert.equal((await cache.counts([7])).starred[7],1);
});

test("cache treats a durable root with a lost native reply as a successful commit",async()=>{
  const storage=memoryStorage();
  const cache=createArticleCache(storage,{autoGc:false,tokenPrefix:"cachelostreply",digest});
  await cache.initialize(ACCOUNT);
  storage.loseNextRootReply();
  await cache.updateCatalog({feeds:[FEED],categories:[CATEGORY]});
  assert.equal((await cache.getCatalog("feeds"))[0].id,7);
  assert.equal(storage.values.get(ROOT_KEY).revision,1);
});

test("legacy cache migrates once to v3 and garbage collection retires old parts",async()=>{
  const feedKey=`${LEGACY_PREFIX}feeds.3.0`;
  const metaKey=`${LEGACY_PREFIX}meta.3.0`;
  const storage=memoryStorage({
    [LEGACY_MANIFEST_KEY]:{
      version:2,generation:3,
      tables:{
        feeds:{codec:"json",keys:[feedKey]},
        meta:{codec:"json",keys:[metaKey]},
      },
    },
    [feedKey]:JSON.stringify([FEED]),
    [metaKey]:JSON.stringify({lastSyncTime:SYNCED_AT}),
  });
  const cache=createArticleCache(storage,{autoGc:false,tokenPrefix:"cachemigrate",digest});
  const meta=await cache.initialize(ACCOUNT);
  assert.equal(meta.version,3);
  assert.equal(meta.lastSyncTime,SYNCED_AT);
  assert.equal((await cache.getCatalog("feeds"))[0].title,FEED.title);
  assert.equal(storage.values.has(LEGACY_MANIFEST_KEY),false);
  assert.ok(storage.values.has(ROOT_KEY));

  await cache.collectGarbage();
  assert.equal(storage.values.has(feedKey),false);
  assert.equal(storage.values.has(metaKey),false);
});

test("persisted cache refuses a different account binding",async()=>{
  const storage=memoryStorage();
  const first=createArticleCache(storage,{autoGc:false,tokenPrefix:"cacheaccounta",digest});
  await first.initialize(ACCOUNT);
  await first.updateCatalog({feeds:[FEED],categories:[CATEGORY]});

  const second=createArticleCache(storage,{autoGc:false,tokenPrefix:"cacheaccountb",digest});
  await assert.rejects(
    second.initialize({...ACCOUNT,userId:"8"}),
    error=>error?.code==="ACCOUNT_CHANGED",
  );
});
