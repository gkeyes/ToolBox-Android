import {z} from 'zod';
import {SKILLS,CONTEXTS,COMPETENCIES,RELATIONSHIPS} from '@/data/taxonomy';
import {useApp} from '@/store/useApp';
import {host} from './bridge';
import {flushStorage} from './storage';

const known=(list: readonly string[])=>z.string().refine(x=>list.includes(x));
const skill=known(SKILLS.map(x=>x.id)),context=known(CONTEXTS.map(x=>x.id));
const number=z.number().finite(),L=z.object({zh:z.string(),en:z.string()});
const proficiency=z.record(z.string(),number.min(1).max(5));
const character=z.object({id:z.string(),name:L,role:L,personality:L,stance:L,hidden:L.optional(),playable:z.boolean().optional(),hue:number});
const scenario=z.object({id:z.string(),title:L,hook:L,background:L,context,contextType:L,competencies:z.array(known(COMPETENCIES.map(x=>x.id))),skills:z.array(skill).min(1),relatedSkills:z.array(skill).optional(),relationship:z.array(known(Object.keys(RELATIONSHIPS))),difficulty:z.union([z.literal(1),z.literal(2),z.literal(3)]),minutes:number.positive(),characters:z.array(character).min(2),objectives:z.array(L).min(1),success:L,failure:L,maxTurns:number.int().positive(),opening:z.object({characterId:z.string(),text:L}),icon:z.string().optional(),source:z.string(),keywords:z.array(z.string()),custom:z.boolean().optional()});
const evidence=z.object({behavior:z.string(),evidence:z.string(),skill});
const report=z.object({scoringVersion:z.literal(2).optional(),ratings:z.array(z.object({skill,level:number.int().min(0).max(3),evidence:z.string(),reason:z.string()})).optional(),stars:number.int().min(0).max(3),outcome:z.enum(['success','partial','failure']),verdictEvidence:z.string().optional(),verdict:z.string(),summary:z.string(),strengths:z.array(evidence),weaknesses:z.array(evidence.extend({deficit:z.enum(['acquisition','performance']),whyItMatters:z.string()})),alternatives:z.array(z.object({original:z.string(),better:z.string(),why:z.string()})),knowledge:z.object({theoryIds:z.array(z.string()),caseIds:z.array(z.string()),whyThis:z.string()}),reflectionQuestions:z.array(z.string()),nextStep:z.string(),deltas:z.record(z.string(),number.min(-5).max(5))});
const prescription=z.object({query:z.string(),core_constraints:z.object({target_skills:z.array(skill),contexts:z.array(context).optional()}),optional_constraints:z.object({related_skills:z.array(skill).optional(),relationship_types:z.array(z.string()).optional(),difficulty:z.union([z.literal(1),z.literal(2),z.literal(3)]).optional()}).optional(),rationale:z.string()});
const retrieval=z.object({relaxed:z.array(z.string()),candidates:number.int().nonnegative(),chosen:z.string(),roleFit:z.object({characterId:z.string(),fit:z.enum(['compatible','uncertain']),reason:z.string()}).optional()});
const session=z.object({prescription:prescription.optional(),retrieval:retrieval.optional(),id:z.string(),scenario,learnerCharacterId:z.string(),messages:z.array(z.object({id:z.string(),role:z.enum(['learner','npc','coach','event']),characterId:z.string().optional(),text:z.string(),ts:number,kind:z.enum(['hint','silence']).optional(),seconds:number.optional()})),objectiveDone:z.array(z.boolean()),status:z.enum(['briefing','active','ended','assessed']),outcome:z.enum(['success','partial','failure']).optional(),outcomeNote:z.string().optional(),startedAt:number,endedAt:number.optional(),report:report.optional(),reflections:z.array(z.object({question:z.string(),answer:z.string(),coachReply:z.string().optional()})),origin:z.enum(['scheduled','arena','rehearse']),stanceTrail:z.array(number.min(0).max(100)).optional(),revealedAtTurn:number.optional(),revealSeen:z.boolean().optional(),timed:z.boolean().optional(),adaptation:z.object({learnerCharacterId:z.string(),briefing:z.string(),objectives:z.array(z.string()),focus:z.string(),why:z.string().optional()}).optional(),closure:z.object({kind:z.enum(['agreement','boundary','deferred','withdrawal']),learnerQuote:z.string().optional(),npcQuote:z.string()}).optional()});
const schema=z.object({schemaVersion:z.literal(1),tool:z.literal('io.toolbox.socialcoach'),profile:z.object({name:z.string(),bio:z.string(),goals:z.array(skill).min(1),contexts:z.array(context),lang:z.enum(['zh','en']),createdAt:number}),proficiency,sessions:z.array(session),customScenarios:z.array(scenario),bookmarks:z.array(z.string()),practiceDays:z.array(z.string().regex(/^\d{4}-\d{2}-\d{2}$/)),settings:z.object({tts:z.boolean().optional(),theme:z.enum(['light','dark','system']).optional(),avatarSeed:number.optional(),avatarPortrait:z.string().optional(),timed:z.boolean().optional(),patience:z.union([z.literal(10),z.literal(15),z.literal(20)]).optional()}).optional(),exportedAt:z.string()});
export function validateBackup(raw:string){
  const value=JSON.parse(raw,(key,value)=>{if(['__proto__','constructor','prototype'].includes(key))throw new Error('备份包含不安全字段。');return value;});
  const parsed=schema.safeParse(value);
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
  useApp.setState({profile:checked.profile,proficiency:checked.proficiency,sessions:checked.sessions,customScenarios:checked.customScenarios,bookmarks:checked.bookmarks,practiceDays:checked.practiceDays,settings:{...checked.settings,tts:false,telemetry:false},todayDate:null,todaySessionId:null,patternInsight:null} as unknown as Partial<typeof state>);
  await flushStorage();
}
export async function shareText(text:string){
  const api=host();
  if(api)await api.share.text(text);
  else if(navigator.share)await navigator.share({text});
  else await navigator.clipboard.writeText(text);
}
