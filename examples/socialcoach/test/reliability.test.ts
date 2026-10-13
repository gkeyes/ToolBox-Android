import test from 'node:test';
import assert from 'node:assert/strict';
import {request,requestScope,abortRequests,setIdleTimeout} from '../platform/network';
import {makeByokLLM,keyOrigin} from '../platform/llm';
import {flushStorage,appStorage,storageFailure,storageReadFailure} from '../platform/storage';
import {validateBackup,backupText,exportBackup,restoreBackup} from '../platform/backup';
import {useApp} from '../src/store/useApp';
import {hydrateApp,holdSessionWrites,releaseSessionWrites,INDEX_KEY,LEGACY_KEY,sessionKey} from '../src/store/persistence';
import {repairScenario,runRehearse} from '../src/lib/tasks/rehearse';
import {SCENARIOS} from '../src/data/corpus';
import {buildSession} from '../src/lib/session-utils';
import type {LLM} from '../src/lib/llm-core';
import type {ByokConfig} from '../src/lib/byok';

const encoder=new TextEncoder();
const wait=(ms:number)=>new Promise(resolve=>setTimeout(resolve,ms));
const profile={name:'测试用户',bio:'',goals:SCENARIOS[0].skills,contexts:[SCENARIOS[0].context],lang:'zh' as const,createdAt:1780000000000};
const config:ByokConfig={provider:'openai',baseUrl:'https://example.invalid/v1',apiKey:'test-key-not-a-real-credential',smartModel:'mock-smart',fastModel:'mock-fast',enabled:true,tokenParam:'max_tokens'};

/** Native host double; records every ordinary-storage write. */
function mockHost(initial:Record<string,string>={}){
  const ordinary=new Map<string,unknown>(Object.entries(initial)),secure=new Map<string,unknown>();
  const writes:string[]=[],removes:string[]=[];
  let stream:{read:()=>Promise<{data:Uint8Array;done:boolean}>;open?:(signal?:AbortSignal)=>Promise<unknown>}={read:async()=>({data:new Uint8Array(),done:true})};
  let cancelled=0;
  const api={ready:async()=>({hostVersion:'1.0.0'}),storage:{
    get:async(k:string)=>ordinary.get(k)??null,set:async(k:string,v:unknown)=>{writes.push(k);ordinary.set(k,v);},remove:async(k:string)=>{removes.push(k);ordinary.delete(k);},
    secure:{get:async(k:string)=>secure.get(k)??null,set:async(k:string,v:unknown)=>{secure.set(k,v);},remove:async(k:string)=>{secure.delete(k);}},
  },network:{
    openStream:async(_r:unknown,{signal}:{signal?:AbortSignal}={})=>{if(stream.open)await stream.open(signal);if(signal?.aborted)throw new DOMException('cancel','AbortError');return {streamId:'s',status:200,headers:{'Content-Type':'text/event-stream'}};},
    readStream:()=>stream.read(),cancelStream:async()=>{cancelled++;},
  }};
  Object.defineProperty(globalThis,'window',{value:{ToolBox:api,dispatchEvent:()=>true},configurable:true,writable:true});
  return {api,ordinary,writes,removes,setStream:(s:typeof stream)=>{stream=s;},get cancelled(){return cancelled;}};
}
const sessionsOf=(n:number)=>Array.from({length:n},(_,i)=>({...buildSession(SCENARIOS[i%SCENARIOS.length],'arena','zh'),id:`s-${i}`}));

test.afterEach(async()=>{abortRequests();setIdleTimeout(180000);await flushStorage().catch(()=>{});delete (globalThis as any).window;});

test('v1 single-blob store migrates to per-session keys without losing sessions beyond the old 200 cap',async()=>{
  const sessions=sessionsOf(250);
  const legacy=JSON.stringify({state:{profile,proficiency:{},sessions,customScenarios:[],bookmarks:['t1'],practiceDays:['2026-10-01'],settings:{tts:false,telemetry:false,voiceNoticeSeen:true,theme:'dark',patience:20},todayDate:null,todaySessionId:null,patternInsight:null},version:0});
  const host=mockHost({[LEGACY_KEY]:legacy});
  await hydrateApp();
  const state=useApp.getState();
  assert.equal(state.sessions.length,250);
  assert.deepEqual(state.settings,{theme:'dark',patience:20});
  assert.equal(host.ordinary.has(LEGACY_KEY),false);
  const index=JSON.parse(host.ordinary.get(INDEX_KEY) as string);
  assert.equal(index.sessionIds.length,250);
  assert.equal(index.sessions,undefined);
  for(const s of sessions)assert.equal(JSON.parse(host.ordinary.get(sessionKey(s.id)) as string).id,s.id);
  // A fresh load reads the new layout back unchanged.
  useApp.setState({sessions:[],bookmarks:[]});
  await hydrateApp();
  assert.equal(useApp.getState().sessions.length,250);
  assert.deepEqual(useApp.getState().bookmarks,['t1']);
});

