(function (root) {
  'use strict';
  const C = root.RSSScoutCore;
  class Miniflux {
    constructor(network, account) { this.network=network; this.account=account; this.feeds=null; this.feedTime=0; this.inflight=new Map(); }
    ready() { return Boolean(this.account && this.account.base && this.account.token); }
    async api(path, method='GET', body, signal) {
      if (!this.ready()) throw new Error('先在设置中填写 Miniflux 地址与 API Token');
      if (!/^\/v1\/[a-z/]+$/.test(path)) throw new Error('无效 API 路径');
      const base=C.serviceBase(this.account.base), url=base+path;
      const r=await this.network.request(url,{method,body,signal,headers:{'Accept':'application/json','Content-Type':'application/json','X-Auth-Token':this.account.token}});
      const actual=new URL(r.url), expected=new URL(url);
      if (actual.origin!==expected.origin || actual.pathname!==expected.pathname) throw new Error('Miniflux API 被重定向到其他地址；请核对反向代理与服务根路径');
      let data; try { data=JSON.parse(r.text); } catch (_) {}
      if (r.status<200 || r.status>=300) {
        const hints={401:'API Token 无效或已撤销',403:'服务器拒绝访问，请核对 Token 权限、反向代理或访问策略',404:'接口不存在，请检查服务地址与子路径',429:'请求过于频繁，请稍后重试'};
        const detail=C.cleanText(data && data.error_message,350).split(this.account.token).join('[已隐藏 Token]');
        const error=new Error('Miniflux HTTP '+r.status+'：'+(detail||hints[r.status]||'服务器请求失败')); error.status=r.status; throw error;
      }
      if(data===undefined) throw new Error('Miniflux 未返回 JSON（可能是登录页或服务地址错误），HTTP '+r.status);
      return {status:r.status,data};
    }
    async connect(signal) {
      const me=(await this.api('/v1/me','GET',undefined,signal)).data;
      if (!me || !Number.isSafeInteger(me.id) || me.id<1) throw new Error('用户接口返回格式不正确');
      return {username:C.cleanText(me.username,100),categories:await this.categories(signal)};
    }
    async categories(signal) {
      const cats=(await this.api('/v1/categories','GET',undefined,signal)).data;
      if (!Array.isArray(cats)) throw new Error('分类接口返回格式不正确');
      return cats.filter(c=>c&&Number.isSafeInteger(c.id)&&c.id>0).map(c=>({id:c.id,title:C.cleanText(c.title,200)||('分类 '+c.id)}));
    }
    async listFeeds(force=false) {
      if (!force && this.feeds && Date.now()-this.feedTime<60000) return this.feeds;
      const data=(await this.api('/v1/feeds')).data;
      if (!Array.isArray(data)) throw new Error('订阅列表格式不正确，已停止提交以避免重复订阅');
      this.feeds=data.filter(f=>f&&Number.isSafeInteger(f.id)&&f.id>0&&typeof f.feed_url==='string').map(f=>({id:f.id,feed_url:f.feed_url,title:C.cleanText(f.title,300)}));
      this.feedTime=Date.now(); return this.feeds;
    }
    match(feeds, candidate) {
      const aliases=new Set([candidate.url,...(candidate.aliases||[])].map(s=>{try{return C.urlOf(s);}catch(_){return s;}}));
      return feeds.find(f=>{try{return aliases.has(C.urlOf(f.feed_url));}catch(_){return false;}});
    }
    async discover(url, signal) {
      const data=(await this.api('/v1/discover','POST',{url:C.urlOf(url)},signal)).data;
      if (!Array.isArray(data)) throw new Error('发现接口返回格式不正确');
      const result=[];
      for (const f of data) { try { result.push({url:C.urlOf(f.url),title:C.cleanText(f.title,300)||'Miniflux 发现的源',type:C.cleanText(f.type,30),kind:'native',sources:['Miniflux 发现']}); } catch (_) {} }
      return result;
    }
    async reconcile(candidate) { return this.match(await this.listFeeds(true),candidate)||null; }
    subscribe(candidate) {
      const key=C.urlOf(candidate.url);
      if (this.inflight.has(key)) return this.inflight.get(key);
      const promise=this.create(candidate).finally(()=>this.inflight.delete(key)); this.inflight.set(key,promise); return promise;
    }
    async create(candidate) {
      const existing=this.match(await this.listFeeds(),candidate);
      if (existing) return {id:existing.id,existing:true};
      const body={feed_url:C.urlOf(candidate.url)};
      const category=this.account.category;
      if (Number.isSafeInteger(category)&&category>0) body.category_id=category;
      try {
        const result=await this.api('/v1/feeds','POST',body);
        if (result.status!==201 || !result.data || !Number.isSafeInteger(result.data.feed_id) || result.data.feed_id<1) throw new Error('创建接口没有返回有效 feed_id，不能确认订阅成功');
        const id=result.data.feed_id;
        this.feeds=(this.feeds||[]).concat({id,feed_url:body.feed_url,title:candidate.title}); this.feedTime=Date.now();
        return {id,existing:false};
      } catch (error) {
        try { const found=await this.reconcile(candidate); if(found) return {id:found.id,existing:true,reconciled:true}; } catch (_) {}
        if (!error.status || error.status>=500) error.uncertain=true;
        throw error;
      }
    }
  }
  root.RSSScoutMiniflux=Miniflux;
})(window);
