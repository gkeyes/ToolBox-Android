'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
function runtime(files){const ctx={window:{ToolBox:{ready:async()=>({hostVersion:'test'}),files}},File,Blob,Uint8Array,Promise,Error};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../runtime.js'),'utf8'),ctx);return ctx.window.StitchRuntime;}
test('image input opts into native photo picker and consumes token once',async()=>{
  let opens=0,reads=0;
  const r=runtime({open:async types=>{opens++;assert.deepEqual(Array.from(types),['image/*']);return {name:'Screenshot.png',mimeType:'image/png',size:3,token:'one'};},read:async token=>{reads++;assert.equal(token,'one');return new Uint8Array([1,2,3]);}});
  assert.equal(r.canPickMultiple,false);
  const result=await r.pickImages(12);
  assert.equal(result.ordered,true);assert.equal(result.readers.length,1);assert.equal(reads,0);
  const file=await result.readers[0]();assert.equal(file.type,'image/png');assert.equal(file.size,3);assert.equal(opens,1);assert.equal(reads,1);
});
test('batch photo selection keeps tap order and reads tokens one at a time',async()=>{
  const tokens=[];
  const r=runtime({
    openMultiple:async limit=>{
      assert.equal(limit,11);
      return {ordered:true,files:[
        {name:'Screenshot_9.jpg',mimeType:'image/jpeg',size:1,token:'first'},
        {name:'Screenshot_2.png',mimeType:'image/png',size:1,token:'second'},
      ]};
    },
    read:async token=>{tokens.push(token);return new Uint8Array([tokens.length]);},
    open:async()=>assert.fail('must use the batch picker when supported'),
  });
  assert.equal(r.canPickMultiple,true);
  const result=await r.pickImages(11);
  assert.equal(result.ordered,true);
  assert.deepEqual(tokens,[]);
  assert.equal((await result.readers[0]()).name,'Screenshot_9.jpg');
  assert.deepEqual(tokens,['first']);
  assert.equal((await result.readers[1]()).name,'Screenshot_2.png');
  assert.deepEqual(tokens,['first','second']);
});
test('system order without a guarantee is reported and oversized files are not read',async()=>{
  const r=runtime({openMultiple:async()=>({ordered:false,files:[
    {name:'Big.jpg',size:41*1024*1024,token:'skip'},
  ]}),read:async()=>assert.fail('oversized selection must not be read')});
  const result=await r.pickImages(12);
  assert.equal(result.ordered,false);
  await assert.rejects(result.readers[0](),/Big.jpg 超过 40 MB/);
});
test('a photo provider can omit the MIME type and file extension',async()=>{
  const r=runtime({openMultiple:async()=>({ordered:true,files:[
    {name:'image-1',mimeType:'application/octet-stream',size:4,token:'photo'},
  ]}),read:async()=>new Uint8Array([0xff,0xd8,0xff,0xe0])});
  assert.equal((await (await r.pickImages(2)).readers[0]()).type,'image/jpeg');
});
test('cancelled input does not read a token or fall back to another picker',async()=>{
  const read=async()=>assert.fail('must not read on cancellation');
  const batch=runtime({openMultiple:async()=>null,open:async()=>assert.fail('cancel must not open another picker'),read});
  const single=runtime({open:async()=>null,read});
  assert.equal(await batch.pickImages(12),null);
  assert.equal(await single.pickImages(12),null);
});
test('export uses binary native save and preserves cancellation',async()=>{
  let calls=0;const r=runtime({save:async(name,mime,bytes)=>{calls++;assert.equal(name,'long.png');assert.equal(mime,'image/png');assert(bytes instanceof Uint8Array);assert.deepEqual([...bytes],[7,8]);return null;}});
  assert.equal(await r.save('long.png',new Blob([new Uint8Array([7,8])],{type:'image/png'})),false);assert.equal(calls,1);
});
test('release manifest preserves the already imported package identity',()=>{
  const m=JSON.parse(fs.readFileSync(path.join(__dirname,'../manifest.json'),'utf8'));
  assert.equal(m.id,'io.gkeyes.seamlessstitch');
  assert.deepEqual(m.permissions.map(x=>x.name).sort(),['files.open','files.save']);
});
