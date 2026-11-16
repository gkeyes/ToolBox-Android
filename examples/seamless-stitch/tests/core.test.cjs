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
function screenshot(doc,offset,{chrome=true,side=true,dark=false,noise=false}={}){
  const w=doc.w+(side?60:0),h=680,top=chrome?60:0,bottom=chrome?50:0,gray=new Uint8Array(w*h).fill(235);
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
