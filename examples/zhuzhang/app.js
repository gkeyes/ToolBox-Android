'use strict';
(() => {
  // Offline mini-app; host I/O is isolated in runtime.js. No remote resources or CDN dependencies.
  // Ledger money is integer cents. DOM content originating from users is escaped.
  const KEY = 'ledger.v1';
  const runtime = window.ZZRuntime;
  const VERSION = '1.5.8';
  const MAX_CENTS = 100000000000;
  const $ = s => document.querySelector(s);
  const $$ = s => [...document.querySelectorAll(s)];
  const clone = value => JSON.parse(JSON.stringify(value));
  const esc = value => String(value ?? '').replace(/[&<>"']/g, x => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[x]));
  const icon = (name, cls='') => `<svg class="icon ${cls}" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><use href="#i-${name}"/></svg>`;
  const fmt = cents => (cents/100).toLocaleString('zh-CN',{minimumFractionDigits:cents%100?2:0,maximumFractionDigits:2});
  const money = cents => '¥' + fmt(cents);
  const signed = cents => (cents > 0 ? '+' : cents < 0 ? '−' : '') + money(Math.abs(cents));
  const unitNumber = v => Number(v).toLocaleString('zh-CN',{maximumFractionDigits:3});
  const rawMoney = cents => cents == null ? '' : (cents/100).toFixed(cents%100?2:0);
  const uid = prefix => prefix + '-' + (globalThis.crypto?.randomUUID ? crypto.randomUUID() : Date.now().toString(36)+Math.random().toString(36).slice(2));
  const nowDate = () => {const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;};
  const localDay = iso => {const d=new Date(iso);return isNaN(d)?String(iso??'').slice(0,10):`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;};
  const dateText = d => {const a=d.split('-');return `${Number(a[1])}月${Number(a[2])}日`;};
  const validDate = d => /^\d{4}-\d{2}-\d{2}$/.test(d) && !isNaN(Date.parse(d)) && new Date(d+'T12:00:00Z').toISOString().slice(0,10)===d;
  function fixed(value, scale=2, name='金额') {
    const s=String(value??'').trim();
    if(!new RegExp('^\\d+(?:\\.\\d{1,'+scale+'})?$').test(s))throw new Error(`${name}请输入非负数，最多 ${scale} 位小数。`);
    const [a,b='']=s.split('.'); const n=Number(a)*10**scale+Number(b.padEnd(scale,'0'));
    if(!Number.isSafeInteger(n)||n>MAX_CENTS)throw new Error(`${name}超出可处理范围。`);
    return n;
  }
  const CATS=[
    {id:'construction',name:'基础施工',icon:'tool',color:'#4f7963'},
    {id:'materials',name:'主材设备',icon:'tile',color:'#859b78'},
    {id:'custom',name:'全屋定制',icon:'cabinet',color:'#bdc59c'},
    {id:'furniture',name:'家具家电',icon:'sofa',color:'#c5ab8a'},
    {id:'soft',name:'软装杂项',icon:'lamp',color:'#cbd4c3'}
  ];
  const ROOMS=['全屋','客餐厅','厨房','主卧','次卧','次卧2','卫生间','阳台'];
  const HM=window.ZZHomeModel; // 1.4.0 我的家: templates, saved-shape sanitizers, placement math (home-model.js)
  const NAV=[['overview','grid','总览','预算总览'],['ledger','list','清单','费用清单'],['statistics','chart','统计','预算统计'],['calculator','calc','测算','费用测算'],['home','home','我的家','我的家']];
  const cat = id => CATS.find(c=>c.id===id) || CATS[0];

  // Rebuild a whitelisted shape on restore; reject dangling references and invalid amounts.
  function validate(source){
    const fail = message => {throw new Error(message||'备份结构无效，未更改当前数据。');};
    if(!source||![1,2].includes(source.schemaVersion)||!source.project||!Array.isArray(source.items)||!Array.isArray(source.payments))fail('备份格式不受支持，请选择筑账导出的 JSON 文件。');
    
    const string=(v,max,empty=true)=>{if(typeof v!=='string'||v.length>max||(!empty&&!v.trim()))fail('备份中存在无效文本字段。');return v;};
    const cents=(v,positive=false)=>{if(!Number.isSafeInteger(v)||v<0||v>MAX_CENTS||(positive&&v===0))fail('备份中存在无效金额。');return v;};
    const p=source.project;
    const out={schemaVersion:2,project:{name:string(p.name,60,false),area:string(p.area??'',20),layout:string(p.layout??'',60),budget:cents(p.budget),initialBudget:cents(p.initialBudget??p.budget)},revision:Number.isSafeInteger(source.revision)&&source.revision>=0?source.revision:0,completedDrafts:[],items:[],payments:[],changes:[]};
    // 1.4.0: optional floor plan. Unknown template → dropped (the plan is derived from 户型 again); sizes clamped.
    const home=HM.sanitizeHome(p.home);if(home)out.project.home=home;
    if(source.completedDrafts!==undefined){if(!Array.isArray(source.completedDrafts))fail('草稿提交记录无效。');out.completedDrafts=[...new Set(source.completedDrafts.map(id=>string(id,100,false)))];}
    const ids=new Set();
    for(const i of source.items){
      if(!i||ids.has(i.id))fail('备份中费用项 ID 重复。');
      const id=string(i.id,100,false);ids.add(id);
      if(!CATS.some(c=>c.id===i.category)||!['estimate','confirmed','settled'].includes(i.status))fail('备份中存在不支持的分类或状态。');
      if(i.due&&!validDate(i.due))fail('备份中付款日期无效。');
      out.items.push({id,name:string(i.name,100,false),category:i.category,room:string(i.room,50,false),budget:i.budget===null?null:cents(i.budget),price:i.price===null?null:cents(i.price),costMode:source.schemaVersion===1?'estimated':(i.costMode||'estimated'),status:i.status,merchant:string(i.merchant??'',100),due:string(i.due??'',10),note:string(i.note??'',1500)});
      // 1.4.0: optional look and position in 我的家. Unknown look → dropped; fractions and quarter turns clamped.
      const model=HM.sanitizeModel(i.model);if(model)out.items[out.items.length-1].model=model;
    }
    const pids=new Set();
    for(const t of source.payments){
      if(!t||pids.has(t.id)||!ids.has(t.itemId)||!['payment','refund'].includes(t.kind)||!validDate(t.date))fail('备份中流水无效、重复或缺少关联项目。');
      pids.add(string(t.id,100,false));
      out.payments.push({id:t.id,itemId:t.itemId,amount:cents(t.amount,true),date:t.date,kind:t.kind,note:string(t.note??'',1500)});
    }
    for(const i of out.items){
      const net=out.payments.filter(t=>t.itemId===i.id).reduce((s,t)=>s+(t.kind==='refund'?-t.amount:t.amount),0);
      if(!['estimated','actual'].includes(i.costMode)||(i.costMode==='actual'&&i.price!==null))fail('费用计价方式无效。');
      if(net<0)fail('备份中退款超过了关联项目已付金额。');if(i.status==='estimate'&&net>0)fail('有净付款的费用项不能标记为未确认。');
      if(i.status==='settled'&&i.costMode!=='actual'&&(i.price===null||net!==i.price))fail('已结算项目的金额与净付款不一致。');
    }
    if(Array.isArray(source.changes))out.changes=source.changes.map(c=>({date:string(c?.date,40),itemId:string(c?.itemId,100),note:string(c?.note,2500)}));
    const total=out.items.reduce((s,i)=>s+i.budget+(i.price??0),0)+out.payments.reduce((s,t)=>s+t.amount,0);
    if(!Number.isSafeInteger(total))fail('备份合计超出精确计算范围。');
    return out;
  }
  function emptyLedger(){return {schemaVersion:2,revision:0,completedDrafts:[],project:{name:'装修项目',area:'',layout:'',budget:0,initialBudget:0},items:[],payments:[],changes:[]};}
  let storageOK=false,restoreError='',bootError='',rawBackup=null;
  let data=emptyLedger(),busy=false,busyBackground=false,actionWaiting=false,waitSeq=0,waitButton=null;
  const WAIT_MSG='我的家正在保存，稍等一下…';
  let entryValues={};
  let activeDraftWritten=false,draftsUnreadable=false,drafts=[],storedDraftIds=new Set(),activeDraftOriginal=null,activeDraftId='',activeDraftBase=null,activeDraftReview=false,pendingNavigation=null,draftBusy=false;
  let draftTimer=null,draftQueue=Promise.resolve(),draftSnapshot='',calcTimer=null,calcQueue=Promise.resolve(),calcSaving=Promise.resolve(true);

  let page=['overview','ledger','calculator','statistics','home'].includes(location.hash.slice(1))?location.hash.slice(1):'overview';
  const ui={page,ledgerTab:'items',category:'all',room:'all',merchant:null,query:'',statsGroup:'category',calcMode:'area',calc:{quantity:'',price:'',loss:'0',labor:'0',auxiliary:'0',freight:'0',install:'0',pack:'',packing:false,fixed:''},baseline:null};
  let pickerState=null, pendingImport=null, applyResult=null, toastTimer;
  // background: a 我的家 change (moving furniture, the floor plan) that is not the open form's submission.
  async function write(next,{background=false}={}){
    if(busy&&(background||!busyBackground))return false;
    // The form locks first: a tap on 保存 during a 我的家 background save waits for it instead of being dropped.
    if(!background)document.body.classList.add('saving');
    const submit=background?null:$('#sheet [type="submit"]');
    const label=submit?.innerHTML;
    const controls=background?[]:$$('#sheet input,#sheet button,#sheet textarea').map(el=>[el,el.disabled]);
    controls.forEach(([el])=>el.disabled=true);
    if(submit){submit.disabled=true;submit.textContent='正在保存…';}
    const unlock=()=>{if(!background)document.body.classList.remove('saving');controls.forEach(([el,wasDisabled])=>{if(el.isConnected)el.disabled=wasDisabled;});if(submit?.isConnected){submit.disabled=false;submit.innerHTML=label;}};
    if(busy){
      const before=data;
      for(let n=0;n<200&&busy;n++)await new Promise(r=>setTimeout(r,50));
      if(busy){unlock();showError('我的家还在保存，这笔记录未保存，请稍后再按保存。');return false;}
      // Background saves only touch item.model and project.home; carry over what they changed unless the form changed it too.
      if(data!==before){const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b),old=new Map(before.items.map(i=>[i.id,i.model])),fresh=new Map(data.items.map(i=>[i.id,i]));for(const i of next.items||[]){const f=fresh.get(i.id),o=old.get(i.id);if(f&&!same(o,f.model)&&same(o,i.model)){if(f.model)i.model=clone(f.model);else delete i.model;}}if(next.project&&!same(before.project.home,data.project.home)&&same(before.project.home,next.project.home)){if(data.project.home)next.project.home=clone(data.project.home);else delete next.project.home;}}
    }
    busy=true;busyBackground=background;
    try{
      if(!storageOK)throw new Error(bootError||'存储未就绪；记录未保存，请重试连接。');
      if(restoreError)throw new Error('原始账本无法解析。请先导出原始数据，再导入有效备份。');
      if(!background&&activeDraftId&&$('#sheet').open&&$('#sheet form[data-form]'))next.completedDrafts=[...new Set([...(next.completedDrafts||[]),activeDraftId])];
      if(!draftsUnreadable)next.completedDrafts=(next.completedDrafts||[]).filter(id=>id===activeDraftId||storedDraftIds.has(id));
      next=validate(next);next.revision=(data.revision||0)+1;
      // A slow store gets a word after 10 s, but the write keeps the lock until it settles so nothing can overtake it.
      const slowMsg='存储响应很慢，仍在等待保存完成，请不要关闭。';let slowShown=false;
      const slow=setTimeout(()=>{slowShown=true;showError(slowMsg);},10000);
      // Once the write settles the warning is stale, wherever it landed.
      try{await runtime.set(KEY,next);}finally{clearTimeout(slow);if(slowShown)$$('.error-message').forEach(x=>{if(x.textContent===slowMsg)x.remove();});}
      data=next;
      return true;
    }catch(error){if(!background)showError(runtime.explain(error));return false;}
    finally{busy=false;busyBackground=false;unlock();}
  }
  const paymentIndex = new WeakMap();
  function cashIndex(source){
    if(!paymentIndex.has(source)){
      const index=new Map();
      for(const p of source.payments)index.set(p.itemId,(index.get(p.itemId)||0)+(p.kind==='refund'?-p.amount:p.amount));
      paymentIndex.set(source,index);
    }
    return paymentIndex.get(source);
  }
  function paid(i,source=data){return cashIndex(source).get(i.id)||0;}

  const missingPrice=i=>i.costMode!=='actual'&&i.price===null;
  const expected=(i,source=data)=>Math.max(paid(i,source),i.costMode==='actual'?0:(i.price??0));
  const remaining=(i,source=data)=>i.costMode==='actual'||i.price===null?0:Math.max(i.price-paid(i,source),0);
  const overpaid=(i,source=data)=>i.costMode==='actual'||i.price===null?0:Math.max(paid(i,source)-i.price,0);
  const budgetText=i=>i.budget===null?'未设置':money(i.budget);
  const priceText=i=>i.costMode==='actual'?'按实际支出':i.price===null?'待估价':money(i.price);
  const overshoots=()=>data.items.filter(i=>i.budget!==null&&expected(i)>i.budget).sort((a,b)=>(expected(b)-b.budget)-(expected(a)-a.budget));
  function totals(source=data,items=source.items){
    return items.reduce((a,i)=>{
      const p=paid(i,source),r=remaining(i,source);
      a.budget+=i.budget??0;a.expected+=expected(i,source);a.paid+=p;a.remaining+=r;
      a.overpaid+=overpaid(i,source);a.unknown+=missingPrice(i)?1:0;a.unbudgeted+=i.budget===null?1:0;
      if(i.status==='estimate')a.unordered+=r;else a.confirmedUnpaid+=r;
      return a;
    },{budget:0,expected:0,paid:0,remaining:0,overpaid:0,unknown:0,unbudgeted:0,unordered:0,confirmedUnpaid:0});
  }
  function groupItems(items=data.items,by='category'){
    const keys=by==='category'?CATS.map(c=>c.id):[...new Set(items.map(i=>i[by]||'未填写'))];
    return keys.map(key=>({key,name:by==='category'?cat(key).name:key,items:items.filter(i=>(i[by]||'未填写')===key)})).filter(g=>g.items.length).map(g=>({...g,...totals(data,g.items)}));
  }
  function statusText(i){return i.status==='settled'?'已结算':i.costMode==='actual'?'已记账':i.price===null?'待估价':i.status==='estimate'?'待确认':'已确认';}
  function badge(i){return `<span class="badge ${missingPrice(i)?'warn':i.status==='estimate'?'gray':''}">${statusText(i)}</span>`;}
  function comparisonLabel(g){return g.unbudgeted?'预算未齐':g.expected>g.budget?(g.unknown?'至少超出 ':'超出 ')+money(g.expected-g.budget):g.unknown?'待补估价':signed(g.expected-g.budget);}

  // action: {label, run} adds one button (1.5.0 「去摆放」); the toast then stays a little longer and takes taps.
  // warn: a failure; it shows the info mark instead of the tick and stays a little longer.
  // ms: how long it stays, for a notice that lasts as long as a wait.
  function toast(text,action,warn=false,ms=0){
    const el=$('#toast');clearTimeout(toastTimer);el.classList.toggle('warn',warn);el.innerHTML=icon(warn?'info':'check')+`<span class="toast-text">${esc(text)}</span>`+(action?`<button type="button" class="toast-action">${esc(action.label)}</button>`:'');
    el.classList.toggle('has-action',Boolean(action));
    const hide=()=>hideToast();
    if(action)el.querySelector('.toast-action').onclick=()=>{hide();action.run();};
    try{if(el.showPopover&&!el.matches(':popover-open'))el.showPopover();}catch(_){}
    el.classList.add('show');toastTimer=setTimeout(hide,ms||(action||warn?5200:3000));
  }
  // only: hide just when that text is still showing, so a newer notice stays.
  function hideToast(only){
    const el=$('#toast');if(only&&el.querySelector('.toast-text')?.textContent!==only)return;
    clearTimeout(toastTimer);el.classList.remove('show');try{if(el.hidePopover&&el.matches(':popover-open'))el.hidePopover();}catch(_){}
  }
  // Drops a tap that is waiting for a background save, at once, so the next tap counts.
  function cancelWait(){
    if(!actionWaiting)return;waitSeq++;actionWaiting=false;
    if(waitButton){waitButton.classList.remove('pending');waitButton.removeAttribute('aria-busy');waitButton=null;}
    hideToast(WAIT_MSG);
  }
  function showError(message){if(ui.page==='home'&&!$('#guard').open&&!$('#sheet').open&&matchMedia('(max-width:700px)').matches){toast(message,null,true);return;}const body=$('#guard').open?$('#guard .guard-content'):$('#sheet').open?$('#sheet-body'):$('#main');let box=body.querySelector('.error-message');if(!box){box=document.createElement('div');box.className='error-message';box.setAttribute('role','alert');body.prepend(box);}box.textContent=message;box.scrollIntoView({block:'nearest',behavior:'smooth'});}
  function dialog(title,html){
    clearTimeout(draftTimer);
    $('#sheet-title').textContent=title;$('#sheet-body').innerHTML=html;
    if(!$('#sheet').open)$('#sheet').showModal();
    $('#sheet-body').scrollTop=0;
    draftSnapshot=formSnapshot();activeDraftId=$('#sheet form[data-form]')?uid('d'):'';activeDraftOriginal=null;activeDraftWritten=false;activeDraftBase=draftBase();activeDraftReview=false;
    $('#draft-status').textContent='';
    // Opening a sheet never summons the soft keyboard without a field tap.
    $('#sheet [data-action="close-dialog"]').focus({preventScroll:true});
  }
  function closeDialog(force=false){
    // A form save (body.saving) keeps the sheet even while it waits behind a 我的家 save.
    if(busy&&!busyBackground||document.body.classList.contains('saving')||draftBusy&&!force)return;
    cancelWait();
    if(!force&&isFormDirty()){
      $('#guard').showModal();$('#guard [data-action="continue-edit"]').focus({preventScroll:true});return;
    }
    if(window.ZZCalendar?.isOpen)window.ZZCalendar.close();
    if($('#picker').open)$('#picker').close();
    if($('#sheet').open)$('#sheet').close();
  }

  function done(message,after){
    const committed=activeDraftId;draftSnapshot=formSnapshot();closeDialog(true);entryValues={};render();if(after)after(message);else toast(message);
    if(committed)clearDraft(committed).catch(()=>{showError('记录已保存，草稿清理未完成。该草稿不会重复记账。');});
  }
  function openPicker(title,options,current,onPick){pickerState={options,current,onPick};$('#picker-title').textContent=title;$('#picker-search').value='';renderPicker();if(!$('#picker').open)$('#picker').showModal();$('#picker [data-action="close-picker"]').focus({preventScroll:true});}
  function renderPicker(){if(!pickerState)return;const q=$('#picker-search').value.trim().toLowerCase();$('#picker-list').innerHTML=pickerState.options.map((o,n)=>({...o,n})).filter(o=>(o.label+' '+(o.sub||'')).toLowerCase().includes(q)).map(o=>`<button type="button" class="pick-option ${o.value===pickerState.current?'active':''}" data-action="pick" data-index="${o.n}" ${o.value===pickerState.current?'aria-current="true"':''}><span class="option-text"><strong>${esc(o.label)}</strong>${o.sub?`<small>${esc(o.sub)}</small>`:''}</span>${o.value===pickerState.current?icon('check'):''}</button>`).join('')||'<div class="empty">没有匹配的选项</div>';}
  const pickerField=(id,label,value,display,kind)=>`<label class="field"><span>${esc(label)}</span><input id="${id}" name="${id}" type="hidden" value="${esc(value)}"><button type="button" class="picker-button" data-action="form-picker" data-kind="${kind}" data-field="${id}"><span id="${id}-label">${esc(display)}</span>${icon('down')}</button></label>`;
  function heading(title,subtitle,action=''){return `<div class="page-heading"><div><h1>${title}</h1><p>${subtitle}</p></div>${action}</div>`;}
  function navigate(next,opts={}){if(!NAV.some(n=>n[0]===next))return;Object.assign(ui,{merchant:null},opts,{page:next});history.replaceState(null,'','#'+next);render();window.scrollTo({top:0,behavior:'instant'});}
  function rowActivity(t){const i=data.items.find(i=>i.id===t.itemId);if(!i)return '';return `<button class="activity-row" style="width:100%;text-align:left" data-action="transaction" data-id="${esc(t.id)}"><span class="row-icon">${icon(cat(i.category).icon)}</span><span class="activity-info"><span class="activity-title" style="display:block">${esc(i.name)}</span><span class="activity-meta" style="display:block">${esc(t.note||cat(i.category).name)} · ${dateText(t.date)}</span></span><span class="activity-amount num ${t.kind==='refund'?'positive':''}">${t.kind==='refund'?'+':'−'}${money(t.amount)}<small>${t.kind==='refund'?'已退款':'已付款'}</small></span></button>`;}
  const dueItems=()=>data.items.filter(i=>i.status!=='estimate'&&remaining(i)>0).sort((a,b)=>(a.due||'9999').localeCompare(b.due||'9999'));

  function renderOverview(){
    if(!data.project.budget&&!data.items.length)return renderWelcome();
    const t=totals(),hasBudget=data.project.budget>0,pct=hasBudget?t.expected/data.project.budget*100:0,balance=data.project.budget-t.expected;
    const over=hasBudget&&balance<0,due=dueItems(),first=due[0],recent=[...data.payments].sort((a,b)=>b.date.localeCompare(a.date)).slice(0,3),groups=groupItems(),overs=overshoots();
    const reserveText=!hasBudget?'尚未设置总预算，可继续记录费用与支出。':t.unknown?`${t.unknown} 项待估价，暂不判断最终结余。`:t.unbudgeted?`${t.unbudgeted} 项未设置项目预算，未计入分项超支比较。`:t.budget>data.project.budget?`项目预算超额分配 ${money(t.budget-data.project.budget)}。`:t.expected>t.budget?`预计需使用预备金 ${money(t.expected-t.budget)}。`:'预计费用未超出已分配预算。';
    return heading('装修预算','项目预算、费用预计与实际支出',`<button class="button outline desktop-action" data-action="export">${icon('download','small-icon')}导出备份</button>`)+homeCard()+`
      <div class="home-grid"><div class="home-left">
      <section class="card budget-card" aria-label="预算概览"><div class="between"><div><div class="budget-label">${t.unknown?'已知费用合计':'预计总费用'}</div><div class="hero-amount num"><span class="currency">¥</span>${fmt(t.expected)}</div></div><span class="badge ${over?'danger':t.unknown?'warn':''}">${!hasBudget?'未设总预算':over?(t.unknown?'已知费用超预算':'预计超支'):t.unknown?'待补估价':'预算内'}</span></div>
      <div class="budget-progress-label"><button class="budget-limit-link" data-action="project">总预算 ${hasBudget?money(data.project.budget):'未设置'}</button><span>${hasBudget?pct.toFixed(1)+'%':'—'}</span></div><div class="progress-track" role="progressbar" aria-label="预计费用占总预算" aria-valuenow="${Math.min(pct,100).toFixed(1)}" aria-valuemin="0" aria-valuemax="100"><div class="progress-fill" style="width:${Math.min(pct,100)}%;${over?'background:var(--red)':''}"></div></div>
      <div class="budget-remainder"><span>${!hasBudget?'预算结余':over?(t.unknown?'已知费用超出':'预计超支'):t.unknown?'结余待确认':'预计结余'}</span><strong class="num ${over?'negative':'positive'}">${!hasBudget||(t.unknown&&!over)?'—':money(Math.abs(balance))}</strong></div><div class="budget-note">${icon('info')}<span>${reserveText}</span></div></section>
      <section class="card money-summary" aria-label="支出汇总"><div class="metric"><div class="metric-label">${icon('wallet','small-icon')}实际支出</div><div class="metric-value num">${money(t.paid)}</div><div class="metric-note">累计付款减已收到退款</div></div><div class="metric"><div class="metric-label">${icon('clock','small-icon')}${t.unknown?'已知待付金额':'预计待付金额'}</div><div class="metric-value num">${money(t.remaining)}</div><div class="metric-note">${t.overpaid?'支出超出预计 '+money(t.overpaid)+'，请核对':'含已确认待付及未确认预计费用'}</div></div></section>
      <section class="card card-pad"><div class="section-header"><h2>费用分布</h2><button class="text-button" data-action="nav" data-page="statistics">统计 ${icon('right')}</button></div><div class="category-preview">${groups.map(g=>`<div class="category-mini"><button class="name" data-action="filter-category" data-id="${g.key}">${g.name}</button><div class="bar" aria-hidden="true"><span style="width:${t.expected?g.expected/t.expected*100:0}%;background:${cat(g.key).color}"></span></div><button class="value num" data-action="filter-category" data-id="${g.key}">${money(g.expected)}</button></div>`).join('')}</div><p class="form-hint">预计总费用不低于已发生的净支出；直接记账按净支出统计。</p></section>
      </div><div class="home-right"><section class="card card-pad"><div class="section-header"><h2>待付款</h2><button class="text-button" data-action="due-list">全部 ${icon('right')}</button></div>${first?`<div class="due-lead"><div><div class="caption muted">${esc(first.name)}</div><div class="amount num">${money(remaining(first))}</div><button class="text-button" data-action="payment" data-id="${esc(first.id)}" data-full="true">记录支出 ${icon('arrow','small-icon')}</button></div>${first.due?`<div class="date-box"><span>${Number(first.due.slice(5,7))}月</span><strong>${Number(first.due.slice(8))}</strong></div>`:icon('clock')}</div>${due.slice(1,3).map(i=>`<button class="due-row" data-action="payment" data-id="${esc(i.id)}"><span class="row-icon">${icon(cat(i.category).icon)}</span><span><h3>${esc(i.name)}</h3><p>${i.due?dateText(i.due):'未设置日期'}</p></span><span class="right"><span class="money num">${money(remaining(i))}</span></span></button>`).join('')}`:'<div class="empty">暂无已确认的待付款项</div>'}</section>
      ${overs.length?`<section class="notice-card">${icon('info')}<button data-action="detail" data-id="${esc(overs[0].id)}"><strong>${esc(overs[0].name)}超出预算 ${money(expected(overs[0])-overs[0].budget)}</strong><p>查看预算、预计费用与支出明细</p></button></section>`:''}
      <section class="card card-pad"><div class="section-header"><h2>最近支出</h2><button class="text-button" data-action="payment-list">全部 ${icon('right')}</button></div>${recent.map(rowActivity).join('')||'<div class="empty">暂无支出记录</div>'}</section></div></div>`;
  }

  function filteredItems(){return data.items.filter(i=>(ui.category==='all'||i.category===ui.category)&&(ui.room==='all'||i.room===ui.room)&&(ui.merchant===null||(i.merchant||'未填写')===ui.merchant)&&[i.name,i.merchant||'未填写',i.note,cat(i.category).name,i.room].join(' ').toLowerCase().includes(ui.query.toLowerCase()));}

  function renderLedger(){
    const items=filteredItems(),t=totals(data,items);let content='';
    if(ui.ledgerTab==='items')content=groupItems(items).map(g=>`<section class="card ledger-group"><div class="ledger-group-head"><div class="group-name"><span class="row-icon" style="color:${cat(g.key).color}">${icon(cat(g.key).icon)}</span>${g.name}</div><div class="group-summary"><div class="muted">已设预算 <span class="num">${money(g.budget)}</span></div><div class="muted">${g.items.length} 项 · ${g.unbudgeted?g.unbudgeted+' 项未设预算':g.unknown?'待补估价':g.expected>g.budget?'超出 '+money(g.expected-g.budget):'预算内'}</div></div></div>${g.items.map(i=>{
      const label=i.costMode==='actual'?'实际支出':i.price!==null?'预计费用':i.budget!==null?'项目预算':'预计费用';
      const value=i.costMode==='actual'?money(paid(i)):i.price!==null?money(i.price):i.budget!==null?money(i.budget):'待估价';
      return `<article class="expense-entry"><button type="button" class="expense-row" data-action="payment" data-id="${esc(i.id)}" aria-label="${esc(i.name)}，记录实际支出"><div class="expense-top"><div><div class="expense-name">${esc(i.name)}</div><div class="expense-meta"><span>${esc(i.room)}</span><span>·</span>${badge(i)}</div></div><div class="expense-amount"><div class="caption muted">${label}</div><div class="expense-expected num">${value}</div></div></div><div class="expense-numbers"><div>项目预算<b class="num">${budgetText(i)}</b></div><div>累计净支出<b class="num">${money(paid(i))}</b></div><div>${overpaid(i)?'支出超出预计':'待付金额'}<b class="num ${overpaid(i)?'negative':''}">${missingPrice(i)?'待确认':money(overpaid(i)||remaining(i))}</b></div></div></button><div class="expense-entry-actions"><button type="button" class="text-button muted" data-action="detail" data-id="${esc(i.id)}">查看详情</button><button type="button" class="text-button" data-action="payment" data-id="${esc(i.id)}">记支出 ${icon('plus','small-icon')}</button></div></article>`;
    }).join('')}</section>`).join('');
    if(ui.ledgerTab==='payments'){
      const ids=new Set(items.map(i=>i.id)),ts=data.payments.filter(p=>ids.has(p.itemId)).sort((a,b)=>b.date.localeCompare(a.date)),months=[...new Set(ts.map(p=>p.date.slice(0,7)))];
      content=months.map(m=>`<section class="card list-card" style="margin-bottom:17px"><div class="month-heading">${m.replace('-','年')}月 <span class="muted caption">净支出 ${(n=>(n<0?'−':'')+money(Math.abs(n)))(ts.filter(p=>p.date.startsWith(m)).reduce((v,p)=>v+(p.kind==='refund'?-p.amount:p.amount),0))}</span></div>${ts.filter(p=>p.date.startsWith(m)).map(rowActivity).join('')}</section>`).join('');
    }
    if(ui.ledgerTab==='due'){
      const ds=items.filter(i=>i.status!=='estimate'&&remaining(i)>0).sort((a,b)=>(a.due||'9999').localeCompare(b.due||'9999'));
      content=ds.map(i=>`<section class="due-list-item"><div class="between"><div class="flex gap12"><span class="row-icon">${icon(cat(i.category).icon)}</span><div><h3>${esc(i.name)}</h3><div class="date">${i.due?dateText(i.due)+' · '+(i.due<nowDate()?'已过计划日期':'计划付清'):'未设置计划日期'}</div></div></div><strong class="num">${money(remaining(i))}</strong></div><div class="between"><span class="caption muted">${esc(i.merchant||'未填写商家')}</span><button class="button secondary" data-action="payment" data-id="${esc(i.id)}" data-full="true">记录支出 ${icon('plus','small-icon')}</button></div></section>`).join('');
    }
    const filtering=ui.query||ui.category!=='all'||ui.room!=='all'||ui.merchant!==null;
    if(!content)content=`<section class="card empty">${icon('list')}<p>${filtering?'没有符合筛选条件的记录':ui.ledgerTab==='due'?'暂无已确认的待付款项':ui.ledgerTab==='payments'?'暂无支出记录':'暂无费用项目'}</p><button class="button secondary" data-action="${filtering?'clear-filters':ui.ledgerTab==='payments'?'payment':'add-item'}">${filtering?'清除筛选':ui.ledgerTab==='payments'?'记录支出':'新增预算'}</button></section>`;
    return heading('费用清单',ui.ledgerTab==='items'?'选择预算项目，记录实际支出':'查看费用项目与资金往来',`<button class="button outline" data-action="add-item">${icon('plus')}新增预算</button>`)+`
      <div class="toolbar"><div class="segmented" aria-label="清单视图">${[['items','预算'],['payments','支出'],['due','待付款']].map(([v,l])=>`<button class="seg ${ui.ledgerTab===v?'active':''}" data-action="ledger-tab" data-tab="${v}" aria-pressed="${ui.ledgerTab===v}">${l}</button>`).join('')}</div><div class="filters"><div class="search-box ledger-search">${icon('search')}<input id="ledger-search" value="${esc(ui.query)}" placeholder="搜索项目、商家" aria-label="搜索费用清单" autocomplete="off"></div><button class="filter-button ${ui.category!=='all'?'selected':''}" data-action="category-filter">${ui.category==='all'?'全部类别':cat(ui.category).name}${icon('down')}</button><button class="filter-button ${ui.room!=='all'?'selected':''}" data-action="room-filter">${ui.room==='all'?'全部空间':esc(ui.room)}${icon('down')}</button></div></div>
      ${ui.merchant!==null?`<div class="merchant-filter"><span>商家：${esc(ui.merchant)}</span><button class="text-button" data-action="clear-merchant">清除 ${icon('close','small-icon')}</button></div>`:''}<div class="mobile-summary-strip"><span>${items.length} 项费用</span><span>预计<b class="num">${money(t.expected)}</b></span><span>支出<b class="num">${money(t.paid)}</b></span>${t.unknown?`<span class="amber">${t.unknown} 项待估价</span>`:''}</div><div class="ledger-layout"><div id="ledger-items">${content}</div><aside class="card card-pad ledger-summary"><h3>当前清单</h3><div class="result-label">${t.unknown?'已知费用合计':'预计总费用'}</div><div class="result-number num">${money(t.expected)}</div><div class="caption muted">${items.length} 项费用</div><hr class="summary-rule"><div class="summary-pair"><span>已设项目预算</span><b class="num">${money(t.budget)}</b></div><div class="summary-pair"><span>实际支出</span><b class="num">${money(t.paid)}</b></div><div class="summary-pair"><span>已确认待付</span><b class="num">${money(t.confirmedUnpaid)}</b></div>${t.unbudgeted?`<p class="form-hint">${t.unbudgeted} 项未设置项目预算</p>`:''}<hr class="summary-rule"><button class="button secondary full" data-action="payment">${icon('plus','small-icon')}记录支出</button></aside></div>`;
  }

  const calcField=(key,label,unit)=>`<label class="field"><span>${label}</span><span class="input-wrap"><input inputmode="decimal" type="text" data-calc-field="${key}" value="${esc(ui.calc[key])}" aria-label="${label}" autocomplete="off"><span class="unit">${unit}</span></span></label>`;
  // Exact decimal multiplication via scaled integers; half-up rounding only at line totals.
  function computeCalc(){
    const c=ui.calc;
    if(ui.calcMode==='fixed')return {total:fixed(c.fixed,2,'直接估价'),material:0,labor:0,auxiliary:0,freight:0,install:0,mode:'fixed',q:0,procure:0,boxes:0,price:0};
    const q=fixed(c.quantity,3,'数量');if(q===0)throw new Error('数量需大于 0。');
    const p=fixed(c.price,2,'材料单价'),loss=fixed(c.loss,2,'损耗率');if(loss>10000)throw new Error('损耗率请填写 0–100%。');
    if(ui.calcMode==='unit'&&q%1000)throw new Error('按件计价时，请输入整数件数。');
    const round=(n,d)=>Number((n+d/2n)/d),ceil=(n,d)=>(n+d-1n)/d;
    let procureNum=BigInt(q)*BigInt(10000+loss),procureDen=10000n,boxes=0;
    if(ui.calcMode==='unit'){procureNum=ceil(procureNum,10000000n)*1000n;procureDen=1n;}
    else if(c.packing){const pack=fixed(c.pack,3,'每箱铺贴面积');if(!pack)throw new Error('每箱铺贴面积需大于 0。');boxes=Number(ceil(procureNum,10000n*BigInt(pack)));procureNum=BigInt(boxes)*BigInt(pack);procureDen=1n;}
    const material=round(procureNum*BigInt(p),procureDen*1000n);
    const labor=round(BigInt(q)*BigInt(fixed(c.labor,2,'人工单价')),1000n),auxiliary=round(BigInt(q)*BigInt(fixed(c.auxiliary,2,'辅料单价')),1000n),freight=fixed(c.freight,2,'运费'),install=fixed(c.install,2,'安装费');
    const total=material+labor+auxiliary+freight+install;if(!Number.isSafeInteger(total)||total>MAX_CENTS)throw new Error('测算金额超出可处理范围。');
    return {total,material,labor,auxiliary,freight,install,mode:ui.calcMode,q:q/1000,procure:Number(procureNum)/Number(procureDen)/1000,boxes,price:p};
  }
  function calcResultMarkup(){
    let r;try{r=computeCalc();}catch(e){const incomplete=ui.calcMode==='fixed'?!ui.calc.fixed.trim():!ui.calc.quantity.trim()||!ui.calc.price.trim();return `<div class="between"><h2>测算结果</h2><span class="result-icon">${icon('calc')}</span></div><div class="${incomplete?'empty':'error-message'}" role="status">${incomplete?'填写数量与单价后查看测算结果':esc(e.message)}</div><p class="result-hint">确认应用后才会更新费用清单。</p><button class="button primary full" disabled>预览并应用</button>`;}
    const units=r.mode==='unit'?'件':'㎡';
    return `<div class="between"><h2>测算结果</h2><span class="result-icon">${icon('calc')}</span></div><div class="result-label">本项预计费用</div><div class="result-number num"><span class="currency">¥</span>${fmt(r.total)}</div><div class="caption muted">${r.mode==='fixed'?'按填写的项目总价计算':`采购 ${unitNumber(r.procure)} ${units}${r.boxes?' · '+r.boxes+' 箱（整箱取整）':''}`}</div>
    <div class="formula-list">${r.mode==='fixed'?`<div class="formula-row"><span>直接估价<small>项目总价，不重复叠加分项</small></span><b class="num">${money(r.total)}</b></div>`:[['材料',r.material,`${unitNumber(r.procure)} ${units} × ${money(r.price)}/${units}`],['人工',r.labor,`${unitNumber(r.q)} ${units} × ${money(fixed(ui.calc.labor))}/${units}`],['辅料',r.auxiliary,`${unitNumber(r.q)} ${units} × ${money(fixed(ui.calc.auxiliary))}/${units}`],['运费',r.freight,'固定费用'],['安装费',r.install,'固定费用']].map(([l,v,n])=>`<div class="formula-row"><span>${l}<small>${n}</small></span><b class="num">${money(v)}</b></div>`).join('')}</div>
    <p class="result-hint">测算不直接修改账本。确认关联项目后，将结果保存为预计费用。</p><button class="button primary full" data-action="apply-preview">预览并应用 ${icon('arrow','small-icon')}</button><button class="button outline full" data-action="save-baseline">${icon('copy','small-icon')}${ui.baseline?'更新方案 A':'保存为方案 A'}</button><p class="calc-footer-note">测算结果基于填写的数量与单价</p>`;
  }
  function comparisonMarkup(){
    if(!ui.baseline)return '';
    let r;try{r=computeCalc();}catch(e){return '';}
    const diff=r.total-ui.baseline.total;
    return `<section class="card compare-card"><div class="section-header"><h2>方案对比</h2><button class="text-button" data-action="clear-baseline">清除</button></div><div class="compare-cols"><div class="compare-version"><small>方案 A · 已保存</small><b class="num">${money(ui.baseline.total)}</b><span class="caption muted">${esc(ui.baseline.description)}</span></div><div class="compare-version current"><small>方案 B · 当前结果</small><b class="num">${money(r.total)}</b><span class="caption muted">${r.mode==='fixed'?'项目总价':`材料单价 ${money(r.price)}`}</span></div></div><div class="compare-saving ${diff>0?'negative':''}">${diff===0?'两套方案总价相同':`当前方案${diff<0?'可节省':'增加'} <strong class="num">${money(Math.abs(diff))}</strong>`}</div><p class="form-hint">仅比较本项金额。点击「预览并应用」查看整套装修预算的变化。</p></section>`;
  }
  function calcDockMarkup(){let r;try{r=computeCalc();}catch(e){return '<span class="caption negative">请填写测算参数</span><button class="button primary" disabled>预览应用</button>';}return `<div><span class="caption muted">本项预计</span><strong class="num">${money(r.total)}</strong></div><button class="button primary" data-action="apply-preview">预览应用 ${icon('arrow','small-icon')}</button>`;}
  function renderCalculator(){
    const area=ui.calcMode==='area',units=area?'㎡':'件';
    return heading('费用测算','按工程量与单价计算费用',`<button class="button outline" data-action="reset-calc">${icon('reset','small-icon')}重置</button>`)+`
    <div class="calc-grid"><section class="card calc-form-card"><div class="calc-intro"><span class="row-icon">${icon(area?'tile':ui.calcMode==='unit'?'cabinet':'calc')}</span><div><h2>${area?'瓷砖铺贴':ui.calcMode==='unit'?'按件采购':'项目总价'}</h2><p>${area?'材料、人工与附加费用，分开算清':'适用于自填单价与项目报价'}</p></div></div><div class="segmented calc-mode" aria-label="计价方式">${[['area','面积计价'],['unit','按件计价'],['fixed','直接估价']].map(([v,l])=>`<button class="seg ${ui.calcMode===v?'active':''}" data-action="calc-mode" data-mode="${v}" aria-pressed="${ui.calcMode===v}">${l}</button>`).join('')}</div>
    ${ui.calcMode==='fixed'?`<div class="field-grid">${calcField('fixed','项目总价','元')}</div>`:`<div class="form-section-title">材料</div><div class="field-grid">${calcField('quantity',area?'净铺贴面积':'购买数量',units)}${calcField('price','材料单价','元/'+units)}${calcField('loss','材料损耗','%')}${area&&ui.calc.packing?calcField('pack','每箱铺贴面积','㎡/箱'):''}</div>${area?`<div class="toggle-row"><span>按整箱采购取整</span><label class="switch"><input type="checkbox" data-calc-field="packing" ${ui.calc.packing?'checked':''} aria-label="按整箱采购取整"><span></span></label></div>`:''}<div class="form-section-title">施工与辅料</div><div class="field-grid">${calcField('labor','人工单价','元/'+units)}${calcField('auxiliary','辅料单价','元/'+units)}</div><div class="form-section-title">附加费用</div><div class="field-grid">${calcField('freight','运费','元')}${calcField('install','安装费','元')}</div>`}
    <div class="formula-hint">${icon('info')}<span>${ui.calcMode==='fixed'?'直接填写最终报价；已包含在报价里的费用不再另加。':'损耗只计入材料，不叠加到人工、辅料和运费。已含在报价中的分项请填 0。'}</span></div></section><div><section class="card calc-result" id="calc-result">${calcResultMarkup()}</section><div id="calc-comparison">${comparisonMarkup()}</div></div></div><div class="calc-dock" id="calc-dock" aria-live="polite">${calcDockMarkup()}</div>`;
  }
  function updateCalc(){const target=$('#calc-result');if(target)target.innerHTML=calcResultMarkup();const comp=$('#calc-comparison');if(comp)comp.innerHTML=comparisonMarkup();const dock=$('#calc-dock');if(dock)dock.innerHTML=calcDockMarkup();}

  function renderStatistics(){
    const t=totals(),groups=groupItems(data.items,ui.statsGroup),max=Math.max(1,...groups.flatMap(g=>[g.budget,g.expected])),overs=overshoots();
    const months=[...new Set(data.payments.map(p=>p.date.slice(0,7)))].sort().slice(-6);
    const values=months.map(m=>({month:m,pay:data.payments.filter(p=>p.date.startsWith(m)&&p.kind==='payment').reduce((a,p)=>a+p.amount,0),refund:data.payments.filter(p=>p.date.startsWith(m)&&p.kind==='refund').reduce((a,p)=>a+p.amount,0)}));
    const maxCash=Math.max(1,...values.flatMap(v=>[v.pay,v.refund]));
    return heading('预算统计','预算对比、分类汇总与支出趋势',`<button class="button outline" data-action="csv">${icon('download','small-icon')}导出明细</button>`)+`
      <div class="stats-metrics"><section class="card stat-metric"><div class="metric-label">${t.unknown?'已知费用合计':'预计总费用'}</div><div class="value num">${money(t.expected)}</div><div class="sub">${t.unknown?t.unknown+' 项待估价':data.project.budget?'占总预算 '+(t.expected/data.project.budget*100).toFixed(1)+'%':'未设置总预算'}</div></section><section class="card stat-metric"><div class="metric-label">实际支出</div><div class="value num">${money(t.paid)}</div><div class="sub">${data.payments.length} 笔流水 · 付款减退款</div></section><section class="card stat-metric"><div class="metric-label">对比项目预算</div><div class="value num ${!t.unknown&&!t.unbudgeted&&t.expected>t.budget?'amber':'positive'}">${t.unknown||t.unbudgeted?'—':signed(t.expected-t.budget)}</div><div class="sub">${t.unbudgeted?t.unbudgeted+' 项未设置预算':t.unknown?t.unknown+' 项待估价':'费用合计与已设预算的差额'}</div></section></div>
      <div class="stats-layout"><div class="stats-left"><section class="card card-pad"><div class="section-header"><h2>预算与费用对比</h2><div class="chart-modes">${[['category','类别'],['room','空间'],['merchant','商家']].map(([v,l])=>`<button class="chart-mode ${ui.statsGroup===v?'active':''}" data-action="stats-group" data-group="${v}" aria-pressed="${ui.statsGroup===v}">${l}</button>`).join('')}</div></div><div class="chart-meta"><div class="legend-inline"><span><i style="background:#d6dfd0"></i>项目预算</span><span><i style="background:#548069"></i>预计总费用</span></div></div>${groups.map(g=>`<button class="comparison-item" data-action="stats-drill" data-key="${esc(g.key)}" aria-label="${esc(g.name)}，已设预算 ${money(g.budget)}，预计费用 ${money(g.expected)}"><div class="comparison-label"><span>${esc(g.name)}</span><span class="${g.unknown||g.unbudgeted?'muted':g.expected>g.budget?'negative':'positive'}">${comparisonLabel(g)}</span></div><div class="double-bar" aria-hidden="true"><div class="track"><div class="bar" style="width:${g.budget/max*100}%"></div></div><div class="track"><div class="bar estimate" style="width:${g.expected/max*100}%"></div></div></div><div class="chart-values num"><span>预算 ${fmt(g.budget)}</span><span>预计 ${fmt(g.expected)}</span></div></button>`).join('')||'<div class="empty">暂无费用项目</div>'}<p class="form-hint">未设预算的项目不作超支判定。直接记账按净支出计入费用。</p></section>
      <section class="card card-pad"><div class="section-header"><h2>支出趋势</h2><span class="caption muted">${months.length?'最近 '+months.length+' 个有记录的月份':''}</span></div><div class="legend-inline"><span><i style="background:#7b9b83"></i>支出</span><span><i style="background:#c8997f"></i>退款</span></div>${months.length?`<div class="cash-chart" role="img" aria-label="${esc(values.map(v=>v.month+' 支出 '+money(v.pay)+' 退款 '+money(v.refund)).join('；'))}">${values.map(v=>`<div class="cash-col"><div class="cash-bar" style="height:${v.pay/maxCash*85}%"><span class="cash-value num">${fmt(v.pay)}</span></div>${v.refund?`<div class="cash-bar refund" style="height:${v.refund/maxCash*85}%" title="退款 ${money(v.refund)}"></div>`:''}<span class="cash-label">${v.month.replace('-','.')}</span></div>`).join('')}</div><div class="cash-accessible-values">${values.map(v=>`<div class="summary-pair"><span>${v.month}</span><span class="num">支出 ${money(v.pay)}${v.refund?' · 退款 '+money(v.refund):''}</span></div>`).join('')}</div>`:'<div class="empty">暂无支出记录</div>'}</section></div>
      <div class="stats-right"><section class="card card-pad"><div class="section-header"><h2>超支项目</h2><span class="caption muted">对比项目预算</span></div>${overs.slice(0,8).map((i,n)=>`<button class="overshoot-row" data-action="detail" data-id="${esc(i.id)}"><span class="rank num">${String(n+1).padStart(2,'0')}</span><span><span class="rank-name">${esc(i.name)}</span><span class="rank-meta">${cat(i.category).name}</span></span><span class="amount num">+${money(expected(i)-i.budget)}</span></button>`).join('')||'<div class="empty">暂无可确认的超支项目</div>'}</section><section class="card card-pad"><h2>统计口径</h2><p class="form-hint">项目预算用于分项规划；预计费用用于报价管理；实际支出按付款减退款计算。</p><p class="form-hint">同一笔费用仅统计一次。预计总费用取预计费用与净支出的较高值；未估价项目计入已发生的净支出，并保留待估价状态。尚未收到的退款不提前抵减。</p></section></div></div>`;
  }

  function render(){
    document.title='筑账 · '+NAV.find(n=>n[0]===ui.page)[3];
    paintNav();$('#crumb-page').textContent=NAV.find(n=>n[0]===ui.page)[3];
    $$('.project-name').forEach(el=>el.textContent=data.project.name);$$('.project-meta').forEach(el=>el.textContent=[data.project.area?data.project.area+'㎡':'',data.project.layout].filter(Boolean).join(' · ')||'项目与预算');
    $('#main').innerHTML=`<div class="page-content">${({overview:renderOverview,ledger:renderLedger,calculator:renderCalculator,statistics:renderStatistics,home:renderHome}[ui.page])()}</div>`;
    $('#fab').classList.add('hidden');document.body.dataset.page=ui.page;updateChrome();updateDraftBanner();
    const warning=restoreError||bootError;$('#storage-warning').textContent=warning;$('#storage-warning').classList.toggle('hidden',!warning);
    mountHome();
  }
  // 1.4.0 · bottom bar: five tabs in one glass capsule (built once, so the active pill can slide); 记一笔 is the round + beside it.
  function paintNav(){
    $('#side-nav').innerHTML=NAV.map(([p,i,,full])=>`<button class="nav-button ${ui.page===p?'active':''}" data-action="nav" data-page="${p}" ${ui.page===p?'aria-current="page"':''}>${icon(i)}<span>${full}</span></button>`).join('');
    const bar=$('#bottom-nav');
    if(!bar.querySelector('.nav-pill'))bar.innerHTML='<span class="nav-pill" aria-hidden="true"></span>'+NAV.map(([p,i,l])=>`<button data-action="nav" data-page="${p}">${icon(i)}<span>${l}</span></button>`).join('');
    for(const b of bar.querySelectorAll('button')){const on=b.dataset.page===ui.page;b.classList.toggle('active',on);if(on)b.setAttribute('aria-current','page');else b.removeAttribute('aria-current');}
    movePill();
  }
  function movePill(){
    const bar=$('#bottom-nav'),pill=bar.querySelector('.nav-pill'),on=bar.querySelector('button.active');if(!pill)return;
    if(!on||!on.offsetWidth){pill.style.opacity='0';return;}
    pill.style.opacity='1';pill.style.width=on.offsetWidth+'px';pill.style.transform=`translateX(${on.offsetLeft}px)`;
    if(!pill.dataset.ready)requestAnimationFrame(()=>{pill.dataset.ready='1';});
  }
  window.addEventListener('resize',()=>{movePill();if(ui.page==='home')homeTop();});

  /* ---------- 1.4.0 · 我的家 bridge: the 3D module is imported only when the card or the tab is shown ---------- */
  const homeState={mod:null,loading:null,failed:false,ui:{labels:true}};
  const placedItems=()=>{const t=HM.planOf(data.project.home,data.project.layout),{rects}=HM.makeRects(t),rooms=new Set(HM.roomNames(rects));return {t,rects,rooms,list:data.items.filter(i=>i.category===HM.FURNITURE&&i.room!=='全屋'&&i.model&&rooms.has(i.room))};};
  function homeCard(){
    const {t,rooms,list}=placedItems();
    return `<section class="home-card" data-action="nav" data-page="home" aria-label="我的家"><div class="home-card-slot" id="home-card-slot">${homeState.mod?'':HM.planSvg(t)}</div><div class="home-card-cap"><b>我的家</b><span>${list.length} 件 · ${rooms.size} 个空间</span></div><button class="home-card-go hg gbtn gchip" data-action="nav" data-page="home">进入我的家 ${icon('right','small-icon')}</button></section>`;
  }
  function renderHome(){
    if(!homeState.failed)return `<div class="home-host" id="home-host"><div class="home-loading">${HM.planSvg(placedItems().t)}<span>正在搭建我的家…</span></div></div>`;
    const {t,rects,list}=placedItems(),counts={};for(const i of list)counts[i.room]=(counts[i.room]||0)+1;
    return heading('我的家','这台设备暂不支持 3D 显示，下面是户型平面')+`<section class="card card-pad home-fallback">${HM.planSvg(t,counts,{text:true,label:t.n+'平面图'})}<p class="form-hint">${esc(t.n)} · ${HM.roomNames(rects).length} 个空间 · 已放进 ${list.length} 件家具家电。账本的记录与统计不受影响。</p></section>`;
  }
  function homeTop(){const bar=$('.topbar');if(bar)document.documentElement.style.setProperty('--home-top',Math.max(0,bar.getBoundingClientRect().bottom)+'px');}
  function homeWrite(mutate){
    const run=async()=>{
      for(let n=0;n<40;n++){
        if(!busy&&!draftBusy){const next=clone(data);if(mutate(next)===false)return false;return await write(next,{background:true});}
        await new Promise(r=>setTimeout(r,250));
      }
      return false;
    };
    return homeQueue=homeQueue.then(run,run);
  }
  let homeQueue=Promise.resolve(true);
  const homeApi=Object.freeze({
    data:()=>data,paid:i=>paid(i),money,esc,toast,
    saveModel:(id,model)=>homeWrite(next=>{const i=next.items.find(x=>x.id===id);if(!i)return false;i.model=model;}),
    saveHome:home=>homeWrite(next=>{next.project.home=home;}),
    saveUi:value=>{homeState.ui=value;if(storageOK)runtime.set('home.ui.v1',value).catch(()=>{});}
  });
  // Probe WebGL before importing three.js: without it the tab shows the static plan, with no error from the renderer.
  function webglAvailable(){
    try{const c=document.createElement('canvas'),gl=c.getContext('webgl2')||c.getContext('webgl');if(!gl)return false;gl.getExtension('WEBGL_lose_context')?.loseContext();return true;}catch(_){return false;}
  }
  function loadHome(){
    if(!homeState.loading&&!webglAvailable()){homeState.failed=true;homeState.loading=Promise.resolve(null);if(ui.page==='home')render();}
    if(!homeState.loading)homeState.loading=import('./home3d.js').then(m=>{homeState.mod=m.createHome(homeApi);homeState.mod.setUi(homeState.ui);window.ZZHomeDebug=homeState.mod.debug;return homeState.mod;}).catch(error=>{
      homeState.failed=true;console.warn('筑账：我的家 3D 不可用，改用平面图。',error);if(ui.page==='home')render();return null;
    });
    return homeState.loading;
  }
  function mountHome(){
    const target=ui.page==='home'?$('#home-host'):ui.page==='overview'?$('#home-card-slot'):null;
    if(!target||homeState.failed){homeState.mod?.hide();return;}
    if(!homeState.mod){loadHome().then(mod=>{if(mod&&target.isConnected)mountHome();});return;}
    if(ui.page==='home')homeTop();
    target.textContent='';homeState.mod.mount(target,ui.page==='home'?'home':'mini');
  }

  /* ---------- 1.5.0 · record-time look: 家具家电 picks a look in the ledger form and lands in 我的家 on save ---------- */
  const CUBE='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" aria-hidden="true"><path d="M12 3 4 7.5v9L12 21l8-4.5v-9z"/><path d="M4 7.5 12 12l8-4.5M12 12v9"/></svg>';
  const planRects=source=>HM.makeRects(HM.planOf(source.project.home,source.project.layout)).rects;
  // The look is a form field, so drafts, mode switches and the unsaved-changes guard carry it like any other.
  function lookRow(kind=''){
    const value=typeof kind==='string'&&Object.prototype.hasOwnProperty.call(HM.KINDS,kind)?kind:'';
    return `<input type="hidden" name="model-kind" id="model-kind" value="${value}"><button type="button" class="model-row form-row" id="look-row" data-action="pick-look" hidden><span class="th" id="look-th">${CUBE}</span><span class="look-text"><b id="look-t">选一个外观</b><small id="look-s"></small></span><span class="look-go" aria-hidden="true">${icon('right')}</span></button>`;
  }
  // Applies the form's look to the item about to be saved; true when it now stands somewhere new or looks different.
  function lookOnSave(next,item,kind){return HM.applyLook(item,String(kind||''),planRects(next),next.items);}
  function lookContext(){
    const form=$('#sheet form[data-form]');if(!form||!$('#look-row'))return null;
    let category,room,existing=null;
    if(form.dataset.form==='payment'){
      if($('#payment-kind').value==='refund')return {state:'hidden'};
      if($('#payment-target').value==='linked'){const i=data.items.find(x=>x.id===$('#payment-item').value);if(!i)return {state:'hidden'};category=i.category;room=i.room;existing=HM.sanitizeModel(i.model);}
      else{category=$('#item-category').value;room=$('#item-room').value;}
    }else if(form.dataset.form==='item'){
      category=$('#item-category').value;room=$('#item-room').value;
      const old=data.items.find(x=>x.id===form.elements.namedItem('id')?.value);existing=old?HM.sanitizeModel(old.model):null;
    }else return null;
    let state=HM.lookState(category,room,planRects(data));
    if(state==='ok'&&homeState.failed)state='no3d';
    return {state,room,existing};
  }
  function refreshLook(){
    const c=lookContext(),row=$('#look-row');if(!c||!row)return;
    row.hidden=c.state==='hidden';if(row.hidden)return;
    const off=c.state!=='ok',picked=$('#model-kind').value,kind=off?'':picked||c.existing?.kind||'';
    row.classList.toggle('off',off);row.classList.toggle('has',Boolean(kind));
    if(off)row.setAttribute('aria-disabled','true');else row.removeAttribute('aria-disabled');
    const thumb=kind&&homeState.mod?homeState.mod.thumbs()[kind]:'';
    $('#look-th').innerHTML=thumb?`<img alt="" src="${thumb}">`:CUBE;
    const [t,sub]=c.state==='whole'?['全屋的东西不放进模型','像全屋灯具这类不好定位的，只记账不显示']
      :c.state==='missing'?[`户型里没有「${c.room}」`,'先记账；换个户型后，可在「我的家」摆放里放进去']
      :c.state==='no3d'?['这台设备暂不显示 3D 外观','先记账；外观可以以后在支持的设备上挑']
      :kind?['已选外观 · 可更换',c.existing&&!picked?`已在${c.room}，换外观不挪位置`:`记完放进${c.room}`]
      :['选一个外观',`挑一个样子相近的，记完放进${c.room}`];
    $('#look-t').textContent=t;$('#look-s').textContent=sub;
    row.setAttribute('aria-label',`${t}，${sub}`);
    // A restored draft can name a look before the 3D module (and its thumbnails) has loaded.
    if(kind&&!homeState.mod&&!homeState.failed)loadHome().then(mod=>{if(mod)refreshLook();});
  }
  async function openLook(){
    const c=lookContext();if(!c||c.state!=='ok')return;
    if(!homeState.mod&&!webglAvailable()){homeState.failed=true;refreshLook();toast('这台设备暂不显示 3D 外观，可以先记账');return;}
    const row=$('#look-row');row.setAttribute('aria-busy','true');
    const mod=await loadHome();if(row.isConnected)row.removeAttribute('aria-busy');
    if(!mod){refreshLook();toast('这台设备暂不显示 3D 外观，可以先记账');return;}
    if(!$('#sheet').open||!row.isConnected)return;
    mod.lendPicker($('#look-dialog'),{sel:$('#model-kind').value||c.existing?.kind||null,hint:`挑一个样子相近的，记完放进${c.room}。`,
      cb:kind=>{const input=$('#model-kind');if(!input||!row.isConnected)return;input.value=kind;refreshLook();markDraft();},
      onClose:()=>{if(row.isConnected)row.focus({preventScroll:true});}});
  }
  // After a save that put a look into a room: open 我的家 on that room, drop it in, offer 摆放.
  function revealLook(id){
    const i=data.items.find(x=>x.id===id);if(!i)return;
    navigate('home');
    const show=mod=>{
      if(!mod||ui.page!=='home'){toast('已保存，外观会显示在「我的家」');return;}
      if(mod.reveal(id))toast(`已放进${i.room}`,{label:'去摆放',run:()=>mod.arrange(id)});
      else toast(`已保存。「${i.room}」放满了，这件计入房间标签的 +N`);
    };
    if(homeState.mod)requestAnimationFrame(()=>show(homeState.mod));else loadHome().then(mod=>requestAnimationFrame(()=>show(mod)));
  }
  function entryTabs(mode,editing=false){
    const modes=editing?[['plan','项目策划'],['estimate','费用预算']]:[['plan','项目策划'],['estimate','费用预算'],['expense','直接记账']];
    return `<div class="segmented entry-tabs" role="group" aria-label="记录类型">${modes.map(([v,l])=>`<button type="button" class="seg ${mode===v?'active':''}" data-action="entry-mode" data-mode="${v}" aria-pressed="${mode===v}">${l}</button>`).join('')}</div>`;
  }
  function dateField(id,label,value='',required=false){
    return `<div class="field date-field"><span id="${id}-caption">${esc(label)}</span><input type="hidden" name="${id}" id="${id}" value="${esc(value)}"><button type="button" class="picker-button date-button" data-action="date-picker" data-field="${id}" data-required="${required}" aria-labelledby="${id}-caption ${id}-label" aria-haspopup="dialog"><span id="${id}-label" class="${value?'':'muted'}">${value?esc(window.ZZCalendar.display(value)):(required?'选择日期':'选择日期（选填）')}</span>${icon('clock')}</button></div>`;
  }
  function refreshDateFields(){
    for(const button of $$('#sheet [data-action="date-picker"]')){
      const value=$('#'+button.dataset.field)?.value||'',label=$('#'+button.dataset.field+'-label');
      label.textContent=value?window.ZZCalendar.display(value):button.dataset.required==='true'?'选择日期':'选择日期（选填）';label.classList.toggle('muted',!value);
    }
  }
  function baseFields(v){return `<label class="field"><span>项目名称</span><input class="plain" name="name" value="${esc(v.name||'')}" placeholder="填写费用项目名称" maxlength="100" required autocomplete="off"></label><div class="field-grid form-row">${pickerField('item-category','费用类别',v.category,cat(v.category).name,'category')}${pickerField('item-room','所在空间',v.room,v.room,'room')}</div>`;}
  function optionalFields(v,withDue=true){return `<div class="field-grid form-row"><label class="field ${withDue?'':'wide'}"><span>商家或施工方 <small>选填</small></span><input class="plain" name="merchant" value="${esc(v.merchant||'')}" maxlength="100" placeholder="填写商家或施工方"></label>${withDue?dateField('due','计划付清日期',v.due||''):''}<label class="field wide"><span>备注 <small>选填</small></span><textarea class="plain" name="note" rows="2" maxlength="1000" placeholder="填写补充说明">${esc(v.note||'')}</textarea></label></div>`;}
  function collectEntry(){
    const form=$('#sheet form');if(!form)return {};
    const fields=Object.fromEntries(formFields(form));
    if(form.querySelector('[name="quote-confirmed"]'))fields['quote-confirmed']=form.querySelector('[name="quote-confirmed"]').checked?'on':'off';
    Object.assign(entryValues,fields);return entryValues;
  }
  function openItem(id='',mode='estimate',carry={}){
    const old=data.items.find(i=>i.id===id),fresh=!old;
    if(!['plan','estimate','info'].includes(mode))mode='estimate';
    const v=old?clone(old):{id:'',name:'',category:ui.category==='all'?'construction':ui.category,room:ui.room==='all'?'全屋':ui.room,budget:null,price:null,costMode:'estimated',status:'estimate',merchant:'',due:'',note:''};
    for(const field of ['name','merchant','due','note'])if(carry[field]!==undefined)v[field]=carry[field];
    if(carry['item-category'])v.category=carry['item-category'];if(carry['item-room'])v.room=carry['item-room'];
    const field=mode==='plan'?'budget':'price',label=mode==='plan'?'项目预算':'预计费用';
    const amount=carry[field]??rawMoney(v[field]);
    const legacy=fresh?(mode==='plan'?'price':'budget'):'';
    const retained=legacy&&carry[legacy]!==undefined&&carry[legacy]!==''?String(carry[legacy]):'';
    const title=mode==='info'?'编辑资料':fresh?(mode==='plan'?'新增项目预算':'新增费用预算'):(mode==='plan'?'修改项目预算':'修改预计费用');
    dialog(title,`<form id="item-form" data-form="item" novalidate><input type="hidden" name="id" value="${esc(v.id)}"><input type="hidden" name="record-mode" value="${mode}">${mode==='info'?'':entryTabs(mode,!fresh)}${baseFields(v)}${mode==='info'?'':`<label class="field entry-money-field"><span>${label}（元）${mode==='estimate'?'<small>可留空</small>':''}</span><div class="money-field"><span aria-hidden="true">¥</span><input inputmode="decimal" name="${field}" value="${esc(amount)}" aria-label="${label}（元）" placeholder="${mode==='plan'?'0.00':'待估价'}" autocomplete="off"></div><small class="field-note">${mode==='plan'?'用于规划该项目的支出上限。':'填写估价或报价，保存后不计入实际支出。'}</small></label>${retained?`<input type="hidden" name="retained-${legacy}" value="${esc(retained)}"><p class="form-hint">同时保存另一页填写的${legacy==='budget'?'项目预算':'预计费用'} ${esc(retained)} 元。</p>`:''}${mode==='estimate'?`<label class="check-row"><input type="checkbox" name="quote-confirmed" ${carry['quote-confirmed']==='on'||(!('quote-confirmed' in carry)&&v.status!=='estimate')?'checked':''}><span>报价已确认</span></label>`:''}`}${lookRow(carry['model-kind'])}${optionalFields(v)}<div class="dialog-actions"><button type="submit" class="button primary">${mode==='info'?'保存资料':mode==='plan'?'保存项目预算':'保存预计费用'}</button></div></form>`);
    refreshLook();draftSnapshot=formSnapshot();
  }
  async function submitItem(form){
    const f=new FormData(form),id=String(f.get('id')||''),old=data.items.find(i=>i.id===id),mode=String(f.get('record-mode')||'estimate');
    if(id&&!old)throw new Error('该费用项目不存在，请返回清单重新选择。');
    const name=String(f.get('name')||'').trim();if(!name)throw new Error('请填写项目名称。');
    const due=String(f.get('due')||'');if(due&&!validDate(due))throw new Error('计划付清日期无效，请重新选择。');
    const item=old?clone(old):{id:uid('e'),budget:null,price:null,costMode:'estimated',status:'estimate'};
    Object.assign(item,{name,category:String(f.get('item-category')),room:String(f.get('item-room')),merchant:String(f.get('merchant')||'').trim(),due,note:String(f.get('note')||'').trim()});
    if(mode==='plan')item.budget=String(f.get('budget')||'').trim()===''?null:fixed(f.get('budget'),2,'项目预算');
    else if(mode==='estimate'){
      const value=String(f.get('price')||'').trim();item.price=value===''?null:fixed(value,2,'预计费用');item.costMode=value===''&&old?.costMode==='actual'?'actual':'estimated';
      const confirmed=f.get('quote-confirmed')==='on'||Boolean(old&&paid(old)>0);
      item.status=confirmed?'confirmed':'estimate';
      if(old?.status==='settled'&&item.price===paid(old))item.status='settled';
    }else if(mode!=='info')throw new Error('记录类型无效，请重新打开表单。');
    if(!old&&f.has('retained-budget'))item.budget=fixed(f.get('retained-budget'),2,'项目预算');
    if(!old&&f.has('retained-price'))item.price=fixed(f.get('retained-price'),2,'预计费用');
    const next=clone(data);
    if(old){next.items=next.items.map(i=>i.id===id?item:i);next.changes.push({date:new Date().toISOString(),itemId:id,note:mode==='plan'?`项目预算：${budgetText(old)} → ${budgetText(item)}`:mode==='estimate'?`预计费用：${priceText(old)} → ${priceText(item)}`:'项目资料已更新'});}else next.items.push(item);
    const placed=lookOnSave(next,item,f.get('model-kind'));
    if(await write(next))done(mode==='info'?'资料已保存':mode==='plan'?'项目预算已保存':'费用预算已保存',placed?()=>revealLook(item.id):null);
  }
  function openPayment(itemId='',full=false,carry={}){
    const i=data.items.find(i=>i.id===itemId),v={name:carry.name||'',category:carry['item-category']||(ui.category==='all'?'construction':ui.category),room:carry['item-room']||(ui.room==='all'?'全屋':ui.room),merchant:carry.merchant||'',note:carry.note||''};
    const linked=Boolean(i)||carry['payment-target']==='linked',amount=carry.amount??(full&&i?rawMoney(remaining(i)):'');
    dialog('记录实际支出',`<form id="payment-form" data-form="payment" novalidate><input type="hidden" name="record-mode" value="expense">${i?'':entryTabs('expense')}<div class="segmented payment-types" aria-label="资金类型"><button type="button" class="seg active" data-action="payment-kind" data-kind="payment" aria-pressed="true">支出</button><button type="button" class="seg" data-action="payment-kind" data-kind="refund" aria-pressed="false">退款</button></div><input type="hidden" name="kind" id="payment-kind" value="payment"><label class="field entry-money-field"><span id="payment-amount-label">实际支出（元）</span><div class="money-field"><span aria-hidden="true">¥</span><input name="amount" id="payment-amount" inputmode="decimal" placeholder="0.00" value="${esc(amount)}" aria-labelledby="payment-amount-label" autocomplete="off"></div></label>
    <input type="hidden" name="payment-target" id="payment-target" value="${linked?'linked':'new'}"><div class="payment-target-row" aria-label="费用归属"><button type="button" data-action="payment-target" data-target="new" class="target-option">新增费用</button><button type="button" data-action="payment-target" data-target="linked" class="target-option">关联预算</button></div><div id="payment-new-fields">${baseFields(v)}<label class="field form-row"><span>商家或施工方 <small>选填</small></span><input class="plain" name="merchant" value="${esc(v.merchant)}" placeholder="填写商家或施工方" maxlength="100"></label></div><div id="payment-linked-fields">${pickerField('payment-item','关联项目',i?.id||'',i?.name||'选择预算项目','item')}<div id="payment-item-info">${paymentInfo(i)}</div></div>${lookRow(carry['model-kind'])}<div class="field-grid form-row">${dateField('date','支出日期',carry.date||nowDate(),true)}<label class="field"><span>备注 <small>选填</small></span><input class="plain" name="note" value="${esc(v.note)}" placeholder="填写款项用途" maxlength="1000"></label></div><div id="refund-adjustment" class="refund-adjustment" hidden><label class="check-row"><input type="checkbox" id="refund-adjust" name="refund-adjust"><span>同时修改该项目的预计费用</span></label><div id="refund-price-row" hidden><label class="field form-row"><span>调整后预计费用（元）</span><input class="plain" inputmode="decimal" name="refund-price" id="refund-price" placeholder="填写新的项目总费用"></label><p class="form-hint">适用于退货或减项。请填写调整后的总费用，不是本次退款额。</p></div></div><p class="form-hint" id="payment-hint"></p><div class="dialog-actions"><button type="submit" class="button primary" id="payment-save">保存支出</button></div></form>`);
    updatePaymentTarget(linked?'linked':'new');
    if(carry.kind==='refund')updatePaymentKind('refund');
    if(carry['refund-adjust']==='on')$('#refund-adjust').checked=true;
    if(carry['refund-price']!==undefined)$('#refund-price').value=carry['refund-price'];
    updateRefundAdjustment();refreshLook();draftSnapshot=formSnapshot();
  }
  function paymentInfo(i){
    if(!i)return '<p class="form-hint">选择项目后，支出将自动关联该项目。</p>';
    return `<div class="selected-item-info"><span>${i.costMode==='actual'?'项目预算':'预计费用'}<b class="num">${i.costMode==='actual'?budgetText(i):priceText(i)}</b></span><span>累计支出<b class="num">${money(paid(i))}</b></span><span>${overpaid(i)?'支出超出预计':'待付金额'}<b class="num">${missingPrice(i)?'待确认':money(overpaid(i)||remaining(i))}</b></span></div>`;
  }
  function updatePaymentTarget(target){
    if($('#payment-kind').value==='refund')target='linked';
    $('#payment-target').value=target;
    for(const [selector,enabled]of [['#payment-new-fields',target==='new'],['#payment-linked-fields',target==='linked']]){
      const container=$(selector);container.hidden=!enabled;
      for(const control of container.querySelectorAll('input,button,textarea'))control.disabled=!enabled;
    }
    for(const b of $$('.target-option')){b.classList.toggle('active',b.dataset.target===target);b.setAttribute('aria-pressed',String(b.dataset.target===target));}
    updatePaymentHint();
  }
  function updatePaymentHint(){
    const refund=$('#payment-kind').value==='refund',linked=$('#payment-target').value==='linked',i=data.items.find(i=>i.id===$('#payment-item').value);
    $('#payment-hint').textContent=refund?(i?.costMode==='actual'?'退款将扣减该项目的实际支出。':'退款扣减实际支出，默认不修改报价。退货或减项时，可勾选同时修改预计费用。'):linked?(i?.costMode==='actual'?'本笔支出追加到该项目，不创建新的预算。':'本笔支出追加到所选项目，不修改项目预算或预计费用。'):'保存费用项目及支出记录，无需先填写预算或预计费用。';
    $('#payment-amount-label').textContent=refund?'退款金额（元）':'实际支出（元）';
    $('#date-caption').textContent=refund?'退款日期':'支出日期';
    $('#payment-save').textContent=refund?'保存退款':'保存支出';
    $('#sheet-title').textContent=refund?'记录退款':'记录实际支出';
    if(i?.status==='settled')$('#payment-hint').textContent+=' 该项目已结算，新增收支后将重新核对结算。';
    updateRefundAdjustment();refreshLook();
  }

  function updateRefundAdjustment(){
    const i=data.items.find(i=>i.id===$('#payment-item')?.value);
    const available=$('#payment-kind')?.value==='refund'&&i&&i.costMode!=='actual';
    const box=$('#refund-adjustment');if(!box)return;
    box.hidden=!available;$('#refund-adjust').disabled=!available;
    const enabled=Boolean(available&&$('#refund-adjust').checked);
    $('#refund-price-row').hidden=!enabled;$('#refund-price').disabled=!enabled;
  }
  function assertCashTimeline(source,itemId){
    const days=new Map();
    for(const t of source.payments.filter(t=>t.itemId===itemId))days.set(t.date,(days.get(t.date)||0)+(t.kind==='refund'?-t.amount:t.amount));
    let net=0;
    for(const [date,amount]of [...days].sort((a,b)=>a[0].localeCompare(b[0]))){net+=amount;if(net<0)throw new Error(`${date} 的退款超过当时已付金额，请核对付款与退款的金额或日期。`);}
  }
  function updatePaymentKind(kind){
    $('#payment-kind').value=kind;
    for(const b of $$('.payment-types .seg')){b.classList.toggle('active',b.dataset.kind===kind);b.setAttribute('aria-pressed',String(b.dataset.kind===kind));}
    const newButton=$('[data-action="payment-target"][data-target="new"]');newButton.disabled=kind==='refund';
    updatePaymentTarget(kind==='refund'?'linked':$('#payment-target').value);
  }
  async function submitPayment(form){
    const f=new FormData(form),kind=String(f.get('kind')),target=String(f.get('payment-target'));
    if(!['payment','refund'].includes(kind))throw new Error('资金类型无效。');
    const amount=fixed(f.get('amount'),2,kind==='refund'?'退款金额':'实际支出');if(!amount)throw new Error('金额须大于 0 元。');
    const date=String(f.get('date')||'');if(!validDate(date))throw new Error('请选择有效日期。');
    if(date>nowDate())throw new Error('实际收支日期不能晚于今天。尚未付款的项目请设置计划付清日期。');
    const next=clone(data);let item;
    if(target==='new'&&kind==='payment'){
      const name=String(f.get('name')||'').trim();if(!name)throw new Error('请填写项目名称。');
      item={id:uid('e'),name,category:String(f.get('item-category')),room:String(f.get('item-room')),budget:null,price:null,costMode:'actual',status:'confirmed',merchant:String(f.get('merchant')||'').trim(),due:'',note:''};
      next.items.push(item);
    }else if(target==='linked'){
      item=next.items.find(i=>i.id===f.get('payment-item'));if(!item)throw new Error('请选择关联项目。');
      const net=paid(item,data);if(kind==='refund'&&amount>net)throw new Error(`退款不能超过该项目的累计净支出 ${money(net)}。`);
      if(item.status==='estimate')item.status='confirmed';
      if(item.status==='settled')item.status='confirmed';
    }else throw new Error('请选择新增费用或关联预算。');
    next.payments.push({id:uid('p'),itemId:item.id,amount,date,kind,note:String(f.get('note')||'').trim()});
    if(kind==='refund'&&f.get('refund-adjust')==='on'){
      if(item.costMode==='actual')throw new Error('按实际支出核算的项目无需同步修改预计费用。');
      const revised=fixed(f.get('refund-price'),2,'调整后预计费用'),before=priceText(item);
      item.price=revised;item.status='confirmed';
      next.changes.push({date:new Date().toISOString(),itemId:item.id,note:`退款时调整预计费用：${before} → ${money(revised)}`});
    }
    assertCashTimeline(next,item.id);
    const placed=kind==='payment'&&lookOnSave(next,item,f.get('model-kind'));
    // A direct expense creates its item and transaction in one validated snapshot.
    if(await write(next))done(kind==='refund'?'退款已保存':'支出已保存',placed?()=>revealLook(item.id):null);
  }
  function openDetail(id){
    const i=data.items.find(x=>x.id===id);if(!i)return;
    const ps=data.payments.filter(t=>t.itemId===id).sort((a,b)=>b.date.localeCompare(a.date)),changes=data.changes.filter(c=>c.itemId===id).slice(-4).reverse();
    dialog(i.name,`<div class="detail-meta"><span class="caption muted">${cat(i.category).name} · ${esc(i.room)}</span>${badge(i)}</div><section class="detail-hero"><div class="caption muted">${i.status==='settled'?'结算费用':i.costMode==='actual'?'实际支出':'预计费用'}</div><div class="value num">${i.costMode==='actual'?money(paid(i)):priceText(i)}</div><div class="between caption"><span class="muted">项目预算 ${budgetText(i)}</span>${i.budget!==null?`<span class="${expected(i)>i.budget?'negative':missingPrice(i)?'muted':'positive'}">${expected(i)>i.budget?'超出 '+money(expected(i)-i.budget):missingPrice(i)?'结余待确认':'结余 '+money(i.budget-expected(i))}</span>`:''}</div><div class="detail-grid"><div><span>累计净支出</span><b class="num">${money(paid(i))}</b></div><div><span>${overpaid(i)?'支出超出预计':'待付金额'}</span><b class="num">${missingPrice(i)?'待确认':money(overpaid(i)||remaining(i))}</b></div></div></section>
    <div class="detail-edit-links"><button class="text-button" data-action="edit-plan" data-id="${esc(id)}">${i.budget===null?'设置':'修改'}项目预算</button><button class="text-button" data-action="edit-estimate" data-id="${esc(id)}">${i.costMode==='actual'||i.price===null?'设置':'修改'}预计费用</button><button class="text-button" data-action="edit-item" data-id="${esc(id)}">编辑资料</button></div>${i.merchant?`<div class="summary-pair"><span class="muted">商家或施工方</span><span>${esc(i.merchant)}</span></div>`:''}${i.due?`<div class="summary-pair"><span class="muted">计划付清日期</span><span>${esc(window.ZZCalendar.display(i.due))}</span></div>`:''}${i.note?`<p class="form-hint">备注：${esc(i.note)}</p>`:''}<div class="detail-section">支出记录 <span class="muted caption">${ps.length} 笔</span></div><div class="detail-list">${ps.map(rowActivity).join('')||'<div class="empty">暂无支出记录</div>'}</div>${changes.length?`<div class="detail-section">变更记录</div>${changes.map(c=>`<p class="form-hint">${esc(localDay(c.date))} · ${esc(c.note)}</p>`).join('')}`:''}
    <div class="detail-admin-actions">${i.status!=='settled'?`<button class="text-button" data-action="settle-item" data-id="${esc(id)}">确认结算</button>`:''}<button class="text-button text-danger" data-action="delete-item" data-id="${esc(id)}">删除项目</button></div><div class="dialog-actions"><button class="button primary" data-action="payment" data-id="${esc(id)}">${icon('plus','small-icon')}记录实际支出</button></div>`);
  }
  function openSettlement(id){
    const i=data.items.find(x=>x.id===id);if(!i)return;
    dialog('确认项目结算',`<form data-form="settlement" novalidate><input type="hidden" name="id" value="${esc(id)}"><p class="dialog-sub">${esc(i.name)}</p><div class="summary-pair"><span>累计净支出</span><b class="num">${money(paid(i))}</b></div><label class="field form-row"><span>最终费用（元）</span><input name="final-price" class="plain" inputmode="decimal" value="${rawMoney(i.price??paid(i))}" aria-label="最终费用（元）"></label><p class="form-hint">最终费用与累计净支出一致后可结算。确认结算不会自动生成付款或退款记录。</p><div class="dialog-actions"><button class="button outline" type="button" data-action="detail" data-id="${esc(id)}">返回详情</button><button class="button primary" type="submit">确认结算</button></div></form>`);
  }
  async function submitSettlement(form){
    const f=new FormData(form),i=data.items.find(x=>x.id===f.get('id'));if(!i)throw new Error('项目不存在。');
    const value=fixed(f.get('final-price'),2,'最终费用'),net=paid(i);
    if(value!==net)throw new Error(value>net?`尚有 ${money(value-net)} 未支付，请先记录支出。`:`尚有 ${money(net-value)} 待退回，请先记录退款或核对最终费用。`);
    const next=clone(data),item=next.items.find(x=>x.id===i.id);
    item.price=value;item.costMode='estimated';item.status='settled';
    next.changes.push({date:new Date().toISOString(),itemId:i.id,note:`结算确认：${priceText(i)} → 最终费用 ${money(value)}`});
    if(await write(next))done('项目已结算');
  }

  function openTransaction(id){const t=data.payments.find(t=>t.id===id);if(!t)return;const i=data.items.find(i=>i.id===t.itemId);dialog('流水详情',`<div class="detail-hero"><div class="caption muted">${t.kind==='refund'?'已收到退款':'已付款'} · ${esc(i.name)}</div><div class="value num ${t.kind==='refund'?'positive':''}">${t.kind==='refund'?'+':'−'}${money(t.amount)}</div></div><div class="summary-pair"><span class="muted">日期</span><span>${t.date}</span></div><div class="summary-pair"><span class="muted">类别</span><span>${cat(i.category).name}</span></div><div class="summary-pair"><span class="muted">备注</span><span style="max-width:70%;overflow-wrap:anywhere">${esc(t.note||'未填写')}</span></div><p class="form-hint">${i.costMode==='actual'?'删除将移除该笔流水，并重新汇总实际费用。':'删除仅移除该笔流水，不修改手动填写的预计费用。'}发生退款时请新增退款记录，保留原支出记录。</p><div class="dialog-actions"><button class="button outline" data-action="detail" data-id="${esc(i.id)}">查看费用项</button><button class="button danger-button" data-action="delete-payment" data-id="${esc(t.id)}">删除记录</button></div>`);}
  function openProject(){const t=totals();dialog('项目与预算',`<form data-form="project"><p class="dialog-sub">设置装修项目资料与总预算。修改设置不影响已有记录。</p><label class="field"><span>项目名称</span><input class="plain" name="name" value="${esc(data.project.name)}" maxlength="60" required></label><div class="field-grid form-row"><label class="field"><span>面积（㎡）</span><input class="plain" name="area" inputmode="decimal" value="${esc(data.project.area)}" maxlength="20" placeholder="选填"></label><label class="field"><span>户型</span><input class="plain" name="layout" value="${esc(data.project.layout)}" maxlength="60" placeholder="选填"></label><label class="field wide"><span>总预算上限（元，含预备金）</span><input class="plain" inputmode="decimal" name="budget" value="${data.project.budget?rawMoney(data.project.budget):''}" placeholder="选填，不影响记录支出"></label></div><div class="edit-budget-note">预备金 = 总预算上限 − 已分配到费用项的预算，不额外累加。</div><div class="project-budget-preview"><span class="muted">已分配预算<b class="num" style="color:var(--ink)">${money(t.budget)}</b></span><span class="muted">${!data.project.budget?'未设总预算':t.budget>data.project.budget?'预算超额分配':'当前预备金'}<b class="num ${t.budget>data.project.budget?'negative':'positive'}">${data.project.budget?money(Math.abs(data.project.budget-t.budget)):'—'}</b></span></div><div class="dialog-actions"><button class="button primary" type="submit">保存设置</button></div></form>`);}

  function confirmation(title,message,action,id='',button='确认删除'){dialog(title,`<p class="dialog-sub">${esc(message)}</p><div class="dialog-actions"><button class="button outline" data-action="close-dialog">取消</button><button class="button danger-button" data-action="${action}" data-id="${esc(id)}">${button}</button></div>`);}
  let exporting=false;
  async function exportFile(filename,text,type){
    if(exporting)return false;
    exporting=true;
    try{
      const ok=await runtime.saveFile(filename,type,text);
      if(ok)toast(runtime.isNative?'文件已保存': '已发起下载，请查看浏览器下载列表');
      else toast('已取消导出');
      return ok;
    }catch(error){if(error?.code==='CANCELLED'){toast('已取消导出');return false;}showError(runtime.explain(error));return false;}
    finally{exporting=false;}
  }
  async function exportJSON(){if(restoreError)throw new Error('账本未能读取，请使用「导出原始数据」。');await exportFile('筑账_备份_'+nowDate()+'.json',JSON.stringify(data,null,2),'application/json;charset=utf-8');}

  async function exportCSV(){if(restoreError)throw new Error('账本未能读取，请使用「导出原始数据」。');
    const cell=v=>{let str=String(v??'');if(/^[=+\-@\t\r]/.test(str))str="'"+str;return '"'+str.replaceAll('"','""')+'"';};
    const rows=[['名称','类别','空间','商家','状态','项目预算（元）','预计费用（元）','计入费用合计（元）','实际净支出（元）','待付金额（元）','支出超出预计（元，待核对）','计划付清日期','费用统计方式','备注'],...data.items.map(i=>[i.name,cat(i.category).name,i.room,i.merchant,statusText(i),i.budget===null?'未设置':(i.budget/100).toFixed(2),i.costMode==='actual'?'不适用':i.price===null?'待估价':(i.price/100).toFixed(2),(expected(i)/100).toFixed(2),(paid(i)/100).toFixed(2),missingPrice(i)?'待确认':(remaining(i)/100).toFixed(2),(overpaid(i)/100).toFixed(2),i.due,i.costMode==='actual'?'按净支出汇总':'按预计费用与净支出较高者汇总',i.note])];
    await exportFile('筑账_费用明细_'+nowDate()+'.csv','\uFEFF'+rows.map(r=>r.map(cell).join(',')).join('\r\n'),'text/csv;charset=utf-8');
  }

  function openApply(restoredTotal=null){try{applyResult=Number.isSafeInteger(restoredTotal)&&restoredTotal>=0&&restoredTotal<=MAX_CENTS?{total:restoredTotal}:computeCalc();}catch(e){showError(e.message);return;}const name=ui.calcMode==='area'?'瓷砖铺贴':ui.calcMode==='unit'?'材料采购':'项目报价';dialog('应用测算结果',`<form data-form="apply"><p class="dialog-sub">可更新未确认且无支出流水的项目；已确认报价不被覆盖。</p>${pickerField('apply-target','应用到','new','新增费用项','apply')}<label class="field form-row" id="apply-name-row"><span>新费用项名称</span><input class="plain" name="name" id="apply-name" value="${name}" maxlength="100" required></label><div class="field-grid form-row" id="apply-category-row">${pickerField('apply-category','类别','materials','主材设备','category')}${pickerField('apply-room','空间','客餐厅','客餐厅','room')}</div><div id="apply-diff"></div><p class="form-hint">新增项目不预设预算，预计费用为 ${money(applyResult.total)}；应用后可在费用项中分配预算。不会自动记录付款。</p><div class="dialog-actions"><button class="button outline" type="button" data-action="close-dialog">返回测算</button><button class="button primary" type="submit">确认应用</button></div></form>`);updateApplyDiff();}
  function updateApplyDiff(){
    const id=$('#apply-target').value,i=data.items.find(i=>i.id===id),t=totals();
    const predicted=t.expected-(i?expected(i):0)+applyResult.total;
    const unknown=t.unknown-(i&&missingPrice(i)?1:0);
    const hasBudget=data.project.budget>0;
    const state=!hasBudget?'未设置总预算，暂不判断结余':unknown>0?(predicted>data.project.budget?'已知费用超预算 '+money(predicted-data.project.budget)+'，另有待估价项目':'含待估价项目，结余暂不确定'):`${predicted>data.project.budget?'预计超支':'预计结余'} ${money(Math.abs(data.project.budget-predicted))}`;
    const tone=!hasBudget?'muted':predicted>data.project.budget?'negative':unknown>0?'muted':'positive';
    $('#apply-diff').innerHTML=`<div class="apply-diff"><div><small>当前整套预计</small><b class="num">${money(t.expected)}</b></div>${icon('arrow')}<div><small>应用后预计</small><b class="num">${money(predicted)}</b></div></div><div class="caption ${tone}">${state}</div>`;
    $('#apply-name-row').classList.toggle('hidden',!!i);$('#apply-category-row').classList.toggle('hidden',!!i);$('#apply-name').required=!i;
  }
  function formPicker(button){
    const field=$('#'+button.dataset.field),kind=button.dataset.kind,current=field.value;let options=[],title='选择';
    if(kind==='item'){title='关联费用项';options=data.items.map(i=>({value:i.id,label:i.name,sub:cat(i.category).name+' · '+i.room+' · '+(i.costMode==='actual'?'已记支出 '+money(paid(i)):i.price===null?'待估价':overpaid(i)?'支出超出预计 '+money(overpaid(i)):'未付 '+money(remaining(i)))}));}
    if(kind==='category'){title='费用类别';options=CATS.map(c=>({value:c.id,label:c.name}));}
    if(kind==='room'){title='所在空间';options=[...new Set([...ROOMS,...data.items.map(i=>i.room)])].map(r=>({value:r,label:r}));}
    if(kind==='apply'){title='应用到费用项';options=[{value:'new',label:'新增费用项',sub:'不修改现有项目'},...data.items.filter(i=>i.status==='estimate'&&!data.payments.some(t=>t.itemId===i.id)).map(i=>({value:i.id,label:i.name,sub:'当前预计 '+(i.price===null?'待估价':money(i.price))}))];}
    openPicker(title,options,current,o=>{field.value=o.value;$('#'+button.dataset.field+'-label').textContent=o.label;if(kind==='item'){if(o.value!==current){$('#refund-adjust').checked=false;$('#refund-price').value='';}$('#payment-item-info').innerHTML=paymentInfo(data.items.find(i=>i.id===o.value));updatePaymentHint();}if(kind==='apply')updateApplyDiff();if(['category','room'].includes(kind))refreshLook();markDraft();});
  }
  // Delegated events keep dynamically rendered pages and bottom sheets consistent.
  document.addEventListener('click',async e=>{
    const b=e.target.closest('[data-action]');if(!b||b.disabled||(busy&&!busyBackground)||draftBusy)return;const a=b.dataset.action,id=b.dataset.id||'';
    // One tap waits for a background save; further taps meanwhile are dropped so nothing runs twice.
    // Switching tabs, ✕ (sheet or picker), 继续编辑, back and Escape always work and drop a tap that is still waiting; other taps meanwhile are ignored.
    const cancels=['nav','close-dialog','close-picker','continue-edit'].includes(a);
    if(cancels&&actionWaiting)cancelWait();
    else if(actionWaiting)return;
    if(busy&&!cancels){
      const t=++waitSeq;actionWaiting=true;waitButton=b;b.classList.add('pending');b.setAttribute('aria-busy','true');toast(WAIT_MSG,null,true,10500);
      try{for(let n=0;n<200&&busy&&waitSeq===t;n++)await new Promise(r=>setTimeout(r,50));}finally{if(waitSeq===t){actionWaiting=false;waitButton=null;b.classList.remove('pending');b.removeAttribute('aria-busy');}}
      if(waitSeq!==t)return;
      hideToast(WAIT_MSG);
      if(busy){showError('我的家还在保存，请稍后再试。');return;}
    }
    if(['nav','detail','transaction','payment','add-item','edit-item','edit-plan','edit-estimate','settle-item','project','settings','apply-preview'].includes(a)&&isFormDirty()){pendingNavigation=()=>{if(b.isConnected)b.click();};closeDialog();return;}
    try{
      if(a==='nav')navigate(b.dataset.page);
      else if(a==='payment'){entryValues={};openPayment(id,b.dataset.full==='true');}
      else if(a==='detail')openDetail(id);
      else if(a==='transaction')openTransaction(id);
      else if(a==='add-item'){entryValues={};openItem();}
      else if(a==='edit-item'){entryValues={};openItem(id,'info');}
      else if(a==='edit-plan'){entryValues={};openItem(id,'plan');}
      else if(a==='edit-estimate'){entryValues={};openItem(id,'estimate');}
      else if(a==='settle-item')openSettlement(id);
      else if(a==='entry-mode'){const draftId=activeDraftId,original=activeDraftOriginal,base=activeDraftBase,review=activeDraftReview;const values=collectEntry();if(b.dataset.mode==='expense')openPayment('',false,values);else openItem(values.id||'',b.dataset.mode,values);activeDraftId=draftId;activeDraftOriginal=original;activeDraftBase=base;activeDraftReview=review;if(review)appendDraftReview();draftSnapshot='__mode_switch__';markDraft();}
      else if(a==='payment-target'){updatePaymentTarget(b.dataset.target);markDraft();}
      else if(a==='date-picker'){const field=$('#'+b.dataset.field);window.ZZCalendar.open({value:field.value,label:$('#'+b.dataset.field+'-caption').textContent,required:b.dataset.required==='true',trigger:b,onChange:value=>{field.value=value;refreshDateFields();field.dispatchEvent(new Event('input',{bubbles:true}));}});}
      else if(a==='project')openProject();
      else if(a==='settings')settings();
      else if(a==='close-dialog')closeDialog();
      else if(a==='close-picker')$('#picker').close();
      else if(a==='form-picker')formPicker(b);
      else if(a==='pick-look')await openLook();
      else if(a==='pick'){const o=pickerState.options[Number(b.dataset.index)];if(o){const fn=pickerState.onPick;$('#picker').close();fn(o);}}
      else if(a==='ledger-tab'){ui.ledgerTab=b.dataset.tab;render();}
      else if(a==='due-list')navigate('ledger',{ledgerTab:'due',category:'all',room:'all',query:''});
      else if(a==='payment-list')navigate('ledger',{ledgerTab:'payments',category:'all',room:'all',query:''});
      else if(a==='filter-category')navigate('ledger',{ledgerTab:'items',category:id,room:'all',query:''});
      else if(a==='clear-merchant'){ui.merchant=null;render();}
      else if(a==='clear-filters'){ui.category='all';ui.room='all';ui.merchant=null;ui.query='';render();}
      else if(a==='category-filter')openPicker('筛选费用类别',[{value:'all',label:'全部类别'},...CATS.map(c=>({value:c.id,label:c.name}))],ui.category,o=>{ui.category=o.value;render();});
      else if(a==='room-filter')openPicker('筛选所在空间',[{value:'all',label:'全部空间'},...[...new Set([...ROOMS,...data.items.map(i=>i.room)])].map(r=>({value:r,label:r}))],ui.room,o=>{ui.room=o.value;render();});
      else if(a==='calc-mode'){ui.calcMode=b.dataset.mode;render();scheduleCalc();}
      else if(a==='reset-calc'){ui.calcMode='area';ui.calc={quantity:'',price:'',loss:'0',labor:'0',auxiliary:'0',freight:'0',install:'0',pack:'',packing:false,fixed:''};ui.baseline=null;render();scheduleCalc();toast('测算参数已重置');}
      else if(a==='save-baseline'){const r=computeCalc();ui.baseline={total:r.total,description:r.mode==='fixed'?'项目总价':'材料单价 '+money(r.price)};updateCalc();scheduleCalc();toast('方案 A 已保存，可修改输入对比');}
      else if(a==='clear-baseline'){ui.baseline=null;updateCalc();scheduleCalc();}
      else if(a==='apply-preview')openApply();
      else if(a==='stats-group'){ui.statsGroup=b.dataset.group;render();}
      else if(a==='stats-drill'){const group=ui.statsGroup;const key=b.dataset.key;navigate('ledger',{ledgerTab:'items',category:group==='category'?key:'all',room:group==='room'?key:'all',query:'',merchant:group==='merchant'?key:null});}
      else if(a==='payment-kind'){updatePaymentKind(b.dataset.kind);markDraft();}
      else if(a==='delete-item'){if(data.payments.some(t=>t.itemId===id))showError('此费用项已有付款记录。为避免丢失关联信息，请先处理相关流水。');else confirmation('删除费用项','该费用项将从预算和统计中移除；此操作不能撤销。','delete-item-confirm',id);}
      else if(a==='delete-item-confirm'){const next=clone(data);next.items=next.items.filter(i=>i.id!==id);next.changes=next.changes.filter(c=>c.itemId!==id);if(await write(next))done('费用项已删除');}
      else if(a==='delete-payment')confirmation('删除支出记录？','仅用于纠正错误记录。删除会重新计算净支出与待付款，不改变手动填写的预计费用。按实际支出核算的项目将同步更新费用合计。此操作不可撤销。','delete-payment-confirm',id);
      else if(a==='delete-payment-confirm'){const next=clone(data);const removed=next.payments.find(t=>t.id===id);next.payments=next.payments.filter(t=>t.id!==id);if(removed){const i=next.items.find(i=>i.id===removed.itemId);if(i.status==='settled')i.status='confirmed';assertCashTimeline(next,i.id);}if(await write(next))done('流水已删除');}
      else if(a==='export')await exportJSON();
      else if(a==='csv')await exportCSV();
      else if(a==='csv-payments')await exportPayments();
      else if(a==='export-raw'){await exportFile('筑账_原始数据_'+nowDate()+'.txt',typeof rawBackup==='string'?rawBackup:JSON.stringify(rawBackup,null,2),'text/plain;charset=utf-8');}
      else if(a==='import'){if(runtime.isNative){let text;try{text=await runtime.openFile();}catch(error){if(error instanceof TypeError&&!error.code)throw new Error('文件不是有效的筑账备份，未更改当前数据。');throw error;}if(text!==null)previewImport(text);else toast('已取消导入');}else{$('#import-file').value='';$('#import-file').click();}}
      else if(a==='import-confirm'){if(pendingImport){const err=restoreError;restoreError='';if(!await replaceLedger(pendingImport,'备份已恢复'))restoreError=err;}}
      else if(a==='retry-storage'){closeDialog(true);await boot();}
      else if(a==='resume-draft'){openDraftList();}
      else if(a==='restore-draft'){restoreDraft(id);}
      else if(a==='delete-draft'){confirmation('删除草稿？','草稿尚未计入账本，删除后无法恢复。','delete-draft-confirm',id);}
      else if(a==='delete-draft-confirm'){await clearDraft(id);closeDialog(true);render();toast('草稿已删除');}
      else if(a==='discard-draft'){await finishGuard(false);}
      else if(a==='keep-draft'){await finishGuard(true);}
      else if(a==='continue-edit'){pendingNavigation=null;closeGuard();}
      else if(a==='new-ledger')confirmation('清空当前账本？','清空将删除当前项目、预算、费用与支出流水。此操作不可撤销，请先导出备份。','clear-ledger','','确认清空');
      else if(a==='clear-ledger'){await replaceLedger(emptyLedger(),'账本已清空');}
    }catch(error){showError(runtime.explain(error));}
  });
  document.addEventListener('input',e=>{
    if(e.target.id==='refund-adjust')updateRefundAdjustment();
    if(e.target.name==='confirm-draft-update'){activeDraftReview=!e.target.checked;if(e.target.checked)activeDraftBase=draftBase();}
    if(e.target.id==='picker-search')renderPicker();
    if(e.target.id==='ledger-search'){
      const value=e.target.value,start=e.target.selectionStart,end=e.target.selectionEnd;
      if(e.isComposing){ui.query=value;return;}
      ui.query=value;render();const input=$('#ledger-search');input.focus({preventScroll:true});input.setSelectionRange(start,end);
    }
    if(e.target.dataset.calcField){const key=e.target.dataset.calcField;ui.calc[key]=e.target.type==='checkbox'?e.target.checked:e.target.value;if(key==='packing')render();else updateCalc();scheduleCalc();}
  });
  document.addEventListener('compositionend',e=>{if(e.target.id==='ledger-search'){ui.query=e.target.value;render();$('#ledger-search').focus({preventScroll:true});}});
  document.addEventListener('submit',async e=>{
    const form=e.target;if(!form.dataset.form)return;e.preventDefault();if(busy&&!busyBackground)return;$('#sheet-body>.error-message')?.remove();form.querySelector('.error-message')?.remove();
    try{
      const conflict=form.querySelector('[name=confirm-draft-update]');if(conflict&&!conflict.checked)throw new Error('请先核对当前记录，并确认保存草稿中的修改。');
      if(form.dataset.form==='payment')await submitPayment(form);
      if(form.dataset.form==='item')await submitItem(form);
      if(form.dataset.form==='settlement')await submitSettlement(form);
      if(form.dataset.form==='project'){
        const f=new FormData(form),budget=String(f.get('budget')||'').trim()===''?0:fixed(f.get('budget'),2,'总预算');const name=String(f.get('name')).trim();if(!name)throw new Error('请填写项目名称。');const area=String(f.get('area')||'').trim();if(area.length>20)throw new Error('面积最多 20 个字符。');if(area)fixed(area,2,'面积');const next=clone(data);next.project={...data.project,name,area,layout:String(f.get('layout')||'').trim(),budget,initialBudget:data.project.initialBudget||data.project.budget||budget};next.changes.push({date:new Date().toISOString(),itemId:'project',note:`总预算 ${money(data.project.budget)} → ${money(budget)}`});if(await write(next))done('项目设置已保存');
      }
      if(form.dataset.form==='apply'){
        const f=new FormData(form),id=String(f.get('apply-target')),next=clone(data),item=next.items.find(i=>i.id===id);if(!applyResult)throw new Error('测算结果已失效，请重新计算。');
        if(item){if(item.status!=='estimate'||next.payments.some(t=>t.itemId===item.id))throw new Error('不能覆盖已确认或已有付款流水的费用项。');const before=item.price;item.price=applyResult.total;next.changes.push({date:new Date().toISOString(),itemId:id,note:`应用测算：预计 ${before===null?'待估价':money(before)} → ${money(item.price)}`});}
        else{if(id!=='new')throw new Error('关联费用项不存在。');const name=String(f.get('name')).trim();if(!name)throw new Error('请填写新费用项名称。');next.items.push({id:uid('e'),name,category:String(f.get('apply-category')),room:String(f.get('apply-room')),budget:null,price:applyResult.total,costMode:'estimated',status:'estimate',merchant:'',due:'',note:'由费用测算创建。'});}
        if(await write(next)){done('预计费用已更新');navigate('ledger',{ledgerTab:'items',category:'all',room:'all',query:''});}
      }
    }catch(error){showError(error.message||'保存失败，请检查输入。');}
  });
  $('#import-file').addEventListener('change',async e=>{
    const file=e.target.files?.[0];if(!file)return;
    try{previewImport(await file.text());}catch(error){showError('未导入：'+runtime.explain(error));}
  });
  for(const d of [$('#sheet'),$('#picker')])d.addEventListener('click',e=>{
    if(e.target!==d)return;const r=d.getBoundingClientRect();
    if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom){if(d.id==='sheet')closeDialog();else d.close();}
  });
  $('#sheet').addEventListener('cancel',e=>{e.preventDefault();closeDialog();});
  // System Back (ToolBox hosts that send toolboxback) closes the topmost layer the way ✕ or Escape would.
  // With nothing to close it is left to the host, which shows its exit hint. Must answer synchronously.
  window.addEventListener('toolboxback',e=>{if(closeTop())e.preventDefault();});
  function closeTop(){
    if($('#guard').open){if(!draftBusy){cancelWait();pendingNavigation=null;closeGuard();}return true;}
    if(window.ZZCalendar?.isOpen){window.ZZCalendar.close();return true;}
    if($('#picker').open){$('#picker').close();return true;}
    if($('#sheet').open){closeDialog();return true;}
    if(actionWaiting){cancelWait();return true;}
    return ui.page==='home'&&Boolean(homeState.mod?.back());
  }
  // However the picker closes (✕, Escape, backdrop), a pick still waiting on a 我的家 save is dropped.
  $('#picker').addEventListener('close',cancelWait);
  $('#guard').addEventListener('cancel',e=>{e.preventDefault();if(draftBusy)return;cancelWait();pendingNavigation=null;closeGuard();});
  document.addEventListener('input',e=>{if(e.target.closest('#sheet form'))markDraft();});
  document.addEventListener('change',e=>{if(e.target.closest('#sheet form'))markDraft();});
  document.addEventListener('visibilitychange',()=>{if(document.hidden){if(isFormDirty())saveDraftNow().catch(()=>{});flushCalc();}});
  window.addEventListener('pagehide',()=>{flushCalc();if(isFormDirty())saveDraftNow().catch(()=>{});});
  runtime.onFlush(async()=>{
    let failure=null;flushCalc();const calcWrite=calcSaving;
    if(isFormDirty())await saveDraftNow().catch(error=>{failure=error;});else await draftQueue.catch(()=>{});
    if(!await calcWrite)failure=failure||new Error('测算输入未保存');
    if(failure){showError('关闭前保存失败：'+runtime.explain(failure));throw failure;}
  });
  const viewport=window.visualViewport;
  const syncViewport=()=>{const h=viewport?.height||window.innerHeight;const editing=/^(INPUT|TEXTAREA)$/.test(document.activeElement?.tagName||'');document.documentElement.style.setProperty('--viewport-height',h+'px');document.body.classList.toggle('keyboard-open',editing&&window.innerHeight-h>120);};
  viewport?.addEventListener('resize',syncViewport);document.addEventListener('focusin',syncViewport);document.addEventListener('focusout',()=>setTimeout(syncViewport,80));syncViewport();
  window.addEventListener('hashchange',()=>{const p=location.hash.slice(1);if(NAV.some(n=>n[0]===p)){ui.page=p;render();window.scrollTo(0,0);}});

  function renderWelcome(){
    return heading('装修预算','项目预算、费用测算与支出管理')+`<section class="card welcome-card"><div class="welcome-illustration" aria-hidden="true"><span class="welcome-grid"></span><div class="welcome-house">${icon('home')}</div><span class="welcome-chip">${icon('check','small-icon')} 预算 · 支出 · 统计</span></div><span class="eyebrow">装修项目管理</span><h2>建立装修账本</h2><p>可先规划预算，也可直接记录支出。<br>预算与支出分别保存，自动关联统计。</p><button class="button primary full" data-action="add-item">${icon('plus','small-icon')}新增预算</button><button class="button outline full" data-action="payment">${icon('wallet','small-icon')}直接记账</button><button class="text-button welcome-demo" data-action="import">恢复备份 ${icon('arrow','small-icon')}</button></section><div class="welcome-assurance"><span>${icon('wallet','small-icon')}本地保存</span><span>${icon('check','small-icon')}无需联网</span></div>`;
  }
  function updateChrome(){
    const el=$('#runtime-badge');if(el)el.innerHTML='<span class="dot"></span>'+(bootError?'存储未就绪':restoreError?'待恢复':'本地账本');
    $('#main').removeAttribute('aria-busy');
  }


  function availableDrafts(){return drafts.filter(d=>!(data.completedDrafts||[]).includes(d.id));}
  function updateDraftBanner(){
    $('#draft-banner')?.remove();const list=availableDrafts();
    if(!list.length||!$('#main .page-heading'))return;
    const banner=document.createElement('div');banner.id='draft-banner';banner.className='draft-banner';
    banner.innerHTML='<span>'+icon('edit','small-icon')+(list.length>1?`${list.length} 份未完成的草稿`:'未完成：'+esc(list[0].title||'记录'))+'</span><button data-action="resume-draft">'+(list.length>1?'查看草稿':'继续填写')+icon('right','small-icon')+'</button>';
    $('#main .page-heading').after(banner);
  }
  function formFields(form){
    const fields=[...new FormData(form).entries()];
    for(const c of form.querySelectorAll('input[type="checkbox"][name]:not(:disabled)'))if(!c.checked)fields.push([c.name,'off']);
    return fields;
  }
  function formSnapshot(){const form=$('#sheet form[data-form]');return form?JSON.stringify(formFields(form)):'';}
  function draftBase(){
    const form=$('#sheet form[data-form]');if(!form)return null;
    const v=Object.fromEntries(formFields(form));
    if(form.dataset.form==='project')return {kind:'project',value:clone(data.project)};
    if(form.dataset.form==='apply'&&v['apply-target']&&v['apply-target']!=='new'){const item=data.items.find(i=>i.id===v['apply-target']);return item?{kind:'item',id:item.id,value:clone(item)}:null;}
    if(form.dataset.form==='item'&&v.id){const item=data.items.find(i=>i.id===v.id);return item?{kind:'item',id:v.id,value:clone(item)}:null;}
    return null;
  }
  function isFormDirty(){return Boolean($('#sheet').open&&$('#sheet form[data-form]')&&formSnapshot()!==draftSnapshot);}
  function closeGuard(){if($('#guard').open)$('#guard').close();}
  async function finishGuard(keep){
    if(draftBusy)return;draftBusy=true;const controls=$$('#guard button');controls.forEach(b=>b.disabled=true);
    try{
      if(keep)await saveDraftNow();else if(activeDraftOriginal){const original=clone(activeDraftOriginal);clearTimeout(draftTimer);await mutateDrafts(list=>[original,...list.filter(d=>d.id!==original.id)]);}else await clearDraft(activeDraftId);
      const next=pendingNavigation;pendingNavigation=null;closeGuard();closeDialog(true);render();
      if(keep)toast('草稿已保留，未计入账本');
      draftBusy=false;if(next)next();
    }finally{draftBusy=false;controls.forEach(b=>b.disabled=false);}
  }
  function markDraft(){
    if(busy)return;
    if(!isFormDirty()){clearTimeout(draftTimer);if(activeDraftId&&!activeDraftOriginal&&activeDraftWritten&&$('#sheet').open){activeDraftWritten=false;$('#draft-status').textContent='';clearDraft(activeDraftId).catch(()=>{});}return;}
    $('#draft-status').textContent='草稿待保存';clearTimeout(draftTimer);
    draftTimer=setTimeout(()=>saveDraftNow().catch(error=>{if($('#sheet').open){$('#draft-status').textContent='草稿未保存';showError('草稿保存失败：'+runtime.explain(error));}}),450);
  }
  function mutateDrafts(change){
    draftQueue=draftQueue.catch(()=>{}).then(async()=>{
      if(draftsUnreadable)throw new Error('已保存的草稿未能读取；为免覆盖，暂不保存新草稿。');
      const next=change(drafts);
      if(next.length)await runtime.set('draft.v1',{schemaVersion:2,records:next});else await runtime.remove('draft.v1');
      drafts=next;storedDraftIds=new Set(next.map(d=>d.id));updateDraftBanner();
    });return draftQueue;
  }
  function saveDraftNow(){
    clearTimeout(draftTimer);const form=$('#sheet form[data-form]');
    if(!form||!isFormDirty()||busy)return Promise.resolve();
    const id=activeDraftId||(activeDraftId=uid('d'));activeDraftWritten=true;
    const record={id,type:form.dataset.form,title:$('#sheet-title').textContent,fields:formFields(form),baseRevision:data.revision||0,baseEntity:clone(form.dataset.form==='apply'?draftBase():activeDraftBase),requiresReview:activeDraftReview,time:new Date().toISOString(),applyTotal:applyResult?.total??null,entryValues:clone(entryValues)};
    return mutateDrafts(list=>[record,...list.filter(d=>d.id!==id)]).then(()=>{if($('#sheet').open&&activeDraftId===id&&isFormDirty())$('#draft-status').textContent='草稿已保存';});
  }
  function clearDraft(id=activeDraftId,all=false){clearTimeout(draftTimer);return mutateDrafts(list=>all?[]:list.filter(d=>d.id!==id));}
  function validDraft(d){return d&&['payment','item','project','apply','settlement'].includes(d.type)&&Array.isArray(d.fields)&&d.fields.every(e=>Array.isArray(e)&&e.length===2&&typeof e[0]==='string'&&typeof e[1]==='string')&&Number.isSafeInteger(d.baseRevision);}
  function loadDrafts(record){
    const list=record?.schemaVersion===2&&Array.isArray(record.records)?record.records:record?[record]:[];
    const seen=new Set();storedDraftIds=new Set(list.filter(validDraft).map(d=>d.id).filter(id=>typeof id==='string'));
    drafts=list.filter(validDraft).map(d=>({...d,id:typeof d.id==='string'&&d.id.length<=100?d.id:uid('d')})).filter(d=>{if(seen.has(d.id)||(data.completedDrafts||[]).includes(d.id))return false;seen.add(d.id);return true;});
  }
  function openDraftList(){
    const list=availableDrafts();if(!list.length){toast('暂无未完成的草稿');return;}
    if(list.length===1){restoreDraft(list[0].id);return;}
    dialog('未完成的草稿',`<p class="dialog-sub">草稿未计入预算或支出统计。提交一份草稿不会删除其他草稿。</p>${list.map(d=>{const v=Object.fromEntries(d.fields),name=v.name||data.items.find(i=>i.id===(v.id||v['payment-item']))?.name||d.title;return `<div class="draft-list-row"><button class="menu-row" data-action="restore-draft" data-id="${esc(d.id)}">${icon('edit')}<div><strong>${esc(name)}</strong><p>${esc(d.title)}${d.time?' · '+esc(localDay(d.time)):''}</p></div>${icon('right')}</button><button class="icon-button text-danger" data-action="delete-draft" data-id="${esc(d.id)}" aria-label="删除 ${esc(name)} 草稿">${icon('trash')}</button></div>`;}).join('')}`);
  }
  function restoreDraft(id){
    const source=availableDrafts().find(d=>d.id===id);
    if(!source||!validDraft(source)){toast('该草稿已提交或不存在。');return;}
    const draft=clone(source),values=Object.fromEntries(draft.fields);
    if(['item','settlement'].includes(draft.type)&&values.id&&!data.items.some(i=>i.id===values.id)){dialog('草稿关联的项目已删除',`<p class="dialog-sub">该项目已不存在，草稿不能直接保存。原草稿仍被保留，可删除后重新建立记录。</p><div class="dialog-actions"><button class="button outline" data-action="close-dialog">关闭</button><button class="button danger-button" data-action="delete-draft" data-id="${esc(id)}">删除草稿</button></div>`);return;}
    entryValues=draft.entryValues&&typeof draft.entryValues==='object'?draft.entryValues:{};Object.assign(entryValues,values);
    if(draft.type==='item'){if(!values['record-mode'])entryValues.__legacy='true';openItem(values.id||'',values['record-mode']||(values.price?.trim()?'estimate':'plan'),entryValues);}
    if(draft.type==='payment')openPayment(values['payment-item']||'',false,entryValues);
    if(draft.type==='project')openProject();
    if(draft.type==='settlement')openSettlement(values.id);
    if(draft.type==='apply'){
      if(!Number.isSafeInteger(draft.applyTotal)||draft.applyTotal<0||draft.applyTotal>MAX_CENTS){showError('草稿中的测算金额无效，请重新测算。');return;}
      openApply(draft.applyTotal);
    }
    const form=$('#sheet form');if(!form)return;
    for(const [name,value]of draft.fields){const field=form.elements.namedItem(name);if(field&&'value'in field){if(field.type==='checkbox')field.checked=value==='on';else field.value=value;}}
    for(const button of form.querySelectorAll('[data-action="form-picker"]')){
      const field=$('#'+button.dataset.field);if(!field)continue;const kind=button.dataset.kind,v=field.value;
      let text=v;if(kind==='category')text=cat(v).name;
      if(kind==='item'||kind==='apply')text=v==='new'?'新增费用项目':data.items.find(i=>i.id===v)?.name||'选择关联项目';
      $('#'+button.dataset.field+'-label').textContent=text;
    }
    if(draft.type==='payment'){
      updatePaymentTarget(values['payment-target']||(values['payment-item']?'linked':'new'));updatePaymentKind(values.kind||'payment');
      $('#payment-item-info').innerHTML=paymentInfo(data.items.find(i=>i.id===values['payment-item']));
    }
    if(draft.type==='apply')updateApplyDiff();refreshDateFields();refreshLook();
    const baseNow=draft.type==='apply'?draftBase():activeDraftBase,conflict=Boolean(draft.requiresReview||(draft.baseEntity?JSON.stringify(draft.baseEntity)!==JSON.stringify(baseNow):(baseNow&&draft.baseRevision!==data.revision)));
    activeDraftId=draft.id;activeDraftOriginal=clone(source);activeDraftBase=baseNow;activeDraftReview=conflict;
    if(conflict)appendDraftReview();
    draftSnapshot=conflict?'__restored_draft__':formSnapshot();$('#draft-status').textContent='草稿已恢复';
  }
  function appendDraftReview(){
    const form=$('#sheet form[data-form]');if(!form||form.querySelector('.draft-conflict'))return;
    const box=document.createElement('div');box.className='draft-conflict';box.innerHTML='<p>原记录在草稿保存后有更新。请核对当前记录，避免用旧内容覆盖新资料。</p><label class="check-row"><input type="checkbox" name="confirm-draft-update"><span>已核对，保存当前填写的内容</span></label>';form.prepend(box);
  }
  // Replacing the ledger retires previous drafts in the same ledger write, even if draft cleanup fails.
  async function replaceLedger(next,message){
    if(draftsUnreadable){showError('已保存的草稿未能读取，暂不能替换账本。请退出并重新打开筑账后再试。');return false;}
    next=clone(next);next.completedDrafts=[...new Set([...(next.completedDrafts||[]),...drafts.map(d=>d.id)])];
    if(!await write(next))return false;
    pendingImport=null;ui.query='';ui.category='all';ui.room='all';ui.merchant=null;done(message);
    try{await clearDraft('',true);}catch(_){showError('账本已更新，旧草稿清理未完成。旧草稿不会写入新账本。');}
    return true;
  }
  function settings(){
    const rows=[['project','home','项目设置','项目资料与总预算'],['export','download','导出账本备份','JSON · 项目、预算、支出与变更记录'],['import','upload','恢复账本备份','核对备份后替换当前账本'],['csv','list','导出费用明细','CSV · 分项预算与费用汇总'],['csv-payments','wallet','导出支出流水','CSV · 支出与退款记录']];
    if(availableDrafts().length)rows.push(['resume-draft','edit','继续编辑草稿','未提交内容不计入统计']);
    if(bootError)rows.unshift(['retry-storage','reset','重新连接存储','开启存储权限后重试']);
    if(restoreError){for(let k=rows.length-1;k>=0;k--)if(['export','csv','csv-payments'].includes(rows[k][0]))rows.splice(k,1);rows.push(['export-raw','download','导出原始数据','保留原始文件用于恢复']);}
    dialog('项目与数据',`<div class="settings-identity"><span class="brand-mark">${icon('home')}</span><div><h3>筑账 <span class="version">${VERSION}</span></h3><p>装修预算与支出管理</p></div></div><div class="form-options">${rows.map(([a,i,l,n])=>`<button class="menu-row" data-action="${a}">${icon(i)}<div><strong>${l}</strong><p>${n}</p></div>${icon('right')}</button>`).join('')}</div><div class="privacy-note">${icon('info','small-icon')}<p>账本仅保存在本地，不上传云端。清除应用数据或卸载前，请导出账本备份。备份不包含未提交草稿。</p></div>${storageOK?'<button class="text-button text-danger" data-action="new-ledger">清空账本</button>':''}`);
  }

  function previewImport(text){
    if(typeof text!=='string')throw new Error('没有读取到 JSON 文本。');
    let parsed;
    try{parsed=validate(JSON.parse(text.replace(/^\uFEFF/,'')));}
    catch(error){if(error instanceof SyntaxError||error instanceof TypeError)throw new Error('文件不是有效的筑账备份，未更改当前数据。');throw error;}
    for(const i of parsed.items)assertCashTimeline(parsed,i.id);
    if(parsed.payments.some(p=>p.date>nowDate()))throw new Error('备份中存在晚于今天的收支记录，未更改当前数据。');
    pendingImport=parsed;
    const t=totals(pendingImport);
    dialog('确认恢复备份',`<p class="dialog-sub">结构校验已通过。恢复会替换当前账本，而不是叠加导入；请确认现有数据已备份。</p><div class="detail-hero"><h3>${esc(pendingImport.project.name)}</h3><div class="value num">${money(t.expected)}</div><div class="caption muted">${pendingImport.items.length} 个费用项 · ${pendingImport.payments.length} 条流水${t.unknown?' · '+t.unknown+' 项待估价':''}</div></div><div class="dialog-actions"><button class="button outline" data-action="close-dialog">取消</button><button class="button primary" data-action="import-confirm">恢复并替换</button></div>`);
  }
  async function exportPayments(){if(restoreError)throw new Error('账本未能读取，请使用「导出原始数据」。');
    const cell=v=>{let s=String(v??'');if(/^[=+\-@\t\r]/.test(s))s="'"+s;return '"'+s.replaceAll('"','""')+'"';};
    const rows=[['日期','类型','费用项','类别','空间','商家','金额（元）','备注'],...data.payments.map(p=>{const item=data.items.find(i=>i.id===p.itemId);return[p.date,p.kind==='refund'?'退款':'付款',item?.name||'',cat(item?.category).name,item?.room||'',item?.merchant||'',(p.amount/100).toFixed(2),p.note];})];
    await exportFile('筑账_付款流水_'+nowDate()+'.csv','\uFEFF'+rows.map(row=>row.map(cell).join(',')).join('\r\n'),'text/csv;charset=utf-8');
  }
  function scheduleCalc(){clearTimeout(calcTimer);calcTimer=setTimeout(flushCalc,450);}
  function flushCalc(){
    clearTimeout(calcTimer);if(!storageOK)return;
    const record={mode:ui.calcMode,calc:clone(ui.calc),baseline:clone(ui.baseline)};
    calcQueue=calcQueue.catch(()=>{}).then(()=>runtime.set('calculator.v1',record)).catch(error=>{
      showError('测算输入未保存：'+runtime.explain(error));throw error;
    });calcSaving=calcQueue.then(()=>true,()=>false);
  }
  function loadCalc(record){
    if(!record||!['area','unit','fixed'].includes(record.mode)||!record.calc)return;
    const c=record.calc;
    for(const key of Object.keys(ui.calc)){
      if(key==='packing'){if(typeof c[key]==='boolean')ui.calc[key]=c[key];}
      else if(typeof c[key]==='string'&&c[key].length<=40)ui.calc[key]=c[key];
    }
    ui.calcMode=record.mode;
    const b=record.baseline;
    if(b&&Number.isSafeInteger(b.total)&&b.total>=0&&b.total<=MAX_CENTS&&typeof b.description==='string'&&b.description.length<=200)ui.baseline={total:b.total,description:b.description};
  }
  async function boot(){
    storageOK=false;bootError='';restoreError='';
    try{
      await runtime.init();rawBackup=await runtime.get(KEY);storageOK=true;
      if(rawBackup!==null){try{data=validate(typeof rawBackup==='string'?JSON.parse(rawBackup):rawBackup);}catch(error){restoreError='账本校验失败，原数据未覆盖。请在「项目与数据」导出原始数据并恢复有效备份。';data=emptyLedger();}}
      else data=emptyLedger();
      try{loadDrafts(await runtime.get('draft.v1'));}catch(_){drafts=[];draftsUnreadable=true;}
      try{loadCalc(await runtime.get('calculator.v1'));}catch(_){}
      try{const v=await runtime.get('home.ui.v1');if(v&&typeof v.labels==='boolean'){homeState.ui={labels:v.labels};homeState.mod?.setUi(homeState.ui);}}catch(_){}
    }catch(error){bootError=runtime.explain(error);}
    render();
  }

  // validate() is exposed for the unit tests; the 3D module talks to the ledger only through homeApi.
  window.ZZApp=Object.freeze({validate,lookOnSave,lookRow,version:VERSION});
  boot();
})();
