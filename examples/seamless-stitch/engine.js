/* Screenshot registration for vertical scrolling. MIT.
 * Fixed-chrome differencing is adapted from the screenStitch approach by jaflo:
 * https://github.com/jaflo/screenStitch/ (see THIRD_PARTY_NOTICES.md).
 * Tolerant projections, spatial verification and seam selection extend that
 * approach for JPEG screenshots. No OCR, perspective warp or image synthesis.
 */
(function (root) {
  'use strict';
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  function runs(values, threshold, minLength) {
    const found = []; let start = -1;
    for (let i = 0; i <= values.length; i++) {
      if (i < values.length && values[i] > threshold) { if (start < 0) start = i; }
      else if (start >= 0) { if (i - start >= minLength) found.push([start, i]); start = -1; }
    }
    return found;
  }
  function rectFor(image, crop) {
    return {x: crop.left, y: crop.top, w: image.w - crop.left - crop.right, h: image.h - crop.top - crop.bottom};
  }
  function detectCrop(images) {
    const w = images[0].w, h = images[0].h;
    const empty = {left:0,right:0,top:0,bottom:0};
    const same = images.filter(a => a.w === w && a.h === h);
    if (same.length < 2) return {...empty, note:'截图高度不同，请检查或手动裁切固定栏。'};
    const row = new Float32Array(h), col = new Float32Array(w), changedPairs = [];
    for (let k = 1; k < same.length; k++) {
      const a=same[k-1].gray, b=same[k].gray;
      let changed=0;
      for (let y=0; y<h; y+=2) for (let x=0; x<w; x+=2) {
        if (Math.abs(a[y*w+x]-b[y*w+x]) > 18) changed++;
      }
      if (changed < w*h*0.001) continue;
      changedPairs.push([a,b]);
      for (let y=0; y<h; y++) for (let x=0; x<w; x+=2) {
        if (Math.abs(a[y*w+x]-b[y*w+x]) > 18) row[y]+=2/w;
      }
    }
    const pairs=changedPairs.length;
    if (!pairs) return {...empty, note:'截图几乎相同，未自动裁切。'};
    for (let y=0;y<h;y++) row[y]/=pairs;
    const bands=runs(row,0.035,5), blocks=[];
    for(const band of bands){
      const last=blocks[blocks.length-1];
      if(last && band[0]-last[1]<h*.08) last[1]=band[1];
      else blocks.push(band);
    }
    // A gesture handle or clock may change independently of the scrolled page.
    // Ignore only thin, isolated changes at the very edge of the screen.
    const first=blocks[0];
    if(blocks.length>1 && first[1]<=h*.06 && first[1]-first[0]<=h*.04)blocks.shift();
    const last=blocks[blocks.length-1];
    if(blocks.length>1 && last[0]>=h*.94 && last[1]-last[0]<=h*.04)blocks.pop();
    if(!blocks.length) return {...empty,note:'变化区域不足，保留原图边缘。'};
    const y0=blocks[0][0],y1=blocks[blocks.length-1][1];
    for(const [a,b] of changedPairs)for(let y=y0;y<y1;y++)for(let x=0;x<w;x+=2){
      if(Math.abs(a[y*w+x]-b[y*w+x])>18)col[x]+=1/(y1-y0);
    }
    for (let x=0;x<w;x+=2) { col[x]/=pairs; if(x+1<w)col[x+1]=col[x]; }
    const xr=runs(col,0.035,8);
    if (!xr.length) return {...empty,note:'变化区域不足，保留原图边缘。'};
    const x0=xr[0][0], x1=xr[xr.length-1][1];
    // A horizontal divider must be almost uniform, and visible in either image.
    // Unlike the original changed-pixel bounding box, this preserves page margins.
    function lineStats(im,y) {
      let n=0,sum=0,sq=0;
      for(let x=x0;x<x1;x+=3){const v=im.gray[y*w+x];sum+=v;sq+=v*v;n++;}
      return [sum/n,Math.sqrt(Math.max(0,sq/n-(sum/n)**2))];
    }
    function divider(from,to,top) {
      let best=null, score=0;
      for(let y=Math.max(2,from);y<Math.min(h-2,to);y++) {
        for(const im of same.slice(0,3)) {
          const [m,s]=lineStats(im,y),[p,ps]=lineStats(im,y-2),[n,ns]=lineStats(im,y+2);
          const edge=Math.max(Math.abs(m-p),Math.abs(m-n));
          if(s<2.5 && edge>3 && ps<3 && ns<3) {
            // Cap the score of strong lines, so a distant black screen
            // border cannot beat the nearby divider of the scrolling pane.
            const rank=Math.min(edge,24)/(1+Math.abs(y-(top?y0:y1))/18)**2;
            if(rank>score){score=rank; best=top?y+1:y;}
          }
        }
      }
      return best;
    }
    let top=0,bottom=0,left=0,right=0;
    if(y0>h*.035 && y0<h*.38) {
      top=divider(Math.max(0,y0-Math.min(180,h*.16)),y0,true) ?? Math.max(0,y0-Math.round(h*.012));
    }
    if(y1<h*.965 && y1>h*.5) {
      const boundary=divider(y1-5,Math.min(h,y1+Math.min(36,h*.04)),false);
      bottom=h-(boundary ?? Math.min(h,y1+Math.round(h*.012)));
    }
    // A stable vertical panel is removed only when there is a long straight
    // colour boundary outside the moving content, not merely an empty margin.
    function verticalBoundary(from,to) {
      let best=null, bestScore=4;
      for(let x=Math.max(2,from);x<Math.min(w-2,to);x++) {
        let score=0,n=0,positive=0,negative=0;
        for(let y=Math.max(top,y0);y<Math.min(h-bottom,y1);y+=4) {
          const d=same[0].gray[y*w+x]-same[0].gray[y*w+x-1];
          score+=Math.abs(d); if(d>3)positive++; if(d< -3)negative++; n++;
        }
        score/=n;
        if(score>bestScore && Math.max(positive,negative)/n>.65) {best=x;bestScore=score;}
      }
      return best;
    }
    if(x0>w*.07)left=verticalBoundary(0,x0) ?? 0;
    if(w-x1>w*.07) {const b=verticalBoundary(x1,w); if(b!==null)right=w-b;}
    if(w-left-right<w*.45 || h-top-bottom<h*.35)return {...empty,note:'自动裁切范围异常，已保留原图。'};
    return {left,right,top,bottom,note:'已根据多张截图的变化区域识别固定栏，请核对裁切预览。'};
  }
  function isDark(im,r) {
    const samples=[];
    for(let y=0;y<r.h;y+=Math.max(1,Math.floor(r.h/24)))for(let x=0;x<r.w;x+=Math.max(1,Math.floor(r.w/24)))samples.push(im.gray[(r.y+y)*im.w+r.x+x]);
    samples.sort((a,b)=>a-b);return samples[Math.floor(samples.length/2)]<128;
  }
  function profile(im,r) {
    const bins=24, raw=new Float32Array(r.h*bins), smooth=new Float32Array(raw.length);
    const dark=isDark(im,r);
    const texture=new Float32Array(r.h);
    for(let y=0;y<r.h;y++) {
      let sum=0,sq=0;
      for(let b=0;b<bins;b++) {
        let v=0,n=0;
        const start=Math.floor(r.x+b*r.w/bins),end=Math.floor(r.x+(b+1)*r.w/bins);
        for(let x=start;x<end;x+=Math.max(1,Math.floor(r.w/300))) {v+=im.gray[(r.y+y)*im.w+x];n++;}
        v/=n;if(dark)v=255-v; raw[y*bins+b]=v;sum+=v;sq+=v*v;
      }
      texture[y]=Math.sqrt(Math.max(0,sq/bins-(sum/bins)**2));
    }
    for(let y=0;y<r.h;y++)for(let b=0;b<bins;b++){
      let sum=0,n=0;for(let j=Math.max(0,y-2);j<=Math.min(r.h-1,y+2);j++){sum+=raw[j*bins+b];n++;}
      smooth[y*bins+b]=sum/n;
    }
    return {raw,smooth,texture,h:r.h,bins,dark};
  }
  function scoreProfiles(a,b,shift,smooth,step) {
    const begin=Math.max(0,shift),end=Math.min(a.h,shift+b.h);
    let error=0,power=0,informative=0,n=0;
    const aa=smooth?a.smooth:a.raw,bb=smooth?b.smooth:b.raw;
    for(let y=begin+2;y<end-2;y+=step){
      const by=y-shift;
      if(a.texture[y]+b.texture[by]<5)continue;
      informative++;
      for(let x=0;x<a.bins;x++){
        const av=aa[y*a.bins+x],bv=bb[by*b.bins+x],d=av-bv;
        error+=d*d;power+=(255-av)**2+(255-bv)**2;n++;
      }
    }
    if(informative<Math.max(8,24/step) || power<10000)return Infinity;
    return error/Math.max(power*.5,n*100);
  }
  function pixelScore(a,b,ra,rb,shift,dark) {
    const begin=Math.max(0,shift),end=Math.min(ra.h,shift+rb.h);
    let sum=0,power=0,n=0,rows=0;
    const dx=Math.max(2,Math.floor(ra.w/180));
    for(let y=begin+1;y<end-1;y+=2){
      let rowPower=0,rowErr=0;
      for(let x=3;x<ra.w-3;x+=dx){
        let av=a.gray[(ra.y+y)*a.w+ra.x+x];
        let bv=b.gray[(rb.y+y-shift)*b.w+rb.x+x];
        let ax=a.gray[(ra.y+y)*a.w+ra.x+x-1];
        let bx=b.gray[(rb.y+y-shift)*b.w+rb.x+x-1];
        if(dark){av=255-av;bv=255-bv;ax=255-ax;bx=255-bx;}
        // Edge verification avoids mistaking repetitive text lines/blank rows.
        rowErr+=(av-bv)**2+.4*((av-ax)-(bv-bx))**2;
        rowPower+=(255-av)**2+(255-bv)**2;n++;
      }
      if(rowPower>2000)rows++;
      sum+=rowErr;power+=rowPower;
    }
    return {error:sum/Math.max(power*.5,n*100),rows};
  }
  function matchPair(a,b,crop) {
    const ra=rectFor(a,crop),rb=rectFor(b,crop),pa=profile(a,ra),pb=profile(b,rb);
    if(pa.dark!==pb.dark)return {shift:ra.h,overlap:0,confidence:0,reason:'截图的明暗主题不同，请手动检查。'};
    const minOverlap=Math.max(48,Math.min(120,Math.round(Math.min(ra.h,rb.h)*.1)));
    const candidates=[];
    for(let d=-(rb.h-minOverlap);d<=ra.h-minOverlap;d+=4){
      if(Math.abs(d)<8)continue;
      candidates.push({shift:d,error:scoreProfiles(pa,pb,d,true,4)});
    }
    candidates.sort((x,y)=>x.error-y.error);
    const peaks=[];
    for(const c of candidates){if(peaks.every(p=>Math.abs(p.shift-c.shift)>12))peaks.push(c);if(peaks.length===8)break;}
    const refined=[];
    for(const p of peaks){
      let best={shift:p.shift,error:Infinity};
      for(let d=p.shift-4;d<=p.shift+4;d++){
        if(Math.min(ra.h,d+rb.h)-Math.max(0,d)<minOverlap)continue;
        const error=scoreProfiles(pa,pb,d,false,2);
        if(error<best.error)best={shift:d,error};
      }
      refined.push(best);
    }
    refined.sort((x,y)=>x.error-y.error);
    const zero=scoreProfiles(pa,pb,0,false,3);
    if(zero<.012 && ra.h===rb.h) {
      const p=pixelScore(a,b,ra,rb,0,pa.dark);
      if(p.error<.02 && p.rows>16)return {duplicate:true,shift:0,overlap:ra.h,confidence:1,reason:'重复截图'};
    }
    if(!refined.length || !Number.isFinite(refined[0].error))return {shift:ra.h,overlap:0,confidence:0,reason:'没有足够的共同内容'};
    const best=refined[0],second=refined.find(x=>Math.abs(x.shift-best.shift)>12);
    const margin=second?second.error-best.error:1;
    // Row averages find the right overlap, but soft JPEG edges can leave the
    // adjacent pixel as a better fit. Refine locally against actual pixels.
    let shift=best.shift,verified=pixelScore(a,b,ra,rb,shift,pa.dark);
    for(let d=best.shift-1;d<=best.shift+1;d++){
      if(d===best.shift||Math.abs(d)<8||Math.min(ra.h,d+rb.h)-Math.max(0,d)<minOverlap)continue;
      const score=pixelScore(a,b,ra,rb,d,pa.dark);
      if(score.error<verified.error){shift=d;verified=score;}
    }
    const good=best.error<.12 && verified.error<.22 && verified.rows>=14 && margin>.025;
    const confidence=good?clamp(1-verified.error*1.7,0,1):0;
    return {shift,overlap:ra.h-shift,confidence,error:verified.error,margin,
      reason:good?(shift<0?'截图顺序可能相反':'已找到重复内容'):'接缝不确定，请调整重叠高度或直接拼接'};
  }
  function chooseSeam(a,b,crop,shift) {
    const ra=rectFor(a,crop),rb=rectFor(b,crop);
    const begin=Math.max(0,shift),end=Math.min(ra.h,shift+rb.h);
    if(end-begin<8)return ra.h;
    const lo=begin+4,hi=end-4;
    let best=lo,bestEnergy=Infinity;
    const step=Math.max(1,Math.floor(ra.w/180));
    for(let y=lo;y<=hi;y++){
      let energy=0,n=0,sumA=0,sumB=0,squareA=0,squareB=0;
      for(let dy=-3;dy<=2;dy++)for(let x=2;x<ra.w-2;x+=step){
        const ai=(ra.y+y+dy)*a.w+ra.x+x,bi=(rb.y+y+dy-shift)*b.w+rb.x+x;
        const av=a.gray[ai],bv=b.gray[bi];
        energy+=Math.abs(av-bv) + Math.abs(av-a.gray[ai-1]) + Math.abs(bv-b.gray[bi-1]);
        energy+=Math.abs(av-a.gray[ai-a.w])+Math.abs(bv-b.gray[bi-b.w]);n++;
        sumA+=av;sumB+=bv;squareA+=av*av;squareB+=bv*bv;
      }
      // A flat strip through a rounded chat bubble can have few edges but
      // still has two background levels. Prefer a clear gap across the page.
      const spreadA=Math.sqrt(Math.max(0,squareA/n-(sumA/n)**2));
      const spreadB=Math.sqrt(Math.max(0,squareB/n-(sumB/n)**2));
      // Prefer retaining less of the upper image near its clipped bottom
      // edge, when both options are otherwise similarly clean.
      energy=energy/n+(spreadA+spreadB)*.3+(y-begin)/(end-begin)*4;
      if(energy<bestEnergy){bestEnergy=energy;best=y;}
    }
    return best;
  }
  function plan(images,crop,joins) {
    let origin=0;
    const segments=[],seams=[];
    for(let i=0;i<images.length;i++){
      const r=rectFor(images[i],crop);
      const start=i?joins[i-1].seam-joins[i-1].shift:0;
      const end=i<joins.length?joins[i].seam:r.h;
      if(start<0 || end>r.h || end<=start)throw new Error('接缝范围互相覆盖，请调整重叠高度，或移除重复截图。');
      segments.push({index:i,x:r.x,y:r.y+start,w:r.w,h:end-start,outY:origin});
      origin+=end-start;if(i<joins.length)seams.push(origin);
    }
    return {width:rectFor(images[0],crop).w,height:origin,segments,seams};
  }
  // A positive delta moves the selected picture down under a fixed seam.
  // Keep the opposite picture's cut and every neighbouring join unchanged.
  function dragSeam(images,crop,joins,index,side,delta) {
    if(!Number.isFinite(delta)||!joins[index]||!['upper','lower'].includes(side))throw new Error('无效的接缝调整。');
    const join=joins[index],a=rectFor(images[index],crop),b=rectFor(images[index+1],crop);
    const previousStart=index?joins[index-1].seam-joins[index-1].shift:0;
    const nextEnd=index+1<joins.length?joins[index+1].seam:b.h;
    const minShift=a.h-Math.min(a.h,b.h)+1;
    let upper=join.seam,lower=join.seam-join.shift;
    if(side==='upper')upper=clamp(upper-Math.round(delta),Math.max(previousStart+1,lower+minShift),Math.min(a.h,lower+a.h));
    else lower=clamp(lower-Math.round(delta),Math.max(0,upper-a.h),Math.min(nextEnd-1,upper-minShift));
    const shift=upper-lower;
    return {...join,seam:upper,shift,overlap:a.h-shift,reviewed:true,manual:true,duplicate:false};
  }
  root.StitchEngine={detectCrop,rectFor,matchPair,chooseSeam,plan,dragSeam};
  if(typeof module!=='undefined')module.exports=root.StitchEngine;
})(typeof self!=='undefined'?self:globalThis);
