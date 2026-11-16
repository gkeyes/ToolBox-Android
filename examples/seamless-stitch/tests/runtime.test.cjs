'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
function runtime(files){const ctx={window:{ToolBox:{ready:async()=>({hostVersion:'test'}),files}},File,Blob,Uint8Array,Promise,Error};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../runtime.js'),'utf8'),ctx);return ctx.window.StitchRuntime;}
test('image input opts into native photo picker and consumes token once',async()=>{
  let opens=0,reads=0;
  const r=runtime({open:async types=>{opens++;assert.deepEqual(Array.from(types),['image/*']);return {name:'Screenshot.png',mimeType:'image/png',size:3,token:'one'};},read:async token=>{reads++;assert.equal(token,'one');return new Uint8Array([1,2,3]);}});
  const file=await r.open();assert.equal(file.type,'image/png');assert.equal(file.size,3);assert.equal(opens,1);assert.equal(reads,1);
});
test('cancelled input does not try to read a token',async()=>{
  const r=runtime({open:async()=>null,read:async()=>assert.fail('must not read on cancellation')});assert.equal(await r.open(),null);
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
