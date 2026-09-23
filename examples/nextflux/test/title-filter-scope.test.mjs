import { test } from 'node:test';
import assert from 'node:assert/strict';
import { articleIdFromHash, resolveArticleFilterScope as scope, isArticleFilterScopeCurrent as current } from '../src/toolbox/title-filter/article-title-filter.mjs';

const entry = {id:900,feedId:42};
for (const [name, value] of [['cache feedId',entry],['API nested feed',{id:900,feed:{id:42}}],['API feed_id',{id:'900',feed_id:'42'}],['consistent fields',{id:900,feedId:42,feed_id:'42',feed:{id:42}}]]) {
 test(name,()=>assert.deepEqual(scope(value,'900'),{articleId:900,feedId:42,key:'900:42'}));
}
for (const hash of ['#/article/900','#/category/8/article/900','#/feed/99/article/900','#/article/900?filter=starred','#/feed/99/article/900/']) {
 test(`entry owns target in ${hash}`,()=>{
  assert.equal(articleIdFromHash(hash),900);
  assert.equal(current(scope(entry,900),entry,hash),true);
  assert.equal(scope(entry,articleIdFromHash(hash)).feedId,42);
 });
}
for (const [name,value,route] of [['missing entry',null,'900'],['loading another entry',entry,'901'],['no entry route',entry,undefined],['invalid route',entry,'900x'],['no source',{id:900},900],['conflicting fields',{id:900,feedId:42,feed:{id:43}},900],['invalid explicit field',{id:900,feedId:0,feed:{id:42}},900],['negative feed',{id:900,feedId:-1},900],['numeric overflow',{id:900,feedId:Number.MAX_SAFE_INTEGER+1},900],['boolean source',{id:900,feedId:true},900],['object source',{id:900,feedId:{}},900],['invalid string',{id:900,feedId:'42abc'},900]]) {
 test(`fail closed: ${name}`,()=>assert.equal(scope(value,route),null));
}
test('article change invalidates previous editor even within one feed',()=>{
 const old=scope(entry,900);
 assert.equal(current(old,{id:902,feedId:42},'#/article/902'),false);
 assert.equal(current(old,entry,'#/article/902'),false);
 assert.equal(current(old,{id:902,feedId:42},'#/article/900'),false);
 assert.equal(current(old,entry,'#/feed/42'),false);
});
test('source correction invalidates the captured scope',()=>assert.equal(current(scope(entry,900),{id:900,feedId:43},'#/article/900'),false));
test('read and star changes do not invalidate the source',()=>assert.equal(current(scope(entry,900),{...entry,status:'read',starred:1},'#/article/900'),true));
test('reject non-article route and malformed identifiers',()=>{
 for (const hash of ['',null,'#/feed/900','#/article/900x','#/article/-900','#/article/900/other','#/article/9007199254740992']) assert.equal(articleIdFromHash(hash),null);
});
