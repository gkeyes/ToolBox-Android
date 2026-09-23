import {host, permissionMessage} from './bridge';

// Ordinary data and credentials never share a storage namespace or backup.
const pending = new Map<string,{key:string; value:string|null; secure:boolean}>();
const memoryKeys = new Map<string,string>();
let writing: Promise<void> | null = null;
let fault: Error | null = null;
function notify(error: Error | null) {
  fault = error;
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('socialcoach-storage',{detail:error?.message || null}));
}
export const storageFailure = () => fault;
async function drain() {
  while (pending.size) {
    const [id, item] = pending.entries().next().value!;
    pending.delete(id);
    try {
      const api = host();
      if (api) {
        const target = item.secure ? api.storage.secure : api.storage;
        if (item.value === null) await target.remove(item.key); else await target.set(item.key,item.value);
      } else if (item.secure) {
        // Browser preview: secrets are deliberately memory-only.
        if (item.value === null) memoryKeys.delete(item.key); else memoryKeys.set(item.key,item.value);
      } else if (typeof localStorage !== 'undefined') {
        if (item.value === null) localStorage.removeItem(item.key); else localStorage.setItem(item.key,item.value);
      }
      notify(null);
    } catch (e) {
      if (!pending.has(id)) pending.set(id,item);
      notify(new Error(`数据尚未保存：${permissionMessage(e)}`));
      return;
    }
  }
}
function kick() {
  if (!writing) writing = drain().finally(()=>{writing=null;if(pending.size&&!fault)queueMicrotask(()=>{void kick();});});
  return writing;
}
export async function flushStorage() {
  await kick();
  if (fault) throw fault;
  if (pending.size) {await kick(); if (fault) throw fault;}
}
function stateStorage(secure: boolean) {
  return {
    async getItem(key:string): Promise<string|null> {
      try {
        const api = host();
        const value = api ? await (secure ? api.storage.secure : api.storage).get(key)
          : secure ? memoryKeys.get(key) ?? null : typeof localStorage !== 'undefined' ? localStorage.getItem(key) : null;
        return typeof value === 'string' ? value : null;
      } catch(e) {const error=new Error(`无法读取${secure?'模型密钥':'练习记录'}：${permissionMessage(e)}`);notify(error);throw error;}
    },
    setItem(key:string,value:string) {pending.set(`${secure}:${key}`,{key,value,secure});void kick();},
    removeItem(key:string) {pending.set(`${secure}:${key}`,{key,value:null,secure});void kick();},
  };
}
export const appStorage = stateStorage(false);
export const keyStorage = stateStorage(true);

let drafts: Record<string,string> = {};
export async function initializeDrafts() {
  const raw=await appStorage.getItem('socialcoach.drafts.v1');
  if (!raw) return;
  const saved=JSON.parse(raw);
  if (saved && typeof saved==='object' && !Array.isArray(saved)) drafts=Object.fromEntries(Object.entries(saved).filter(([k,v])=>k.startsWith('socialcoach.')&&typeof v==='string')) as Record<string,string>;
}
const saveDrafts=()=>appStorage.setItem('socialcoach.drafts.v1',JSON.stringify(drafts));
export const draftStorage={
  getItem:(key:string)=>drafts[key]??null,
  setItem(key:string,value:string){drafts[key]=value;saveDrafts();},
  removeItem(key:string){delete drafts[key];saveDrafts();},
  keys:()=>Object.keys(drafts),
};
if(typeof window!=='undefined') {
  window.addEventListener('pagehide',()=>{void flushStorage().catch(()=>{});});
  document.addEventListener('visibilitychange',()=>{if(document.hidden)void flushStorage().catch(()=>{});});
}
