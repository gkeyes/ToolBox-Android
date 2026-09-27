/* Rule data is JSON only. Base Radar rules and private discovery rules are independent. */
(function(root){
  'use strict';
  const C=root.RSSScoutCore;
  const PUBLIC_SOURCES=Object.freeze([
    {name:'GitHub 官方构建',url:'https://raw.githubusercontent.com/DIYgod/RSSHub/gh-pages/build/radar-rules.json'},
    {name:'jsDelivr 官方构建镜像',url:'https://cdn.jsdelivr.net/gh/DIYgod/RSSHub@gh-pages/build/radar-rules.json'}
  ]);
  function instanceURL(base,path,key){const url=new URL(C.serviceBase(base)+path);if(key)url.searchParams.set('key',key);return url.href;}
  function publicURL(value){
    const url=new URL(C.urlOf(value));
    if(url.protocol!=='https:')throw new Error('规则地址必须使用 HTTPS');
    if([...url.searchParams.keys()].some(k=>/^(?:key|code|token|api_key|access_token|password|auth|secret|signature|sig)$/i.test(k)))throw new Error('远程规则必须使用不含鉴权参数的公开 HTTPS JSON 地址');
    return url.href;
  }
  function sourceKey(settings){
    const base=settings.ruleMode==='instance'?'instance:'+settings.hubBase:settings.ruleMode==='custom'?'custom:'+settings.ruleUrl:'official';
    const extra=settings.usePrivateRules?'|private:'+settings.privateRuleUrl:'|private:off';
    return 'dual-targets-v1|'+base+extra;
  }
  function basePlan(settings,accessKey){
    const instance={name:'当前 RSSHub 实例',url:instanceURL(settings.hubBase,'/api/radar/rules',accessKey),instance:true,kind:'radar'};
    if(settings.ruleMode==='instance')return [instance];
    if(settings.ruleMode==='custom')return [{name:'自定义 Radar JSON',url:publicURL(settings.ruleUrl),kind:'radar'}];
    return [...PUBLIC_SOURCES.map(x=>({...x,kind:'radar'})),instance];
  }
  function privateSource(settings){
    if(!settings.usePrivateRules)return null;
    if(!String(settings.privateRuleUrl||'').trim())throw new Error('已启用私人规则，但没有填写订阅地址');
    return {name:'私人规则订阅',url:publicURL(settings.privateRuleUrl),kind:'private'};
  }
  function describe(status,text,instance){
    if(status===401)return instance?'需要认证，请核对实例访问密钥':'规则来源要求认证';
    if(status===403)return /cloudflare|challenge|captcha|just a moment/i.test(text)?'站点防护或人机验证拒绝了请求':(instance?'实例拒绝访问，请核对访问密钥或反向代理策略':'规则来源拒绝请求');
    if(status===404)return instance?'实例未提供规则接口，或服务子路径不正确':'规则地址不存在';
    if(status===429)return '请求受限，请稍后重试';
    return '服务未成功返回规则';
  }
  function cacheValid(cache,settings){return !!(cache&&Array.isArray(cache.rules)&&cache.rules.length&&cache.sourceKey===sourceKey(settings));}
  async function fetchSource(network,source,signal,accessKey,attempts,onProgress){
    if(signal?.aborted)throw new DOMException('已停止','AbortError');
    onProgress?.(source.name,attempts.slice());
    try{
      const response=await network.request(source.url,{signal,headers:{Accept:'application/json, text/plain;q=0.9'}});
      if(response.status!==200){const e=new Error('HTTP '+response.status+' · '+describe(response.status,response.text,source.instance));e.status=response.status;throw e;}
      let payload;try{payload=JSON.parse(response.text.replace(/^\uFEFF/,''));}catch(_){throw new Error('返回的不是 JSON 规则');}
      const parsed=source.kind==='private'?C.importScoutRules(payload):C.importRadar(payload);
      return {parsed,source};
    }catch(error){
      if(signal?.aborted||error.name==='AbortError'||error.code==='CANCELLED')throw error;
      let message=C.cleanText(error.message||'请求失败',400);
      if(accessKey)message=message.split(accessKey).join('[密钥已隐藏]').split(encodeURIComponent(accessKey)).join('[密钥已隐藏]');
      attempts.push({source:source.name,status:error.status||null,message});
      throw error;
    }
  }
  async function sync(network,settings,accessKey,signal,onProgress){
    const attempts=[];let base=null;
    for(const source of basePlan(settings,accessKey)){
      try{base=await fetchSource(network,source,signal,accessKey,attempts,onProgress);break;}
      catch(error){if(error.code==='PERMISSION_DENIED'||error.code==='NOT_DECLARED')break;}
    }
    let extra=null,privateError=null;
    const psrc=privateSource(settings);
    if(psrc){try{extra=await fetchSource(network,psrc,signal,accessKey,attempts,onProgress);}catch(error){privateError=error;}}
    if(!base&&!extra){const error=new Error('规则更新未完成；'+attempts.map(x=>x.source+'：'+x.message).join('；'));error.attempts=attempts;throw error;}
    const rules=[...(extra?.parsed.rules||[]),...(base?.parsed.rules||[])];
    const skipped=(extra?.parsed.skipped||0)+(base?.parsed.skipped||0);
    const sources=[extra?.source.name,base?.source.name].filter(Boolean);
    return {sourceKey:sourceKey(settings),source:sources.join(' + '),sourceURL:[extra?.source.url,base?.source.url].filter(Boolean).map(u=>{const x=new URL(u);return x.origin+x.pathname;}),rules,skipped,at:Date.now(),attempts,privateWarning:privateError?C.cleanText(privateError.message||'私人规则同步失败',300):''};
  }
  root.RSSScoutRules={PUBLIC_SOURCES,instanceURL,publicURL,sourceKey,cacheValid,basePlan,privateSource,sync};
})(window);
