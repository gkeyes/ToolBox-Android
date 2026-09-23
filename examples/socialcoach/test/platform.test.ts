import test from 'node:test';
import assert from 'node:assert/strict';
import {endpoint,sse,request,requestScope,abortRequests} from '../platform/network';
import {makeByokLLM,listModels} from '../platform/llm';
import {appStorage,keyStorage,flushStorage,storageFailure} from '../platform/storage';
import {backupText,validateBackup,restoreBackup} from '../platform/backup';
import {useApp} from '../src/store/useApp';
import {useByok} from '../src/lib/byok';
import {SCENARIOS,THEORIES,CASES} from '../src/data/corpus';
import {SKILLS,CONTEXTS} from '../src/data/taxonomy';
import {buildSession} from '../src/lib/session-utils';
import {parseRoleplay} from '../src/lib/client-api';
import type {ByokConfig} from '../src/lib/byok';
const encoder=new TextEncoder();
const config:ByokConfig={provider:'openai',baseUrl:'https://example.invalid/v1',apiKey:'test-key-not-a-real-credential',smartModel:'mock-smart',fastModel:'mock-fast',enabled:true,tokenParam:'max_tokens'};
const opts={system:'test',messages:[{role:'user' as const,content:'hello'}],maxTokens:100};
function mockNative(text:string,status=200,contentType='text/event-stream',chunkSize=1){
  const bytes=encoder.encode(text);let offset=0,cancelled=0;
  const ordinary=new Map<string,unknown>(),secure=new Map<string,unknown>();
  const storage=(map:Map<string,unknown>)=>({get:async(k:string)=>map.get(k)??null,set:async(k:string,v:unknown)=>{map.set(k,v);},remove:async(k:string)=>{map.delete(k);}});
  const api={ready:async()=>({hostVersion:'1.0.0'}),storage:{...storage(ordinary),secure:storage(secure)},network:{
    openStream:async (_request:any,{signal}:any={})=>{if(signal?.aborted)throw new DOMException('cancel','AbortError');return {streamId:'test-stream',status,headers:{'Content-Type':contentType}};},
    readStream:async()=>{const data=bytes.slice(offset,offset+chunkSize);offset+=data.length;return {data,done:offset===bytes.length};},
    cancelStream:async()=>{cancelled++;}
  }};
  Object.defineProperty(globalThis,'window',{value:{ToolBox:api,dispatchEvent:()=>true},configurable:true,writable:true});
  return {api,ordinary,secure,get cancelled(){return cancelled;}};
}
const openaiStream=(text:string,ending=true)=>`data: ${JSON.stringify({choices:[{delta:{content:text}}]})}\r\n\r\n${ending?'data: [DONE]\r\n\r\n':''}`;
async function collect(run:ReturnType<ReturnType<typeof makeByokLLM>['chatStream']>){let result='';for await(const d of run.deltas)result+=d;return result;}
test.afterEach(async()=>{abortRequests();await flushStorage().catch(()=>{});delete (globalThis as any).window;});

