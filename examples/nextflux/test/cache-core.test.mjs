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
  let failNextRootWrite=false;
  return {
    values,
    loseNextRootReply(){loseNextRootReply=true;},
    failNextRootWrite(){failNextRootWrite=true;},
    async getMany(keys){return keys.map(key=>values.has(key)?clone(values.get(key)):null);},
    async apply(change={}){
      if(failNextRootWrite&&(change.set||[]).some(item=>item.key===ROOT_KEY)){
        failNextRootWrite=false;
        throw Object.assign(new Error("simulated failed root write"),{code:"NETWORK_UNAVAILABLE"});
      }
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

const catalogs=(extra={})=>({feeds:[FEED],categories:[CATEGORY],...extra});
const readArticle=(id,extra={})=>article({id,status:"read",content:`<p>第 ${id} 条的完整正文</p>`,...extra});
const ids=async(cache)=>(await cache.selectIds({})).sort((a,b)=>a-b);

async function seedArticles(rows,{storage=memoryStorage(),catalog=catalogs()}={}){
  const cache=createArticleCache(storage,{autoGc:false,tokenPrefix:"retentionseed",digest});
  await cache.initialize(ACCOUNT);
  const token=await cache.prepareSync({account:ACCOUNT,syncedAt:SYNCED_AT});
  await cache.applySyncBatch(token,rows);
  await cache.commitSync(token,catalog);
  return {cache,storage};
}

async function resyncArticles(cache,rows=[],catalog=catalogs()){
  const token=await cache.prepareSync({account:ACCOUNT,syncedAt:"2026-09-27T03:30:00.000Z"});
  await cache.applySyncBatch(token,rows);
  await cache.commitSync(token,catalog);
}

async function reopenCache(storage){
  const reopened=createArticleCache(storage,{autoGc:false,tokenPrefix:"retentionreopened",digest});
  await reopened.initialize(ACCOUNT);
  return reopened;
}

test("recent window retains all unread and starred articles with durable bodies after GC and restart",async()=>{
  const {cache,storage}=await seedArticles([
    readArticle(1),readArticle(2,{status:"unread"}),readArticle(3,{starred:1}),
    readArticle(4),readArticle(5,{status:"unread"}),readArticle(6),
  ]);
  await resyncArticles(cache,[],catalogs({recentEntryIds:[4,5],protectedArticleIds:[6]}));
  assert.deepEqual(await ids(cache),[2,3,4,5,6]);
  assert.deepEqual(await cache.counts([7]),{unread:{7:2},starred:{7:1}});
  const beforeGc=storage.values.size;
  await cache.collectGarbage();
  assert.ok(storage.values.size<beforeGc,"retired article and index documents are collected");
  const reopened=await reopenCache(storage);
  assert.deepEqual(await ids(reopened),[2,3,4,5,6]);
  assert.equal(await reopened.readArticle(1),null);
  for(const id of [2,3,4,5,6])assert.equal((await reopened.readArticle(id)).content,readArticle(id).content);
  assert.equal((await reopened.readArticle(3)).starred,1);
});

test("smaller recent windows retire old read entries and larger fetched windows restore them",async()=>{
  const rows=Array.from({length:8},(_,index)=>readArticle(index+1));
  const {cache}=await seedArticles(rows);
  await resyncArticles(cache,[],catalogs({recentEntryIds:[6,7,8]}));
  assert.deepEqual(await ids(cache),[6,7,8]);
  await resyncArticles(cache,[],catalogs({recentEntryIds:[8]}));
  assert.deepEqual(await ids(cache),[8]);
  await cache.collectGarbage();
  await resyncArticles(cache,rows.slice(1),catalogs({recentEntryIds:[2,3,4,5,6,7,8]}));
  assert.deepEqual(await ids(cache),[2,3,4,5,6,7,8]);
  assert.equal((await cache.readArticle(2)).content,readArticle(2).content);
});

test("omitting the recent window preserves the existing archive policy",async()=>{
  const {cache}=await seedArticles([readArticle(1),readArticle(2)]);
  await resyncArticles(cache,[readArticle(3)],catalogs({protectedArticleIds:[]}));
  assert.deepEqual(await ids(cache),[1,2,3]);
  await resyncArticles(cache,[],catalogs({recentEntryIds:[]}));
  assert.deepEqual(await ids(cache),[],"an explicit successful empty window can retire ordinary read history");
});

test("aborting a prepared recent-window sync preserves the original snapshot and checkpoint",async()=>{
  const {cache,storage}=await seedArticles([readArticle(1),readArticle(2)]);
  const before=await cache.meta();
  const token=await cache.prepareSync({account:ACCOUNT,syncedAt:"2026-09-28T03:30:00.000Z"});
  await cache.applySyncBatch(token,[readArticle(3)]);
  await cache.prepareSyncCommit(token,catalogs({recentEntryIds:[3]}));
  assert.deepEqual(await ids(cache),[1,2],"preparation is not publication");
  await cache.abortSync(token);
  await cache.collectGarbage();
  const reopened=await reopenCache(storage);
  assert.deepEqual(await reopened.meta(),before);
  assert.deepEqual(await ids(reopened),[1,2]);
  assert.equal((await reopened.readArticle(1)).content,readArticle(1).content);
  assert.equal(await reopened.readArticle(3),null);
});

test("a failed recent-window root write leaves original articles readable through GC and restart",async()=>{
  const {cache,storage}=await seedArticles([readArticle(1),readArticle(2)]);
  const before=await cache.meta();
  const token=await cache.prepareSync({account:ACCOUNT,syncedAt:"2026-09-28T03:30:00.000Z"});
  await cache.applySyncBatch(token,[readArticle(3)]);
  const catalog=catalogs({recentEntryIds:[3]});
  await cache.prepareSyncCommit(token,catalog);
  storage.failNextRootWrite();
  await assert.rejects(cache.commitSync(token,catalog),error=>error.code==="NETWORK_UNAVAILABLE");
  assert.deepEqual(await cache.meta(),before);
  assert.deepEqual(await ids(cache),[1,2]);
  await cache.collectGarbage();
  const reopened=await reopenCache(storage);
  assert.deepEqual(await reopened.meta(),before);
  assert.equal((await reopened.readArticle(2)).content,readArticle(2).content);
  assert.equal(await reopened.readArticle(3),null);
});

test("acknowledged stars and read states after preparation restore complete retired rows",async()=>{
  const {cache,storage}=await seedArticles([readArticle(1),readArticle(2),readArticle(4)]);
  const token=await cache.prepareSync({account:ACCOUNT,syncedAt:"2026-09-28T03:30:00.000Z"});
  const updated=readArticle(1,{content:"<p>同步获取到的更新正文</p>",changed_at:"2026-09-28T02:00:00.000Z"});
  await cache.applySyncBatch(token,[updated,readArticle(3)]);
  const catalog=catalogs({recentEntryIds:[3]});
  await cache.prepareSyncCommit(token,catalog);
  await cache.patchState([{id:1,starred:1},{id:2,status:"read"},{id:4,status:"unread"}]);
  await cache.collectGarbage();
  await cache.commitSync(token,catalog);
  assert.deepEqual(await ids(cache),[1,2,3,4]);
  assert.equal((await cache.readArticle(1)).content,updated.content,"newly fetched body survives restoration");
  assert.equal((await cache.readArticle(1)).starred,1);
  assert.equal((await cache.readArticle(2)).status,"read","even a no-op acknowledged read is protected for this round");
  assert.equal((await cache.readArticle(4)).status,"unread");
  assert.deepEqual(await cache.counts([7]),{unread:{7:1},starred:{7:1}});
  await cache.collectGarbage();
  const reopened=await reopenCache(storage);
  assert.equal((await reopened.readArticle(1)).content,updated.content);
  assert.equal((await reopened.readArticle(2)).content,readArticle(2).content);
  assert.equal((await reopened.readArticle(4)).content,readArticle(4).content);
});

test("articles opened before and after preparation survive until a later unprotected sync",async()=>{
  const {cache,storage}=await seedArticles([1,2,3,4].map(id=>readArticle(id)));
  const token=await cache.prepareSync({account:ACCOUNT,syncedAt:"2026-09-28T03:30:00.000Z"});
  await cache.prepareSyncCommit(token,catalogs({recentEntryIds:[4],protectedArticleIds:[1]}));
  await cache.collectGarbage();
  await cache.commitSync(token,catalogs({recentEntryIds:[4],protectedArticleIds:[2]}));
  assert.deepEqual(await ids(cache),[1,2,4]);
  await cache.collectGarbage();
  const reopened=await reopenCache(storage);
  assert.equal((await reopened.readArticle(1)).content,readArticle(1).content);
  assert.equal((await reopened.readArticle(2)).content,readArticle(2).content);
  await resyncArticles(cache,[],catalogs({recentEntryIds:[4],protectedArticleIds:[]}));
  assert.deepEqual(await ids(cache),[4]);
});

test("late reading protection never revives server-removed articles or deleted feeds",async()=>{
  const otherFeed={...FEED,id:8,title:"将删除的订阅"};
  const {cache}=await seedArticles([readArticle(1),readArticle(2,{feedId:8}),readArticle(3)],{
    catalog:catalogs({feeds:[FEED,otherFeed]}),
  });
  const token=await cache.prepareSync({account:ACCOUNT,syncedAt:"2026-09-28T03:30:00.000Z"});
  await cache.applySyncBatch(token,[readArticle(1,{status:"removed"})]);
  await cache.prepareSyncCommit(token,catalogs({recentEntryIds:[3]}));
  await cache.commitSync(token,catalogs({recentEntryIds:[3],protectedArticleIds:[1,2]}));
  assert.deepEqual(await ids(cache),[3]);
  assert.equal(await cache.readArticle(1),null);
  assert.equal(await cache.readArticle(2),null);
});

test("invalid retention IDs fail without evicting local history",async()=>{
  const {cache}=await seedArticles([readArticle(1),readArticle(2)]);
  const token=await cache.prepareSync({account:ACCOUNT,syncedAt:SYNCED_AT});
  await assert.rejects(cache.prepareSyncCommit(token,catalogs({recentEntryIds:["2"]})),error=>error.code==="INVALID_REQUEST");
  await cache.abortSync(token);
  assert.deepEqual(await ids(cache),[1,2]);
});

test("incremental sync preserves counts without rebuilding them over every retained row", async () => {
  const builds = [];
  const cache = createArticleCache(memoryStorage(), {autoGc:false, digest, onIndexBuild: event => builds.push(event)});
  async function sync(rows, feeds) {
    const token = await cache.prepareSync({account: ACCOUNT, syncedAt: SYNCED_AT});
    await cache.applySyncBatch(token, rows);
    await cache.commitSync(token, {feeds, categories:[CATEGORY]});
  }
  const other = {...FEED, id:8};
  await sync([article(), article({id:102, starred:1})], [FEED, other]);
  builds.length = 0;
  await sync([article({id:102, feedId:8, status:"read", starred:1})], [other]);
  assert.deepEqual(await cache.counts(), {unread:{8:0}, starred:{8:1}});
  assert.equal(builds.filter(event=>event.kind==="counts").length, 0);
  assert.equal(await cache.readArticle(101), null);
});

test("short and long article bodies share the existing cache format after a restart", async () => {
  const storage = memoryStorage();
  const cache = createArticleCache(storage, { autoGc: false, digest });
  await cache.initialize(ACCOUNT);
  const rows = [article(), article({ id: 102, content: "<p>中😀文。</p>".repeat(150) })];
  const token = await cache.prepareSync({ account: ACCOUNT, syncedAt: SYNCED_AT });
  await cache.applySyncBatch(token, rows);
  await cache.commitSync(token, { feeds: [FEED], categories: [CATEGORY] });
  const headers = [...storage.values.values()];
  assert.ok(headers.some(row => row.codec === "json" && row.text?.includes(rows[0].content)));
  assert.ok(headers.some(row => row.codec === "gzip-base64"));
  const reopened = createArticleCache(storage, { autoGc: false, digest });
  await reopened.initialize(ACCOUNT);
  for (const row of rows) assert.equal((await reopened.readArticle(row.id)).content, row.content);
});

test("recent identical plain bodies reuse a digest without masking body or enclosure changes", async () => {
  const storage = memoryStorage();
  let hashes = 0;
  const cache = createArticleCache(storage, { autoGc: false, digest(body) { hashes++; return digest(body); } });
  await cache.initialize(ACCOUNT);
  async function sync(row) {
    const token = await cache.prepareSync({ account: ACCOUNT, syncedAt: SYNCED_AT });
    await cache.applySyncBatch(token, [row]);
    await cache.commitSync(token, { feeds: [FEED], categories: [CATEGORY] });
    assert.deepEqual((await cache.readArticle(row.id)).enclosures, row.enclosures);
    assert.equal((await cache.readArticle(row.id)).content, row.content);
  }
  await sync(article());
  await sync(article({ starred: 1 }));
  assert.equal(hashes, 1);
  await sync(article({ content: "<p>另一个正文</p>" }));
  await sync(article({ enclosures: [{ url: "https://example.test/audio.mp3" }] }));
  assert.equal(hashes, 3);
  await cache.clear();
  await cache.initialize(ACCOUNT);
  await sync(article());
  assert.equal(hashes, 4);
});
