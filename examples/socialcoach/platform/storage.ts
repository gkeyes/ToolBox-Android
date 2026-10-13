import {host, permissionMessage} from './bridge';
import {recordWork} from './perf';

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
// A failed read is fatal at boot (state would be incomplete); failed writes stay retryable warnings.
let readFault: Error | null = null;
export const storageReadFailure = () => readFault;
async function drain() {
  while (pending.size) {
    const [id, item] = pending.entries().next().value!;
    pending.delete(id);
    try {
      const api = host();
      if (api) {
        const target = item.secure ? api.storage.secure : api.storage;
        const started = performance.now();
        if (item.value === null) await target.remove(item.key); else await target.set(item.key,item.value);
        recordWork('hostWrite', (item.value?.length ?? 0) * 2, performance.now() - started);
      } else if (item.secure) {
        // Browser preview: secrets are deliberately memory-only.
        if (item.value === null) memoryKeys.delete(item.key); else memoryKeys.set(item.key,item.value);
      } else if (typeof localStorage !== 'undefined') {
        if (item.value === null) localStorage.removeItem(item.key); else localStorage.setItem(item.key,item.value);
      }
      notify(null);
    } catch (e) {
      // Retry this key first so a record is never written after an index that already lists it.
      // A newer value queued while this write was in flight replaces the failed one in that front slot.
      const retry=pending.get(id)??item;pending.delete(id);
      const rest=[...pending];pending.clear();pending.set(id,retry);for(const [k,v] of rest)pending.set(k,v);
      notify(new Error(`数据尚未保存：${permissionMessage(e)}`));
      return;
    }
  }
}
function kick() {
  if (!writing) writing = drain().finally(()=>{writing=null;if(pending.size&&!fault)queueMicrotask(()=>{void kick();});});
  return writing;
}
// Debounced writers (the per-session store) hand over their pending state here
// before every flush, so a flush always commits the latest in-memory data.
const beforeFlush = new Set<() => void>();
export function onBeforeFlush(hook: () => void): () => void { beforeFlush.add(hook); return () => { beforeFlush.delete(hook); }; }
export async function flushStorage() {
  for (const hook of beforeFlush) hook();
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
      } catch(e) {const error=new Error(`无法读取${secure?'模型密钥':'练习记录'}：${permissionMessage(e)}`);readFault=error;notify(error);throw error;}
    },
    // Re-queue at the end so writes land in the order they were made (session records before the index that lists them).
    setItem(key:string,value:string) {pending.delete(`${secure}:${key}`);pending.set(`${secure}:${key}`,{key,value,secure});void kick();},
    removeItem(key:string) {pending.delete(`${secure}:${key}`);pending.set(`${secure}:${key}`,{key,value:null,secure});void kick();},
  };
}
export const appStorage = stateStorage(false);
/**
 * Read ordinary keys in index order. The host rejects a getMany response larger
 * than its memory-derived budget (QUOTA_EXCEEDED), so keys go in small batches
 * and a rejected batch is split until it fits; a single key falls back to get().
 */
export const READ_BATCH_KEYS = 32;
export async function getManyItems(keys: string[]): Promise<(string|null)[]> {
  if (!keys.length) return [];
  const api = host();
  const getMany = api?.storage.getMany?.bind(api.storage);
  if (!getMany) return Promise.all(keys.map(key => appStorage.getItem(key)));
  const read = async (batch: string[]): Promise<(string|null)[]> => {
    if (batch.length === 1) return [await appStorage.getItem(batch[0])];
    try {
      return (await getMany(batch)).map(value => typeof value === 'string' ? value : null);
    } catch (e) {
      if ((e as {code?: string})?.code !== 'QUOTA_EXCEEDED') {const error=new Error(`无法读取练习记录：${permissionMessage(e)}`);readFault=error;notify(error);throw error;}
      const half = Math.ceil(batch.length / 2);
      return [...await read(batch.slice(0, half)), ...await read(batch.slice(half))];
    }
  };
  const result: (string|null)[] = [];
  for (let i = 0; i < keys.length; i += READ_BATCH_KEYS) result.push(...await read(keys.slice(i, i + READ_BATCH_KEYS)));
  return result;
}
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