test('pinned corpus preserves 46 scenes, 34 skills and 7 contexts',()=>{assert.equal(SCENARIOS.length,46);assert.equal(SKILLS.length,34);assert.equal(CONTEXTS.length,7);assert.equal(THEORIES.length,42);assert.equal(CASES.length,30);});
test('HTTPS endpoints reject secret-bearing URLs and preserve base paths',()=>{assert.equal(endpoint('https://example.invalid/v1/','/models'),'https://example.invalid/v1/models');for(const base of ['http://example.invalid','https://u:p@example.invalid','https://example.invalid/?key=x','https://example.invalid/#x'])assert.throws(()=>endpoint(base,'models'));});
test('SSE decoder handles split UTF-8, CRLF and multiline data',async()=>{const m=mockNative('data: {"text":"你好🙂",\r\ndata: "ok":true}\r\n\r\n');const res=await request('https://example.invalid');const events=[];for await(const event of sse(res))events.push(event);assert.deepEqual(JSON.parse(events[0]),{text:'你好🙂',ok:true});assert.equal(m.cancelled,1);});
test('OpenAI streamed Chinese reply is complete and connection released',async()=>{const m=mockNative(openaiStream('先说明边界，再讨论方案。'));const run=makeByokLLM(config).chatStream(opts);assert.equal(await collect(run),'先说明边界，再讨论方案。');assert.equal(run.text(),'先说明边界，再讨论方案。');assert.equal(run.refused(),false);assert.equal(m.cancelled,1);});
test('Anthropic streamed text and completion marker are parsed',async()=>{mockNative(`data: ${JSON.stringify({type:'content_block_delta',delta:{type:'text_delta',text:'可以继续讨论。'}})}\n\ndata: {"type":"message_stop"}\n\n`);assert.equal(await collect(makeByokLLM({...config,provider:'anthropic'}).chatStream(opts)),'可以继续讨论。');});
test('premature SSE EOF fails rather than accepting a partial reply',async()=>{mockNative(openaiStream('半句话',false));await assert.rejects(collect(makeByokLLM(config).chatStream(opts)),/连接提前结束/);});
test('API error redacts credentials from provider text',async()=>{mockNative(JSON.stringify({error:{message:`Bad key ${config.apiKey}`}}),401,'application/json');await assert.rejects(makeByokLLM(config).chatText(opts),(e:Error)=>{assert.match(e.message,/redacted/);assert.ok(!e.message.includes(config.apiKey));return true;});});
test('max token truncation is surfaced and cannot become a report',async()=>{mockNative(JSON.stringify({choices:[{message:{content:'{'},finish_reason:'length'}]}),200,'application/json');await assert.rejects(makeByokLLM(config).chatText(opts),/截断/);});
test('reasoning token parameter and explicit model are forwarded',async()=>{const m=mockNative(JSON.stringify({choices:[{message:{content:'ok'},finish_reason:'stop'}]}),200,'application/json');let body:any;const open=m.api.network.openStream;m.api.network.openStream=async(r:any,o:any)=>{body=JSON.parse(r.body);return open(r,o);};assert.equal(await makeByokLLM({...config,tokenParam:'max_completion_tokens'}).chatText({...opts,model:'second-model'}),'ok');assert.equal(body.model,'second-model');assert.equal(body.max_completion_tokens,100);assert.ok(!('max_tokens'in body));});
test('model picker filters non-conversational model IDs',async()=>{mockNative(JSON.stringify({data:[{id:'chat-b'},{id:'embedding-3'},{id:'chat-a'},{id:'tts-1'}]}),200,'application/json');assert.deepEqual(await listModels(config),['chat-a','chat-b']);});
test('cancel after headers closes native stream and rejects reader',async()=>{const m=mockNative(openaiStream('你好'));m.api.network.readStream=()=>new Promise(()=>{});const scope=requestScope();const res=await request('https://example.invalid',{signal:scope.signal});const reading=res.text();abortRequests();await assert.rejects(reading,e=>e instanceof Error&&e.name==='AbortError');scope.dispose();assert.equal(m.cancelled,1);});
test('already-cancelled parent prevents starting the next JSON repair request',async()=>{const m=mockNative('');let opened=false;const prev=m.api.network.openStream;m.api.network.openStream=async(r,o)=>{opened=true;return prev(r,o);};const c=new AbortController();c.abort();await assert.rejects(makeByokLLM(config,c.signal).chatText(opts),e=>e instanceof Error&&e.name==='AbortError');assert.equal(opened,false);});
test('native ordinary storage and secure credentials stay separate',async()=>{const m=mockNative('');appStorage.setItem('record','practice');keyStorage.setItem('secret','key');await flushStorage();assert.equal(m.ordinary.get('record'),'practice');assert.ok(!m.ordinary.has('secret'));assert.equal(m.secure.get('secret'),'key');});
test('storage error is visible, retains pending data and is retryable',async()=>{const m=mockNative('');let fail=true;const write=m.api.storage.set;m.api.storage.set=async(k,v)=>{if(fail)throw new Error('disk write failed');await write(k,v);};appStorage.setItem('record','new');await assert.rejects(flushStorage(),/尚未保存/);assert.ok(storageFailure());fail=false;await flushStorage();assert.equal(m.ordinary.get('record'),'new');assert.equal(storageFailure(),null);});
test('backup validates a real corpus session, restores it and excludes the key',async()=>{mockNative('');const scene=SCENARIOS[0],session=buildSession(scene,'arena','zh');useApp.setState({profile:{name:'测试用户',bio:'',goals:scene.skills,contexts:[scene.context],lang:'zh',createdAt:Date.now()},sessions:[session]});useByok.getState().set(config);const text=backupText();assert.ok(!text.includes(config.apiKey));const parsed=validateBackup(text);assert.equal(parsed.sessions[0].scenario.id,scene.id);await restoreBackup(parsed);assert.equal(useApp.getState().sessions[0].id,session.id);assert.equal(useByok.getState().apiKey,config.apiKey);});
test('malformed and prototype-bearing backups do not mutate existing records',()=>{const before=JSON.stringify(useApp.getState().sessions);assert.throws(()=>validateBackup('{"schemaVersion":1}'));assert.throws(()=>validateBackup('{"__proto__":{}}'));assert.equal(JSON.stringify(useApp.getState().sessions),before);});
test('original role metadata and NPC IDs survive transport adaptation',()=>{const raw='@@meta\n{"objectives":[false],"ended":false,"stance":25}\n@@npc\n我需要再考虑一下。';const parsed=parseRoleplay(raw,['npc']);assert.equal(parsed.utterances[0].characterId,'npc');assert.match(parsed.utterances[0].text,/再考虑/);assert.equal(parsed.meta?.stance,25);});
