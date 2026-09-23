/** Mock-only browser regression suite. No API credential or real model is used. */
import {test,expect,type Page} from '@playwright/test';
import {SCENARIOS} from '../src/data/corpus';
const scene=SCENARIOS[0];
const learnerLine='我会在明天下午三点前给出反馈。';
const npcLine='谢谢你说明时间。不过文档缺什么信息，我们现在能先确定吗？';
const profile={name:'练习者',bio:'产品团队成员',goals:scene.skills,contexts:[scene.context],lang:'zh',createdAt:1780000000000};
const key='mock-test-key-never-use-in-production';
async function setup(page:Page,{withProfile=true,withKey=true,minimax=false}:{withProfile?:boolean;withKey?:boolean;minimax?:boolean}={}){
  await page.addInitScript(({profile,scene,withProfile,withKey,key,learnerLine,npcLine,minimax})=>{
    const w=window as any;
    const saved=(name:string,initial:unknown)=>{const text=localStorage.getItem(name);return text?JSON.parse(text):initial;};
    const ordinary=saved('test.native.ordinary',withProfile?{'socialcoach.v1':JSON.stringify({state:{profile,proficiency:{},sessions:[],customScenarios:[],bookmarks:[],practiceDays:[],settings:{tts:false,telemetry:false,theme:'light'},todayDate:null,todaySessionId:null,patternInsight:null},version:0})}:{});
    const secure=saved('test.native.secure',withKey?{'socialcoach.llm.v1':JSON.stringify({state:{enabled:true,provider:'openai',baseUrl:minimax?'https://api.minimax.io/v1':'https://mock.invalid/v1',apiKey:key,fastModel:minimax?'MiniMax-M3':'mock-chat',smartModel:minimax?'MiniMax-M3':'mock-assess',tokenParam:'max_tokens'},version:0})}:{});
    w.__testHost={ordinary,secure,calls:[],cancelled:0,exports:[],hold:false,onlyReasoning:false,leaks:[]};
    if(minimax)new MutationObserver(records=>{for(const r of records){for(const n of Array.from(r.addedNodes)){if(/PRIVATE_MINIMAX_TRACE|<\/?think/i.test(n.textContent||''))w.__testHost.leaks.push('added-node');}if(r.type==='characterData'&&/PRIVATE_MINIMAX_TRACE|<\/?think/i.test(r.target.textContent||''))w.__testHost.leaks.push('text-change');}}).observe(document,{subtree:true,childList:true,characterData:true});
    const storage=(data:any,name:string)=>({get:async(k:string)=>data[k]??null,set:async(k:string,v:unknown)=>{data[k]=v;localStorage.setItem(name,JSON.stringify(data));},remove:async(k:string)=>{delete data[k];localStorage.setItem(name,JSON.stringify(data));}});
    const streams=new Map<string,{bytes:Uint8Array;offset:number;hold:boolean}>();let seq=0;
    const report={ratings:[{skill:scene.skills[0],level:2,evidence:learnerLine,reason:'测试夹具：提出了时间承诺。'}],outcome:'partial',verdictEvidence:learnerLine,verdict:'测试复盘：表达了明确时间。',summary:'测试夹具，仅验证界面和引用校验。',strengths:[{skill:scene.skills[0],evidence:learnerLine,behavior:'明确时间'}],weaknesses:[],alternatives:[{original:learnerLine,better:'我会在明天下午三点前反馈，也想先确认缺少的信息。',why:'把承诺和具体请求结合。'}],knowledge:{theoryIds:[],caseIds:[],whyThis:''},reflectionQuestions:['下一次准备怎样开口？'],nextStep:'再试一次。',deltas:{[scene.skills[0]]:0.2}};
    w.ToolBox={ready:async()=>({hostVersion:'1.0.0'}),storage:{...storage(ordinary,'test.native.ordinary'),secure:storage(secure,'test.native.secure')},
      network:{openStream:async(req:any,{signal}:any={})=>{
        if(signal?.aborted)throw new DOMException('cancel','AbortError');
        w.__testHost.calls.push({url:req.url,method:req.method,body:req.body?JSON.parse(req.body):null});
        const body=req.body?JSON.parse(req.body):{};const messages=body.messages||[];const prompt=messages.map((m:any)=>m.content).join('\n');let text='ok';
        if(prompt.includes('Produce the adaptation JSON.'))text=JSON.stringify({learnerCharacterId:'you',briefing:'测试准备：先承认反馈延迟，再讨论具体安排。',objectives:scene.objectives.map((o:any)=>o.zh),focus:'明确表达，听取对方。'});
        else if(prompt.includes('Produce the assessment JSON.'))text=JSON.stringify(report);
        else if(prompt.includes('Give the hint.'))text='先表达感受，再提出一个具体的沟通时间。';
        else if(body.stream)text='@@meta\n'+JSON.stringify({objectives:[false,false,true],ended:false,stance:35,revealed:false})+'\n@@jason\n'+npcLine;
        const models=req.url.endsWith('/models');
        const sse=body.stream&&!models;
        const hidden='<think>PRIVATE_MINIMAX_TRACE {\"verdict\":\"错误草稿\"}</think>';
        if(minimax&&!models)text=w.__testHost.onlyReasoning&&prompt.includes('Give the hint.')?hidden:hidden+text;
        const payload=models?JSON.stringify({data:[{id:'mock-chat'},{id:'mock-assess'},{id:'text-embedding'}]}):sse?(minimax?'data: '+JSON.stringify({choices:[{delta:{reasoning_content:'PRIVATE_MINIMAX_TRACE',reasoning_details:[{text:'PRIVATE_MINIMAX_TRACE'}]}}]})+'\r\n\r\n'+Array.from(text).map(c=>'data: '+JSON.stringify({choices:[{delta:{content:c}}]})+'\r\n\r\n').join(''):'data: '+JSON.stringify({choices:[{delta:{content:text}}]})+'\r\n\r\n')+'data: [DONE]\r\n\r\n':JSON.stringify({choices:[{message:{content:text},finish_reason:'stop'}]});
        const id='stream-'+(++seq);streams.set(id,{bytes:new TextEncoder().encode(payload),offset:0,hold:sse&&w.__testHost.hold});
        return {streamId:id,status:200,headers:{'Content-Type':sse?'text/event-stream':'application/json'}};
      },readStream:async(id:string)=>{const s=streams.get(id);if(!s)throw new Error('Stream cancelled');if(s.hold)return new Promise(()=>{});const data=s.bytes.slice(s.offset,s.offset+17);s.offset+=data.length;return {data,done:s.offset>=s.bytes.length};},cancelStream:async(id:string)=>{streams.delete(id);w.__testHost.cancelled++;}},
      files:{save:async(name:string,mime:string,content:string)=>{w.__testHost.exports.push({name,mime,content});return {token:'saved'};},open:async()=>null,read:async()=>new Uint8Array()},
      share:{text:async(text:string)=>{w.__testHost.shared=text;}},browser:{open:async(url:string)=>{w.__testHost.external=url;}}
    };
  },{profile,scene,withProfile,withKey,key,learnerLine,npcLine,minimax});
  await page.route('https://**/*',route=>route.abort());
}
function consoleCheck(page:Page){const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});return errors;}
async function screenshot(page:Page,name:string){await page.screenshot({path:test.info().outputPath(name+'.png'),fullPage:false,animations:'disabled'});}
async function arena(page:Page){await page.goto('/#/arena');await expect(page.getByRole('heading',{level:1})).toBeVisible();await expect(page).toHaveTitle(/SocialCoach/);}
async function startScene(page:Page){await arena(page);await page.getByRole('searchbox').fill(scene.title.zh);await page.getByRole('button').filter({has:page.getByText(scene.title.zh,{exact:true})}).first().click();await expect(page.getByText('测试准备：先承认反馈延迟，再讨论具体安排。')).toBeVisible();await page.getByRole('button',{name:'进入对话',exact:true}).filter({visible:true}).click();await expect(page.getByRole('textbox',{name:'说点什么…',exact:true})).toBeVisible();}