test('a migration interrupted after the index was written recovers missing sessions from the v1 record',async()=>{
  const sessions=sessionsOf(3);
  const legacy=JSON.stringify({state:{profile,sessions},version:0});
  const index=JSON.stringify({version:2,sessionIds:sessions.map(s=>s.id),profile});
  const host=mockHost({[LEGACY_KEY]:legacy,[INDEX_KEY]:index,[sessionKey('s-0')]:JSON.stringify(sessions[0])});
  await hydrateApp();
  assert.deepEqual(useApp.getState().sessions.map(s=>s.id),['s-0','s-1','s-2']);
  assert.ok(host.ordinary.has(sessionKey('s-2')));
  assert.equal(host.ordinary.has(LEGACY_KEY),false);
});

test('a change to one session rewrites only that session, debounced until flush',async()=>{
  const host=mockHost();
  useApp.setState({profile,sessions:sessionsOf(50)});
  await hydrateApp();
  await flushStorage();
  host.writes.length=0;
  for(let i=0;i<5;i++)useApp.getState().updateSession('s-7',{timed:i%2===0});
  assert.deepEqual(host.writes,[]);
  await flushStorage();
  assert.deepEqual(host.writes,[sessionKey('s-7')]);
  // Adding and removing a session also updates the index; a removed session's record is deleted.
  host.writes.length=0;
  useApp.getState().removeSession('s-3');
  await flushStorage();
  assert.deepEqual(host.writes,[INDEX_KEY]);
  assert.deepEqual(host.removes,[sessionKey('s-3')]);
});

test('debounced writes land without an explicit flush',async()=>{
  const host=mockHost();
  useApp.setState({profile,sessions:sessionsOf(2)});
  await hydrateApp();
  await flushStorage();
  host.writes.length=0;
  useApp.getState().updateSession('s-1',{timed:true});
  await wait(700);
  assert.deepEqual(host.writes,[sessionKey('s-1')]);
});

test('streaming checkpoints stay in memory until the turn ends, but a close flush still writes them',async()=>{
  const host=mockHost();
  useApp.setState({profile,sessions:sessionsOf(2)});
  await hydrateApp();
  await flushStorage();
  host.writes.length=0;
  holdSessionWrites('s-0');
  useApp.getState().updateNpcUtterances('s-0',[{id:'npc-1',characterId:'npc',text:'第一'}]);
  useApp.getState().updateNpcUtterances('s-0',[{id:'npc-1',characterId:'npc',text:'第一句'}]);
  await wait(700);
  assert.deepEqual(host.writes,[]);
  await flushStorage();
  assert.deepEqual(host.writes,[sessionKey('s-0')]);
  releaseSessionWrites('s-0');
});

test('LLM scenario is repaired into the backup schema so a backup with it restores',()=>{
  const raw={title:{zh:'和室友谈噪音',en:''},hook:'深夜的音乐',background:{zh:'室友常在深夜放音乐。',en:''},context:'family',contextType:{zh:'合租',en:''},
    competencies:['bogus'],skills:['communication','nonsense'],relationship:['roommate','peer'],difficulty:'2',minutes:'5',maxTurns:'12',
    characters:[{id:'you',name:{zh:'你',en:''},role:{zh:'租客',en:''},personality:{zh:'',en:''},stance:{zh:'',en:''},playable:false},{id:'li',name:{zh:'小李',en:''},role:{zh:'室友',en:''},personality:{zh:'随和',en:''},stance:{zh:'想放松',en:''},hue:'200'}],
    objectives:[{zh:'说明影响',en:''},{zh:'约定时间',en:''}],success:{zh:'达成约定',en:''},failure:{zh:'不了了之',en:''},opening:{characterId:'li',text:{zh:'怎么了？',en:''}},keywords:['噪音',3]};
  const scenario=repairScenario(raw,'custom-test')!;
  assert.ok(scenario);
  assert.equal(scenario.characters.find(c=>c.id==='you')?.playable,true);
  assert.deepEqual(scenario.relationship,['peer']);
  assert.equal(scenario.minutes,5);
  assert.equal(scenario.maxTurns,10);
  assert.equal(scenario.difficulty,2);
  mockHost();
  const session=buildSession(scenario,'rehearse','zh');
  useApp.setState({profile,sessions:[session],customScenarios:[scenario]});
  const restored=validateBackup(backupText());
  assert.equal(restored.customScenarios[0].id,'custom-test');
  assert.equal(restored.sessions[0].learnerCharacterId,'you');
});

