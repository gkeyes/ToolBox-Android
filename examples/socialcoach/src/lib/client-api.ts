import type {Report,RoleplayMeta} from '@/lib/types';
import type {LLM} from '@/lib/llm-core';
import type {ScheduleInput,ScheduleOutput,AssessInput,RehearseInput,TurnInput,ReflectInput,PatternInput} from '@/lib/tasks/types';
import {byokConfig,openModelSheet} from '@/lib/byok';
import {makeByokLLM} from '../../platform/llm';
import {requestScope,abortError} from '../../platform/network';
import {parsePartialJSON} from '@/lib/partial-json';
import {runSchedule} from '@/lib/tasks/schedule';
import {runRehearse} from '@/lib/tasks/rehearse';
import {runHint} from '@/lib/tasks/hint';
import {runRoleplay} from '@/lib/tasks/roleplay';
import {runReflect} from '@/lib/tasks/reflect';
import {runAssess} from '@/lib/tasks/assess';
import {runPattern} from '@/lib/tasks/pattern';

export class ApiError extends Error {
  constructor(message:string,public readonly status:number,public readonly kind:'http'|'network'|'stream'){super(message);}
}
export type ScheduleResult=ScheduleOutput;
async function task<T>(run:(llm:LLM,fast:string,smart:string)=>Promise<T>,signal?:AbortSignal):Promise<T>{
  const c=byokConfig();
  if(!c){openModelSheet();throw new ApiError('请先配置模型 API，再开始 AI 练习。',401,'http');}
  const scope=requestScope(signal);
  try{
    if(scope.signal.aborted)throw abortError();
    const result=await run(makeByokLLM(c,scope.signal),c.fastModel.trim(),c.smartModel.trim());
    if(scope.signal.aborted)throw abortError();
    return result;
  }finally{scope.dispose();}
}
export const schedule=(body:ScheduleInput,signal?:AbortSignal)=>task((llm,fast)=>runSchedule(body,llm,fast),signal);
export const hint=(body:TurnInput)=>task((llm,fast)=>runHint(body,llm,fast));
export const rehearse=(body:RehearseInput)=>task((llm,fast)=>runRehearse(body,llm,fast));
export const pattern=(body:PatternInput)=>task((llm,_fast,smart)=>runPattern(body,llm,smart));
export const assessStream=(body:AssessInput,onPartial:(p:Partial<Report>)=>void)=>task((llm,_fast,smart)=>{
  let acc='';return runAssess(body,llm,smart,d=>{acc+=d;const p=parsePartialJSON<Report>(acc);if(p)onPartial(p);});
});
export const roleplayStream=(body:TurnInput,onText:(full:string)=>void,signal?:AbortSignal)=>task((llm,fast)=>{
  let acc='';return runRoleplay(body,llm,fast,d=>{acc+=d;onText(acc);});
},signal);
export const reflectStream=(body:ReflectInput,onText:(full:string)=>void)=>task((llm,fast)=>{
  let acc='';return runReflect(body,llm,fast,d=>{acc+=d;onText(acc);});
});

export interface ParsedTurn {
  utterances: { characterId: string; text: string }[];
  meta: RoleplayMeta | null;
  error: string | null;
}

export function parseRoleplay(raw: string, validIds: string[]): ParsedTurn {
  const out: ParsedTurn = { utterances: [], meta: null, error: null };
  const lines = raw.split("\n");
  let cur: { characterId: string; text: string } | null = null;
  let metaBuf: string[] | null = null;
  /** Whether lines are still flowing into the meta block. Its buffer staying
   *  non-null is not the same question: meta leads the turn, so the buffer
   *  outlives the block and must not keep swallowing the dialogue after it. */
  let inMeta = false;
  let errBuf: string[] | null = null;
  const flush = () => {
    if (cur) {
      cur.text = cur.text.trim();
      if (cur.text) out.utterances.push(cur);
    }
    cur = null;
  };
  for (const line of lines) {
    if (errBuf) {
      errBuf.push(line);
      continue;
    }
    const m = line.match(/^@@\s*([\w-]+)\s*$/);
    if (inMeta && !m) {
      metaBuf!.push(line);
      continue;
    }
    if (m) {
      flush();
      const id = m[1].toLowerCase();
      inMeta = false;
      if (id === "meta") {
        metaBuf = metaBuf ?? [];
        inMeta = true;
      } else if (id === "error") errBuf = [];
      else {
        const match = validIds.find((v) => v.toLowerCase() === id) ?? validIds[0];
        cur = { characterId: match, text: "" };
      }
      continue;
    }
    if (!cur) {
      // Text before any marker is dialogue the model forgot to label — except a
      // bare meta object, which must never be spoken aloud as a line.
      const t2 = line.trim();
      if (t2.startsWith("{") || t2.startsWith("```")) {
        if (!metaBuf) {
          metaBuf = [line];
          inMeta = true;
        }
        continue;
      }
      if (t2) cur = { characterId: validIds[0], text: line + "\n" };
      continue;
    }
    cur.text += line + "\n";
  }
  flush();
  if (errBuf) out.error = errBuf.join("\n").trim();
  if (metaBuf) {
    const raw2 = metaBuf.join("\n").trim().replace(/^```(?:json)?/i, "").replace(/```$/, "");
    const a = raw2.indexOf("{");
    const b = raw2.lastIndexOf("}");
    const txt = a !== -1 && b > a ? raw2.slice(a, b + 1) : raw2;
    try {
      const j = JSON.parse(txt) as RoleplayMeta;
      const st = typeof j.stance === "number" ? j.stance : NaN;
      out.meta = {
        objectives: Array.isArray(j.objectives) ? j.objectives.map((v) => v === true) : [],
        ended: j.ended === true,
        closure: j.closure,
        outcome: j.outcome === "success" || j.outcome === "partial" || j.outcome === "failure" ? j.outcome : null,
        note: j.note,
        // A model that omits the field, or answers with prose, must not move the meter.
        stance: Number.isFinite(st) ? Math.max(0, Math.min(100, Math.round(st))) : undefined,
        revealed: j.revealed === true,
      };
    } catch {
      out.meta = null; // still streaming
    }
  }
  return out;
}
