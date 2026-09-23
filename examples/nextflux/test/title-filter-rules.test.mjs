import {test} from 'node:test';
import assert from 'node:assert/strict';
import {keywordsFromText,createTitleRule,inspectTitleRules,replaceTitleKeywords,inspectFeed,saveTitleKeywords,FILTER_FIELD,RULE_PREFIX} from '../src/toolbox/title-filter/title-filter-rules.mjs';
import {createTitleFilterApi} from '../src/toolbox/title-filter/title-filter-api.mjs';
const managed=text=>createTitleRule(keywordsFromText(text));
const feed=(rules='',other={})=>({id:42,[FILTER_FIELD]:rules,...other});

test('pipes, fullwidth separators and line breaks normalize without empty alternatives',()=>{
 assert.deepEqual(keywordsFromText(' | 广告 |抽奖|| 广告｜推广\n优惠\r\n '),['广告','抽奖','推广','优惠']);
 assert.equal(managed('|||  \n'),'');
});
test('Chinese, Unicode and regex punctuation are literal and round-trip',()=>{
 const words=['C++','[广告]','a.b','(test)?','^start$','价格$10','a\\b','💡','#推广','a=b','{foo}'];
 const text=words.join('|'),rule=managed(text);
 assert.deepEqual(inspectTitleRules(rule).words,words);
 assert.match(rule,/C\\\+\\\+/);
 assert.match(rule,/a\\\.b/);
});
test('blank keyword list never emits a regex matching every title',()=>{
 assert.equal(replaceTitleKeywords('','| ||'),'');
 assert.equal(replaceTitleKeywords(managed('广告'),'| ||'),'');
});
test('adds a title line without replacing advanced title, URL or content rules',()=>{
 const raw='EntryTitle=^existing$\nEntryURL=tracking\nEntryContent=body';
 assert.equal(replaceTitleKeywords(raw,'a|b'),raw+'\n'+managed('a|b'));
});
test('editing and clearing remove only this editor owned line',()=>{
 const others='EntryTitle=manual.*\nEntryTag=tag';
 const raw=others+'\n'+managed('old');
 assert.equal(replaceTitleKeywords(raw,'new'),others+'\n'+managed('new'));
 assert.equal(replaceTitleKeywords(raw,''),others);
});
test('CRLF and unowned text are retained byte-for-byte',()=>{
 const raw='EntryURL=a\r\n'+managed('old')+'\r\nEntryContent=b';
 assert.equal(replaceTitleKeywords(raw,'new'),'EntryURL=a\r\n'+managed('new')+'\r\nEntryContent=b');
 assert.equal(replaceTitleKeywords(raw,''),'EntryURL=a\r\nEntryContent=b');
});
test('no-op preserves original spacing and raw rule text',()=>{
 const raw='  '+managed('a|b')+'  ';
 assert.equal(replaceTitleKeywords(raw,' a | b '),raw);
});
test('managed rule manually changed to complex regex is not reinterpreted',()=>{
 assert.throws(()=>inspectTitleRules(RULE_PREFIX+'a.*)'),'CUSTOM_RULE');
 assert.throws(()=>inspectTitleRules(managed('a')+'\n'+managed('b')));
});
test('old server field absence fails closed rather than using broad blocklist',()=>{
 assert.throws(()=>inspectFeed({id:42,blocklist_rules:'old'},42),{code:'UNSUPPORTED'});
 assert.throws(()=>inspectFeed(feed(''),43),{code:'INVALID_RESPONSE'});
});
test('rejects control characters and overlong input',()=>{
 assert.throws(()=>keywordsFromText('a\u0000b'),{code:'INVALID_INPUT'});
 assert.throws(()=>keywordsFromText('x'.repeat(8193)),{code:'INVALID_INPUT'});
});
test('save updates exactly one field and merges latest independent changes',async()=>{
 const current=feed('EntryURL=other\n'+managed('old'),{title:'unchanged',blocklist_rules:'legacy'});let sent;
 const result=await saveTitleKeywords({feedId:42,text:'广告|抽奖',baselineLine:managed('old'),api:{read:async()=>current,write:async patch=>{sent=patch;return {...current,...patch};}}});
 assert.deepEqual(Object.keys(sent),[FILTER_FIELD]);
 assert.equal(sent[FILTER_FIELD],'EntryURL=other\n'+managed('广告|抽奖'));
 assert.equal(result.feed.blocklist_rules,'legacy');assert.equal(result.feed.title,'unchanged');
});
test('concurrent owned keyword change prevents any write and requests reload',async()=>{
 let wrote=false;
 await assert.rejects(saveTitleKeywords({feedId:42,text:'new',baselineLine:managed('old'),api:{read:async()=>feed(managed('other')),write:async()=>{wrote=true;}}}),{code:'CONFLICT'});
 assert.equal(wrote,false);
});
test('already equal latest rule is idempotent and needs no PUT',async()=>{
 const result=await saveTitleKeywords({feedId:42,text:'new',baselineLine:null,api:{read:async()=>feed(managed('new')),write:async()=>assert.fail()}});
 assert.equal(result.changed,false);
});
test('ignored or unconfirmed API update never reports success',async()=>{
 await assert.rejects(saveTitleKeywords({feedId:42,text:'new',baselineLine:null,api:{read:async()=>feed(''),write:async()=>feed('')}}),{code:'UNCONFIRMED'});
});
test('account/lifetime invalidation after read prevents write',async()=>{
 let current=true;
 await assert.rejects(saveTitleKeywords({feedId:42,text:'new',baselineLine:null,check:()=>current,api:{read:async()=>{current=false;return feed('');},write:async()=>assert.fail()}}),{code:'CANCELLED'});
});

