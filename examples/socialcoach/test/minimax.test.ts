/** Synthetic protocol fixtures only: no API key, remote request or real M3 output. */
import test from 'node:test';
import assert from 'node:assert/strict';
import {makeByokLLM,isMiniMax} from '../platform/llm';
import {FinalTextFilter,finalText} from '../platform/reasoning';
import {abortRequests} from '../platform/network';
import {extractJSON} from '../src/lib/llm-core';
import type {ByokConfig} from '../src/lib/byok';

const key='mock-minimax-key-not-a-credential';
const config:ByokConfig={provider:'openai',baseUrl:'https://api.minimax.io/v1',apiKey:key,fastModel:'MiniMax-M3',smartModel:'MiniMax-M3',enabled:true,tokenParam:'max_tokens'};
const opts={system:'test',messages:[{role:'user' as const,content:'hello'}],maxTokens:800,thinking:false};
const response=(content:unknown,extra:object={})=>JSON.stringify({choices:[{message:{content,...extra},finish_reason:'stop'}],base_resp:{status_code:0}});
const event=(body:unknown)=>`data: ${JSON.stringify(body)}\r\n\r\n`;
const chunk=(content:unknown)=>event({choices:[{delta:{content}}]});
const done='data: [DONE]\r\n\r\n';
function mock(payload:string,stream=false){
  const bytes=new TextEncoder().encode(payload);let offset=0,cancelled=0;
  const calls:{url:string;body:any}[]=[];
  Object.defineProperty(globalThis,'window',{configurable:true,value:{ToolBox:{network:{
    openStream:async(r:any,{signal}:any={})=>{if(signal?.aborted)throw new DOMException('cancel','AbortError');calls.push({url:r.url,body:JSON.parse(r.body)});return {streamId:'mini',status:200,headers:{'Content-Type':stream?'text/event-stream':'application/json'}};},
    readStream:async()=>{const data=bytes.slice(offset,offset+1);offset+=data.length;return {data,done:offset===bytes.length};},
    cancelStream:async()=>{cancelled++;}
  }}}});
  return {calls,get cancelled(){return cancelled;}};
}
async function streamed(payload:string,c=config,o=opts){
  const native=mock(payload,true);const run=makeByokLLM(c).chatStream(o);let visible='';
  for await(const part of run.deltas){visible+=part;assert.doesNotMatch(visible,/PRIVATE|<\/?think/i);}
  assert.equal(run.text(),visible);assert.equal(native.cancelled,1);return {visible,body:native.calls[0].body};
}
test.afterEach(()=>{abortRequests();delete (globalThis as any).window;});

