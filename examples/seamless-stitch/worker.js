'use strict';
importScripts('engine.js');
self.onmessage=event=>{
  const {id,images,crop:autoCrop}=event.data;
  try{
    const crop=autoCrop||StitchEngine.detectCrop(images),joins=[];
    for(let i=1;i<images.length;i++){
      self.postMessage({id,progress:i,total:images.length-1});
      const match=StitchEngine.matchPair(images[i-1],images[i],crop);
      const h=StitchEngine.rectFor(images[i-1],crop).h;
      const shift=match.confidence>.5&&!match.duplicate&&match.shift>0?match.shift:h;
      joins.push({...match,detectedShift:match.shift,shift,overlap:h-shift,
        seam:StitchEngine.chooseSeam(images[i-1],images[i],crop,shift),reviewed:match.confidence>.5&&match.shift>0&&!match.duplicate});
    }
    self.postMessage({id,crop,joins,done:true});
  }catch(error){self.postMessage({id,error:error.message});}
};
