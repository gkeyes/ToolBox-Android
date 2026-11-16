/* Local screenshot stitching UI. No uploads, remote libraries, or bundled SDK. */
(() => {
  'use strict';
  const $=id=>document.getElementById(id),E=window.StitchEngine,R=window.StitchRuntime;
  const state={items:[],crop:null,joins:[],plan:null,busy:false,selected:0,worker:null,generation:0};
  const tick=()=>new Promise(r=>setTimeout(r,0));
  const status=(message,type='')=>{$('status').textContent=message;$('status').className=type;};
  const sizeLabel=n=>n<1048576?`${(n/1024).toFixed(0)} KB`:`${(n/1048576).toFixed(2)} MB`;
  function button(label,cls,handler,title){const b=document.createElement('button');b.textContent=label;b.className=cls;if(title)b.setAttribute('aria-label',title);b.addEventListener('click',handler);return b;}
  function setBusy(value,cancelable=false){
    state.busy=value;
    document.querySelectorAll('main button,main input,main select').forEach(el=>{el.disabled=value;});
    $('busy-bar').hidden=!cancelable;
    $('cancel').disabled=false;
    if(!value){$('stitch').disabled=state.items.length<2;updateExport();renderThumbs();}
  }
  function invalidate(){
    state.plan=null;state.joins=[];state.crop=null;
    $('result-section').hidden=true;$('export-status').textContent='';
    $('preview').width=$('preview').height=1;
    $('zoom-canvas').width=$('zoom-canvas').height=1;
  }
  function release(item){if(item.url)URL.revokeObjectURL(item.url);item.img.src='';}
  function renderThumbs(){
    $('thumbs').replaceChildren();$('count').textContent=String(state.items.length);
    $('drop-zone').hidden=state.items.length>0;$('clear').hidden=!state.items.length;$('reverse').hidden=state.items.length<2;
    $('stitch').disabled=state.busy||state.items.length<2;
    state.items.forEach((item,i)=>{
      const card=document.createElement('article');card.className='thumb'+(state.selected===i?' active':'');
      const im=button('','thumb-image',()=>{state.selected=i;renderThumbs();if(state.crop)drawCrop();},`查看第 ${i+1} 张截图`);
      const pic=new Image();pic.src=item.url;pic.alt=item.name;im.append(pic);
      const badge=document.createElement('span');badge.className='thumb-index';badge.textContent=i+1;
      const name=document.createElement('div');name.className='thumb-name';name.textContent=item.name;
      const actions=document.createElement('div');actions.className='thumb-actions';
      const move=(to)=>{[state.items[i],state.items[to]]=[state.items[to],state.items[i]];state.selected=to;invalidate();renderThumbs();status('顺序已调整，请重新拼接。');};
      const up=button('←','text-button',()=>move(i-1),`第 ${i+1} 张前移`);up.disabled=state.busy||i===0;
      const down=button('→','text-button',()=>move(i+1),`第 ${i+1} 张后移`);down.disabled=state.busy||i===state.items.length-1;
      const del=button('×','text-button danger',()=>{release(state.items.splice(i,1)[0]);state.selected=Math.min(state.selected,Math.max(0,state.items.length-1));invalidate();renderThumbs();status('截图已移除。');},`移除第 ${i+1} 张`);del.disabled=state.busy;im.disabled=state.busy;
      actions.append(up,down,del);card.append(im,badge,name,actions);$('thumbs').append(card);
    });
  }
  async function decode(file){
    if(!/^image\/(png|jpeg|webp)$/.test(file.type)&&!(/\.(png|jpe?g|webp)$/i.test(file.name)))throw new Error('请选择 PNG、JPG 或 WebP 图片。');
    if(file.size>40*1024*1024)throw new Error(`${file.name} 超过 40 MB，暂不支持。`);
    const url=URL.createObjectURL(file),img=new Image();
    try{await new Promise((resolve,reject)=>{img.onload=resolve;img.onerror=()=>reject(new Error(`${file.name} 无法解码，请换成 PNG 或 JPG。`));img.src=url;});}
    catch(e){URL.revokeObjectURL(url);throw e;}
    const w=img.naturalWidth,h=img.naturalHeight;
    if(w<80||h<80||w*h>24000000||w>12000||h>30000){URL.revokeObjectURL(url);img.src='';throw new Error('图片尺寸不适合处理：单张需至少 80×80，且不超过 2400 万像素。');}
    return {name:file.name,img,url,w,h};
  }
  async function addFiles(files){
    if(state.busy)return;
    const sorted=Array.from(files).sort((a,b)=>a.name.localeCompare(b.name,undefined,{numeric:true}));
    if(!sorted.length)return;
    setBusy(true);let added=0;const errors=[];
    try{
      for(const file of sorted){
        if(state.items.length>=12){errors.push('单次最多添加 12 张截图。');break;}
        try{
          const item=await decode(file);
          if(state.items.reduce((n,a)=>n+a.w*a.h,0)+item.w*item.h>60000000){release(item);throw new Error('图片总像素超过 6000 万，请分批拼接以避免内存不足。');}
          state.items.push(item);added++;
        }catch(e){errors.push(R.explain(e));}
      }
      if(added){invalidate();renderThumbs();}
      status(errors.length?`已添加 ${added} 张。${errors.join(' ')}`:`已添加 ${added} 张截图。确认顺序后点击“自动拼接”。`,errors.length?'error':'');
    }finally{setBusy(false);}
  }
  async function pick(){
    if(state.busy)return;
    if(!R.isNative){$('file-input').click();return;}
    setBusy(true);let file;
    try{file=await R.open();}catch(e){status(R.explain(e),'error');}
    finally{setBusy(false);}
    if(file)await addFiles([file]);
  }
  async function prepareImages(){
    // Same screenshot scale is required: resize only when widths differ.
    const width=Math.min(...state.items.map(x=>x.w));
    const minWidth=Math.min(...state.items.map(x=>x.w)),maxWidth=Math.max(...state.items.map(x=>x.w));
    if(maxWidth/minWidth>1.1)throw new Error('截图宽度差异超过 10%，可能来自不同设备或页面缩放。请先使用同一尺寸截图。');
    const canvas=document.createElement('canvas'),ctx=canvas.getContext('2d',{willReadFrequently:true});
    for(const item of state.items){
      if(item.analysis&&item.analysis.w===width)continue;
      const h=Math.round(item.h*width/item.w);canvas.width=width;canvas.height=h;
      ctx.fillStyle='#fff';ctx.fillRect(0,0,width,h);ctx.drawImage(item.img,0,0,width,h);
      const rgba=ctx.getImageData(0,0,width,h).data,gray=new Uint8Array(width*h);
      for(let k=0;k<gray.length;k++)gray[k]=Math.round(rgba[k*4]*.299+rgba[k*4+1]*.587+rgba[k*4+2]*.114);
      item.analysis={w:width,h,gray};await tick();
    }
    canvas.width=canvas.height=1;
    return state.items.map(x=>x.analysis);
  }
  function validateCrop(crop,images){
    for(const key of ['top','bottom','left','right'])if(!Number.isInteger(crop[key])||crop[key]<0)throw new Error('裁切值需要是大于或等于 0 的整数。');
    for(const im of images){const r=E.rectFor(im,crop);if(r.w<80||r.h<100)throw new Error('裁切后至少保留 80 px 宽、100 px 高的正文。');}
    return crop;
  }
  async function runAnalysis(images,crop,id){
    try{
      return await new Promise((resolve,reject)=>{
        let worker;try{worker=new Worker('worker.js');}catch(e){reject(e);return;}
        state.worker=worker;
        state.rejectWorker=reject;
        worker.onerror=()=>{worker.terminate();reject(new Error('WORKER_UNAVAILABLE'));};
        worker.onmessage=({data})=>{
          if(data.id!==id)return;
          if(data.error){worker.terminate();reject(new Error(data.error));return;}
          if(data.done){worker.terminate();state.worker=null;state.rejectWorker=null;resolve(data);}
          else status(`正在匹配第 ${data.progress} / ${data.total} 处接缝…`);
        };
        worker.postMessage({id,images,crop});
      });
    }catch(error){
      state.worker=null;state.rejectWorker=null;
      if(state.generation!==id)throw new Error('CANCELED');
      if(error.message!=='WORKER_UNAVAILABLE'&&error.name!=='SecurityError')throw error;
      // Older hosts can still run the same deterministic engine on the page.
      const actual=crop||E.detectCrop(images),joins=[];
      for(let i=1;i<images.length;i++){
        await tick();if(state.generation!==id)throw new Error('CANCELED');
        const m=E.matchPair(images[i-1],images[i],actual),h=E.rectFor(images[i-1],actual).h;
        const shift=m.confidence>.5&&!m.duplicate&&m.shift>0?m.shift:h;
        joins.push({...m,detectedShift:m.shift,shift,overlap:h-shift,seam:E.chooseSeam(images[i-1],images[i],actual,shift),reviewed:m.confidence>.5&&m.shift>0&&!m.duplicate});
      }
      return {crop:actual,joins};
    }
  }
  async function analyze(manualCrop=null,reversed=false){
    if(state.busy||state.items.length<2)return;
    if(manualCrop&&state.items.every(x=>x.analysis)){
      try{validateCrop(manualCrop,state.items.map(x=>x.analysis));}catch(e){status(e.message,'error');return;}
    }
    const id=++state.generation;setBusy(true,true);status('正在识别固定栏与重复内容…');
    // Old output must never be exported while a new analysis is pending/failed.
    state.plan=null;$('result-section').hidden=true;$('export-status').textContent='';
    let retry=false;
    try{
      const images=await prepareImages();if(state.generation!==id)throw new Error('CANCELED');
      if(manualCrop)validateCrop(manualCrop,images);
      const result=await runAnalysis(images,manualCrop,id);if(state.generation!==id)throw new Error('CANCELED');
      validateCrop(result.crop,images);
      if(images.length===2 && !reversed && result.joins[0].confidence>.5 && result.joins[0].detectedShift<0){state.items.reverse();state.selected=0;retry=true;}
      else{
        state.crop=result.crop;state.joins=result.joins;
        for(const key of ['top','bottom','left','right'])$('crop-'+key).value=state.crop[key];
        rebuildPlan();drawCrop();renderSeams();
        const uncertain=state.joins.filter(x=>!x.reviewed).length;
        $('result-section').hidden=false;$('seams-details').open=uncertain>0;
        status(uncertain?`有 ${uncertain} 处接缝需要检查。请调整并确认后导出。`:`已完成拼接${reversed?'，并自动修正了两张截图的顺序':''}。请检查预览后保存。`,uncertain?'error':'success');
      }
    }catch(e){
      if(e.message==='CANCELED')status('已取消识别，截图仍保留。');
      else {state.plan=null;status(R.explain(e),'error');}
    }finally{setBusy(false);}
    if(retry)await analyze(manualCrop,true);
  }
  function rebuildPlan(){
    state.plan=E.plan(state.items.map(x=>x.analysis),state.crop,state.joins);
    const p=state.plan;
    $('result-info').textContent=`${p.width} × ${p.height} px · ${state.items.length} 张截图 · ${state.joins.length} 处接缝`;
    const c=state.crop;$('crop-summary').textContent=`上 ${c.top} / 下 ${c.bottom} / 左 ${c.left} / 右 ${c.right}`;
    drawPlan($('preview'),Math.min(600,p.width,Math.floor(p.width*14000/p.height)));
    $('export-status').textContent='';updateExport();
  }
  function drawPlan(canvas,width){
    const p=state.plan,ratio=width/p.width;
    canvas.width=Math.max(1,Math.round(width));canvas.height=Math.max(1,Math.round(p.height*ratio));
    const ctx=canvas.getContext('2d');if(!ctx)throw new Error('无法创建图片画布，请缩小导出尺寸。');
    ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.imageSmoothingQuality='high';
    for(const s of p.segments){
      const item=state.items[s.index],sourceScale=item.w/item.analysis.w;
      // One shared rounded boundary for neighbouring pieces avoids 1px gaps.
      const top=Math.round(s.outY*ratio),bottom=Math.round((s.outY+s.h)*ratio);
      ctx.drawImage(item.img,s.x*sourceScale,s.y*sourceScale,s.w*sourceScale,s.h*sourceScale,0,top,canvas.width,bottom-top);
    }
  }
  function readCrop(){return Object.fromEntries(['top','bottom','left','right'].map(k=>[k,Number($('crop-'+k).value)]));}
  function drawCrop(){
    if(!state.items.length||!state.crop)return;
    const item=state.items[state.selected],im=item.analysis,crop=readCrop();
    const canvas=$('crop-preview'),scale=Math.min(360/im.w,460/im.h);
    canvas.width=Math.round(im.w*scale);canvas.height=Math.round(im.h*scale);
    const ctx=canvas.getContext('2d');ctx.drawImage(item.img,0,0,canvas.width,canvas.height);
    if(Object.values(crop).some(x=>!Number.isFinite(x)))return;
    const x=crop.left*scale,y=crop.top*scale,w=(im.w-crop.left-crop.right)*scale,h=(im.h-crop.top-crop.bottom)*scale;
    ctx.fillStyle='rgba(22,15,38,.65)';ctx.fillRect(0,0,canvas.width,y);ctx.fillRect(0,y+h,canvas.width,canvas.height-y-h);ctx.fillRect(0,y,x,h);ctx.fillRect(x+w,y,canvas.width-x-w,h);
    ctx.strokeStyle='#b48bff';ctx.lineWidth=2;ctx.strokeRect(x,y,w,h);
  }
  function drawSeam(canvas,i){
    const p=state.plan;if(!p)return;
    const center=p.seams[i],from=Math.max(0,center-100),to=Math.min(p.height,center+100);
    canvas.width=Math.min(660,p.width);const ratio=canvas.width/p.width;canvas.height=Math.max(1,Math.round((to-from)*ratio));
    const ctx=canvas.getContext('2d');ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);
    for(const s of p.segments){const lo=Math.max(from,s.outY),hi=Math.min(to,s.outY+s.h);if(hi<=lo)continue;
      const item=state.items[s.index],sc=item.w/item.analysis.w;
      const dy=Math.round((lo-from)*ratio),end=Math.round((hi-from)*ratio);
      ctx.drawImage(item.img,s.x*sc,(s.y+lo-s.outY)*sc,s.w*sc,(hi-lo)*sc,0,dy,canvas.width,end-dy);
    }
    ctx.strokeStyle='#9a69eb';ctx.lineWidth=1;ctx.setLineDash([5,5]);const y=(center-from)*ratio;ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(canvas.width,y);ctx.stroke();
  }
  function adjustOverlap(i,value){
    const images=state.items.map(x=>x.analysis),a=E.rectFor(images[i],state.crop),b=E.rectFor(images[i+1],state.crop);
    const max=Math.min(a.h,b.h)-1,n=Math.round(Number(value));
    if(!Number.isFinite(n)||n<0||n>max){status(`重叠高度应为 0 到 ${max} px。`,'error');renderSeams();return;}
    const prev={...state.joins[i]},shift=a.h-n;
    state.joins[i]={...prev,shift,overlap:n,seam:E.chooseSeam(images[i],images[i+1],state.crop,shift),reviewed:true,manual:true,duplicate:false};
    try{rebuildPlan();renderSeams();status('接缝已更新，请检查文字是否连续。');}
    catch(e){state.joins[i]=prev;rebuildPlan();renderSeams();status(e.message,'error');}
  }
  function renderSeams(){
    $('seams').replaceChildren();const warnings=state.joins.filter(j=>!j.reviewed).length;
    $('seam-summary').textContent=warnings?`${warnings} 处待确认`:`${state.joins.length} 处已对齐`;
    state.joins.forEach((join,i)=>{
      const wrap=document.createElement('div');wrap.className='seam';
      const title=document.createElement('div');title.className='seam-title';
      const label=document.createElement('strong');label.textContent=`第 ${i+1} 张 → 第 ${i+2} 张`;
      const result=document.createElement('span');result.className='seam-state'+(!join.reviewed?' warn':'');
      result.textContent=join.manual?'手动设置':join.reviewed?'已自动对齐':'需要检查';title.append(label,result);
      const canvas=document.createElement('canvas');canvas.className='seam-canvas';canvas.setAttribute('aria-label',`第 ${i+1} 处接缝局部预览`);
      const stepper=document.createElement('div');stepper.className='stepper';
      const text=document.createElement('label');text.textContent='重叠高度 (px)';text.htmlFor=`overlap-${i}`;
      const input=document.createElement('input');input.id=`overlap-${i}`;input.type='number';input.min='0';input.value=join.overlap;
      const images=state.items.map(x=>x.analysis),max=Math.min(E.rectFor(images[i],state.crop).h,E.rectFor(images[i+1],state.crop).h)-1;input.max=max;input.inputMode='numeric';input.addEventListener('change',()=>adjustOverlap(i,input.value));
      stepper.append(text,button('−','secondary',()=>adjustOverlap(i,join.overlap-1),'减少 1 像素重叠'),input,button('＋','secondary',()=>adjustOverlap(i,join.overlap+1),'增加 1 像素重叠'));
      const range=document.createElement('input');range.type='range';range.className='seam-range';range.min=0;range.max=max;range.value=join.overlap;range.setAttribute('aria-label',`第 ${i+1} 处重叠高度`);range.addEventListener('input',()=>{input.value=range.value;});range.addEventListener('change',()=>adjustOverlap(i,range.value));
      wrap.append(title,canvas,stepper,range);
      if(!join.reviewed){
        const msg=document.createElement('p');msg.className='seam-message';msg.textContent=join.duplicate?'这两张几乎相同，请移除重复截图；如确需保留，可确认直接拼接。':join.detectedShift<0?'顺序可能反了，请调整截图顺序。当前预览为直接拼接。':'没有找到可靠的共同内容，当前预览为直接拼接。';
        const confirm=button('确认直接拼接','secondary wide',()=>adjustOverlap(i,0));wrap.append(msg,confirm);
      }
      $('seams').append(wrap);drawSeam(canvas,i);
    });
    updateExport();
  }
  function exportDimensions(){
    if(!state.plan)return null;
    const p=state.plan,val=Number($('scale').value),ratio=val>1?Math.min(1,val/p.width):val;
    const w=Math.max(1,Math.round(p.width*ratio)),h=Math.max(1,Math.round(p.height*w/p.width));return {w,h};
  }
  function updateExport(){
    $('quality-row').hidden=$('format').value!=='jpeg';
    const dims=exportDimensions(),unchecked=state.joins.some(j=>!j.reviewed);
    const dirty=state.crop&&Object.entries(readCrop()).some(([k,v])=>v!==state.crop[k]);
    $('export').disabled=state.busy||!state.plan||unchecked||dirty;
    if(!dims)return;
    const over=dims.w*dims.h>32000000||dims.h>32760||dims.w>16384;
    const fmt=$('format').value==='png'?'PNG 无损':`JPG · ${Math.round(Number($('quality').value)*100)}% 画质`;
    $('export-info').textContent=dirty?'裁切值已改变，请先点击“应用并识别”。':unchecked?'请先检查并确认所有接缝。':over?'当前尺寸超过安全画布范围，请选择较小尺寸或分批拼接。':`${fmt} · ${dims.w} × ${dims.h} px。${$('format').value==='png'?'适合文字截图，文件通常更大。':'画质越高，文件通常越大。'}`;
    if(over)$('export').disabled=true;
  }
  async function exportImage(){
    if(state.busy||!state.plan||state.joins.some(j=>!j.reviewed))return;
    const dims=exportDimensions();if(dims.w*dims.h>32000000||dims.h>32760||dims.w>16384){updateExport();return;}
    setBusy(true);$('export-status').className='hint';$('export-status').textContent='正在生成图片…';
    const canvas=document.createElement('canvas');
    try{
      await tick();drawPlan(canvas,dims.w);
      const format=$('format').value,mime=format==='png'?'image/png':'image/jpeg';
      const blob=await new Promise((resolve,reject)=>{try{canvas.toBlob(b=>b?resolve(b):reject(new Error('图片编码失败，请选择较小尺寸后重试。')),mime,Number($('quality').value));}catch(e){reject(e);}});
      canvas.width=canvas.height=1;
      if(blob.size>48*1024*1024)throw new Error('导出文件超过 48 MB，请选择 JPG 或缩小尺寸后重试。');
      const stamp=new Date(),pad=n=>String(n).padStart(2,'0');
      const ts=`${stamp.getFullYear()}${pad(stamp.getMonth()+1)}${pad(stamp.getDate())}_${pad(stamp.getHours())}${pad(stamp.getMinutes())}${pad(stamp.getSeconds())}`;
      $('export-status').textContent=`${sizeLabel(blob.size)} · 请选择保存位置…`;
      const ok=await R.save(`无缝长图_${ts}.${format==='png'?'png':'jpg'}`,blob);
      $('export-status').textContent=ok?`${R.isNative?'已保存':'已发起下载'} · ${sizeLabel(blob.size)} · ${dims.w} × ${dims.h} px`:'已取消保存，拼接结果仍保留。';
    }catch(e){$('export-status').textContent=R.explain(e);$('export-status').className='hint error';}
    finally{canvas.width=canvas.height=1;setBusy(false);}
  }
  $('add').addEventListener('click',pick);$('drop-zone').addEventListener('click',pick);
  $('file-input').addEventListener('change',async e=>{await addFiles(e.target.files);e.target.value='';});
  $('stitch').addEventListener('click',()=>analyze());
  $('reverse').addEventListener('click',()=>{state.items.reverse();state.selected=0;invalidate();renderThumbs();status('已倒转顺序，请重新拼接。');});
  $('clear').addEventListener('click',()=>{state.items.forEach(release);state.items=[];state.selected=0;invalidate();renderThumbs();status('已清空，可以开始新的拼接。');});
  $('cancel').addEventListener('click',()=>{state.generation++;state.worker?.terminate();state.rejectWorker?.(new Error('CANCELED'));});
  $('apply-crop').addEventListener('click',()=>analyze(readCrop()));
  $('uncrop').addEventListener('click',()=>analyze({top:0,bottom:0,left:0,right:0}));
  $('redetect').addEventListener('click',()=>analyze());
  for(const k of ['top','bottom','left','right'])$('crop-'+k).addEventListener('input',()=>{drawCrop();updateExport();});
  for(const k of ['format','quality','scale'])$(k).addEventListener('change',()=>{updateExport();$('export-status').textContent='';});
  $('export').addEventListener('click',exportImage);
  $('help-button').addEventListener('click',()=>$('help-dialog').showModal());$('close-help').addEventListener('click',()=>$('help-dialog').close());
  $('zoom').addEventListener('click',()=>{if(!state.plan)return;const p=state.plan;drawPlan($('zoom-canvas'),Math.min(p.width,Math.floor(p.width*30000/p.height),Math.floor(Math.sqrt(20000000*p.width/p.height))));$('zoom-canvas').style.width='100%';$('zoom-dialog').showModal();});
  $('close-zoom').addEventListener('click',()=>{$('zoom-dialog').close();$('zoom-canvas').width=$('zoom-canvas').height=1;});
  $('fit-zoom').addEventListener('click',()=>{$('zoom-canvas').style.width='100%';});
  $('actual-zoom').addEventListener('click',()=>{$('zoom-canvas').style.width=state.plan.width+'px';});
  document.addEventListener('dragover',e=>{e.preventDefault();if(!state.busy)$('input-section').classList.add('drag-over');});
  document.addEventListener('dragleave',e=>{if(!e.relatedTarget)$('input-section').classList.remove('drag-over');});
  document.addEventListener('drop',e=>{e.preventDefault();$('input-section').classList.remove('drag-over');if(!R.isNative)addFiles(e.dataTransfer.files);});
  document.addEventListener('paste',e=>{if(!R.isNative&&e.clipboardData.files.length)addFiles(e.clipboardData.files);});
  R.init().then(()=>{if(R.isNative)$('picker-note').textContent='每次选一张图片，可继续添加；按页面从上到下排列。';}).catch(e=>status(R.explain(e),'error'));
  renderThumbs();
})();
