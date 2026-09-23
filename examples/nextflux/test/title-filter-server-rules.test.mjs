import test from 'node:test';
import assert from 'node:assert/strict';
import {FILTER_FIELD,createTitleRule,inspectTitleRules,existingServerRules,hasFeedRules,saveTitleKeywords,replaceTitleKeywords} from '../src/toolbox/title-filter/title-filter-rules.mjs';
const originals=()=>({id:42,blocklist_rules:'(?i)广告|抽奖',keeplist_rules:'Important|重要',block_filter_entry_rules:'EntryTitle=^公告$\r\nEntryContent=推广\r\nEntryURL=tracking\\.invalid',keep_filter_entry_rules:'EntryTag=科技'});
test('all four server rule fields display verbatim without modifying the feed',()=>{
 const feed=originals(),before=structuredClone(feed);
 assert.deepEqual(existingServerRules(feed).map(r=>[r.field,r.raw]),['blocklist_rules','keeplist_rules',FILTER_FIELD,'keep_filter_entry_rules'].map(f=>[f,feed[f]]));
 assert.deepEqual(feed,before);assert.deepEqual(inspectTitleRules(feed[FILTER_FIELD]).words,[]);
});
test('only exact managed line is represented by editable keywords',()=>{
 const feed={...originals(),[FILTER_FIELD]:'EntryTitle=^公告$\n'+createTitleRule(['广告','C++'])+'\nEntryAuthor=Somebody'};
 assert.equal(existingServerRules(feed).find(r=>r.field===FILTER_FIELD).raw,'EntryTitle=^公告$\nEntryAuthor=Somebody');
 assert.deepEqual(inspectTitleRules(feed[FILTER_FIELD]).words,['广告','C++']);
});
test('complex and multiple managed markers remain read-only source text',()=>{
 for(const raw of ['EntryTitle=(?i)(?P<nextflux_title_keywords>a.*)',createTitleRule(['one'])+'\n'+createTitleRule(['two'])]){
  assert.equal(existingServerRules({[FILTER_FIELD]:raw})[0].raw,raw);assert.throws(()=>inspectTitleRules(raw),{code:'CUSTOM_RULE'});
 }
});
test('unsupported advanced filtering still permits reading legacy rules',()=>{
 const feed={id:42,blocklist_rules:'中文|legacy',keeplist_rules:'重要'};
 assert.deepEqual(existingServerRules(feed).map(r=>r.raw),['中文|legacy','重要']);assert.equal(hasFeedRules(feed),true);
});
test('indicator includes per-feed rules without inventing global-state reads',()=>{
 for(const f of ['blocklist_rules','keeplist_rules',FILTER_FIELD,'keep_filter_entry_rules'])assert.equal(hasFeedRules({[f]:'rule'}),true);
 assert.equal(hasFeedRules({}),false);assert.equal(hasFeedRules(null),false);assert.equal(hasFeedRules({global_block_filter_entry_rules:'EntryTitle=global'}),false);
});
test('absent blank and malformed values never invent rules',()=>{
 for(const feed of [null,undefined,{}, {blocklist_rules:42,keeplist_rules:' \n ',[FILTER_FIELD]:[]}])assert.deepEqual(existingServerRules(feed),[]);
});
test('HTML-like and long regex strings remain data',()=>{
 const raw='<script>window.bad=true</script>|[A-Z]{5}'+'.*'.repeat(1000);
 assert.equal(existingServerRules({blocklist_rules:raw})[0].raw,raw);
});
test('save and clear preserve pre-existing fields and CRLF rules',async()=>{
 const remote=originals(),before=structuredClone(remote),patches=[];
 const api={read:async()=>structuredClone(remote),write:async patch=>{patches.push(patch);Object.assign(remote,patch);return structuredClone(remote);}};
 await saveTitleKeywords({feedId:42,text:'New|推广',baselineLine:null,api});
 for(const f of ['blocklist_rules','keeplist_rules','keep_filter_entry_rules'])assert.equal(remote[f],before[f]);
 assert.equal(remote[FILTER_FIELD],before[FILTER_FIELD]+'\r\n'+createTitleRule(['New','推广']));
 await saveTitleKeywords({feedId:42,text:'',baselineLine:inspectTitleRules(remote[FILTER_FIELD]).line,api});
 assert.deepEqual(remote,before);assert.equal(patches.length,2);assert.ok(patches.every(p=>Object.keys(p).join()===FILTER_FIELD));
});
test('final CRLF removal cannot add a stray CR to a prior anchored regex',()=>{
 const original='EntryTitle=^ads$\r\nEntryURL=blocked\\.invalid$';
 const combined=original+'\r\n'+createTitleRule(['old']);
 assert.equal(replaceTitleKeywords(combined,''),original);assert.equal(existingServerRules({[FILTER_FIELD]:combined})[0].raw,original);
});
test('first and middle CRLF record removal retain complete other rules',()=>{
 const line=createTitleRule(['old']);
 assert.equal(replaceTitleKeywords(line+'\r\nEntryTitle=ads\r\nEntryURL=example',''),'EntryTitle=ads\r\nEntryURL=example');
 assert.equal(replaceTitleKeywords('EntryTitle=ads\r\n'+line+'\r\nEntryURL=example',''),'EntryTitle=ads\r\nEntryURL=example');
});
