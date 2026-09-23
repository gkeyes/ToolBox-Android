import {Children,isValidElement,useState,type ReactNode} from 'react';
import {Sheet} from '@/components/ui';
import {useLang} from '@/store/useApp';
/** In-document options, independent of WebView's native select popup implementation. */
export function Select({value,onChange,children,className,id,'aria-label':label}: {value:string|number;onChange:(event:{target:{value:string}})=>void;children:ReactNode;className?:string;id?:string;'aria-label'?:string}){
  const [open,setOpen]=useState(false);const lang=useLang();
  const options=Children.toArray(children).filter(isValidElement).map(child=>{const p=child.props as {value?:string|number;children:ReactNode};return {value:String(p.value??''),text:p.children};});
  const selected=options.find(o=>o.value===String(value));
  const title=label||(lang==='zh'?'选择选项':'Choose an option');
  return <><button type="button" id={id} role="combobox" aria-haspopup="listbox" aria-label={label} aria-expanded={open} className={className} onClick={()=>setOpen(true)}>{selected?.text}<span aria-hidden className="ml-2">⌄</span></button><Sheet open={open} onClose={()=>setOpen(false)} title={title}><div role="listbox" aria-label={title} className="flex flex-col">{options.map(option=><button role="option" aria-selected={option.value===String(value)} key={option.value} type="button" className="min-h-12 text-left px-4 py-3 rounded-xl aria-selected:bg-accent-soft aria-selected:text-accent-deep" onClick={()=>{onChange({target:{value:option.value}});setOpen(false);}}>{option.text}</button>)}</div></Sheet></>;
}