test('an unfixable generated scenario is retried once, then rejected instead of stored',async()=>{
  const bad=JSON.stringify({title:{zh:'只有自己',en:''},characters:[{id:'you',name:{zh:'你',en:''}}],objectives:[]});
  const good=JSON.stringify({title:{zh:'谈加薪',en:''},hook:{zh:'年度评估',en:''},background:{zh:'背景',en:''},context:'workplace',contextType:{zh:'一对一',en:''},skills:['communication'],relationship:['senior'],difficulty:2,minutes:4,maxTurns:8,
    characters:[{id:'you',name:{zh:'你',en:''},role:{zh:'员工',en:''},personality:{zh:'',en:''},stance:{zh:'',en:''},playable:true,hue:40},{id:'boss',name:{zh:'王总',en:''},role:{zh:'经理',en:''},personality:{zh:'谨慎',en:''},stance:{zh:'控制预算',en:''},hue:10}],
    objectives:[{zh:'提出诉求',en:''}],success:{zh:'同意复议',en:''},failure:{zh:'拒绝',en:''},opening:{characterId:'boss',text:{zh:'你找我？',en:''}},keywords:[]});
  const replies=[bad,good];let calls=0;
  const llm:LLM={chatText:async()=>{calls++;return replies.shift()??bad;},chatStream:()=>{throw new Error('unused');}};
  const {scenario}=await runRehearse({description:'我想和经理谈加薪的事情',lang:'zh'},llm,'fast');
  assert.equal(calls,2);assert.equal(scenario.title.zh,'谈加薪');
  const never:LLM={chatText:async()=>bad,chatStream:()=>{throw new Error('unused');}};
  await assert.rejects(runRehearse({description:'我想和经理谈加薪的事情',lang:'zh'},never,'fast'),/不完整/);
});

test('a slow but progressing stream outlives the idle timeout',async()=>{
  const host=mockHost();
  setIdleTimeout(80);
  const parts=['你','好','，','慢','慢','说'].map(c=>`data: ${JSON.stringify({choices:[{delta:{content:c}}]})}\n\n`);
  parts.push('data: [DONE]\n\n');
  host.setStream({read:async()=>{await wait(40);const next=parts.shift();return {data:encoder.encode(next??''),done:!parts.length};}});
  const run=makeByokLLM(config).chatStream({system:'s',messages:[{role:'user',content:'hi'}],maxTokens:10});
  let text='';for await(const d of run.deltas)text+=d;
  assert.equal(text,'你好，慢慢说');
});

test('silence longer than the idle timeout fails with a distinct timed-out error, not a cancel',async()=>{
  const host=mockHost();
  setIdleTimeout(60);
  host.setStream({read:()=>new Promise(()=>{})});
  const run=makeByokLLM(config).chatStream({system:'s',messages:[{role:'user',content:'hi'}],maxTokens:10});
  await assert.rejects((async()=>{for await(const _ of run.deltas){}})(),(e:Error)=>e.name==='TimeoutError'&&/超时/.test(e.message)&&!/取消/.test(e.message));
  assert.equal(host.cancelled,1);
  // A request that never answers at all is also released.
  host.setStream({open:(signal)=>new Promise((_,reject)=>signal?.addEventListener('abort',()=>reject(new DOMException('cancel','AbortError')))),read:()=>new Promise(()=>{})});
  const scope=requestScope();
  await assert.rejects(request('https://example.invalid',{signal:scope.signal}),(e:Error)=>e.name==='TimeoutError');
  scope.dispose();
});

test('the saved API key is bound to the origin it was saved for',()=>{
  assert.equal(keyOrigin({provider:'openai',baseUrl:''}),'https://api.openai.com');
  assert.equal(keyOrigin({provider:'openai',baseUrl:'https://proxy.example/v1'}),'https://proxy.example');
  assert.notEqual(keyOrigin({provider:'openai',baseUrl:'https://proxy.example/v1'}),keyOrigin({provider:'openai',baseUrl:'https://other.example/v1'}));
});

test('a failed in-flight record write is retried, with its newer value, before an index queued behind it',async()=>{
  const host=mockHost();
  const order:string[]=[];let fail:((e:Error)=>void)|null=null;let first=true;
  const set=host.api.storage.set;
  host.api.storage.set=async(k:string,v:unknown)=>{
    if(first&&k==='rec'){first=false;await new Promise((_,reject)=>{fail=reject;});}
    order.push(`${k}=${v}`);await set(k,v);
  };
  appStorage.setItem('rec','v1');
  await wait(0);
  appStorage.setItem('index','lists-rec');
  appStorage.setItem('rec','v2');
  fail!(new Error('disk busy'));
  await assert.rejects(flushStorage(),/尚未保存/);
  await flushStorage();
  assert.deepEqual(order,['rec=v2','index=lists-rec']);
  assert.equal(host.ordinary.get('rec'),'v2');
});

