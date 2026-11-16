/* ToolBox native file bridge; patterned after ToolBox zhuzhang/runtime.js (MIT). */
'use strict';
window.StitchRuntime=(()=>{
  let ready;
  const native=()=>window.ToolBox;
  function init(){return ready||(ready=Promise.resolve().then(()=>native()?.ready?.()));}
  async function open(){
    await init();if(!native())return undefined;
    if(!native().files?.open||!native().files?.read)throw new Error('请更新 ToolBox：缺少图片读取接口。');
    const file=await native().files.open(['image/*']);
    if(!file)return null;
    if(file.size>40*1024*1024)throw new Error('单张图片超过 40 MB，请先缩小后再添加。');
    const bytes=await native().files.read(file.token);
    return new File([bytes],file.name,{type:file.mimeType});
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
    return ({PERMISSION_DENIED:'请在 ToolBox 的工具权限中允许打开或保存文件，然后重试。',NOT_DECLARED:'当前工具包权限不完整，请重新导入。',QUOTA_EXCEEDED:'可用内存或空间不足。请减少图片数量，或选择 JPG、较小尺寸重试。',BUSY:'系统文件窗口尚未结束，请稍后重试。',SESSION_ENDED:'工具会话已结束，请重新打开。'})[e?.code]||e?.message||'操作失败，请重试。';
  }
  return {init,open,save,explain,get isNative(){return Boolean(native());}};
})();
