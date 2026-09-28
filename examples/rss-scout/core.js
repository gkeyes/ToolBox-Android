/* RSS Scout 1.4.0 — independently implemented, no remote code execution. */
(function (root) {
  'use strict';
  const FEED_TYPES = /^(application\/(rss\+xml|atom\+xml|rdf\+xml|feed\+json)|text\/(rss\+xml|atom\+xml))$/i;
  function cleanText(value, max) {
    const text = String(value == null ? '' : value).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '').trim();
    return max ? text.slice(0, max) : text;
  }
  function urlOf(value, base) {
    const text = String(value || '').trim();
    if (!text || /[\u0000-\u0020\u007f]/.test(text)) throw new Error('网址包含空格或控制字符');
    const u = base ? new URL(text, base) : new URL(text);
    if (!['http:', 'https:'].includes(u.protocol)) throw new Error('仅支持 HTTP / HTTPS 网址');
    if (u.username || u.password) throw new Error('请勿在网址中嵌入用户名或密码');
    u.hash = '';
    return u.href;
  }
  function inputUrl(value) {
    let text = String(value || '').trim();
    if (!text) throw new Error('请粘贴网页或订阅源网址');
    const match = text.match(/https?:\/\/[^\s<>"'，。；！？）】》]+/i);
    if (match && match[0] !== text) text = match[0].replace(/[),;]+$/, '');
    else if (match) text = match[0];
    else if (!/^[a-z][a-z0-9+.-]*:/i.test(text)) text = 'https://' + text;
    return urlOf(text);
  }
  function serviceBase(value) {
    const u = new URL(urlOf(value));
    if (u.protocol !== 'https:') throw new Error('ToolBox 原生网络仅支持 HTTPS；请填写 HTTPS 服务地址');
    if (u.search) throw new Error('服务地址不能带查询参数；请填写站点根路径或反向代理子路径');
    return u.href.replace(/\/+$/, '');
  }
  function header(headers, name) {
    const key = Object.keys(headers || {}).find(k => k.toLowerCase() === name.toLowerCase());
    return key ? String(headers[key]) : '';
  }
  function feedHint(value) {
    try {
      const u = new URL(value);
      return /\.(rss|atom)(?:$|\/)|\/(rss|atom|feed|feeds)(?:[./_-]|$)|\/(rss|atom|feed|index)\.(xml|json)$/i.test(u.pathname) || /[?&](feed|format)=(rss2?|atom|jsonfeed)(?:&|$)/i.test(u.search);
    } catch (_) { return false; }
  }
  function directChildren(node, name) {
    return Array.from(node.children || []).filter(n => n.localName.toLowerCase() === name);
  }
  function firstText(node, name) { const child = directChildren(node, name)[0]; return child ? cleanText(child.textContent) : ''; }
  function parseFeed(text) {
    const trimmed = String(text || '').replace(/^\uFEFF/, '').trim();
    if (trimmed.startsWith('{')) {
      let json;
      try { json = JSON.parse(trimmed); } catch (_) { return null; }
      if (!['https://jsonfeed.org/version/1', 'https://jsonfeed.org/version/1.1'].includes(json.version) || typeof json.title !== 'string' || !Array.isArray(json.items)) return null;
      return { type: 'JSON Feed', title: cleanText(json.title) || '未命名 JSON Feed', count: json.items.length,
        preview: json.items.slice(0, 3).map(item => cleanText((item && typeof item === 'object' && (item.title || item.summary || item.content_text)) || '无标题条目', 200)) };
    }
    if (!trimmed.startsWith('<') || /<!DOCTYPE\b|<!ENTITY\b/i.test(trimmed)) return null;
    const doc = new DOMParser().parseFromString(trimmed, 'application/xml');
    if (doc.getElementsByTagName('parsererror').length) return null;
    const el = doc.documentElement;
    if (!el) return null;
    const name = el.localName.toLowerCase();
    let container, entries, type;
    if (name === 'rss') {
      container = directChildren(el, 'channel')[0]; type = 'RSS';
      entries = container ? directChildren(container, 'item') : [];
    } else if (name === 'feed' && el.namespaceURI === 'http://www.w3.org/2005/Atom') {
      container = el; type = 'Atom'; entries = directChildren(el, 'entry');
    } else if (name === 'rdf' && el.namespaceURI === 'http://www.w3.org/1999/02/22-rdf-syntax-ns#') {
      container = directChildren(el, 'channel').find(n => n.namespaceURI === 'http://purl.org/rss/1.0/');
      type = 'RSS 1.0'; entries = directChildren(el, 'item').filter(n => n.namespaceURI === 'http://purl.org/rss/1.0/');
    } else return null;
    if (!container || !directChildren(container, 'title').length) return null;
    return { type, title: firstText(container, 'title') || '未命名订阅源', count: entries.length,
      preview: entries.slice(0, 3).map(item => cleanText(firstText(item, 'title') || firstText(item, 'summary') || '无标题条目', 200)) };
  }
  function splitLinks(value) {
    const result = []; let start = 0, angle = false, quote = false, escaped = false;
    for (let i = 0; i < value.length; i++) {
      const ch = value[i];
      if (escaped) { escaped = false; continue; }
      if (ch === '\\' && quote) { escaped = true; continue; }
      if (ch === '"' && !angle) quote = !quote;
      if (ch === '<' && !quote) angle = true;
      if (ch === '>' && !quote) angle = false;
      if (ch === ',' && !quote && !angle) { result.push(value.slice(start, i)); start = i + 1; }
    }
    result.push(value.slice(start)); return result;
  }
  function extractPage(text, finalUrl, headers) {
    const found = new Map(); let doc = null, base = finalUrl;
    const add = (href, title, source, type) => {
      try {
        const url = urlOf(href, base);
        const old = found.get(url);
        if (old) { if (!old.sources.includes(source)) old.sources.push(source); return; }
        found.set(url, {url, title: cleanText(title, 300) || '待验证订阅源', sources:[source], kind:'native', type:type || ''});
      } catch (_) {}
    };
    for (const link of splitLinks(header(headers, 'link'))) {
      const path = link.match(/^\s*<([^>]+)>/);
      const type = (link.match(/;\s*type\s*=\s*(?:"([^"]+)"|([^;\s]+))/i) || []).slice(1).find(Boolean) || '';
      const rel = (link.match(/;\s*rel\s*=\s*(?:"([^"]+)"|([^;\s]+))/i) || []).slice(1).find(Boolean) || '';
      try {
        if (path && /(?:^|\s)alternate(?:\s|$)/i.test(rel) && (FEED_TYPES.test(type.split(';')[0]) || feedHint(urlOf(path[1], finalUrl)))) add(path[1], 'HTTP Link 声明', 'HTTP Link', type);
      } catch (_) {}
    }
    const htmlLike = /<!doctype\s+html|<html(?:\s|>)|<head(?:\s|>)|<body(?:\s|>)|<link\s|<a\s/i.test(text);
    if (htmlLike) {
      doc = new DOMParser().parseFromString(String(text), 'text/html');
      const baseNode = doc.querySelector('base[href]');
      if (baseNode) { try { base = urlOf(baseNode.getAttribute('href'), finalUrl); } catch (_) {} }
      for (const el of doc.querySelectorAll('link[href]')) {
        const rel = (el.getAttribute('rel') || '').toLowerCase().split(/\s+/);
        const type = (el.getAttribute('type') || '').split(';')[0].trim();
        const href = el.getAttribute('href');
        let hint = false; try { hint = feedHint(urlOf(href, base)); } catch (_) {}
        if (rel.includes('alternate') && (FEED_TYPES.test(type) || hint)) add(href, el.getAttribute('title') || doc.title, '网页声明', type);
      }
      for (const el of doc.querySelectorAll('a[href]')) {
        const href = el.getAttribute('href'); let target;
        try { target = urlOf(href, base); } catch (_) { continue; }
        const label = cleanText(el.textContent, 160);
        if (feedHint(target) || /\b(rss|atom|json\s*feed)\b/i.test(label)) add(href, label, '页面链接');
      }
      const u = new URL(finalUrl);
      if (u.hostname === 'www.youtube.com' || u.hostname === 'youtube.com') {
        const meta = doc.querySelector('meta[itemprop="channelId"]');
        const id = meta && meta.getAttribute('content');
        if (id && /^UC[\w-]{20,30}$/.test(id)) add('https://www.youtube.com/feeds/videos.xml?channel_id=' + encodeURIComponent(id), doc.title || 'YouTube 频道', '频道元数据', 'Atom');
      }
    }
    return { candidates:Array.from(found.values()), title:doc ? cleanText(doc.title, 300) : '', base };
  }
  function nativeTemplates(value) {
    const u = new URL(value); const parts = u.pathname.split('/').filter(Boolean); const out = [];
    const add = (url, title) => out.push({url, title, kind:'native', sources:['平台规则'], type:'Atom'});
    if (u.hostname === 'github.com' && parts.length >= 2 && !/^(orgs|users|topics|search|settings|marketplace|login|features|enterprise|collections|sponsors)$/i.test(parts[0]) && /^[\w.-]+$/.test(parts[0]) && /^[\w.-]+$/.test(parts[1])) {
      const base = 'https://github.com/' + parts.slice(0, 2).join('/');
      add(base + '/releases.atom', parts[0] + '/' + parts[1] + ' · Releases');
      add(base + '/commits.atom', parts[0] + '/' + parts[1] + ' · 提交');
    }
    if (['youtube.com','www.youtube.com','m.youtube.com'].includes(u.hostname) && parts[0] === 'channel' && /^UC[\w-]{20,30}$/.test(parts[1] || '')) add('https://www.youtube.com/feeds/videos.xml?channel_id=' + encodeURIComponent(parts[1]), 'YouTube 频道视频');
    return out;
  }
  function commonPaths(value) {
    const root = new URL('/', value).href;
    return ['feed/','rss.xml','feed.xml','atom.xml','index.xml','feed.json','?feed=rss2'].map(path => ({url:new URL(path, root).href, title:'常见路径待验证', kind:'guess', sources:['常见路径']}));
  }
  function safePathPattern(source) {
    if (typeof source !== 'string' || !source.startsWith('/') || source.startsWith('//')) return false;
    return source.split('/').slice(1).every(s => !s || /^:[A-Za-z_][\w]*\??$/.test(s) || /^[\w.~-]+$/.test(s));
  }
  function matchPath(source, pathname) {
    if (!safePathPattern(source)) return null;
    const template = source.split('/').filter(Boolean);
    const actual = pathname.split('/').filter(Boolean);
    if (actual.length > template.length) return null;
    const params = Object.create(null);
    for (let i = 0; i < template.length; i++) {
      const part = template[i];
      if (part.startsWith(':')) {
        if (actual[i] != null) { try { params[part.slice(1).replace(/\?$/, '')] = decodeURIComponent(actual[i]); } catch (_) { return null; } }
        else if (template.slice(i).some(t => !t.startsWith(':'))) return null;
      } else if (part !== actual[i]) return null;
    }
    return params;
  }
  function fillTarget(target, params) {
    if (typeof target !== 'string' || !target.startsWith('/') || target.startsWith('//') || /[\\\u0000-\u0020#{}()]/.test(target)) return null;
    const parts = target.split('/').slice(1), result = [];
    for (const part of parts) {
      const match = part.match(/^:([A-Za-z_]\w*)(\?)?$/);
      if (match) {
        const value = params[match[1]];
        if (value == null || value === '') { if (match[2]) break; return null; }
        if (value === '.' || value === '..') return null;
        result.push(encodeURIComponent(value));
      } else {
        if (part.includes(':') || part === '.' || part === '..' || part.includes('?') || part.includes('&')) return null;
        result.push(part);
      }
    }
    return '/' + result.join('/');
  }
  function importRadar(payload) {
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new Error('Radar 接口没有返回规则对象');
    const rules = []; let skipped = 0;
    for (const [domain, group] of Object.entries(payload)) {
      if (!/^[a-z0-9.-]+$/i.test(domain) || !domain.includes('.') || !group || typeof group !== 'object' || Array.isArray(group)) continue;
      for (const [sub, entries] of Object.entries(group)) {
        if (!Array.isArray(entries)) continue;
        const host = sub === '.' ? domain : sub + '.' + domain;
        if (!/^[a-z0-9.-]+$/i.test(host)) { skipped += entries.length; continue; }
        for (const rule of entries) {
          if (!rule || typeof rule.target !== 'string' || !rule.target.startsWith('/') || rule.target.startsWith('//') || /[\\\s{}()#]/.test(rule.target)) { skipped++; continue; }
          const sources = Array.isArray(rule.source) ? rule.source : [rule.source];
          const accepted = sources.filter(safePathPattern);
          if (!accepted.length) { skipped++; continue; }
          if (accepted.length < sources.length) skipped++;
          for (const source of accepted) rules.push({host:host.toLowerCase(), source, target:rule.target, title:cleanText(rule.title || group._name || domain, 300)});
        }
      }
    }
    if (!rules.length) throw new Error('没有可安全导入的字符串规则；旧规则保持不变');
    return { rules, skipped, imported:rules.length };
  }

  function safeTargetPattern(target) {
    if (typeof target !== 'string' || !target.startsWith('/') || target.startsWith('//') || /[\\\u0000-\u0020#{}()]/.test(target)) return false;
    return target.split('/').slice(1).every(part => !part || /^:[A-Za-z_]\w*\??$/.test(part) || /^[\w.~-]+$/.test(part));
  }

  function importScoutRules(payload) {
    if (!payload || typeof payload !== 'object' || Array.isArray(payload) || payload.schemaVersion !== 1 || !Array.isArray(payload.rules)) throw new Error('私人规则不是 RSS Scout Rules v1');
    const rules = []; let skipped = 0;
    for (const item of payload.rules) {
      if (!item || item.enabled === false) { skipped++; continue; }
      const targets = Object.create(null);
      if (item.targets && typeof item.targets === 'object' && !Array.isArray(item.targets)) {
        for (const service of ['rsshub', 'worker']) {
          if (safeTargetPattern(item.targets[service])) targets[service] = item.targets[service];
        }
      }
      if (!Object.keys(targets).length && safeTargetPattern(item.target)) {
        targets[item.service === 'private' || item.service === 'worker' ? 'worker' : 'rsshub'] = item.target;
      }
      if (!Object.keys(targets).length) { skipped++; continue; }
      const hosts = Array.isArray(item.hosts) ? item.hosts : [item.host];
      const sources = Array.isArray(item.source) ? item.source : [item.source];
      const deny = item.paramDeny && typeof item.paramDeny === 'object' && !Array.isArray(item.paramDeny) ? item.paramDeny : {};
      const denyPrefix = item.paramDenyPrefix && typeof item.paramDenyPrefix === 'object' && !Array.isArray(item.paramDenyPrefix) ? item.paramDenyPrefix : {};
      for (const rawHost of hosts) {
        const host = String(rawHost || '').toLowerCase();
        if (!/^[a-z0-9.-]+$/i.test(host) || !host.includes('.')) { skipped += sources.length; continue; }
        for (const source of sources) {
          if (!safePathPattern(source)) { skipped++; continue; }
          const safeDeny = Object.create(null);
          for (const [key, values] of Object.entries(deny)) {
            if (!/^[A-Za-z_]\w*$/.test(key) || !Array.isArray(values)) continue;
            safeDeny[key] = values.map(v => cleanText(v, 100).toLowerCase()).filter(Boolean).slice(0, 50);
          }
          const safePrefix = Object.create(null);
          for (const [key, values] of Object.entries(denyPrefix)) {
            if (!/^[A-Za-z_]\w*$/.test(key) || !Array.isArray(values)) continue;
            safePrefix[key] = values.map(v => cleanText(v, 20)).filter(Boolean).slice(0, 20);
          }
          const queryPassthrough = Object.create(null);
          if (item.queryPassthrough && typeof item.queryPassthrough === 'object' && !Array.isArray(item.queryPassthrough)) {
            for (const service of ['rsshub', 'worker']) {
              const values = item.queryPassthrough[service];
              if (!Array.isArray(values)) continue;
              queryPassthrough[service] = values
                .map(value => String(value || '').trim())
                .filter(value => /^[A-Za-z_][A-Za-z0-9_]*$/.test(value))
                .slice(0, 30);
            }
          }
          rules.push({host, source, targets:{...targets}, title:cleanText(item.title || payload.name || host, 300), origin:'private', paramDeny:safeDeny, paramDenyPrefix:safePrefix, queryPassthrough});
        }
      }
    }
    if (!rules.length) throw new Error('私人规则文件没有可安全导入的启用规则');
    return {rules, skipped, imported:rules.length, name:cleanText(payload.name || '私人规则', 200)};
  }

  function radarCandidates(value, rules, hubBase, workerBase) {
    const u = new URL(value), found = new Map();
    for (const rule of rules || []) {
      if (!(rule.host === u.hostname || (rule.host === 'www.' + u.hostname) || ('www.' + rule.host === u.hostname))) continue;
      const params = matchPath(rule.source, u.pathname); if (!params) continue;
      if (rule.paramDeny || rule.paramDenyPrefix) {
        let denied = false;
        for (const [key, values] of Object.entries(rule.paramDeny || {})) {
          const value = String(params[key] || '').toLowerCase();
          if (value && Array.isArray(values) && values.includes(value)) { denied = true; break; }
        }
        if (!denied) for (const [key, prefixes] of Object.entries(rule.paramDenyPrefix || {})) {
          const value = String(params[key] || '');
          if (value && Array.isArray(prefixes) && prefixes.some(prefix => value.startsWith(prefix))) { denied = true; break; }
        }
        if (denied) continue;
      }
      const targets = rule.targets && typeof rule.targets === 'object' ? rule.targets : {rsshub:rule.target};
      for (const service of ['rsshub', 'worker']) {
        const target = targets[service]; if (!target) continue;
        const chosenBase = service === 'worker' ? workerBase : (hubBase || 'https://rsshub.app');
        if (!chosenBase) continue;
        const path = fillTarget(target, params); if (!path) continue;
        let url; try {
          const output = new URL(urlOf(chosenBase.replace(/\/+$/, '') + path));
          const allowedQuery = Array.isArray(rule.queryPassthrough?.[service]) ? rule.queryPassthrough[service] : [];
          for (const key of allowedQuery) {
            if (!u.searchParams.has(key)) continue;
            output.searchParams.set(key, u.searchParams.get(key) ?? '');
          }
          url = output.href;
        } catch (_) { continue; }
        if (new URL(url).origin !== new URL(chosenBase).origin) continue;
        if (!found.has(url)) found.set(url, {
          url,
          title:rule.title,
          kind:service === 'worker' ? 'rssworker' : 'rsshub',
          sources:[rule.origin === 'private' ? ('私人规则 · ' + (service === 'worker' ? 'RSSWorker' : 'RSSHub')) : 'RSSHub 规则'],
          type:''
        });
      }
    }
    return Array.from(found.values());
  }
  function escapeXml(value) { return String(value || '').replace(/[<>&"']/g, ch => ({'<':'&lt;','>':'&gt;','&':'&amp;','"':'&quot;',"'":'&apos;'}[ch])); }
  function opml(feeds) {
    return '<?xml version="1.0" encoding="UTF-8"?>\n<opml version="2.0"><head><title>RSS Scout</title></head><body>\n' + feeds.map(f => '  <outline type="rss" text="' + escapeXml(f.title) + '" title="' + escapeXml(f.title) + '" xmlUrl="' + escapeXml(f.url) + '"/>').join('\n') + '\n</body></opml>\n';
  }
  root.RSSScoutCore = {cleanText,urlOf,inputUrl,serviceBase,header,feedHint,parseFeed,extractPage,nativeTemplates,commonPaths,safePathPattern,matchPath,fillTarget,safeTargetPattern,importRadar,importScoutRules,radarCandidates,opml};
})(typeof window === 'undefined' ? globalThis : window);
