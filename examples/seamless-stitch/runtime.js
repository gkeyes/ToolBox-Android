/* ToolBox native file bridge; patterned after ToolBox zhuzhang/runtime.js (MIT). */
'use strict';
window.StitchRuntime=(()=>{
  let ready;
  const native=()=>window.ToolBox;
  function init(){return ready||(ready=Promise.resolve().then(()=>native()?.ready?.()));}
  function imageType(bytes,fallback){
    if(fallback&&fallback!=='application/octet-stream')return fallback;
    if(bytes[0]===0xff&&bytes[1]===0xd8&&bytes[2]===0xff)return 'image/jpeg';
    if(bytes[0]===0x89&&bytes[1]===0x50&&bytes[2]===0x4e&&bytes[3]===0x47)return 'image/png';
    if(bytes.length>=12&&String.fromCharCode(...bytes.subarray(0,4))==='RIFF'&&
      String.fromCharCode(...bytes.subarray(8,12))==='WEBP')return 'image/webp';
    return fallback;
  }
  function readImage(files,file){
    return async ()=>{
      if(file.size>40*1024*1024)throw new Error(`${file.name} 超过 40 MB，请缩小后再添加。`);
      const bytes=await files.read(file.token);
      return new File([bytes],file.name,{type:imageType(bytes,file.mimeType)});
    };
  }
  async function pickImages(limit){
    await init();if(!native())return undefined;
    const files=native().files;
    if(!files?.read)throw new Error('请更新 ToolBox：缺少图片读取接口。');
    if(typeof files.openMultiple==='function'){
      const selection=await files.openMultiple(limit);
      return selection?{ordered:selection.ordered===true,readers:selection.files.map(file=>readImage(files,file))}:null;
    }
    if(!files.open)throw new Error('请更新 ToolBox：缺少图片读取接口。');
    const file=await files.open(['image/*']);
    return file?{ordered:true,readers:[readImage(files,file)]}:null;
  }
  async function save(name,blob){
    await init();
    if(native()){
      if(!native().files?.save)throw new Error('请更新 ToolBox：缺少图片保存接口。');
      const result=await native().files.save(name,blob.type,new Uint8Array(await blob.arrayBuffer()));
      return Boolean(result);
    }
    const url=URL.createObjectURL(blob),a=Object.assign(document.createElement('a'),{href:url,download:name});
    document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);return true;
  }
  function explain(e){
    return ({PERMISSION_DENIED:'请在 ToolBox 的工具权限中允许打开或保存文件，然后重试。',NOT_DECLARED:'当前工具包权限不完整，请重新导入。',QUOTA_EXCEEDED:'图片数量、大小或可用空间达到上限。请减少图片数量，或导出时选择 JPG、较小尺寸。',BUSY:'系统文件窗口尚未结束，请稍后重试。',SESSION_ENDED:'工具会话已结束，请重新打开。'})[e?.code]||e?.message||'操作失败，请重试。';
  }
  return {init,pickImages,save,explain,get isNative(){return Boolean(native());},
    get canPickMultiple(){return typeof native()?.files?.openMultiple==='function';}};
})();
