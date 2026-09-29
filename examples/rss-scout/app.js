/* RSS Scout 1.4.2 — unified RSSHub instance + additive private rules + Radar + Miniflux discovery. */
(function(){
  'use strict';
  const C=window.RSSScoutCore, R=window.RSSScoutRules, $=id=>document.getElementById(id);
  const KEYS={settings:'rss-scout.settings.v1',account:'rss-scout.miniflux.v1',rules:'rss-scout.radar.v2',legacyRules:'rss-scout.radar.v1',hub:'rss-scout.hub-auth.v1'};
  const defaults={hubBase:'https://rsshub.app',workerBase:'',useRadar:true,checkHome:true,timeout:20000,ruleMode:'auto',ruleUrl:'',usePrivateRules:true,privateRuleUrl:'https://raw.githubusercontent.com/gkeyes/ToolBox-Android/refs/heads/codex/refactor-lightweight-v2/examples/rss-scout/private-rules.json'};
  const state={api:null,network:null,miniflux:null,settings:{...defaults},account:null,hubAuth:null,cache:null,
    records:[],lastUrl:'',filter:'all',busy:false,writing:0,controller:null,nextId:1,scanWarning:'',
    accountDraft:null,categoryContext:'saved',categoryBusy:false,categoryEpoch:0,categoryController:null,
    connectController:null,syncController:null,ruleAttempts:[],modals:[],confirm:null,scroll:0};
  let toastTimer,renderQueued=false;
  function element(tag,className,text){const el=document.createElement(tag);if(className)el.className=className;if(text!==undefined)el.textContent=text;return el;}
  function icon(type){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');const path=document.createElementNS(svg.namespaceURI,'path');path.setAttribute('d',({check:'m5 12 4 4 10-10',copy:'M9 9h11v11H9zM15 5V3H3v12h2',more:'M5 12h.01M12 12h.01M19 12h.01',retry:'M20 7v5h-5M19 12a7 7 0 1 0-2 5'})[type]||'');svg.append(path);return svg;}
  function button(label,handler,className,disabled){const b=element('button',className||'text-button',label);b.type='button';b.disabled=!!disabled;b.addEventListener('click',handler);return b;}
  function status(id,text,type=''){const el=$(id);el.textContent=text;el.classList.toggle('error',type==='error');el.classList.toggle('success',type==='success');}
  function redact(value){let text=String(value||'');for(const secret of [state.account?.token,$('miniflux-token').value,state.hubAuth?.key,$('hub-key').value])if(secret)text=text.split(secret).join('[已隐藏]').split(encodeURIComponent(secret)).join('[已隐藏]');return text.replace(/([?&](?:key|code|token|api_key|access_token|password|auth)=)[^\s&#]+/gi,'$1[已隐藏]');}
  function safeError(error){let text=C.cleanText(redact(error?.message||String(error)),650);if(['PERMISSION_DENIED','NOT_DECLARED'].includes(error?.code))text+='。请在 ToolBox 的本工具权限页打开对应能力。';return text;}
  function displayURL(value){try{const u=new URL(value);u.search='';u.hash='';return u.href+(new URL(value).search?'?…':'');}catch(_){return C.cleanText(redact(value),400);}}
  function toast(text){clearTimeout(toastTimer);$('toast').textContent=text;$('toast').hidden=false;toastTimer=setTimeout(()=>{$('toast').hidden=true;},3400);}
  function note(text){$('log').textContent+=(new Date().toLocaleTimeString('zh-CN',{hour12:false}))+'  '+C.cleanText(redact(text),750)+'\n';}
  function validRecords(){return state.records.filter(f=>f.validation==='valid'||f.subStatus==='subscribed');}
  function categoryName(account){if(!account?.category)return '服务器默认分类';return account.categories?.find(c=>c.id===account.category)?.title||account.categoryTitle||('分类 #'+account.category);}
  function accountSignature(a){return a?.base&&a?.token?a.base+'\n'+a.token:'';}
  function hubKey(base=state.settings.hubBase){return state.hubAuth?.base===base?state.hubAuth.key:'';}
  function cacheUsable(){return R.cacheValid(state.cache,state.settings);}
  function topModal(){return state.modals.at(-1)?.id;}
  function updateModalState(){
    $('app-shell').inert=state.modals.length>0;document.body.style.overflow=state.modals.length?'hidden':'';
    state.modals.forEach((m,i)=>{const node=$(m.id);node.style.zIndex=String(30+i);node.inert=i!==state.modals.length-1;});
  }
  function openModal(id){if(state.modals.some(m=>m.id===id))return;state.modals.push({id,opener:document.activeElement});$(id).hidden=false;updateModalState();$(id).querySelector('[role=dialog]').focus({preventScroll:true});}
  function closeModal(){
    const item=state.modals.pop();if(!item)return;
    $(item.id).hidden=true;$(item.id).inert=false;
    if(item.id==='category-modal'){state.categoryEpoch++;state.categoryController?.abort();state.categoryBusy=false;state.categoryController=null;}
    if(item.id==='account-modal'){$('miniflux-token').type='password';$('toggle-token').textContent='显示';$('toggle-token').setAttribute('aria-pressed','false');$('toggle-token').setAttribute('aria-label','显示 API Token');}
    updateModalState();const focus=item.opener;
    if(focus?.isConnected && !focus.closest('[hidden]'))focus.focus({preventScroll:true});
    else if(topModal())$(topModal()).querySelector('[role=dialog]').focus({preventScroll:true});
  }
  const drafts=new Map();
  function draftValue(id){if(id==='account-modal')return JSON.stringify([$('miniflux-base').value,$('miniflux-token').value,state.accountDraft?.category]);if(id==='hub-modal')return JSON.stringify([$('rsshub-base').value,$('hub-key').value,$('use-radar').checked,document.querySelector('[name=rule-mode]:checked')?.value,$('rule-url').value,$('use-private-rules').checked,$('private-rule-url').value,$('rssworker-base').value]);if(id==='preferences-modal')return JSON.stringify([$('check-home').checked,$('timeout').value]);return '';}
  function confirmAction(title,message,label,handler){$('confirm-title').textContent=title;$('confirm-message').textContent=message;$('confirm-action').textContent=label;state.confirm=handler;openModal('confirm-modal');}
  function requestClose(){
    const id=topModal();if(!id)return;
    if(state.writing){if(state.connectController){state.connectController.abort();return;}toast(state.syncController?'请先停止同步，或等待同步结束':'操作进行中，请稍候');return;}
    if(drafts.has(id)&&drafts.get(id)!==draftValue(id)){confirmAction('放弃未保存修改？','本页修改尚未保存，已保存的配置不会改变。','放弃修改',()=>closeModal());return;}
    closeModal();
  }
  function settingsOpen(open){if(state.writing||state.busy){toast('请先完成或停止当前操作');return;}if(open)state.scroll=window.scrollY;$('discovery-view').hidden=open;$('settings-view').hidden=!open;window.scrollTo(0,open?0:state.scroll);summaries();if(open)$('close-settings').focus({preventScroll:true});else $('settings-toggle').focus({preventScroll:true});}
  function controls(){
    const locked=state.busy||state.writing>0;
    $('scan').disabled=!state.api||locked;$('scan').textContent=state.busy?'正在发现…':'发现 RSS';
    for(const id of ['paste','clear-url','url'])$(id).disabled=!state.api||locked;
    $('stop').hidden=!state.busy;$('scan-progress').hidden=!state.busy;
    $('deep-scan').disabled=!state.api||locked||!state.lastUrl;
    $('server-discover').disabled=!state.api||locked||!state.lastUrl||!state.miniflux?.ready();
    $('export').disabled=!state.api||locked||!validRecords().length;
    for(const id of ['save-account','connect','forget','save-hub','sync-rules','save-preferences'])$(id).disabled=!state.api||locked;
    $('category').disabled=locked;$('refresh-categories').disabled=state.categoryBusy||!state.api||locked;
    $('stop-sync').hidden=!state.syncController;$('stop-connect').hidden=!state.connectController;
    for(const id of ['account-form','hub-form','preferences-form'])for(const input of $(id).querySelectorAll('input'))input.disabled=locked;
    for(const b of $('category-list').querySelectorAll('button'))b.disabled=state.writing>0;
    $('settings-toggle').disabled=locked;$('destination').disabled=locked;
    $('category-modal').setAttribute('aria-busy',String(state.categoryBusy));
  }
  function summaries(){
    const a=state.account;
    $('destination-title').textContent=a?'订阅到 '+categoryName(a):'连接 Miniflux';
    $('destination-detail').textContent=a?'Miniflux · '+new URL(a.base).host+' · 点击切换分类':'配置后可一键订阅；不影响发现 RSS';
    $('account-summary').textContent=a?new URL(a.base).host+(a.username?' · '+a.username:''):'未配置 · 点击连接自己的阅读器';
    $('account-badge').textContent=a?(a.checkedAt?'已验证':'已保存'):'设置';
    $('category-summary').textContent=categoryName(a);
    $('hub-summary').textContent=!state.settings.useRadar?'已关闭':((state.settings.workerBase?'RSSHub + RSSWorker':'RSSHub')+' · '+(state.settings.ruleMode==='instance'?'当前实例':state.settings.ruleMode==='custom'?'自定义 Radar':'官方 Radar')+(state.settings.usePrivateRules?' + 私人规则':'')+' · '+(cacheUsable()?state.cache.rules.length+' 个匹配项':'尚未同步'));
    $('preferences-summary').textContent=(state.settings.checkHome?'首页补查开启':'仅检查当前页')+' · '+(state.settings.timeout?state.settings.timeout/1000+' 秒超时':'不限请求时长');
  }
  function showRuleStatus(){
    if(cacheUsable()){status('rule-status',state.cache.source+' · '+state.cache.rules.length+' 个字符串匹配项\n'+new Date(state.cache.at).toLocaleString('zh-CN',{hour12:false})+' 更新；跳过 '+state.cache.skipped+' 项');}
    else status('rule-status','尚未缓存匹配规则。首次探测或点击同步时获取。');
    summaries();
  }
  function rebuildMiniflux(){state.miniflux=new window.RSSScoutMiniflux(state.network,state.account);}
  function adoptAccount(account){const changed=accountSignature(account)!==accountSignature(state.account);state.account=account;rebuildMiniflux();if(changed)for(const f of state.records){f.subStatus='';f.subError='';delete f.feedId;}summaries();}
  function openAccount(){
    if(state.busy||state.writing)return;
    state.accountDraft=state.account?structuredClone(state.account):{categories:[],category:undefined};
    $('miniflux-base').value=state.account?.base||'';$('miniflux-token').value=state.account?.token||'';
    $('category-label').textContent=categoryName(state.accountDraft);
    status('connection-status',state.account?.checkedAt?'已保存连接 · '+(state.account.username||new URL(state.account.base).host):'填写地址和 Token 后测试连接，或直接保存。');
    status('account-save-status','');drafts.set('account-modal',draftValue('account-modal'));openModal('account-modal');controls();
  }
  function readAccount(){
    const rawBase=$('miniflux-base').value.trim(),token=$('miniflux-token').value.trim();
    if(!rawBase||!token)throw new Error('请填写 Miniflux 地址和 API Token');
    if(/[\u0000-\u0020\u007f]/.test(token))throw new Error('API Token 中不能包含空格或控制字符');
    const base=C.serviceBase(rawBase),draft=state.accountDraft||{};
    const same=accountSignature({base,token})===accountSignature(draft);
    const result={base,token,categories:same?(draft.categories||[]):[]};
    if(same){for(const key of ['category','categoryTitle','username','checkedAt'])if(draft[key]!==undefined)result[key]=draft[key];}
    return result;
  }
  function accountEdited(){
    if(!state.accountDraft)return;
    let base=$('miniflux-base').value.trim();try{base=C.serviceBase(base);}catch(_){}
    const token=$('miniflux-token').value.trim();
    if(accountSignature({base,token})!==accountSignature(state.accountDraft)){
      const restored=accountSignature({base,token})===accountSignature(state.account);
      state.accountDraft=restored?structuredClone(state.account):{base,token,categories:[]};$('category-label').textContent=categoryName(state.accountDraft);
      status('connection-status',restored?'已恢复保存的账户与分类。':'账户已更改，请重新读取分类。');
    }
    status('account-save-status','');
  }
  async function connect(){
    if(state.busy||state.writing)return;
    let account;try{account=readAccount();}catch(e){status('connection-status',safeError(e),'error');return;}
    state.writing++;state.connectController=new AbortController();controls();status('connection-status','正在验证账户并读取分类…');
    try{const info=await new window.RSSScoutMiniflux(state.network,account).connect(state.connectController.signal);account={...account,...info,checkedAt:Date.now()};
      if(account.category&&!info.categories.some(c=>c.id===account.category)){account.category=undefined;account.categoryTitle='';}
      state.accountDraft=account;$('category-label').textContent=categoryName(account);status('connection-status','连接成功 · '+(info.username||'已认证')+' · '+info.categories.length+' 个分类。点击右上角保存。','success');
    }catch(e){status('connection-status',state.connectController?.signal.aborted?'测试已停止，配置尚未保存。':safeError(e),'error');}
    finally{state.connectController=null;state.writing--;controls();}
  }
  async function saveAccount(event){event.preventDefault();if(state.busy||state.writing)return;
    let account;try{account=readAccount();}catch(e){status('account-save-status',safeError(e),'error');return;}
    state.writing++;controls();
    try{await state.api.storage.secure.set(KEYS.account,account);adoptAccount(account);drafts.set('account-modal',draftValue('account-modal'));closeModal();toast('Miniflux 账户已保存');render();}
    catch(e){status('account-save-status','未保存：'+safeError(e),'error');}
    finally{state.writing--;controls();}
  }
  async function forget(){
    if(state.busy||state.writing)return;
    confirmAction('移除 Miniflux 账户？','只移除本工具内的账户与分类缓存，不删除服务器订阅，也不撤销 API Token。','移除本地账户',async()=>{
      state.writing++;controls();try{await state.api.storage.secure.remove(KEYS.account);adoptAccount(null);drafts.delete('account-modal');closeModal();render();toast('已移除本地账户');}
      catch(e){status('account-save-status',safeError(e),'error');}finally{state.writing--;controls();}
    });
  }
  function pickerAccount(){return state.categoryContext==='draft'?state.accountDraft:state.account;}
  function renderCategories(){
    const account=pickerAccount(),q=$('category-search').value.trim().toLocaleLowerCase();$('category-list').replaceChildren();
    const categories=[{id:null,title:'服务器默认分类'},...(account?.categories||[])];
    if(account?.category&&!categories.some(c=>c.id===account.category))categories.splice(1,0,{id:account.category,title:categoryName(account)});
    let shown=0;
    for(const c of categories){if(q&&!c.title.toLocaleLowerCase().includes(q))continue;
      const b=button('',()=>selectCategory(c),'category-option',state.writing>0);b.dataset.category=c.id===null?'default':String(c.id);b.setAttribute('aria-pressed',String((account?.category||null)===c.id));b.append(element('span','option-title',c.title));if((account?.category||null)===c.id)b.append(icon('check'));$('category-list').append(b);shown++;
    }
    if(!shown)$('category-list').append(element('p','category-list-empty','没有匹配的分类。请换个关键词，或刷新服务器分类。'));
  }
  function openCategories(context='saved'){
    if(state.busy||state.writing)return;
    if(context==='saved'&&!state.account){openAccount();return;}
    state.categoryContext=context;state.categoryEpoch++;$('category-search').value='';status('category-status','');$('category-hint').textContent=context==='draft'?'选好分类后，点击账户页右上角“保存”。':'选择后立即保存，之后的新订阅默认使用此分类。';
    if(context==='draft'){try{state.accountDraft=readAccount();}catch(e){openModal('category-modal');renderCategories();status('category-status',safeError(e),'error');return;}}
    renderCategories();openModal('category-modal');
    const a=pickerAccount();
    if(!a?.categories?.length)refreshCategories();else status('category-status','已缓存 '+a.categories.length+' 个分类，可点击刷新。');
  }
  async function refreshCategories(){
    if(state.categoryBusy||state.writing||!state.api)return;
    let account;try{account=state.categoryContext==='draft'?readAccount():state.account;if(!account)throw new Error('请先连接 Miniflux');}catch(e){status('category-status',safeError(e),'error');return;}
    const epoch=state.categoryEpoch,context=state.categoryContext;state.categoryController=new AbortController();state.categoryBusy=true;controls();status('category-status','正在读取服务器分类…');
    try{
      const categories=await new window.RSSScoutMiniflux(state.network,account).categories(state.categoryController.signal);
      if(epoch!==state.categoryEpoch||topModal()!=='category-modal')return;
      const updated={...account,categories};let reset=false;
      if(updated.category&&!categories.some(c=>c.id===updated.category)){delete updated.category;delete updated.categoryTitle;reset=true;}
      if(context==='draft')state.accountDraft=updated;
      else{state.writing++;controls();try{await state.api.storage.secure.set(KEYS.account,updated);if(epoch!==state.categoryEpoch)return;adoptAccount(updated);}finally{state.writing--;controls();}}
      $('category-label').textContent=categoryName(state.accountDraft);renderCategories();status('category-status',(reset?'原分类已删除，已恢复服务器默认分类。':'')+(categories.length?'已读取 '+categories.length+' 个分类':'服务器未返回分类，可选择默认分类'));
    }catch(e){if(epoch===state.categoryEpoch&&!state.categoryController?.signal.aborted)status('category-status',safeError(e)+'；保留已有分类。','error');}
    finally{if(epoch===state.categoryEpoch){state.categoryBusy=false;state.categoryController=null;controls();}}
  }
  async function selectCategory(category){
    if(state.writing)return;
    state.categoryEpoch++;state.categoryController?.abort();state.categoryBusy=false;
    const account=pickerAccount();if(!account){status('category-status','先填写有效的 Miniflux 账户。','error');return;}
    const updated={...account};if(category.id){updated.category=category.id;updated.categoryTitle=category.title;}else{delete updated.category;delete updated.categoryTitle;}
    if(state.categoryContext==='draft'){state.accountDraft=updated;$('category-label').textContent=categoryName(updated);closeModal();return;}
    state.writing++;controls();
    try{await state.api.storage.secure.set(KEYS.account,updated);adoptAccount(updated);closeModal();toast('默认分类：'+categoryName(updated));}
    catch(e){status('category-status','分类未保存：'+safeError(e),'error');}
    finally{state.writing--;controls();}
  }
  function openHub(){if(state.busy||state.writing)return;const s=state.settings;$('rsshub-base').value=s.hubBase;$('rssworker-base').value=s.workerBase||'';$('hub-key').value=hubKey();$('use-radar').checked=s.useRadar;$('rule-url').value=s.ruleUrl;$('use-private-rules').checked=!!s.usePrivateRules;$('private-rule-url').value=s.privateRuleUrl||'';document.querySelector('[name=rule-mode][value="'+s.ruleMode+'"]').checked=true;toggleRuleField();togglePrivateField();status('hub-save-status','');showRuleStatus();renderRuleLog();drafts.set('hub-modal',draftValue('hub-modal'));openModal('hub-modal');controls();}
  function toggleRuleField(){$('custom-rule-field').hidden=document.querySelector('[name=rule-mode]:checked').value!=='custom';}
  function togglePrivateField(){$('private-rule-field').hidden=!$('use-private-rules').checked;}
  function readHub(){const mode=document.querySelector('[name=rule-mode]:checked').value;const usePrivate=$('use-private-rules').checked;const privateURL=$('private-rule-url').value.trim();const workerRaw=$('rssworker-base').value.trim();const settings={...state.settings,hubBase:C.serviceBase($('rsshub-base').value.trim()||defaults.hubBase),workerBase:workerRaw?C.serviceBase(workerRaw):'',useRadar:$('use-radar').checked,ruleMode:mode,ruleUrl:mode==='custom'?R.publicURL($('rule-url').value):$('rule-url').value.trim(),usePrivateRules:usePrivate,privateRuleUrl:usePrivate?R.publicURL(privateURL):privateURL};const key=$('hub-key').value.trim();if(/[\u0000-\u001f\u007f]/.test(key))throw new Error('实例密钥含有非法控制字符');return {settings,auth:key?{base:settings.hubBase,key}:null};}
  async function persistHub(){
    const {settings,auth}=readHub();
    if(JSON.stringify(auth)!==JSON.stringify(state.hubAuth)){if(auth)await state.api.storage.secure.set(KEYS.hub,auth);else await state.api.storage.secure.remove(KEYS.hub);state.hubAuth=auth;}
    await state.api.storage.set(KEYS.settings,settings);state.settings=settings;drafts.set('hub-modal',draftValue('hub-modal'));summaries();return settings;
  }
  async function saveHub(event){event.preventDefault();if(state.writing||state.busy)return;state.writing++;controls();try{await persistHub();closeModal();toast('RSS 服务设置已保存');}catch(e){status('hub-save-status','未全部保存：'+safeError(e),'error');}finally{state.writing--;controls();}}
  function renderRuleLog(){$('rule-log').textContent=state.ruleAttempts.length?state.ruleAttempts.map(a=>a.source+' · '+redact(a.message)).join('\n'):'尚无失败记录。';}
  async function syncRules(signal){
    state.ruleAttempts=[];
    try{const cache=await R.sync(state.network,state.settings,hubKey(),signal,(name,attempts)=>{state.ruleAttempts=attempts;status('rule-status','正在读取：'+name+'…');renderRuleLog();});
      if(signal?.aborted)throw new DOMException('已停止','AbortError');
      let persisted=true;try{await state.api.storage.set(KEYS.rules,cache);}catch(e){persisted=false;note('规则已获取，但缓存保存失败：'+safeError(e));}
      state.cache=cache;state.ruleAttempts=cache.attempts;showRuleStatus();renderRuleLog();if(cache.privateWarning)note('私人规则未更新：'+cache.privateWarning);
      if(!persisted)status('rule-status','已获取 '+cache.rules.length+' 个匹配项，但无法保存缓存；仅本次打开可用。','error');
      return {cache,persisted};
    }catch(e){
      state.ruleAttempts=e.attempts||state.ruleAttempts;renderRuleLog();
      const codes=[...new Set(state.ruleAttempts.map(a=>a.status).filter(Boolean))];
      const failure=signal?.aborted?'同步已停止。':codes.length?'规则更新未完成（HTTP '+codes.join(' / ')+'）。':'规则更新未完成：'+C.cleanText(state.ruleAttempts[0]?.message||safeError(e),150)+'。';
      status('rule-status',failure+(cacheUsable()?'继续使用原有 '+state.cache.rules.length+' 个匹配项。':'暂无可用规则；原生 RSS 发现不受影响。')+(!signal?.aborted&&state.ruleAttempts.length?'\n具体来源与原因见“同步详情”。':''),'error');throw e;
    }
  }
  async function syncManually(){if(state.writing||state.busy)return;state.writing++;controls();status('hub-save-status','');try{
    await persistHub();state.syncController=new AbortController();controls();const result=await syncRules(state.syncController.signal);toast(result.persisted?'规则已更新 · '+result.cache.source:'规则已获取，但缓存未保存');
  }catch(e){if(!state.syncController)status('hub-save-status','请先修正设置：'+safeError(e),'error');else if(!state.syncController.signal.aborted)note('规则同步未完成：'+safeError(e));}finally{state.syncController=null;state.writing--;controls();}}
  function openPreferences(){if(state.writing||state.busy)return;$('check-home').checked=state.settings.checkHome;$('timeout').value=state.settings.timeout/1000;status('preferences-status','');drafts.set('preferences-modal',draftValue('preferences-modal'));openModal('preferences-modal');controls();}
  async function savePreferences(event){event.preventDefault();if(state.writing||state.busy)return;const secs=Number($('timeout').value);if($('timeout').value.trim()===''||!Number.isSafeInteger(secs)||secs<0||secs>2147483){status('preferences-status','请输入 0–2147483 的整数秒数。','error');return;}state.writing++;controls();try{const settings={...state.settings,checkHome:$('check-home').checked,timeout:secs*1000};await state.api.storage.set(KEYS.settings,settings);state.settings=settings;drafts.set('preferences-modal',draftValue('preferences-modal'));closeModal();summaries();toast('探测偏好已保存');}catch(e){status('preferences-status',safeError(e),'error');}finally{state.writing--;controls();}}

  function addRadarCandidates(url){const items=C.radarCandidates(url,state.cache.rules,state.settings.hubBase,state.settings.workerBase);const key=hubKey();if(key)for(const item of items){if(item.kind!=='rsshub')continue;const u=new URL(item.url);u.searchParams.set('key',key);item.url=u.href;}addCandidates(items);}
  function candidate(input) {
    let url;try{url=C.urlOf(input.url);}catch(_){return null;}
    const old=state.records.find(f=>f.url===url||f.aliases.includes(url));
    if(old){for(const source of input.sources||[]) if(!old.sources.includes(source))old.sources.push(source);return old;}
    const record={id:state.nextId++,url,title:input.title||'待验证订阅源',kind:input.kind||'native',type:input.type||'',
      sources:[...(input.sources||[])],aliases:[url],validation:'pending',validationError:'',preview:[],subStatus:'',subError:''};
    state.records.push(record);return record;
  }
  function addCandidates(items) { for(const item of items)candidate(item);render(); }
  function mergeRedirect(record,finalUrl) {
    if(!record.aliases.includes(finalUrl))record.aliases.push(finalUrl);
    record.url=finalUrl;
    const other=state.records.find(f=>f!==record&&f.url===finalUrl);
    if(other && other.validation!=='checking') {
      for(const alias of other.aliases)if(!record.aliases.includes(alias))record.aliases.push(alias);
      for(const source of other.sources)if(!record.sources.includes(source))record.sources.push(source);
      state.records=state.records.filter(f=>f!==other);
    }
  }
  async function inspectPage(url,signal) {
    const r=await state.network.request(url,{signal,headers:{Accept:'text/html,application/rss+xml,application/atom+xml,application/feed+json,application/xml;q=0.9,*/*;q=0.5'}});
    if(r.status<200||r.status>=300)throw new Error('网页请求 HTTP '+r.status+'；可能需要登录、通过验证或稍后重试');
    const feed=C.parseFeed(r.text);
    if(feed) {
      const f=candidate({url:r.url,title:feed.title,sources:['直接订阅源'],kind:'native'});
      Object.assign(f,feed,{validation:'valid'});if(!f.aliases.includes(url))f.aliases.push(url);
      return {url:r.url,count:1,direct:true};
    }
    const extracted=C.extractPage(r.text,r.url,r.headers);addCandidates(extracted.candidates);
    return {url:r.url,count:extracted.candidates.length,direct:false};
  }
  async function checkCandidate(f,signal) {
    if(f.validation!=='pending')return;
    f.validation='checking';render();
    try {
      const isXhsWorker=f.kind==='rssworker'&&new URL(f.url).pathname.startsWith('/rss/xiaohongshu/user/');
      const timeoutMs=isXhsWorker?(state.settings.timeout===0?0:Math.max(state.settings.timeout,45000)):state.settings.timeout;
      const r=await state.network.request(f.url,{signal,timeoutMs,headers:{Accept:'application/rss+xml,application/atom+xml,application/feed+json,application/xml;q=0.9,*/*;q=0.5'}});
      if(signal.aborted)throw new DOMException('已停止','AbortError');
      if(r.status<200||r.status>=300)throw new Error('HTTP '+r.status+'；保留为候选，未验证成功');
      const feed=C.parseFeed(r.text);
      if(!feed){f.validation='invalid';f.validationError='响应不是可识别的 RSS / Atom / JSON Feed（也可能是登录页、验证码或本地解析不支持的源）';}
      else {Object.assign(f,feed,{validation:'valid',validationError:''});mergeRedirect(f,r.url);}
    } catch(error) {
      if(signal.aborted||error.name==='AbortError'||error.code==='CANCELLED'){f.validation='pending';f.validationError='已停止，尚未完成验证';}
      else {f.validation='error';f.validationError=safeError(error);}
    } finally {render();}
  }
  async function verifyPending(signal) {
    const queue=state.records.filter(f=>f.validation==='pending');let index=0;
    async function worker(){while(!signal.aborted&&index<queue.length){const f=queue[index++];if(state.records.includes(f))await checkCandidate(f,signal);}}
    await Promise.all([worker(),worker()]);
  }
  async function operation(mode) {
    if(!state.api||state.busy||state.writing)return;
    let url;try{url=mode==='scan'?C.inputUrl($('url').value):state.lastUrl;}catch(error){status('scan-status',safeError(error),'error');return;}
    if(!url)return;
    state.busy=true;state.controller=new AbortController();const signal=state.controller.signal;controls();
    let fatal='';
    try {
      if(mode==='scan') {
        state.records=[];state.filter='all';state.lastUrl=url;$('url').value=url;$('log').textContent='';render();
        state.scanWarning='';status('scan-status','正在检查网页声明与订阅源…');
        let page={url,count:0,direct:false};
        try{page=await inspectPage(url,signal);note(page.direct?'输入本身是可识别订阅源。':'已解析当前页声明与正文链接。');}
        catch(error){if(signal.aborted)throw error;state.scanWarning='网页读取未成功：'+safeError(error);note(state.scanWarning);if(C.feedHint(url)||new URL(url).protocol==='http:')candidate({url,title:'输入链接（未验证）',sources:['手动输入']});}
        if(signal.aborted)throw new DOMException('已停止','AbortError');
        state.lastUrl=page.url;
        if(!page.direct){
          addCandidates(C.nativeTemplates(url));if(page.url!==url)addCandidates(C.nativeTemplates(page.url));
          if(state.settings.checkHome&&page.count===0&&new URL(page.url).pathname!=='/'){
            try{await inspectPage(new URL('/',page.url).href,signal);note('已补查站点首页。');}
            catch(error){if(signal.aborted)throw error;note('首页补查：'+safeError(error));}
          }
          if(state.settings.useRadar){
            $('scan-status').textContent='正在匹配 RSSHub / RSSWorker 路由并验证候选…';
            if(!cacheUsable()){
              try{await syncRules(signal);}
              catch(error){if(signal.aborted)throw error;note('Radar 不可用，不影响原生源结果：'+safeError(error));state.scanWarning+=(state.scanWarning?'；':'')+'RSSHub 规则暂不可用，原生源已继续检查';}
            }
            if(cacheUsable()){
              addRadarCandidates(url);
              if(page.url!==url)addRadarCandidates(page.url);
              note('已匹配本地字符串规则；不执行函数规则，也不运行目标页面脚本。');
            }
          }
        }
      }else if(mode==='deep'){
        $('scan-status').textContent='正在补查站点的 7 个常见订阅路径…';addCandidates(C.commonPaths(url));note('用户触发了 7 个常见路径补查。');
      }else if(mode==='server'){
        if(!state.miniflux?.ready())throw new Error('请先配置并保存 Miniflux 账户');
        $('scan-status').textContent='已将网址发给你配置的 Miniflux 进行补查…';
        addCandidates(await state.miniflux.discover(url,signal));note('Miniflux 返回的是发现候选；仍需验证，不等同于创建订阅。');
      }
      await verifyPending(signal);
    }catch(error){if(!signal.aborted)fatal=safeError(error);}
    finally{
      const stopped=signal.aborted;state.busy=false;state.controller=null;controls();render();
      const n=state.records.filter(f=>f.validation==='valid').length;
      status('scan-status',fatal||((stopped?'已停止；保留当前结果。':'探测完成。')+' '+state.records.length+' 个候选，'+n+' 个已验证。'+(!state.records.length?'可使用更多发现方式继续补查。':'')+(state.scanWarning?' '+state.scanWarning:'')),fatal||(!n&&state.scanWarning)?'error':'');
    }
  }
  async function nativeAction(action){try{await action();}catch(e){toast(safeError(e));}}
  async function subscribe(f){
    if(state.busy||state.writing||['working','subscribed'].includes(f.subStatus))return;
    if(!state.miniflux?.ready()){openAccount();return;}
    state.writing++;const previous=f.subStatus;f.subStatus='working';f.subError='';controls();render();
    try{
      if(previous==='uncertain'){
        const existing=await state.miniflux.reconcile(f);
        if(!existing){f.subStatus='uncertain';f.subError='暂未查到订阅记录。没有重发创建请求，请先在 Miniflux 核对。';return;}
        f.subStatus='subscribed';f.feedId=existing.id;toast('已核对到 Miniflux 中的订阅');
      }else{
        const result=await state.miniflux.subscribe(f);f.subStatus='subscribed';f.feedId=result.id;
        toast(result.existing?'Miniflux 已有此订阅，未更改原分类':'已订阅到 '+categoryName(state.account));
      }
    }catch(e){f.subStatus=(previous==='uncertain'||e.uncertain)?'uncertain':'error';f.subError=safeError(e)+(f.subStatus==='uncertain'?'。结果待核对，未自动重发。':'');}
    finally{state.writing--;controls();render();}
  }
  async function copyFeed(f){await state.api.clipboard.writeText(f.url);toast(/[?&](key|code|token|auth|access_token)=/i.test(f.url)?'已复制；链接含鉴权参数，请勿公开分享':'已复制订阅链接');}
  function showFeedActions(f){
    const detail=$('feed-detail');detail.replaceChildren(element('h3','',f.title),element('p','detail-url',displayURL(f.url)));
    const list=element('div','action-list');
    list.append(button('复制完整链接',()=>nativeAction(()=>copyFeed(f))));
    list.append(button('在内置浏览器打开',()=>nativeAction(()=>state.api.browser.open(f.url))));
    list.append(button('分享链接',()=>{
      if(/[?&](key|code|token|auth|access_token)=/i.test(f.url)){confirmAction('分享含鉴权参数的链接？','完整链接可能包含你的实例密钥或订阅 Token。请仅分享给可信接收方。','继续分享',()=>nativeAction(()=>state.api.share.text(f.url)));}
      else nativeAction(()=>state.api.share.text(f.url));
    }));
    if(f.validation!=='valid')list.append(button('重新验证这个源',async()=>{
      if(state.busy||state.writing)return;closeModal();state.busy=true;state.controller=new AbortController();f.validation='pending';f.validationError='';controls();
      try{await checkCandidate(f,state.controller.signal);}finally{const stopped=state.controller.signal.aborted;state.busy=false;state.controller=null;controls();render();status('scan-status',stopped?'已停止验证':f.validation==='valid'?'该源已验证':'该源仍未通过验证，请查看错误详情');}
    }));
    detail.append(list);openModal('actions-modal');
  }
  function render(){controls();if(renderQueued)return;renderQueued=true;requestAnimationFrame(()=>{renderQueued=false;renderNow();});}
  function renderNow(){
    const list=$('results');
    for(const b of $('filters').querySelectorAll('[data-filter]'))b.setAttribute('aria-pressed',String(b.dataset.filter===state.filter));
    const records=state.records.filter(f=>state.filter==='all'||(state.filter==='valid'&&(f.validation==='valid'||f.subStatus==='subscribed'))||(state.filter==='route'&&['rsshub','rssworker'].includes(f.kind)));
    const valid=state.records.filter(f=>f.validation==='valid').length;
    $('result-count').textContent=state.records.length?state.records.length+' 个候选 · '+valid+' 个已验证':state.lastUrl?'暂未找到候选':'尚未开始探测';
    $('empty').hidden=records.length>0;
    if(!records.length){$('empty').querySelector('h3').textContent=state.lastUrl?'没有符合条件的结果':'等待一个网址';$('empty').querySelector('p').textContent=state.lastUrl?'尝试切换筛选，或展开“更多发现方式”继续补查。':'粘贴网页链接即可开始。没有原生 RSS，也可以寻找 RSSHub / RSSWorker 路由。';}
    const ids=new Set(records.map(f=>String(f.id)));for(const node of [...list.children])if(!ids.has(node.dataset.feedId))node.remove();
    for(const [index,f] of records.entries()){
      let card=list.querySelector('[data-feed-id="'+f.id+'"]');
      const signature=JSON.stringify([f.title,f.url,f.validation,f.validationError,f.subStatus,f.subError,f.type,f.sources,f.preview,f.count,state.busy,state.writing]);
      if(card && list.children[index]!==card)list.insertBefore(card,list.children[index]||null);
      if(card?.dataset.signature===signature)continue;
      const opened=card?.querySelector('details')?.open||false;
      const focusLabel=card?.contains(document.activeElement)?document.activeElement.getAttribute('aria-label')||document.activeElement.textContent:null;
      if(!card){card=element('article','feed');card.dataset.feedId=String(f.id);list.insertBefore(card,list.children[index]||null);}else card.replaceChildren();
      card.dataset.signature=signature;
      const top=element('div','feed-top');top.append(element('h3','feed-title',f.title));
      const label=f.subStatus==='subscribed'?'已订阅':({valid:'已验证',checking:'验证中',pending:'待验证',invalid:'非订阅源',error:'未验证'})[f.validation];
      top.append(element('span','badge '+((f.validation==='valid'||f.subStatus==='subscribed')?'valid':''),label));card.append(top);
      card.append(element('p','feed-meta',[f.kind==='rsshub'?'RSSHub 路由':f.kind==='rssworker'?'RSSWorker 路由':f.kind==='guess'?'路径候选':'网站原生',f.type].filter(Boolean).join(' · ')));
      card.append(element('p','feed-url',displayURL(f.url)));
      if(f.validationError)card.append(element('p','feed-error',redact(f.validationError)));
      if(f.subError)card.append(element('p','feed-error',redact(f.subError)));
      if(f.preview?.length){const details=element('details','preview');details.open=opened;details.append(element('summary','',f.count+' 条内容 · 预览前 '+f.preview.length+' 条'));for(const title of f.preview)details.append(element('p','preview-item',title));card.append(details);}
      const actions=element('div','feed-actions');
      const subLabel=f.subStatus==='subscribed'?'已订阅':f.subStatus==='working'?'正在订阅…':f.subStatus==='uncertain'?'核对订阅':f.validation==='valid'?'订阅 Miniflux':'尝试订阅';
      actions.append(button(subLabel,()=>subscribe(f),'subscribe',state.busy||state.writing>0||f.subStatus==='subscribed'||!state.api));
      const copy=button('',()=>nativeAction(()=>copyFeed(f)),'secondary-action',!state.api);copy.setAttribute('aria-label','复制链接');copy.append(icon('copy'));actions.append(copy);
      const more=button('',()=>showFeedActions(f),'secondary-action',state.busy||state.writing>0||!state.api);more.setAttribute('aria-label','更多操作');more.append(icon('more'));actions.append(more);
      card.append(actions);
      if(focusLabel){const replacement=[...card.querySelectorAll('button')].find(b=>(b.getAttribute('aria-label')||b.textContent)===focusLabel);replacement?.focus({preventScroll:true});}
    }
    controls();
  }
  async function exportFeeds(){
    try{const feeds=validRecords();if(!feeds.length)return;const result=await state.api.files.save('rss-scout.opml','text/x-opml',C.opml(feeds));toast(result?'OPML 已保存':'已取消保存');}
    catch(e){toast(safeError(e));}
  }
  $('settings-toggle').addEventListener('click',()=>settingsOpen(true));$('close-settings').addEventListener('click',()=>settingsOpen(false));
  $('edit-account').addEventListener('click',openAccount);$('edit-hub').addEventListener('click',openHub);$('edit-preferences').addEventListener('click',openPreferences);
  $('settings-category').addEventListener('click',()=>openCategories());$('destination').addEventListener('click',()=>openCategories());
  $('category').addEventListener('click',()=>openCategories('draft'));$('category-search').addEventListener('input',renderCategories);$('refresh-categories').addEventListener('click',refreshCategories);
  $('account-form').addEventListener('submit',saveAccount);$('connect').addEventListener('click',connect);$('stop-connect').addEventListener('click',()=>state.connectController?.abort());$('forget').addEventListener('click',forget);
  $('miniflux-base').addEventListener('input',accountEdited);$('miniflux-token').addEventListener('input',accountEdited);
  $('toggle-token').addEventListener('click',()=>{const shown=$('miniflux-token').type==='password';$('miniflux-token').type=shown?'text':'password';$('toggle-token').textContent=shown?'隐藏':'显示';$('toggle-token').setAttribute('aria-pressed',String(shown));$('toggle-token').setAttribute('aria-label',(shown?'隐藏':'显示')+' API Token');});
  $('hub-form').addEventListener('submit',saveHub);$('sync-rules').addEventListener('click',syncManually);$('stop-sync').addEventListener('click',()=>state.syncController?.abort());
  $('rsshub-base').addEventListener('input',()=>{let base=$('rsshub-base').value;try{base=C.serviceBase(base);}catch(_){}if(state.hubAuth?.base!==base)$('hub-key').value='';});
  for(const radio of document.querySelectorAll('[name=rule-mode]'))radio.addEventListener('change',toggleRuleField);$('use-private-rules').addEventListener('change',togglePrivateField);
  $('preferences-form').addEventListener('submit',savePreferences);
  for(const node of document.querySelectorAll('[data-close]'))node.addEventListener('click',requestClose);
  for(const node of document.querySelectorAll('.modal'))node.addEventListener('click',event=>{if(event.target===node&&node.id===topModal())requestClose();});
  $('confirm-action').addEventListener('click',async()=>{const fn=state.confirm;state.confirm=null;closeModal();try{await fn?.();}catch(e){toast(safeError(e));}});
  document.addEventListener('keydown',event=>{const id=topModal();if(!id)return;if(event.key==='Escape'){event.preventDefault();requestClose();return;}if(event.key==='Tab'){const nodes=[...$(id).querySelectorAll('button,input,textarea,[tabindex="0"]')].filter(n=>!n.disabled&&n.getClientRects().length);if(!nodes.length){event.preventDefault();return;}const i=nodes.indexOf(document.activeElement);if(event.shiftKey&&i<=0){event.preventDefault();nodes.at(-1).focus();}else if(!event.shiftKey&&(i<0||i===nodes.length-1)){event.preventDefault();nodes[0].focus();}}});
  $('search-form').addEventListener('submit',event=>{event.preventDefault();operation('scan');});
  $('deep-scan').addEventListener('click',()=>operation('deep'));$('server-discover').addEventListener('click',()=>operation('server'));
  $('stop').addEventListener('click',()=>{state.controller?.abort();status('scan-status','正在停止网络请求…');});
  $('paste').addEventListener('click',()=>nativeAction(async()=>{$('url').value=await state.api.clipboard.readText();$('url').focus();}));
  $('clear-url').addEventListener('click',()=>{$('url').value='';$('url').focus();});
  $('export').addEventListener('click',()=>{if(validRecords().some(f=>/[?&](key|code|token|auth|access_token)=/i.test(f.url)))confirmAction('导出含鉴权参数的链接？','OPML 中将保留完整订阅地址，可能包含访问密钥，请妥善保存。','导出 OPML',exportFeeds);else exportFeeds();});
  $('filters').addEventListener('click',event=>{const b=event.target.closest('[data-filter]');if(b){state.filter=b.dataset.filter;render();}});
  $('copy-log').addEventListener('click',()=>nativeAction(async()=>{if(!state.api)throw new Error('请在 ToolBox 中使用复制功能');const text='RSS Scout 1.4.2\n'+redact($('log').textContent)+'\n规则同步\n'+redact($('rule-log').textContent);await state.api.clipboard.writeText(text);toast('已复制诊断信息（密钥已隐藏）');}));
  async function init(){
    if(!window.ToolBox){$('environment').textContent='浏览器预览未连接原生能力。请将 .tbx 导入 ToolBox 使用。';controls();render();return;}
    try{
      await window.ToolBox.ready();if(!window.ToolBox.network?.openStream||!window.ToolBox.network?.readStream)throw new Error('当前运行环境不支持流式网络接口');
      state.api=window.ToolBox;state.network=new window.RSSScoutNetwork(state.api,()=>state.settings.timeout);const warnings=[];
      try{const s=await state.api.storage.get(KEYS.settings);if(s&&typeof s==='object')state.settings={...defaults,hubBase:C.serviceBase(s.hubBase||defaults.hubBase),workerBase:typeof s.workerBase==='string'&&s.workerBase.trim()?C.serviceBase(s.workerBase):'',useRadar:s.useRadar!==false,checkHome:s.checkHome!==false,timeout:Number.isSafeInteger(s.timeout)&&s.timeout>=0&&s.timeout<=2147483647?s.timeout:defaults.timeout,ruleMode:['auto','instance','custom'].includes(s.ruleMode)?s.ruleMode:'auto',ruleUrl:typeof s.ruleUrl==='string'?s.ruleUrl:'',usePrivateRules:s.usePrivateRules!==false,privateRuleUrl:typeof s.privateRuleUrl==='string'&&s.privateRuleUrl?s.privateRuleUrl:defaults.privateRuleUrl};}catch(e){warnings.push('设置读取失败：'+safeError(e));}
      try{const a=await state.api.storage.secure.get(KEYS.account);if(a&&typeof a.token==='string'&&a.token){state.account={base:C.serviceBase(a.base),token:a.token,categories:Array.isArray(a.categories)?a.categories.filter(c=>c&&Number.isSafeInteger(c.id)&&c.id>0).map(c=>({id:c.id,title:C.cleanText(c.title,200)})):[]};if(Number.isSafeInteger(a.category)&&a.category>0)state.account.category=a.category;if(a.categoryTitle)state.account.categoryTitle=C.cleanText(a.categoryTitle,200);if(a.username)state.account.username=C.cleanText(a.username,100);if(a.checkedAt)state.account.checkedAt=a.checkedAt;}}catch(e){warnings.push('安全存储读取失败：'+safeError(e));}
      try{const auth=await state.api.storage.secure.get(KEYS.hub);if(auth?.base&&typeof auth.key==='string')state.hubAuth={base:C.serviceBase(auth.base),key:auth.key};}catch(e){note('实例密钥不可读：'+safeError(e));}
      try{let c=await state.api.storage.get(KEYS.rules);if(!c){const old=await state.api.storage.get(KEYS.legacyRules);if(old?.base&&Array.isArray(old.rules))c={...old,sourceKey:'legacy:'+old.base,source:'旧版实例缓存',attempts:[]};}if(c&&Array.isArray(c.rules)&&Number.isFinite(c.at)&&typeof c.sourceKey==='string')state.cache={...c,skipped:Number.isSafeInteger(c.skipped)?c.skipped:0,source:c.source||'已缓存规则'};}catch(e){note('规则缓存不可读：'+safeError(e));}
      rebuildMiniflux();summaries();render();$('environment').classList.toggle('ready',warnings.length===0);$('environment').textContent=warnings.join(' ');
    }catch(e){state.api=null;$('environment').textContent=safeError(e);controls();}
  }
  init();
})();
