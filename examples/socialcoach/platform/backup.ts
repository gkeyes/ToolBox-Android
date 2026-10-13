import {backupSchema} from '@/lib/schema';
import {useApp} from '@/store/useApp';
import {cleanSettings} from '@/store/persistence';
import {host} from './bridge';
import {flushStorage} from './storage';

export function validateBackup(raw:string){
  const value=JSON.parse(raw,(key,value)=>{if(['__proto__','constructor','prototype'].includes(key))throw new Error('备份包含不安全字段。');return value;});
  const parsed=backupSchema.safeParse(value);
  if(!parsed.success)throw new Error('不是兼容的 SocialCoach v1 备份，或备份内容不完整。原有数据未修改。');
  const data=parsed.data;
  if(new Set(data.sessions.map(s=>s.id)).size!==data.sessions.length)throw new Error('备份包含重复的练习记录。');
  for(const sc of [...data.customScenarios,...data.sessions.map(s=>s.scenario)]){
    const ids=new Set(sc.characters.map(c=>c.id));
    if(ids.size!==sc.characters.length||!ids.has(sc.opening.characterId))throw new Error('备份场景的角色数据无效。');
  }
  for(const s of data.sessions){
    if(!s.scenario.characters.some(c=>c.id===s.learnerCharacterId&&c.playable)||s.objectiveDone.length!==s.scenario.objectives.length||(s.status==='assessed'&&!s.report))throw new Error('备份练习数据不完整。');
  }
  return data;
}
export type Backup = ReturnType<typeof validateBackup>;
export function backupText(){
  const s=useApp.getState();
  if(!s.profile)throw new Error('请先完成个人练习设置。');
  return JSON.stringify({schemaVersion:1,tool:'io.toolbox.socialcoach',profile:s.profile,proficiency:s.proficiency,sessions:s.sessions,customScenarios:s.customScenarios,bookmarks:s.bookmarks,practiceDays:s.practiceDays,settings:s.settings,exportedAt:new Date().toISOString()},null,2);
}
export async function exportBackup(){
  await flushStorage();
  const text=backupText(),name=`socialcoach-${new Date().toISOString().slice(0,10)}.json`,api=host();
  if(api)return (await api.files.save(name,'application/json',text))!==null;
  const url=URL.createObjectURL(new Blob([text],{type:'application/json'}));
  const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);return true;
}
export async function chooseBackup():Promise<Backup|null>{
  const api=host();
  if(api){const file=await api.files.open(['application/json','text/plain']);if(!file)return null;return validateBackup(new TextDecoder().decode(await api.files.read(file.token)));}
  return new Promise((resolve,reject)=>{const input=document.createElement('input');input.type='file';input.accept='.json,application/json';input.oncancel=()=>resolve(null);input.onchange=async()=>{try{resolve(input.files?.[0]?validateBackup(await input.files[0].text()):null);}catch(e){reject(e);}};input.click();});
}
export async function restoreBackup(data:Backup){
  // Validate again at the write boundary. Keys are never part of the backup.
  const checked=validateBackup(JSON.stringify(data));
  const state=useApp.getState();
  useApp.setState({profile:checked.profile,proficiency:checked.proficiency,sessions:checked.sessions,customScenarios:checked.customScenarios,bookmarks:checked.bookmarks,practiceDays:checked.practiceDays,settings:cleanSettings(checked.settings),todayDate:null,todaySessionId:null,patternInsight:null} as unknown as Partial<typeof state>);
  await flushStorage();
}
export async function shareText(text:string){
  const api=host();
  if(api)await api.share.text(text);
  else if(navigator.share)await navigator.share({text});
  else await navigator.clipboard.writeText(text);
}
