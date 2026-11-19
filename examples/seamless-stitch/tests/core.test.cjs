'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const E=require('../engine.js');

// Deterministic synthetic article; no user screenshots or external dependencies.
function article(seed=17) {
  const w=320,h=3200,gray=new Uint8Array(w*h).fill(255);
  let rng=seed;const next=()=>{rng=(Math.imul(rng,1664525)+1013904223)>>>0;return rng;};
  for(let y=16;y<h-24;y+=38)for(let x=18;x<w-18;x+=13){
    const code=next();if(code%7===0)continue;
    for(let dy=0;dy<18;dy++)for(let dx=0;dx<8;dx++){
      if(dx===0||dy===0||dy===17||((code>>>((dx+dy)%24))&1))gray[(y+dy)*w+x+dx]=35;
    }
  }
  return {w,h,gray};
}
function screenshot(doc,offset,{chrome=true,side=true,dark=false,noise=false,footer=50}={}){
  const w=doc.w+(side?60:0),h=680,top=chrome?60:0,bottom=chrome?footer:0,gray=new Uint8Array(w*h).fill(235);
  for(let y=top;y<h-bottom;y++)for(let x=0;x<doc.w;x++){
    let v=doc.gray[(offset+y-top)*doc.w+x];if(dark)v=255-v;
    if(noise)v=Math.max(0,Math.min(255,v+(((x*19+y*31+offset)%13)-6)));
    gray[y*w+x]=v;
  }
  if(chrome){for(let x=0;x<doc.w;x++){gray[(top-1)*w+x]=180;gray[(h-bottom)*w+x]=180;}}
  return {w,h,gray};
}
const doc=article();
for(const [name,opts] of [['light',{}],['dark',{dark:true}],['compression noise',{noise:true}],['no chrome',{chrome:false,side:false}]]){
  test(`${name}: recognises exact scrolling distance`,()=>{
    const a=screenshot(doc,0,opts),b=screenshot(doc,327,opts),crop=E.detectCrop([a,b]);
    const m=E.matchPair(a,b,crop);assert.equal(m.shift,327);assert(m.confidence>.5);
  });
}
test('fixed bars are cropped without losing page margins',()=>{
  const c=E.detectCrop([screenshot(doc,0),screenshot(doc,327)]);
  assert.equal(c.left,0);assert.equal(c.right,60);assert(c.top>=59&&c.top<=62);assert(c.bottom>=49&&c.bottom<=53);
});
test('a moving gesture handle and a stronger black border do not move the crop off the chat pane',()=>{
  const images=[0,327,709].map((offset,index)=>{
    const image=screenshot(doc,offset,{footer:140});
    const {w,h,gray}=image;
    // A fixed black band above the header, and a system handle that moves
    // below a long, otherwise fixed footer.
    for(let y=0;y<20;y++)gray.fill(0,y*w,y*w+doc.w);
    for(let y=h-37+index*10;y<h-31+index*10;y++)gray.fill(20,y*w+100,y*w+215);
    return image;
  });
  const crop=E.detectCrop(images);
  assert.equal(crop.right,60);
  assert(crop.top>=59&&crop.top<=62);
  assert(crop.bottom>=139&&crop.bottom<=143);
  assert.equal(E.matchPair(images[0],images[1],crop).shift,327);
  const next=E.matchPair(images[1],images[2],crop);
  assert.equal(next.shift,382);
  assert(next.confidence>.5);
});
test('seam prefers blank space between flat chat bubbles when both images agree',()=>{
  const w=200,h=360,shift=180;
  const rows=new Uint8Array(w*(h+shift)).fill(237);
  for(let y=180;y<273;y++)rows.fill(213,y*w+56,y*w+183);
  for(let y=304;y<374;y++)rows.fill(249,y*w+10,y*w+145);
  const a={w,h,gray:rows.slice(0,h*w)},b={w,h,gray:rows.slice(shift*w,(shift+h)*w)};
  const seam=E.chooseSeam(a,b,{top:0,bottom:0,left:0,right:0},shift);
  assert(seam>=277&&seam<301, `cut at y=${seam}`);
});
test('reverse order is identified',()=>{
  const a=screenshot(doc,0),b=screenshot(doc,327),crop=E.detectCrop([a,b]);const m=E.matchPair(b,a,crop);
  assert.equal(m.shift,-327);assert(m.confidence>.5);
});
test('duplicate images are not silently appended',()=>{
  const a=screenshot(doc,0);assert(E.matchPair(a,a,{top:60,bottom:50,left:0,right:60}).duplicate);
});
test('unrelated images require review',()=>{
  const a=screenshot(doc,0),b=screenshot(article(58),0),crop=E.detectCrop([a,b]);assert.equal(E.matchPair(a,b,crop).confidence,0);
});
test('blank images do not count as a matching paragraph',()=>{
  const a={w:200,h:400,gray:new Uint8Array(200*400).fill(255)};assert.equal(E.matchPair(a,a,{top:0,bottom:0,left:0,right:0}).confidence,0);
});
test('three screenshots make a continuous nonoverlapping output plan',()=>{
  const ims=[0,327,709].map(y=>screenshot(doc,y)),crop=E.detectCrop(ims);
  const joins=[1,2].map(i=>{const m=E.matchPair(ims[i-1],ims[i],crop);assert(m.confidence>.5);return {...m,seam:E.chooseSeam(ims[i-1],ims[i],crop,m.shift)};});
  const p=E.plan(ims,crop,joins);assert.equal(p.height,ims[0].h-crop.top-crop.bottom+709);
  for(let i=1;i<p.segments.length;i++){const a=p.segments[i-1],b=p.segments[i];assert.equal(a.outY+a.h,b.outY);}
});