test('hydration reads a large history in batches the host budget accepts, keeping index order',async()=>{
  const sessions=sessionsOf(120);
  const initial:Record<string,string>={[INDEX_KEY]:JSON.stringify({version:2,sessionIds:sessions.map(s=>s.id),profile})};
  for(const s of sessions)initial[sessionKey(s.id)]=JSON.stringify(s);
  const host=mockHost(initial);
  const budget=JSON.stringify(sessions[0]).length*5;
  const batches:number[]=[];
  (host.api.storage as any).getMany=async(keys:string[])=>{
    const values=keys.map(k=>host.ordinary.get(k)??null);
    if(values.reduce((n,v)=>n+String(v).length,0)>budget)throw Object.assign(new Error('Storage batch response is too large; request fewer keys'),{code:'QUOTA_EXCEEDED'});
    batches.push(keys.length);return values;
  };
  await hydrateApp();
  assert.deepEqual(useApp.getState().sessions.map(s=>s.id),sessions.map(s=>s.id));
  assert.ok(batches.length>4);assert.equal(batches.reduce((a,b)=>a+b,0),2+120); // index + v1 probe, then every session once
});

test('a failed migration write keeps the loaded data and the v1 record, as a retryable warning',async()=>{
  const sessions=sessionsOf(3);
  const host=mockHost({[LEGACY_KEY]:JSON.stringify({state:{profile,sessions},version:0})});
  const set=host.api.storage.set;let full=true;
  host.api.storage.set=async(k:string,v:unknown)=>{if(full)throw new Error('disk full');await set(k,v);};
  await hydrateApp();
  assert.equal(useApp.getState().sessions.length,3);
  assert.ok(storageFailure());assert.equal(storageReadFailure(),null);
  assert.ok(host.ordinary.has(LEGACY_KEY));
  full=false;await flushStorage();
  assert.ok(host.ordinary.has(INDEX_KEY));assert.ok(host.ordinary.has(sessionKey('s-2')));
});

test('the index does not list a new session until its held record is queued ahead of it',async()=>{
  const host=mockHost();
  useApp.setState({profile,sessions:sessionsOf(1)});
  await hydrateApp();
  await flushStorage();
  host.writes.length=0;
  const fresh={...buildSession(SCENARIOS[1],'arena','zh'),id:'fresh'};
  holdSessionWrites('fresh');
  useApp.getState().addSession(fresh);
  await wait(700);
  assert.deepEqual(host.writes,[]);
  releaseSessionWrites('fresh');
  await wait(700);
  assert.deepEqual(host.writes,[sessionKey('fresh'),INDEX_KEY]);
});

test('browser preview: an idle timeout after the headers is reported as timed out, not cancelled',async()=>{
  setIdleTimeout(60);
  const realFetch=globalThis.fetch;
  globalThis.fetch=(async()=>new Response(new ReadableStream({start(c){c.enqueue(encoder.encode('data: {}\n\n'));}}),{status:200,headers:{'Content-Type':'text/event-stream'}})) as typeof fetch;
  try{
    const scope=requestScope();
    const res=await request('https://example.invalid',{signal:scope.signal});
    await assert.rejects(res.text(),(e:Error)=>e.name==='TimeoutError'&&/超时/.test(e.message));
    scope.dispose();
  }finally{globalThis.fetch=realFetch;}
});

test('session ids encodeURIComponent cannot encode still get a stable, distinct key and round-trip',async()=>{
  assert.equal(sessionKey('s-1'),'socialcoach.v2.session.s-1');
  const odd='\ud800x';
  assert.doesNotThrow(()=>sessionKey(odd));
  assert.notEqual(sessionKey(odd),sessionKey('\ud801x'));
  const host=mockHost();
  await hydrateApp();
  const session={...buildSession(SCENARIOS[0],'arena','zh'),id:odd};
  useApp.setState({profile,sessions:[]});
  await restoreBackup(validateBackup(JSON.stringify({...JSON.parse(backupText()),sessions:[session]})));
  assert.ok(host.ordinary.has(sessionKey(odd)));
  useApp.setState({sessions:[]});
  await hydrateApp();
  assert.deepEqual(useApp.getState().sessions.map(s=>s.id),[odd]);
});

test('export still saves the in-memory backup when storage writes are failing',async()=>{
  const host=mockHost();
  const saved:string[]=[];
  (host.api as any).files={save:async(_n:string,_m:string,content:string)=>{saved.push(content);return {token:'t'};}};
  await hydrateApp();
  host.api.storage.set=async()=>{throw new Error('disk full');};
  useApp.setState({profile,sessions:sessionsOf(2)});
  const result=await exportBackup();
  assert.deepEqual(result,{saved:false});
  assert.equal(JSON.parse(saved[0]).sessions.length,2);
});