function apiHarness(response, options={}) {
 let auth={userId:7,serverUrl:'https://miniflux.xiaochen.win',authType:'token',token:'fixture-only',...options.auth};let current=auth;const calls=[];
 const api=createTitleFilterApi({feedId:42,serverUrl:'https://miniflux.xiaochen.win',auth,authState:{get:()=>current},isCurrent:()=>true,deadlineMs:30,
  network:()=>({request:async payload=>{calls.push(payload);return typeof response==='function'?response(payload):response;}})});
 return {api,calls,change:()=>{current={...auth,userId:8};}};
}
test('native request stays on trusted feed endpoint, sends timeout and minimal JSON',async()=>{
 const h=apiHarness({status:200,body:JSON.stringify(feed(''))});await h.api.read();await h.api.write({[FILTER_FIELD]:''});
 assert.equal(h.calls[0].url,'https://miniflux.xiaochen.win/v1/feeds/42');assert.equal(h.calls[0].timeoutMs,15000);
 assert.equal(h.calls[0].headers['X-Auth-Token'],'fixture-only');
 assert.deepEqual(JSON.parse(h.calls[1].body),{[FILTER_FIELD]:''});
 await assert.rejects(h.api.write({title:'overwrite'}),{code:'INVALID_PATCH'});
});
test('Basic auth encodes Unicode correctly',async()=>{
 const h=apiHarness({status:200,body:JSON.stringify(feed(''))},{auth:{authType:'basic',username:'用户',password:'密码'}});await h.api.read();
 assert.equal(h.calls[0].headers.Authorization,'Basic '+Buffer.from('用户:密码').toString('base64'));
});
test('native base64 response is decoded without losing Chinese',async()=>{
 const h=apiHarness({status:200,bodyEncoding:'base64',body:Buffer.from(JSON.stringify(feed(managed('中文')))).toString('base64')});
 assert.equal((await h.api.read())[FILTER_FIELD],managed('中文'));
});
test('auth change blocks request before network',async()=>{
 const h=apiHarness({status:200,body:JSON.stringify(feed(''))});h.change();
 await assert.rejects(h.api.read(),{code:'ACCOUNT_CHANGED'});assert.equal(h.calls.length,0);
});
test('auth change during pending read discards response',async()=>{
 let done;const h=apiHarness(()=>new Promise(resolve=>{done=resolve;}));const result=h.api.read();await Promise.resolve();h.change();done({status:200,body:JSON.stringify(feed(''))});
 await assert.rejects(result,{code:'ACCOUNT_CHANGED'});
});
test('PUT deadline ambiguity requires verification and never retries automatically',async()=>{
 const h=apiHarness(()=>new Promise(()=>{}));await assert.rejects(h.api.write({[FILTER_FIELD]:managed('a')}),{code:'UNCONFIRMED'});assert.equal(h.calls.length,1);
});
test('PUT transport disconnect is unconfirmed, not an invitation to resend',async()=>{
 const h=apiHarness(()=>{throw {code:'NETWORK_UNAVAILABLE',message:'headers contain fixture secret'};});
 await assert.rejects(h.api.write({[FILTER_FIELD]:managed('a')}),error=>error.code==='UNCONFIRMED'&&!error.message.includes('secret'));
});
test('wrong origin and arbitrary feed ID are refused',()=>{
 const values={feedId:42,auth:{serverUrl:'https://other.invalid'},serverUrl:'https://miniflux.xiaochen.win',authState:{get:()=>null},isCurrent:()=>true};
 assert.throws(()=>createTitleFilterApi(values),{code:'INVALID_SERVER'});
 assert.throws(()=>createTitleFilterApi({...values,feedId:'42/entries'}),{code:'INVALID_FEED'});
});
test('server errors and malformed body do not disclose raw payload',async()=>{
 const h=apiHarness({status:403,body:'secret'});await assert.rejects(h.api.read(),e=>e.code==='HTTP_ERROR'&&!e.message.includes('secret'));
 const h2=apiHarness({status:200,body:'secret'});await assert.rejects(h2.api.read(),{code:'INVALID_RESPONSE'});
});
