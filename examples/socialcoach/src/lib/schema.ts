import {z} from 'zod';
import {SKILLS,CONTEXTS,COMPETENCIES,RELATIONSHIPS} from '@/data/taxonomy';

/**
 * Shared record schemas. Backup restore and LLM scenario generation validate
 * with the same definitions, so anything generated can always be restored.
 */
const known=(list: readonly string[])=>z.string().refine(x=>list.includes(x));
const skill=known(SKILLS.map(x=>x.id)),context=known(CONTEXTS.map(x=>x.id));
const number=z.number().finite(),L=z.object({zh:z.string(),en:z.string()});
// Estimates for skill ids this taxonomy does not know (another version, a hand-edited file) are dropped on restore.
const SKILL_IDS=new Set<string>(SKILLS.map(x=>x.id));
const proficiency=z.record(z.string(),number.min(1).max(5)).transform(p=>Object.fromEntries(Object.entries(p).filter(([k])=>SKILL_IDS.has(k))));
const character=z.object({id:z.string(),name:L,role:L,personality:L,stance:L,hidden:L.optional(),playable:z.boolean().optional(),hue:number});
export const scenarioSchema=z.object({id:z.string(),title:L,hook:L,background:L,context,contextType:L,competencies:z.array(known(COMPETENCIES.map(x=>x.id))),skills:z.array(skill).min(1),relatedSkills:z.array(skill).optional(),relationship:z.array(known(Object.keys(RELATIONSHIPS))),difficulty:z.union([z.literal(1),z.literal(2),z.literal(3)]),minutes:number.positive(),characters:z.array(character).min(2),objectives:z.array(L).min(1),success:L,failure:L,maxTurns:number.int().positive(),opening:z.object({characterId:z.string(),text:L}),icon:z.string().optional(),source:z.string(),keywords:z.array(z.string()),custom:z.boolean().optional()});
const evidence=z.object({behavior:z.string(),evidence:z.string(),skill});
const report=z.object({scoringVersion:z.literal(2).optional(),ratings:z.array(z.object({skill,level:number.int().min(0).max(3),evidence:z.string(),reason:z.string()})).optional(),stars:number.int().min(0).max(3),outcome:z.enum(['success','partial','failure']),verdictEvidence:z.string().optional(),verdict:z.string(),summary:z.string(),strengths:z.array(evidence),weaknesses:z.array(evidence.extend({deficit:z.enum(['acquisition','performance']),whyItMatters:z.string()})),alternatives:z.array(z.object({original:z.string(),better:z.string(),why:z.string()})),knowledge:z.object({theoryIds:z.array(z.string()),caseIds:z.array(z.string()),whyThis:z.string()}),reflectionQuestions:z.array(z.string()),nextStep:z.string(),deltas:z.record(z.string(),number.min(-5).max(5))});
const prescription=z.object({query:z.string(),core_constraints:z.object({target_skills:z.array(skill),contexts:z.array(context).optional()}),optional_constraints:z.object({related_skills:z.array(skill).optional(),relationship_types:z.array(z.string()).optional(),difficulty:z.union([z.literal(1),z.literal(2),z.literal(3)]).optional()}).optional(),rationale:z.string()});
const retrieval=z.object({relaxed:z.array(z.string()),candidates:number.int().nonnegative(),chosen:z.string(),roleFit:z.object({characterId:z.string(),fit:z.enum(['compatible','uncertain']),reason:z.string()}).optional()});
export const sessionSchema=z.object({prescription:prescription.optional(),retrieval:retrieval.optional(),id:z.string(),scenario:scenarioSchema,learnerCharacterId:z.string(),messages:z.array(z.object({id:z.string(),role:z.enum(['learner','npc','coach','event']),characterId:z.string().optional(),text:z.string(),ts:number,kind:z.enum(['hint','silence']).optional(),seconds:number.optional()})),objectiveDone:z.array(z.boolean()),status:z.enum(['briefing','active','ended','assessed']),outcome:z.enum(['success','partial','failure']).optional(),outcomeNote:z.string().optional(),startedAt:number,endedAt:number.optional(),report:report.optional(),reflections:z.array(z.object({question:z.string(),answer:z.string(),coachReply:z.string().optional()})),origin:z.enum(['scheduled','arena','rehearse']),stanceTrail:z.array(number.min(0).max(100)).optional(),revealedAtTurn:number.optional(),revealSeen:z.boolean().optional(),timed:z.boolean().optional(),adaptation:z.object({learnerCharacterId:z.string(),briefing:z.string(),objectives:z.array(z.string()),focus:z.string(),why:z.string().optional()}).optional(),closure:z.object({kind:z.enum(['agreement','boundary','deferred','withdrawal']),learnerQuote:z.string().optional(),npcQuote:z.string()}).optional()});
export const backupSchema=z.object({schemaVersion:z.literal(1),tool:z.literal('io.toolbox.socialcoach'),profile:z.object({name:z.string(),bio:z.string(),goals:z.array(skill).min(1),contexts:z.array(context),lang:z.enum(['zh','en']),createdAt:number}),proficiency,sessions:z.array(sessionSchema),customScenarios:z.array(scenarioSchema),bookmarks:z.array(z.string()),practiceDays:z.array(z.string().regex(/^\d{4}-\d{2}-\d{2}$/)),settings:z.object({tts:z.boolean().optional()/* retired; accepted from old backups and dropped */,theme:z.enum(['light','dark','system']).optional(),avatarSeed:number.optional(),avatarPortrait:z.string().optional(),timed:z.boolean().optional(),patience:z.union([z.literal(10),z.literal(15),z.literal(20)]).optional()}).optional(),exportedAt:z.string()});
export type ScenarioRecord=z.infer<typeof scenarioSchema>;
/** Cross-field rules the object schema cannot express. */
export function scenarioProblem(sc:{characters:{id:string;playable?:boolean}[];opening:{characterId:string}}):string|null{
  const ids=new Set(sc.characters.map(c=>c.id));
  if(ids.size!==sc.characters.length||!ids.has(sc.opening.characterId))return '场景的角色数据无效。';
  if(!sc.characters.some(c=>c.playable))return '场景缺少可扮演的角色。';
  return null;
}
