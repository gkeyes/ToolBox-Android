import { useSyncExternalStore, useMemo, useEffect, useRef, type AnchorHTMLAttributes } from 'react';
import { abortRequests } from './network';

const snapshot = () => typeof location === 'undefined' ? '/' : (location.hash.startsWith('#/') ? location.hash.slice(1) : '/');
const listeners = new Set<() => void>();
let previous = snapshot();
function emit() {
  const current = snapshot();
  if (current.split('?')[0] !== previous.split('?')[0]) { abortRequests(); window.scrollTo(0,0); }
  previous = current;
  for (const listener of listeners) listener();
}
if (typeof window !== 'undefined') { window.addEventListener('hashchange',emit); window.addEventListener('popstate',emit); }
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; };
export const useLocation = () => useSyncExternalStore(subscribe, snapshot, () => '/');
export const usePathname = () => useLocation().split('?')[0];
export function useSearchParams() { const loc = useLocation(); return useMemo(()=>new URLSearchParams(loc.split('?')[1] || ''),[loc]); }
export function useParams<T = {id: string}>(): T { const loc = usePathname(); return {id:decodeURIComponent(loc.split('/')[2] || '')} as T; }
function navigate(to: string, replace = false) {
  if (!to.startsWith('/') || to.startsWith('//')) throw new Error('无效的应用内路径。');
  if (snapshot() === to) return;
  // Drop a sheet sentinel before leaving its route; it must not reappear on Back.
  if (history.state?.scModal) history.replaceState(null, '', location.href);
  if (replace) history.replaceState(null,'','#'+to); else history.pushState(null,'','#'+to);
  emit();
}
const router = {push:(to:string)=>navigate(to),replace:(to:string)=>navigate(to,true),back:()=>history.back(),prefetch:()=>{}};
export const useRouter = () => router;
export default function Link({href = '/',onClick,replace,scroll:_scroll,prefetch:_prefetch,...props}: AnchorHTMLAttributes<HTMLAnchorElement> & {replace?:boolean;scroll?:boolean;prefetch?:boolean}) {
  const internal = href.startsWith('/') && !href.startsWith('//');
  return <a {...props} href={internal ? '#'+href : href} onClick={e=>{
    onClick?.(e);
    if (!e.defaultPrevented && internal && e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey && !e.altKey) {e.preventDefault();navigate(href,replace);}
  }}/>;
}
/** Android WebView Back traverses history. A modal consumes one entry without changing the route. */
export function useSheetHistory(open:boolean,onClose:()=>void) {
  const close = useRef(onClose); close.current = onClose;
  useEffect(()=>{
    if (!open) return;
    const id = crypto.randomUUID(); let popped = false;
    history.pushState({...history.state,scModal:id},'',location.href);
    const pop = () => { if (history.state?.scModal !== id) {popped = true;close.current();} };
    window.addEventListener('popstate',pop);
    return ()=>{
      window.removeEventListener('popstate',pop);
      if (!popped && history.state?.scModal === id) history.back();
    };
  },[open]);
}
