import {Component,Suspense,lazy,useEffect,useState,type ReactNode} from 'react';
import {createRoot} from 'react-dom/client';
import {AppProviders} from '@/components/AppProviders';
import {useApp} from '@/store/useApp';
import {useByok} from '@/lib/byok';
import {initializeHost,host,permissionMessage} from './bridge';
import {initializeDrafts,storageFailure,flushStorage} from './storage';
import {usePathname} from './navigation';
import {abortRequests} from './network';
import '@/app/globals.css';
import './tbx.css';

const pages: Record<string,React.LazyExoticComponent<React.ComponentType>>={
  '/':lazy(()=>import('@/app/page')),
  '/arena':lazy(()=>import('@/app/arena/page')),
  '/learn':lazy(()=>import('@/app/learn/page')),
  '/onboarding':lazy(()=>import('@/app/onboarding/page')),
  '/progress':lazy(()=>import('@/app/progress/page')),
  '/rehearse':lazy(()=>import('@/app/rehearse/page')),
  '/settings':lazy(()=>import('@/app/settings/page')),
  '/practice':lazy(()=>import('@/app/practice/[id]/page')),
};
const Missing=lazy(()=>import('@/app/not-found'));
class Boundary extends Component<{children:ReactNode},{error:string|null}> {
  state={error:null as string|null};
  static getDerivedStateFromError(error:Error){return {error:error.message};}
  render(){return this.state.error?<div className="tbx-boot" role="alert"><h1>页面加载失败</h1><p>你的练习记录不会因此删除。请关闭后重新打开小工具。</p><button onClick={()=>location.reload()}>重新加载</button></div>:this.props.children;}
}
function App(){
  const path=usePathname();
  const Page=pages[path.startsWith('/practice/')?'/practice':path]||Missing;
  const [storageError,setStorageError]=useState<string|null>(storageFailure()?.message||null);
  useEffect(()=>{
    const onError=(event:Event)=>setStorageError((event as CustomEvent<string|null>).detail);
    window.addEventListener('socialcoach-storage',onError);
    return ()=>window.removeEventListener('socialcoach-storage',onError);
  },[]);
  return <Boundary><AppProviders>
    {storageError&&<div role="alert" className="tbx-storage-warning">{storageError}<button onClick={()=>{void flushStorage().catch(()=>{});}}>重试保存</button></div>}
    <Suspense fallback={<div className="tbx-boot" role="status">正在打开…</div>}><Page key={path}/></Suspense>
  </AppProviders></Boundary>;
}
function installExternalLinks(){
  document.addEventListener('click',event=>{
    const anchor=(event.target as Element)?.closest?.('a');
    if(!anchor)return;
    if(anchor.getAttribute('href')==='#main-content'){event.preventDefault();document.getElementById('main-content')?.focus();return;}
    const href=anchor.getAttribute('href')||'';
    if(/^https?:\/\//.test(href)&&host()){
      event.preventDefault();void host()!.browser.open(href).catch(e=>window.alert(permissionMessage(e)));
    }
  });
  window.addEventListener('pagehide',abortRequests);
}
async function boot(){
  try{
    await initializeHost();
    await Promise.all([useApp.persist.rehydrate(),useByok.persist.rehydrate(),initializeDrafts()]);
    if(storageFailure())throw storageFailure();
    useApp.setState({hydrated:true});useByok.setState({hydrated:true});
    if(!location.hash.startsWith('#/'))history.replaceState(null,'','#/');
    installExternalLinks();
    createRoot(document.getElementById('root')!).render(<App/>);
  }catch(error){
    const root=document.getElementById('root')!;root.replaceChildren();
    const box=document.createElement('div');box.className='tbx-boot';box.setAttribute('role','alert');
    const h=document.createElement('h1');h.textContent='SocialCoach 暂未启动';
    const p=document.createElement('p');p.textContent=permissionMessage(error);
    const b=document.createElement('button');b.textContent='重试';b.onclick=()=>location.reload();
    box.append(h,p,b);root.append(box);
  }
}
void boot();
