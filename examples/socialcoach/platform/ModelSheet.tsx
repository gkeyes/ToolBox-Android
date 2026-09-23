import {useEffect,useState} from 'react';
import {Button,Chip,Sheet,Spinner} from '@/components/ui';
import {useByok,isReady,type ByokConfig} from '@/lib/byok';
import {useLang} from '@/store/useApp';
import {pick} from '@/lib/i18n';
import {listModels,makeByokLLM,isMiniMaxM3} from './llm';
import {flushStorage} from './storage';
import {endpoint,abortRequests} from './network';

const input='min-w-0 w-full h-12 px-3 rounded-xl bg-card border border-line text-[15px]';
export function ModelSheet({open,onClose}: {open:boolean;onClose:()=>void;forced?:boolean}) {
  const lang=useLang(); const c=useByok();
  const [draft,setDraft]=useState<ByokConfig>(c);
  const [busy,setBusy]=useState(false), [note,setNote]=useState('');
  const [models,setModels]=useState<string[]>([]),[target,setTarget]=useState<'fastModel'|'smartModel'|null>(null);
  const [query,setQuery]=useState('');
  const tr=(zh:string,en:string)=>pick({zh,en},lang);
  useEffect(()=>{if(open){setDraft({...useByok.getState()});setNote('');setModels([]);setTarget(null);}},[open]);
  const update=(p:Partial<ByokConfig>)=>{setDraft(d=>({...d,...p}));setNote('');};
  const miniMaxPreset=(baseUrl:string)=>{
    let sameOrigin=false;
    try{sameOrigin=new URL(draft.baseUrl).origin===new URL(baseUrl).origin;}catch{}
    update({provider:'openai',baseUrl,fastModel:'MiniMax-M3',smartModel:'MiniMax-M3',tokenParam:'max_completion_tokens',apiKey:sameOrigin?draft.apiKey:''});
    setModels([]);setTarget(null);
    setNote(tr('已填入 MiniMax M3。请使用与该区域对应的 API Key；更换区域不会沿用原密钥。','MiniMax M3 selected. Use the API key for this region; switching regions clears the previous key.'));
  };
  const ready=isReady({...draft,enabled:true});
  const config:ByokConfig={enabled:true,provider:draft.provider,tokenParam:draft.tokenParam,apiKey:draft.apiKey.trim(),baseUrl:draft.baseUrl.trim(),fastModel:draft.fastModel.trim(),smartModel:draft.smartModel.trim()};
  const load=async()=>{
    setBusy(true);setNote('');
    try {const all=await listModels(config);setModels(all);setNote(all.length?tr(`已读取 ${all.length} 个模型，点击模型框下方按钮选择。`,`Loaded ${all.length} models.`):tr('未返回模型列表，请手动输入。','No model list returned; type a model ID.'));}
    catch(e){setNote((e as Error).message);}finally{setBusy(false);}
  };
  const save=async(test:boolean)=>{
    setBusy(true);setNote('');
    try {
      if(!ready)throw new Error(tr('请完整填写密钥和两个模型名称。','Fill in the key and both model IDs.'));
      if(config.baseUrl)endpoint(config.baseUrl,'models');
      if(test){
        for(const model of new Set([config.fastModel,config.smartModel])) await makeByokLLM(config).chatText({model,maxTokens:1024,thinking:false,system:'Reply with exactly: ok',messages:[{role:'user',content:'ok'}]});
      }
      c.set(config);await flushStorage();
      setNote(test?tr('两个模型均已验证，配置已保存。','Both models verified and saved.'):tr('配置已保存，尚未验证模型连接。','Saved; model connection not yet verified.'));
    }catch(e){setNote((e as Error).message);}finally{setBusy(false);}
  };
  return <Sheet open={open} onClose={()=>{abortRequests();onClose();}} title={tr('模型设置','Model settings')}>
    <div className="flex flex-col gap-4 pb-3">
      <p className="text-[13px] text-ink-3 leading-relaxed">{tr('训练记录留在本机。对话会发送到你填写的模型服务；API Key 由 ToolBox 安全存储保存。','Practice stays on this device. Your conversation is sent to your chosen model provider; ToolBox stores your key securely.')}</p>
      <div className="flex flex-wrap gap-2">{(['openai','anthropic'] as const).map(p=><Chip key={p} active={draft.provider===p} onClick={()=>{update({provider:p,baseUrl:'',fastModel:'',smartModel:''});setModels([]);}}>{p==='openai'?tr('OpenAI / 兼容接口','OpenAI / compatible'):'Anthropic'}</Chip>)}</div>
      <div className="flex flex-wrap gap-2"><Button size="sm" variant="secondary" disabled={busy} onClick={()=>miniMaxPreset('https://api.minimaxi.com/v1')}>{tr('MiniMax M3 · 国内','MiniMax M3 · China')}</Button><Button size="sm" variant="secondary" disabled={busy} onClick={()=>miniMaxPreset('https://api.minimax.io/v1')}>{tr('MiniMax M3 · 国际','MiniMax M3 · Global')}</Button></div>
      {(isMiniMaxM3(config,config.fastModel)||isMiniMaxM3(config,config.smartModel))&&<p className="text-[12px] text-ink-3 leading-relaxed">{tr('M3 专用适配已启用：提示与角色对话关闭思考，复盘保留推理；只显示最终答案。无需读取模型列表。','M3 adaptation is active: hints and dialogue skip thinking; assessments retain reasoning. Only final answers are shown. Loading the model list is optional.')}</p>}
      <label className="flex flex-col gap-2 text-sm">{tr('API 地址','API base URL')}<input className={input} value={draft.baseUrl} onChange={e=>{update({baseUrl:e.target.value});setModels([]);}} placeholder={draft.provider==='openai'?'https://api.openai.com/v1':'https://api.anthropic.com/v1'} autoCapitalize="none" autoCorrect="off" spellCheck={false}/></label>
      <p className="text-[12px] text-ink-3">{tr('留空使用官方地址。兼容接口填写到 /v1 等基路径，不要填 /chat/completions。Gemini 可使用其 OpenAI 兼容端点。','Leave blank for the official endpoint. Use the base path, not /chat/completions. Gemini can use its OpenAI-compatible endpoint.')}</p>
      <label className="flex flex-col gap-2 text-sm">API Key<input className={input} type="password" value={draft.apiKey} onChange={e=>{update({apiKey:e.target.value});setModels([]);}} autoComplete="off" autoCapitalize="none" spellCheck={false} placeholder={tr('粘贴你的密钥','Paste your API key')}/></label>
      {(['fastModel','smartModel'] as const).map(k=><label key={k} className="flex flex-col gap-2 text-sm">{k==='fastModel'?tr('对话模型','Conversation model'):tr('复盘模型','Assessment model')}
        <input className={input} value={draft[k]} onChange={e=>update({[k]:e.target.value})} placeholder={tr('填写服务提供的完整模型名称','Exact model ID from your provider')} autoCapitalize="none" spellCheck={false}/>
        {!!models.length&&<Button size="sm" variant="secondary" onClick={()=>{setTarget(target===k?null:k);setQuery('');}}>{tr('从模型列表选择','Choose from model list')}</Button>}
        {target===k&&<div className="rounded-xl border border-line p-2"><input className={input} aria-label={tr('搜索模型','Search models')} value={query} onChange={e=>setQuery(e.target.value)} placeholder={tr('搜索模型','Search models')}/><div className="max-h-48 overflow-y-auto">{models.filter(m=>m.toLowerCase().includes(query.toLowerCase())).slice(0,100).map(m=><button type="button" className="w-full min-h-11 text-left px-2 py-2 break-all border-b border-line text-sm" key={m} onClick={()=>{update({[k]:m});setTarget(null);}}>{m}</button>)}</div></div>}
      </label>)}
      <button className="text-sm text-accent-deep min-h-11 text-left" type="button" onClick={()=>update({smartModel:draft.fastModel})}>{tr('两个用途使用同一模型','Use the conversation model for both')}</button>
      {draft.provider==='openai'&&<div className="flex flex-col gap-2"><span className="text-sm">{tr('令牌参数','Token parameter')}</span><div className="flex flex-wrap gap-2">{(['max_tokens','max_completion_tokens'] as const).map(p=><Chip key={p} active={draft.tokenParam===p} onClick={()=>update({tokenParam:p})}>{p}</Chip>)}</div><p className="text-xs text-ink-3">{tr('推理模型若不接受 max_tokens，请选 max_completion_tokens。','Choose max_completion_tokens when required by a reasoning model.')}</p></div>}
      {note&&<p role="status" className="text-sm text-ink-2 leading-relaxed break-words">{note}</p>}
      <div className="model-actions grid grid-cols-1 sm:grid-cols-[auto_minmax(0,1fr)] gap-2"><Button block variant="secondary" disabled={busy||!draft.apiKey.trim()} onClick={load}>{tr('读取模型','Load models')}</Button><Button block disabled={busy||!ready} onClick={()=>save(true)}>{busy?<Spinner/>:tr('测试并保存','Test and save')}</Button></div>
      <Button variant="ghost" disabled={busy||!ready} onClick={()=>save(false)}>{tr('仅保存配置','Save without testing')}</Button>
      {busy&&<Button variant="ghost" onClick={abortRequests}>{tr('取消请求','Cancel request')}</Button>}
    </div>
  </Sheet>;
}