const crop0={top:0,bottom:0,left:0,right:0};
const sheets=[600,600,600].map(h=>({w:320,h}));
const cuts=[{seam:430,shift:300,overlap:300},{seam:440,shift:300,overlap:300}];
test('dragging upper picture changes only its ending cut',()=>{
  const before=structuredClone(cuts),next=E.dragSeam(sheets,crop0,cuts,0,'upper',20);
  assert.equal(next.seam,410);assert.equal(next.seam-next.shift,130);assert.equal(next.overlap,320);
  assert.deepEqual(cuts,before);assert.equal(E.plan(sheets,crop0,[next,cuts[1]]).height,1180);
});
test('dragging lower picture changes only its starting cut',()=>{
  const next=E.dragSeam(sheets,crop0,cuts,0,'lower',20);
  assert.equal(next.seam,430);assert.equal(next.seam-next.shift,110);assert.equal(next.overlap,280);
  assert.equal(E.plan(sheets,crop0,[next,cuts[1]]).height,1220);
});
test('dragging respects neighbouring seams and keeps each segment nonempty',()=>{
  for(let index=0;index<2;index++)for(const side of ['upper','lower'])for(const delta of [-10000,10000]){
    const joins=cuts.map(j=>({...j}));joins[index]=E.dragSeam(sheets,crop0,joins,index,side,delta);
    const p=E.plan(sheets,crop0,joins);assert(p.segments.every(s=>s.h>=1));assert(joins[index].overlap>=0&&joins[index].overlap<600);
    assert.deepEqual(joins[1-index],cuts[1-index]);
    for(let i=1;i<p.segments.length;i++)assert.equal(p.segments[i].outY,p.segments[i-1].outY+p.segments[i-1].h);
  }
});
test('unequal heights keep overlap within the precision control bounds',()=>{
  const images=[{w:320,h:1000},{w:320,h:600}],joins=[{seam:800,shift:600,overlap:400}];
  for(const side of ['upper','lower'])for(const delta of [-10000,10000]){
    const j=E.dragSeam(images,crop0,joins,0,side,delta);assert(j.overlap>=0&&j.overlap<600);E.plan(images,crop0,[j]);
  }
});
test('drag positions snap to original image pixels and mark manual review',()=>{
  const j=E.dragSeam(sheets,crop0,cuts,0,'upper',2.6);assert.equal(j.seam,427);assert(j.manual&&j.reviewed);assert.equal(j.duplicate,false);
});
test('dragging back restores source cuts without shifting the other picture',()=>{
  const moved=E.dragSeam(sheets,crop0,cuts,0,'lower',30),back=E.dragSeam(sheets,crop0,[moved,cuts[1]],0,'lower',-30);
  assert.equal(back.seam,cuts[0].seam);assert.equal(back.shift,cuts[0].shift);
});
test('invalid drag input fails explicitly',()=>{
  assert.throws(()=>E.dragSeam(sheets,crop0,cuts,0,'upper',NaN));assert.throws(()=>E.dragSeam(sheets,crop0,cuts,0,'sideways',1));
});