test('M3 short tasks request disabled thinking, split reasoning and new token parameter',async()=>{
  const m=mock(response('提示'));assert.equal(await makeByokLLM(config).chatText(opts),'提示');
  assert.equal(m.calls[0].url,'https://api.minimax.io/v1/chat/completions');
  const b=m.calls[0].body;assert.deepEqual(b.thinking,{type:'disabled'});assert.equal(b.reasoning_split,true);assert.equal(b.max_completion_tokens,800);assert.ok(!('max_tokens'in b));assert.ok(!('enable_thinking'in b));assert.ok(!('reasoning_effort'in b));
});
test('M3 assessment keeps adaptive reasoning with a bounded 16K allowance',async()=>{
  const m=mock(response('{"verdict":"ok"}'));await makeByokLLM(config).chatText({...opts,maxTokens:8000,thinking:undefined,effort:'medium'});
  assert.deepEqual(m.calls[0].body.thinking,{type:'adaptive'});assert.equal(m.calls[0].body.max_completion_tokens,16384);
});
test('actual per-task model controls M3 adaptation, not merely the saved smart model',async()=>{
  const m=mock(response('ok'));await makeByokLLM(config).chatText({...opts,model:'MiniMax-M2.7'});
  assert.equal(m.calls[0].body.model,'MiniMax-M2.7');assert.ok(!('thinking'in m.calls[0].body));assert.equal(m.calls[0].body.reasoning_split,true);
});
test('official China domains and bare origins are recognized without suffix matching',async()=>{
  for(const domain of ['api.minimaxi.com','api.minimax.cn']){
    const c={...config,baseUrl:`https://${domain}`};const m=mock(response('ok'));await makeByokLLM(c).chatText(opts);assert.equal(m.calls[0].url,`https://${domain}/v1/chat/completions`);assert.equal(m.calls[0].body.reasoning_split,true);
  }
  assert.equal(isMiniMax({...config,baseUrl:'https://api.minimax.io.attacker.invalid/v1'}),false);
});
test('third-party and OpenAI gateways receive no MiniMax-specific request fields',async()=>{
  for(const url of ['https://gateway.invalid/v1','https://api.openai.com/v1']){
    const m=mock(response('<think>PRIVATE</think>答案'));assert.equal(await makeByokLLM({...config,baseUrl:url}).chatText(opts),'答案');
    assert.ok(!('thinking'in m.calls[0].body));assert.ok(!('reasoning_split'in m.calls[0].body));assert.equal(m.calls[0].body.max_tokens,800);
  }
});
test('M3 Anthropic base normalization, thought separation and task reasoning control',async()=>{
  for(const path of ['/anthropic','/anthropic/v1','']){
    const c={...config,provider:'anthropic' as const,baseUrl:'https://api.minimax.io'+path};
    const m=mock(JSON.stringify({content:[{type:'thinking',thinking:'PRIVATE'},{type:'text',text:'答案'}],stop_reason:'end_turn'}));
    assert.equal(await makeByokLLM(c).chatText(opts),'答案');assert.equal(m.calls[0].url,'https://api.minimax.io/anthropic/v1/messages');assert.deepEqual(m.calls[0].body.thinking,{type:'disabled'});assert.equal(m.calls[0].body.max_tokens,800);assert.ok(!('reasoning_split'in m.calls[0].body));
  }
});
test('non-stream mixed reasoning is removed before JSON extraction',async()=>{
  mock(response('<think>PRIVATE {"verdict":"WRONG"}</think>\n{"verdict":"正确"}'));
  assert.deepEqual(extractJSON(await makeByokLLM(config).chatText(opts)),{verdict:'正确'});
});
test('separate reasoning_content and reasoning_details are never rendered',async()=>{
  mock(response('最终提示',{reasoning_content:'PRIVATE',reasoning_details:[{type:'reasoning.text',text:'PRIVATE'}]}));
  assert.equal(await makeByokLLM(config).chatText(opts),'最终提示');
  const data=event({choices:[{delta:{reasoning_content:'PRIVATE',reasoning_details:[{text:'PRIVATE'}]}}]})+chunk('最终提示')+event({choices:[],usage:{completion_tokens:10}})+done;
  assert.equal((await streamed(data)).visible,'最终提示');
});
test('every split position and nested reasoning delimiter preserves only the answer',()=>{
  const raw='<think>PRIVATE<thinking>PRIVATE</thinking>PRIVATE</think>你好🙂，先表达感受。';
  for(let i=0;i<=raw.length;i++){
    const f=new FinalTextFilter();let out=f.write(raw.slice(0,i));assert.doesNotMatch(out,/PRIVATE|<\/?think/i);out+=f.write(raw.slice(i));out+=f.finish();assert.equal(out,'你好🙂，先表达感受。');
  }
  const f=new FinalTextFilter();let out='';for(const ch of raw){out+=f.write(ch);assert.doesNotMatch(out,/PRIVATE|<\/?think/i);}assert.equal(out+f.finish(),'你好🙂，先表达感受。');
});
test('streamed split tags, UTF-8 and roleplay metadata reach the existing parser intact',async()=>{
  const answer='@@meta\n{"stance":25}\n@@npc\n可以单独聊聊吗？🙂';
  const data=['<th','ink>PRIVATE {"stance":100}','</thi','nk>',...Array.from(answer)].map(chunk).join('')+done;
  assert.equal((await streamed(data)).visible,answer);
});
test('ordinary repeated deltas and literal angle brackets are not deduplicated',async()=>{
  assert.equal((await streamed(['哈','哈','哈哈','，1 < 2，<tag>保留</tag>。'].map(chunk).join('')+done)).visible,'哈哈哈哈，1 < 2，<tag>保留</tag>。');
});
test('only reasoning, unterminated reasoning and partial tags fail without hidden output',async()=>{
  for(const raw of ['<think>PRIVATE</think>','<think>PRIVATE','<thi']){
    mock(response(raw));await assert.rejects(makeByokLLM(config).chatText(opts),e=>{assert.doesNotMatch((e as Error).message,/PRIVATE/);return true;});
    mock(chunk(raw)+done,true);const run=makeByokLLM(config).chatStream(opts);let seen='';
    await assert.rejects(async()=>{for await(const d of run.deltas)seen+=d;});assert.equal(seen,'');assert.equal(run.text(),'');
  }
});
test('Anthropic stream ignores thinking blocks but preserves start and delta text',async()=>{
  const data=event({type:'content_block_start',content_block:{type:'thinking',thinking:'PRIVATE'}})+event({type:'content_block_delta',delta:{type:'thinking_delta',thinking:'PRIVATE'}})+event({type:'content_block_start',content_block:{type:'text',text:'先'}})+event({type:'content_block_delta',delta:{type:'text_delta',text:'表达感受。'}})+event({type:'message_stop'});
  assert.equal((await streamed(data,{...config,provider:'anthropic',baseUrl:'https://api.minimax.io/anthropic'})).visible,'先表达感受。');
});
test('HTTP 200 MiniMax errors are surfaced and credentials redacted, including SSE',async()=>{
  const body={base_resp:{status_code:1004,status_msg:`invalid ${key}`}};
  mock(JSON.stringify(body));await assert.rejects(makeByokLLM(config).chatText(opts),(e:Error)=>{assert.match(e.message,/1004/);assert.ok(!e.message.includes(key));return true;});
  mock(event(body),true);await assert.rejects(async()=>{for await(const _ of makeByokLLM(config).chatStream(opts).deltas){}},/1004/);
});
test('truncated generation cannot be accepted as an assessment after filtering',async()=>{
  mock(event({choices:[{delta:{content:'<think>PRIVATE'},finish_reason:'length'}]})+done,true);
  await assert.rejects(async()=>{for await(const _ of makeByokLLM(config).chatStream(opts).deltas){}},/截断/);
});
test('JSON response to a streaming request uses the same final-answer filter',async()=>{
  mock(response('<THINK>PRIVATE</THINK>答案'));const run=makeByokLLM(config).chatStream(opts);let out='';for await(const d of run.deltas)out+=d;assert.equal(out,'答案');assert.equal(run.text(),'答案');
});
test('refusal and an empty final response remain explicit failures',async()=>{
  mock(JSON.stringify({choices:[{message:{content:'',refusal:'no'},finish_reason:'stop'}]}));await assert.rejects(makeByokLLM(config).chatText(opts),/拒绝/);
  mock(response(null,{reasoning_content:'PRIVATE'}));await assert.rejects(makeByokLLM(config).chatText(opts),/最终答案/);
});
test('filter keeps normal text and fails on malformed reserved closing tags',()=>{
  assert.equal(finalText('正常中文\n@@meta\n{}'),'正常中文\n@@meta\n{}');
  assert.throws(()=>finalText('</think>bad'),/分隔格式/);
});
