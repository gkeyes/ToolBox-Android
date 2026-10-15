/* Native adapter. No SDK is bundled: ToolBox injects the actual bridge. */
'use strict';
window.ZZRuntime = (() => {
  const native = window.ToolBox;
  const expectedNative = document.documentElement.dataset.runtime === 'toolbox';
  let ready = false;
  let info = null;
  const prefix = 'zhuzhang-tool-v1:';
  const explain = error => {
    const code = error?.code || '';
    const messages = {
      PERMISSION_DENIED: '请在 ToolBox 的小工具权限页开启对应权限，然后返回重试。',
      NOT_DECLARED: '当前安装包缺少所需权限声明，请重新安装应用。',
      SYSTEM_PERMISSION_DENIED: '系统尚未允许此操作，请检查 Android 权限。',
      SESSION_ENDED: '运行环境已结束，请退出并重新打开筑账。',
      INVALID_SESSION: '运行环境已失效，请退出并重新打开筑账。',
      QUOTA_EXCEEDED: '可用空间或内存不足；原账本没有被覆盖，请释放空间后重试。',
      BUSY: '上一个文件或保存操作尚未结束，请稍后重试。'
    };
    return messages[code] || error?.message || '操作失败，请重试。';
  };
  async function init() {
    if (native) {
      info = await native.ready();
      if (typeof native.storage?.get !== 'function' || typeof native.storage?.set !== 'function')
        throw new Error('宿主没有提供所需存储接口，请更新 ToolBox。');
    } else if (expectedNative) {
      throw new Error('ToolBox 接口未注入。请将 .tbx 安装包导入 ToolBox，而不是直接打开包内网页。');
    } else {
      // Browser preview only. Native failures never silently fall back to localStorage.
      const probe = prefix + 'probe';
      localStorage.setItem(probe, '1');
      localStorage.removeItem(probe);
    }
    ready = true;
    return info;
  }
  function check() { if (!ready) throw new Error('存储尚未就绪，请先完成连接。'); }
  async function get(key) {
    check();
    if (native) return await native.storage.get(key);
    const raw = localStorage.getItem(prefix + key);
    return raw === null ? null : JSON.parse(raw);
  }
  async function set(key, value) {
    check();
    if (native) await native.storage.set(key, value);
    else localStorage.setItem(prefix + key, JSON.stringify(value));
  }
  async function remove(key) {
    check();
    if (native) await native.storage.remove(key);
    else localStorage.removeItem(prefix + key);
  }
  async function saveFile(name, mime, text) {
    check();
    if (native) {
      if (typeof native.files?.save !== 'function') throw new Error('宿主未提供文件导出接口。');
      const result = await native.files.save(name, mime.split(';')[0], text);
      return result !== null && result !== undefined;
    }
    const url = URL.createObjectURL(new Blob([text], {type: mime}));
    const anchor = Object.assign(document.createElement('a'), {href:url, download:name});
    document.body.append(anchor); anchor.click(); anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1500);
    return true;
  }
  async function openFile() {
    check();
    if (!native) return undefined;
    if (typeof native.files?.open !== 'function' || typeof native.files?.read !== 'function')
      throw new Error('宿主未提供备份读取接口。');
    const file = await native.files.open(['application/json','text/plain','application/octet-stream']);
    if (!file) return null;
    if (typeof file.token !== 'string') throw new Error('文件选择器返回了无效凭证。');
    const bytes = await native.files.read(file.token); // Each token is consumed once.
    if (!(bytes instanceof Uint8Array)) throw new Error('文件读取接口未返回字节数据。');
    return new TextDecoder('utf-8', {fatal:true}).decode(bytes).replace(/^\uFEFF/, '');
  }
  return Object.freeze({init,get,set,remove,saveFile,openFile,explain,
    get isNative(){return Boolean(native);}, get info(){return info;}});
})();