test('fresh install has a working Chinese onboarding screen',async({page})=>{await setup(page,{withProfile:false,withKey:false});const errors=consoleCheck(page);await page.goto('/');await expect(page.getByRole('button',{name:'开始',exact:true})).toBeVisible();await screenshot(page,'onboarding');await page.getByRole('button',{name:'开始',exact:true}).click();await expect(page.getByRole('heading',{name:'选你最想提升的能力',exact:true})).toBeVisible();expect(errors).toEqual([]);});

test('offline corpus browsing, filtering and hash navigation work without a model',async({page})=>{await setup(page,{withKey:false});const errors=consoleCheck(page);await arena(page);await screenshot(page,'arena');await page.getByRole('searchbox').fill(scene.title.zh);await expect(page).toHaveURL(/#\/arena\?q=/);await expect(page.getByText(scene.title.zh,{exact:true}).first()).toBeVisible();await page.getByRole('combobox').first().click();await expect(page.getByRole('listbox')).toBeVisible();await page.evaluate(()=>history.back());await expect(page.getByRole('listbox')).not.toBeVisible();await expect(page).toHaveURL(/#\/arena\?q=/);await page.getByRole('button',{name:'清空搜索',exact:true}).click();await expect(page.getByRole('searchbox')).toHaveValue('');expect(await page.evaluate(()=>(window as any).__testHost.calls.length)).toBe(0);expect(errors).toEqual([]);});

test('full API key typing, model list selection, save and modal Back',async({page})=>{await setup(page,{withKey:false});const errors=consoleCheck(page);await page.goto('/#/settings');await page.getByRole('button').filter({hasText:'尚未配置模型'}).click();await page.getByLabel('API Key',{exact:true}).pressSequentially(key,{delay:2});await expect(page.getByLabel('API Key',{exact:true})).toHaveValue(key);await page.getByLabel('API 地址',{exact:true}).fill('https://mock.invalid/v1');await page.getByRole('button',{name:'读取模型',exact:true}).click();await expect(page.getByText(/已读取 2 个模型/)).toBeVisible();await page.getByRole('button',{name:'从模型列表选择',exact:true}).first().click();await page.getByRole('button',{name:'mock-chat',exact:true}).click();await page.getByRole('button',{name:'两个用途使用同一模型',exact:true}).click();await page.getByRole('button',{name:'测试并保存',exact:true}).click();await expect(page.getByText('两个模型均已验证，配置已保存。',{exact:true})).toBeVisible();await screenshot(page,'model-settings');await page.evaluate(()=>history.back());await expect(page.getByRole('dialog')).not.toBeVisible();await expect(page).toHaveURL(/#\/settings$/);expect(await page.evaluate(()=>(window as any).__testHost.ordinary['socialcoach.v1'].includes('mock-test-key'))).toBe(false);expect(errors).toEqual([]);});

test('scene preparation -> streamed dialogue -> grounded report -> backup and reopen',async({page})=>{await setup(page);const errors=consoleCheck(page);await startScene(page);await page.getByRole('textbox',{name:'说点什么…',exact:true}).fill(learnerLine);await page.getByRole('button',{name:'发送',exact:true}).click();await expect(page.getByText(npcLine,{exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'停止生成',exact:true})).not.toBeVisible();await screenshot(page,'conversation');await page.getByRole('button',{name:'暂停或结束练习',exact:true}).click();await page.getByRole('button',{name:'结束并复盘',exact:true}).click();await expect.poll(()=>page.evaluate(()=>JSON.parse((window as any).__testHost.ordinary['socialcoach.v1']).state.sessions[0].status)).toBe('assessed');const see=page.getByRole('button',{name:'看复盘',exact:true});if(await see.count())await see.click();await expect(page.getByText('测试复盘：表达了明确时间。',{exact:true})).toBeVisible();await screenshot(page,'review');const hash=new URL(page.url()).hash;await page.reload();await expect(page.getByText('测试复盘：表达了明确时间。',{exact:true})).toBeVisible();expect(new URL(page.url()).hash).toBe(hash);await page.goto('/#/settings');await page.getByRole('button').filter({hasText:'导出'}).first().click();const exported=await page.evaluate(()=>(window as any).__testHost.exports[0]);expect(exported.content).not.toContain(key);expect(JSON.parse(exported.content).sessions[0].report.scoringVersion).toBe(2);expect(errors).toEqual([]);});

test('cancel and edit-resend release the stream and retain one learner message',async({page})=>{
  await setup(page);
  const errors=consoleCheck(page);
  await startScene(page);
  const baseline=await page.evaluate(()=>{const h=(window as any).__testHost;h.hold=true;return {calls:h.calls.length,cancelled:h.cancelled};});
  await page.getByRole('textbox',{name:'说点什么…',exact:true}).fill(learnerLine);
  await page.getByRole('button',{name:'发送',exact:true}).click();
  await expect.poll(()=>page.evaluate(()=>(window as any).__testHost.calls.length)).toBeGreaterThan(baseline.calls);
  await page.getByRole('button',{name:'停止生成',exact:true}).click();
  // The alert deliberately includes a retry button; match its semantic container.
  await expect(page.getByRole('alert')).toContainText('请求已取消');
  await expect(page.getByRole('button',{name:'停止生成',exact:true})).not.toBeVisible();
  await expect.poll(()=>page.evaluate(()=>(window as any).__testHost.cancelled)).toBeGreaterThan(baseline.cancelled);
  const learnerCount=()=>page.evaluate(()=>JSON.parse((window as any).__testHost.ordinary['socialcoach.v1']).state.sessions[0].messages.filter((m:any)=>m.role==='learner').length);
  expect(await learnerCount()).toBe(1);
  await page.evaluate(()=>{(window as any).__testHost.hold=false;});
  await page.getByRole('button',{name:'重试',exact:true}).click();
  // Preserve upstream behavior: retry restores an editable draft, then the user sends.
  await expect(page.getByRole('textbox',{name:'说点什么…',exact:true})).toHaveValue(learnerLine);
  await expect.poll(learnerCount).toBe(0);
  await page.getByRole('button',{name:'发送',exact:true}).click();
  await expect(page.getByText(npcLine,{exact:true})).toBeVisible();
  await expect.poll(learnerCount).toBe(1);
  expect(errors).toEqual([]);
});

test('MiniMax preset saves correct M3 config and clears a key when switching regions',async({page})=>{
  await setup(page,{withKey:false});const errors=consoleCheck(page);
  await page.goto('/#/settings');await page.getByRole('button').filter({hasText:'尚未配置模型'}).click();
  await page.getByRole('button',{name:'MiniMax M3 · 国内',exact:true}).click();
  await page.getByLabel('API Key',{exact:true}).fill('mock-cn-key');
  await page.getByRole('button',{name:'MiniMax M3 · 国际',exact:true}).click();
  await expect(page.getByLabel('API Key',{exact:true})).toHaveValue('');
  await expect(page.getByLabel('API 地址',{exact:true})).toHaveValue('https://api.minimax.io/v1');
  await expect(page.getByLabel('对话模型',{exact:true})).toHaveValue('MiniMax-M3');
  await page.getByLabel('API Key',{exact:true}).fill(key);
  await page.getByRole('button',{name:'测试并保存',exact:true}).click();
  await expect(page.getByText('两个模型均已验证，配置已保存。',{exact:true})).toBeVisible();
  const calls=await page.evaluate(()=>(window as any).__testHost.calls);
  expect(calls).toHaveLength(1);expect(calls[0].body.thinking).toEqual({type:'disabled'});expect(calls[0].body.reasoning_split).toBe(true);expect(calls[0].body.max_completion_tokens).toBe(1024);
  await screenshot(page,'minimax-model-settings');
  await page.reload();
  const saved=await page.evaluate(()=>JSON.parse((window as any).__testHost.secure['socialcoach.llm.v1']).state);
  expect(saved.fastModel).toBe('MiniMax-M3');expect(saved.apiKey).toBe(key);expect(errors).toEqual([]);
});

test('MiniMax reasoning never flashes in hint, role dialogue, report or saved history',async({page})=>{
  await setup(page,{minimax:true});const errors=consoleCheck(page);await startScene(page);
  await page.getByRole('button',{name:'提示',exact:true}).click();
  await expect(page.getByText('先表达感受，再提出一个具体的沟通时间。',{exact:true})).toBeVisible();
  await expect(page.locator('body')).not.toContainText('PRIVATE_MINIMAX_TRACE');await screenshot(page,'minimax-hint');
  await page.getByRole('textbox',{name:'说点什么…',exact:true}).fill(learnerLine);
  await page.getByRole('button',{name:'发送',exact:true}).click();await expect(page.getByText(npcLine,{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'暂停或结束练习',exact:true}).click();await page.getByRole('button',{name:'结束并复盘',exact:true}).click();
  await expect.poll(()=>page.evaluate(()=>JSON.parse((window as any).__testHost.ordinary['socialcoach.v1']).state.sessions[0].status)).toBe('assessed');
  const see=page.getByRole('button',{name:'看复盘',exact:true});if(await see.count())await see.click();
  await expect(page.getByText('测试复盘：表达了明确时间。',{exact:true})).toBeVisible();await screenshot(page,'minimax-review');
  const state=await page.evaluate(()=>{const h=(window as any).__testHost;return {stored:h.ordinary['socialcoach.v1'],calls:h.calls,leaks:h.leaks};});
  expect(state.stored).not.toMatch(/PRIVATE_MINIMAX_TRACE|<\/?think/i);expect(state.leaks).toEqual([]);
  const hint=state.calls.find((c:any)=>JSON.stringify(c.body?.messages).includes('Give the hint.'));
  const assess=state.calls.find((c:any)=>JSON.stringify(c.body?.messages).includes('Produce the assessment JSON.'));
  expect(hint.body.thinking).toEqual({type:'disabled'});expect(hint.body.max_completion_tokens).toBe(800);
  expect(assess.body.thinking).toEqual({type:'adaptive'});expect(assess.body.max_completion_tokens).toBe(16384);
  for(const call of state.calls)expect(call.body.reasoning_split).toBe(true);
  await page.reload();await expect(page.getByText('测试复盘：表达了明确时间。',{exact:true})).toBeVisible();expect(errors).toEqual([]);
});

test('MiniMax reasoning-only hint shows an error and cannot become a saved coach message',async({page})=>{
  await setup(page,{minimax:true});const errors=consoleCheck(page);await startScene(page);
  await page.evaluate(()=>{(window as any).__testHost.onlyReasoning=true;});
  await page.getByRole('button',{name:'提示',exact:true}).click();
  await expect(page.getByRole('alert')).toContainText('模型未返回最终答案');
  const state=await page.evaluate(()=>{const h=(window as any).__testHost;return {session:JSON.parse(h.ordinary['socialcoach.v1']).state.sessions[0],leaks:h.leaks};});
  expect(state.session.messages.filter((m:any)=>m.role==='coach')).toHaveLength(0);expect(state.leaks).toEqual([]);
  await page.evaluate(()=>{(window as any).__testHost.onlyReasoning=false;});
  await page.getByRole('button',{name:'提示',exact:true}).click();
  await expect(page.getByText('先表达感受，再提出一个具体的沟通时间。',{exact:true})).toBeVisible();expect(errors).toEqual([]);
});


test('mobile chat keeps transcript and controls usable in a short viewport and wraps long tokens',async({page})=>{
  await setup(page);const errors=consoleCheck(page);await page.setViewportSize({width:360,height:420});await startScene(page);
  await page.locator('details.practice-context summary').click();
  const long='第一行\n第二行\n第三行\nhttps://example.invalid/'+('A'.repeat(260));
  const box=page.getByRole('textbox',{name:'说点什么…',exact:true});await box.fill(long);
  const before=await page.evaluate(()=>{
    const transcript=document.querySelector('.chat-transcript') as HTMLElement;
    const send=document.querySelector('button[aria-label="发送"]') as HTMLElement;
    return {doc:document.documentElement.scrollHeight,view:innerHeight,transcript:transcript.getBoundingClientRect().height,sendBottom:send.getBoundingClientRect().bottom};
  });
  expect(before.doc).toBeLessThanOrEqual(before.view+1);expect(before.transcript).toBeGreaterThanOrEqual(48);expect(before.sendBottom).toBeLessThanOrEqual(before.view+1);
  await page.getByRole('button',{name:'发送',exact:true}).click();await expect(page.getByText(npcLine,{exact:true})).toBeVisible();
  const overflow=await page.evaluate(()=>{
    const transcript=document.querySelector('.chat-transcript') as HTMLElement;
    const bubbles=Array.from(document.querySelectorAll('.bubble-me,.bubble-npc,.bubble-coach')) as HTMLElement[];
    return {transcript:transcript.scrollWidth-transcript.clientWidth,bubbles:Math.max(0,...bubbles.map(x=>x.scrollWidth-x.clientWidth))};
  });
  expect(overflow.transcript).toBeLessThanOrEqual(1);expect(overflow.bubbles).toBeLessThanOrEqual(1);expect(errors).toEqual([]);
});

test('phone journey is compact and review section buttons preserve the SPA route',async({page})=>{
  await setup(page);const errors=consoleCheck(page);await page.setViewportSize({width:320,height:568});await startScene(page);
  await expect(page.locator('.practice-journey-compact')).toBeVisible();
  const compact=await page.locator('.practice-journey-compact').boundingBox();expect(compact?.width??0).toBeGreaterThan(100);
  await page.getByRole('textbox',{name:'说点什么…',exact:true}).fill(learnerLine);await page.getByRole('button',{name:'发送',exact:true}).click();await expect(page.getByText(npcLine,{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'暂停或结束练习',exact:true}).click();await page.getByRole('button',{name:'结束并复盘',exact:true}).click();
  await expect.poll(()=>page.evaluate(()=>JSON.parse((window as any).__testHost.ordinary['socialcoach.v1']).state.sessions[0].status)).toBe('assessed');
  const see=page.getByRole('button',{name:'看复盘',exact:true});if(await see.count())await see.click();
  await expect(page.getByText('测试复盘：表达了明确时间。',{exact:true})).toBeVisible();
  const before=new URL(page.url()).hash;
  const first=page.locator('nav.review-index button').first();await expect(first).toBeVisible();await first.click();
  expect(new URL(page.url()).hash).toBe(before);await expect(page.getByText('测试复盘：表达了明确时间。',{exact:true})).toBeVisible();
  await page.goto('/#/progress');const row=page.locator('.progress-history-copy').first();await expect(row).toBeVisible();
  const width=await row.evaluate(el=>el.getBoundingClientRect().width);expect(width).toBeGreaterThan(110);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth-document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
  expect(errors).toEqual([]);
});

test('model settings stack primary actions on a narrow phone',async({page})=>{
  await setup(page,{withKey:false});const errors=consoleCheck(page);await page.setViewportSize({width:320,height:568});await page.goto('/#/settings');
  await page.getByRole('button').filter({hasText:'尚未配置模型'}).click();
  await page.getByLabel('API Key',{exact:true}).fill(key);await page.getByLabel('API 地址',{exact:true}).fill('https://mock.invalid/v1');
  await page.getByLabel('对话模型',{exact:true}).fill('mock-chat');await page.getByLabel('复盘模型',{exact:true}).fill('mock-assess');
  const load=page.getByRole('button',{name:'读取模型',exact:true}), save=page.getByRole('button',{name:'测试并保存',exact:true});
  const [a,b]=await Promise.all([load.boundingBox(),save.boundingBox()]);
  expect(a&&b).toBeTruthy();expect((b?.y??0)).toBeGreaterThan((a?.y??0)+(a?.height??0)-1);
  expect(a?.width??0).toBeGreaterThan(240);expect(b?.width??0).toBeGreaterThan(240);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth-document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
  expect(errors).toEqual([]);
});
