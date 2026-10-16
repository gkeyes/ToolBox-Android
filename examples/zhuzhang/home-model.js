'use strict';
// 1.4.0 · 「我的家」 pure model: floor-plan templates, saved-data sanitizers and placement math.
// No DOM and no Three.js here, so app.js (validate) and the node tests can use it; home3d.js draws with it.
(function(root){
  const FURNITURE='furniture'; // CATS id of 家具家电 in app.js
  const WALL_T=.12, GAP=WALL_T/2+.02; // wall thickness and the clearance kept from a wall face
  const MAX_PER_ROOM=6;
  // Footprint (metres, before rotation) of every look; wall:1 hangs on a wall facing into the room;
  // free:1 stands anywhere (tables), every other look turns its back to a wall it is dragged near.
  // Every look's front faces +z at rot 0, so rot = the wall side (0 back, 1 left, 2 front, 3 right).
  const KINDS={
    fridgeA:{k:'app',w:.7,d:.7},fridgeB:{k:'app',w:.92,d:.72},fridgeC:{k:'app',w:.66,d:.66},
    tvA:{k:'app',w:1.7,d:.42},tvB:{k:'app',w:1.1,d:.5},
    acA:{k:'app',w:.9,d:.24,wall:1},acB:{k:'app',w:.5,d:.36},
    hoodA:{k:'app',w:.9,d:.5,wall:1},hoodB:{k:'app',w:.9,d:.4,wall:1},
    microA:{k:'app',w:.62,d:.47},microB:{k:'app',w:.62,d:.6},
    washerA:{k:'app',w:.6,d:.6},washerB:{k:'app',w:.6,d:.62},
    sofaA:{k:'fur',w:2.1,d:.95},sofaB:{k:'fur',w:2.3,d:1.6},sofaC:{k:'fur',w:1.5,d:.85},
    bedA:{k:'fur',w:1.7,d:2.15},bedB:{k:'fur',w:1.75,d:2.2},
    diningA:{k:'fur',w:1.7,d:1.7,free:1},diningB:{k:'fur',w:1.5,d:1.7,free:1},
    deskA:{k:'fur',w:1.2,d:1.05},deskB:{k:'fur',w:1.3,d:1.0},
    cabA:{k:'fur',w:1.4,d:.62},cabB:{k:'fur',w:1.3,d:.45},cabC:{k:'fur',w:1.0,d:.36}
  };
  const ORDER={app:['fridgeA','fridgeB','fridgeC','tvA','tvB','acA','acB','hoodA','hoodB','microA','microB','washerA','washerB'],fur:['sofaA','sofaB','sofaC','bedA','bedB','diningA','diningB','deskA','deskB','cabA','cabB','cabC']};
  // Room names match ROOMS in app.js. back = rooms along the back wall, left to right, with widths.
  const TEMPLATES={
    t1l1:{n:'一室一厅',back:[['主卧',3.6],['卫生间',2.0]],D1:3.5,D2:4.2,L:3.4,K:2.6,BW:3.0},
    t2l1:{n:'两室一厅',back:[['主卧',3.6],['卫生间',2.0],['次卧',3.0]],D1:3.6,D2:4.6,L:6.0,K:4.6,BW:3.6},
    t3l1:{n:'三室一厅',back:[['主卧',3.6],['卫生间',1.9],['次卧',3.0],['次卧2',2.7]],D1:3.6,D2:4.8,L:8.4,K:4.8,BW:4.2},
    t3l2:{n:'三室两厅',back:[['主卧',3.6],['卫生间',1.9],['次卧',3.0],['次卧2',2.7]],D1:3.6,D2:4.8,L:7.6,K:2.8,BW:4.2}
  };
  const TEMPLATE_ORDER=['t1l1','t2l1','t3l1','t3l2'];
  const BALCONY=1.4,MINW=1.4;
  const num=v=>typeof v==='number'&&Number.isFinite(v);
  const clamp=(v,a,b)=>Math.min(Math.max(v,a),b);
  const round2=v=>Math.round(v*100)/100;

  // 「三室两厅」「3室1厅」「两室」… → template key; anything else falls back to 三室两厅.
  function deriveTemplate(layout){
    const s=String(layout||'').replace(/\s+/g,'');
    const n=c=>({'一':1,'1':1,'二':2,'两':2,'2':2,'三':3,'3':3,'四':4,'4':4,'五':5,'5':5}[c]);
    const m=s.match(/([一二两三四五1-5])[室房居]/);
    if(!m)return 't3l2';
    const rooms=n(m[1]),h=s.match(/([一二两三1-3])[厅廳]/),halls=h?n(h[1]):null;
    if(rooms<=1)return 't1l1';
    if(rooms===2)return 't2l1';
    return halls===1?'t3l1':'t3l2';
  }
  // The plan to draw: saved sizes on top of the template defaults, kept geometrically consistent.
  function planOf(home,layout){
    const key=home&&TEMPLATES[home.template]?home.template:deriveTemplate(layout);
    const base=TEMPLATES[key],t={key,n:base.n,back:base.back.map(([n,w])=>[n,w]),D1:base.D1,D2:base.D2,L:base.L,K:base.K,BW:base.BW};
    const s=home&&home.template===key&&home.sizes;
    if(s){
      if(Array.isArray(s.back)&&s.back.length===t.back.length&&s.back.every(num))s.back.forEach((w,i)=>{t.back[i][1]=clamp(w,MINW,12);});
      for(const k of ['D1','D2','L','K','BW'])if(num(s[k]))t[k]=clamp(s[k],1,20);
    }
    const W=t.back.reduce((a,[,w])=>a+w,0);
    t.L=clamp(t.L,Math.min(3,W-1.2),W-1.2);t.K=clamp(t.K,1.2,t.D2);t.BW=clamp(t.BW,2,W);
    return t;
  }
  function sizesOf(t){return {back:t.back.map(([,w])=>round2(w)),D1:round2(t.D1),D2:round2(t.D2),L:round2(t.L),K:round2(t.K),BW:round2(t.BW)};}
  // project.home as saved: unknown template → dropped (the plan is derived again); bad sizes → template defaults.
  function sanitizeHome(h){
    if(!h||typeof h!=='object'||!TEMPLATES[h.template])return null;
    const out={template:h.template};
    if(h.sizes&&typeof h.sizes==='object'){const t=planOf({template:h.template,sizes:h.sizes});out.sizes=sizesOf(t);}
    return out;
  }
  // item.model as saved: unknown look → dropped; position fractions clamped to 0..1, rot to a quarter turn.
  function sanitizeModel(m){
    if(!m||typeof m!=='object'||typeof m.kind!=='string'||!Object.prototype.hasOwnProperty.call(KINDS,m.kind))return null;
    const f=v=>num(v)?Math.round(clamp(v,0,1)*10000)/10000:.5;
    const rot=Number.isInteger(m.rot)?((m.rot%4)+4)%4:0;
    return {kind:m.kind,x:f(m.x),z:f(m.z),rot};
  }
  function makeRects(t){
    const out=[];let x=0;
    t.back.forEach(([n,w])=>{out.push({name:n,r:[x,0,x+w,t.D1]});x+=w;});
    const W=x,z1=t.D1,z2=t.D1+t.D2;
    out.push({name:'客餐厅',r:[0,z1,t.L,z2]});out.push({name:'厨房',r:[t.L,z1,W,z1+t.K]});
    if(t.K<t.D2-.01)out.push({name:'客餐厅',r:[t.L,z1+t.K,W,z2]});
    out.push({name:'阳台',r:[0,z2,t.BW,z2+BALCONY]});
    return {rects:out,W,D:z2+BALCONY};
  }
  const roomNames=rects=>[...new Set(rects.map(r=>r.name))];
  const area=(rects,n)=>rects.filter(r=>r.name===n).reduce((s,{r})=>s+(r[2]-r[0])*(r[3]-r[1]),0);
  function bbox(rects,name){
    const rs=rects.filter(r=>r.name===name);if(!rs.length)return null;
    return {rs,x0:Math.min(...rs.map(r=>r.r[0])),z0:Math.min(...rs.map(r=>r.r[1])),x1:Math.max(...rs.map(r=>r.r[2])),z1:Math.max(...rs.map(r=>r.r[3]))};
  }
  function nearestRect(rs,x,z){let best=rs[0],bd=Infinity;for(const R of rs){const [a,b,c,d]=R.r;const dx=Math.max(a-x,0,x-c),dz=Math.max(b-z,0,z-d),dd=dx*dx+dz*dz;if(dd<bd){bd=dd;best=R;}}return best;}
  const footprint=(dims,rot)=>(rot&1)?[dims.d,dims.w]:[dims.w,dims.d];
  // Keep a look inside its own room: the nearest part of the room (an L-shaped 客餐厅 has two),
  // clear of the walls; a wall-mounted look snaps to the nearest wall and faces into the room.
  // auto (a drag): a look that is not free and comes within SNAP of a wall turns its back to it and backs onto it.
  const SNAP=.35;
  function clampPlacement(dims,bb,x,z,rot,auto=false){
    const NR=nearestRect(bb.rs,x,z).r,[x0,z0,x1,z1]=NR,h=GAP;
    rot=((rot|0)%4+4)%4;
    if(auto&&!dims.wall&&!dims.free){
      // Gap between the look's back and each wall if it backed onto it; the depth faces the wall whatever the side, so no flicker.
      // A side counts only when it is a real wall (not the open seam to the other part of an L-shaped room) and the turned look fits.
      const me=NR,seam=sd=>bb.rs.some(({r})=>r!==me&&(sd===0?Math.abs(r[3]-z0)<.01&&x>r[0]&&x<r[2]:sd===2?Math.abs(r[1]-z1)<.01&&x>r[0]&&x<r[2]:sd===1?Math.abs(r[2]-x0)<.01&&z>r[1]&&z<r[3]:Math.abs(r[0]-x1)<.01&&z>r[1]&&z<r[3]));
      const fits=sd=>{const [along,across]=sd&1?[z1-z0,x1-x0]:[x1-x0,z1-z0];return dims.w<=along-2*h&&dims.d<=across-2*h;};
      const near=[[z-z0,0],[x-x0,1],[z1-z,2],[x1-x,3]].map(([g,sd])=>[g-h-dims.d/2,sd]).filter(([g,sd])=>g<SNAP&&!seam(sd)&&fits(sd)).sort((a,b)=>a[0]-b[0]);
      if(near.length){const side=near.some(([,sd])=>sd===rot)?rot:near[0][1];rot=side;
        if(side===0)z=z0;else if(side===2)z=z1;else if(side===1)x=x0;else x=x1;} // pushed flush below by the clamp
    }
    if(dims.wall){
      const side=[[z-z0,0],[x-x0,1],[z1-z,2],[x1-x,3]].sort((a,b)=>a[0]-b[0])[0][1];
      const cl=(v,a,b,s)=>b-a<2*s?(a+b)/2:clamp(v,a+s,b-s);
      if(side===0){z=z0+h+dims.d/2;x=cl(x,x0,x1,dims.w/2+h);}
      else if(side===2){z=z1-h-dims.d/2;x=cl(x,x0,x1,dims.w/2+h);}
      else if(side===1){x=x0+h+dims.d/2;z=cl(z,z0,z1,dims.w/2+h);}
      else{x=x1-h-dims.d/2;z=cl(z,z0,z1,dims.w/2+h);}
      rot=side;
    }else{
      const [sw,sd]=footprint(dims,rot);
      const cl=(v,a,b,s)=>b-a<2*s?(a+b)/2:clamp(v,a+s,b-s);
      x=cl(x,x0,x1,sw/2+h);z=cl(z,z0,z1,sd/2+h);
    }
    const fx=bb.x1>bb.x0?(x-bb.x0)/(bb.x1-bb.x0):.5,fz=bb.z1>bb.z0?(z-bb.z0)/(bb.z1-bb.z0):.5;
    return {x,z,rot,fx:clamp(fx,0,1),fz:clamp(fz,0,1)};
  }
  // Saved fractions → a clamped position in the current plan (rooms may have been resized since).
  function placeFromModel(model,rects,room){
    const bb=bbox(rects,room),dims=KINDS[model.kind];if(!bb||!dims)return null;
    return clampPlacement(dims,bb,bb.x0+model.x*(bb.x1-bb.x0),bb.z0+model.z*(bb.z1-bb.z0),model.rot);
  }
  // Does the look fit in at least one part of the room, clear of the walls?
  function fitsRoom(dims,rects,room){
    const bb=bbox(rects,room);if(!bb)return false;
    return bb.rs.some(({r})=>{const w=r[2]-r[0]-2*GAP,d=r[3]-r[1]-2*GAP;return (dims.w<=w&&dims.d<=d)||(dims.d<=w&&dims.w<=d);});
  }
  // Which placed items a room shows: up to MAX_PER_ROOM that fit; the rest count towards the +N badge.
  function fitRoom(items,rects,room,max=MAX_PER_ROOM){
    const shown=[],hidden=[];let used=0;const cap=area(rects,room)*.9;
    for(const it of items){
      const dims=KINDS[it.kind];const a=dims?dims.w*dims.d:0;
      if(dims&&fitsRoom(dims,rects,room)&&shown.length<max&&(dims.wall||used+a<=cap)){shown.push(it);if(!dims.wall)used+=a;}else hidden.push(it);
    }
    return {shown,hidden};
  }
  const overlap=(a,b)=>Math.max(0,Math.min(a.x+a.w/2,b.x+b.w/2)-Math.max(a.x-a.w/2,b.x-b.w/2))*Math.max(0,Math.min(a.z+a.d/2,b.z+b.d/2)-Math.max(a.z-a.d/2,b.z-b.d/2));
  // A free spot for a newly placed look: least overlap with what is already there, backed onto a wall.
  function findSpot(kind,rects,room,others=[]){
    const dims=KINDS[kind],bb=bbox(rects,room);if(!dims||!bb)return null;
    const W=Math.max(...rects.map(r=>r.r[2])),D=Math.max(...rects.map(r=>r.r[3]));
    let best=null;
    for(const R of bb.rs){const [x0,z0,x1,z1]=R.r;
      for(let i=0;i<=6;i++)for(let j=0;j<=6;j++){
        const px=x0+(x1-x0)*i/6,pz=z0+(z1-z0)*j/6;
        const side=[[pz-z0,0],[px-x0,1],[z1-pz,2],[x1-px,3]].sort((a,b)=>a[0]-b[0])[0];
        const p=clampPlacement(dims,bb,px,pz,dims.wall?0:side[1]);
        const [w,d]=footprint(dims,p.rot),me={x:p.x,z:p.z,w:w+.1,d:d+.1};
        const hit=others.reduce((s,o)=>s+overlap(me,o),0);
        // Wall-mounted looks go on an outer wall: inner walls are drawn cut down, so a unit there would float.
        const outer=[z0<.01,x0<.01,z1>D-.01,x1>W-.01][p.rot];
        const score=(dims.wall&&!outer?50:0)+hit*100+side[0]*.05+Math.hypot(p.x-(x0+x1)/2,p.z-(z0+z1)/2)*.01;
        if(!best||score<best.score-1e-9)best={score,p};
      }}
    const {fx,fz,rot}=best.p;
    return {kind,x:Math.round(fx*10000)/10000,z:Math.round(fz*10000)/10000,rot};
  }
  // 1.5.0 · the look picked while recording (the form's model-kind) → item.model, in place.
  // Only 家具家电 in a room of the plan gets a look; 全屋 never does. An item that already stands
  // somewhere keeps its spot and only changes look; a new one gets a free spot from findSpot.
  // Returns true when item.model was set or changed (the app then shows it dropping in).
  function lookState(category,room,rects){
    if(category!==FURNITURE)return 'hidden';
    if(room==='全屋')return 'whole';
    return bbox(rects,room)?'ok':'missing';
  }
  function applyLook(item,kind,rects,items=[]){
    if(!kind||!Object.prototype.hasOwnProperty.call(KINDS,kind))return false;
    if(lookState(item.category,item.room,rects)!=='ok')return false;
    const had=sanitizeModel(item.model);
    if(had){if(had.kind===kind)return false;item.model={...had,kind};return true;}
    const others=[];
    for(const o of items){
      if(o===item||o.id===item.id||o.category!==FURNITURE||o.room!==item.room)continue;
      const m=sanitizeModel(o.model);if(!m)continue;
      const p=placeFromModel(m,rects,item.room);if(!p)continue;
      const [w,d]=footprint(KINDS[m.kind],p.rot);others.push({x:p.x,z:p.z,w,d});
    }
    const spot=findSpot(kind,rects,item.room,others);if(!spot)return false;
    item.model=spot;return true;
  }
  // Resize handles: interior walls that can move, and the move itself (pure: returns a new plan).
  function handles(t){
    const {W}=makeRects(t),z1=t.D1,z2=t.D1+t.D2,hs=[];let x=0;
    t.back.forEach(([,w],i)=>{x+=w;if(i<t.back.length-1)hs.push({key:'b'+i,ax:'x',p:[x,t.D1*.25]});});
    hs.push({key:'D1',ax:'z',p:[t.L*.3,z1]});hs.push({key:'L',ax:'x',p:[t.L,z1+Math.min(t.K,t.D2)/2]});
    if(t.K<t.D2-.01)hs.push({key:'K',ax:'z',p:[(t.L+W)/2,z1+t.K]});
    hs.push({key:'BW',ax:'x',p:[t.BW,z2+BALCONY/2]});
    return hs;
  }
  function moveHandle(t,key,px,pz){
    t={...t,back:t.back.map(([n,w])=>[n,w])};const W=t.back.reduce((a,[,w])=>a+w,0);
    if(key[0]==='b'){const i=+key.slice(1);if(!(i>=0&&i<t.back.length-1))return t;let s=0;for(let k=0;k<i;k++)s+=t.back[k][1];const tot=t.back[i][1]+t.back[i+1][1];const nb=clamp(px-s,MINW,tot-MINW);t.back[i][1]=round2(nb);t.back[i+1][1]=round2(tot-nb);}
    else if(key==='D1'){const tot=t.D1+t.D2,full=t.K>=t.D2-.01;const v=clamp(pz,2.6,tot-3);t.D1=round2(v);t.D2=round2(tot-v);t.K=full?t.D2:Math.min(t.K,t.D2-1.2);}
    else if(key==='L')t.L=round2(clamp(px,3,W-1.8));
    else if(key==='K')t.K=round2(clamp(pz-t.D1,1.8,t.D2-1.2));
    else if(key==='BW')t.BW=round2(clamp(px,2,W));
    return t;
  }
  // Static plan for the overview card and the no-WebGL fallback. Colours come from the page's CSS variables.
  function planSvg(t,counts={},opts={}){
    const {rects,W,D}=makeRects(t),s=100/Math.max(W,D),esc=v=>String(v).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
    const fill=n=>n==='客餐厅'?'var(--accent-soft)':n==='阳台'?'var(--amber-bg)':'var(--surface)';
    let out=`<svg viewBox="-2 -2 ${(W*s+4).toFixed(1)} ${(D*s+4).toFixed(1)}" ${opts.label?`role="img" aria-label="${esc(opts.label)}"`:'aria-hidden="true"'}>`;
    out+=rects.map(({name,r})=>`<rect x="${(r[0]*s).toFixed(1)}" y="${(r[1]*s).toFixed(1)}" width="${((r[2]-r[0])*s).toFixed(1)}" height="${((r[3]-r[1])*s).toFixed(1)}" fill="${fill(name)}" stroke="var(--muted)" stroke-width="${opts.text?.6:1.2}"/>`).join('');
    if(opts.text)for(const n of roomNames(rects)){
      const R=rects.filter(r=>r.name===n).sort((a,b)=>(b.r[2]-b.r[0])*(b.r[3]-b.r[1])-(a.r[2]-a.r[0])*(a.r[3]-a.r[1]))[0].r,cx=(R[0]+R[2])/2*s,cy=(R[1]+R[3])/2*s;
      out+=`<text x="${cx.toFixed(1)}" y="${(cy-1).toFixed(1)}" text-anchor="middle" font-size="4.2" font-weight="600" fill="var(--ink)">${esc(n)}</text>`;
      if(counts[n])out+=`<text x="${cx.toFixed(1)}" y="${(cy+4.6).toFixed(1)}" text-anchor="middle" font-size="3.4" fill="var(--muted)">${counts[n]} 件</text>`;
    }
    return out+'</svg>';
  }
  const api={FURNITURE,KINDS,ORDER,TEMPLATES,TEMPLATE_ORDER,MAX_PER_ROOM,WALL_T,GAP,deriveTemplate,planOf,sizesOf,sanitizeHome,sanitizeModel,makeRects,roomNames,area,bbox,nearestRect,footprint,clampPlacement,placeFromModel,fitsRoom,fitRoom,findSpot,lookState,applyLook,handles,moveHandle,planSvg};
  root.ZZHomeModel=Object.freeze(api);
  if(typeof module==='object'&&module.exports)module.exports=api;
})(typeof globalThis!=='undefined'?globalThis:this);
