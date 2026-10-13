import { jsonCall, LLMError, type LLM } from "@/lib/llm-core";
import { rehearseSystem } from "@/lib/prompts";
import type { Scenario } from "@/data/corpus/types";
import { CONTEXTS, SKILLS, COMPETENCIES, RELATIONSHIPS, type CompetencyId, type ContextId, type L, type RelationshipType, type SkillId, skillById } from "@/data/taxonomy";
import { isScenarioIcon } from "@/data/scenario-icons";
import { scenarioProblem, scenarioSchema } from "@/lib/schema";
import type { RehearseInput } from "./types";

type Raw = Record<string, unknown>;
const skillIds = new Set<string>(SKILLS.map((s) => s.id));
const ctxIds = new Set<string>(CONTEXTS.map((c) => c.id));
const compIds = new Set<string>(COMPETENCIES.map((c) => c.id));
const relIds = new Set<string>(Object.keys(RELATIONSHIPS));
const junk = /占位|placeholder|未使用|unused|n\/a|none/i;

const text = (v: unknown) => (typeof v === "string" ? v : typeof v === "number" ? String(v) : "");
/** Every bilingual field ends up complete: the single generated language is mirrored into the other key. */
function bilingual(v: unknown): L {
  if (typeof v === "string") return { zh: v, en: v };
  const o = (v && typeof v === "object" ? v : {}) as Raw;
  const zh = text(o.zh), en = text(o.en);
  return { zh: zh || en, en: en || zh };
}
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const strings = (v: unknown) => list(v).filter((x): x is string => typeof x === "string");
const num = (v: unknown) => (typeof v === "number" ? v : typeof v === "string" ? Number.parseFloat(v) : NaN);

/**
 * Repair what can be repaired, then validate with the same schema backup
 * restore uses. A scenario that still fails is rejected instead of stored,
 * because a stored one that fails would make every later backup unrestorable.
 */
export function repairScenario(raw: Raw, id = `custom-${Date.now().toString(36)}`): Scenario | null {
  const skills = strings(raw.skills).filter((k) => skillIds.has(k)) as SkillId[];
  if (!skills.length) skills.push("communication");
  const competencies = Array.from(new Set([...strings(raw.competencies).filter((c) => compIds.has(c)), ...skills.map((k) => skillById(k).competency)])) as CompetencyId[];
  const relatedSkills = strings(raw.relatedSkills).filter((k) => skillIds.has(k)) as SkillId[];
  const relationship = Array.from(new Set(strings(raw.relationship).filter((r) => relIds.has(r)))) as RelationshipType[];
  const seen = new Set<string>();
  const characters = list(raw.characters)
    .filter((c): c is Raw => !!c && typeof c === "object")
    .map((c, i) => {
      const cid = text(c.id) || `c${i}`;
      const hue = num(c.hue);
      return {
        id: cid,
        name: bilingual(c.name),
        role: bilingual(c.role),
        personality: bilingual(c.personality),
        stance: bilingual(c.stance),
        ...(c.hidden ? { hidden: bilingual(c.hidden) } : {}),
        // The learner always plays themself.
        ...(cid === "you" || c.playable === true ? { playable: true } : {}),
        hue: Number.isFinite(hue) ? hue : (i * 97) % 360,
      };
    })
    .filter((c) => c.id === "you" || !(junk.test(`${c.name.zh} ${c.name.en} ${c.role.zh} ${c.role.en}`) || (!c.personality.zh && !c.personality.en)))
    .filter((c) => !seen.has(c.id) && seen.add(c.id));
  if (!characters.some((c) => c.id === "you")) characters.unshift({ id: "you", name: { zh: "你", en: "You" }, role: { zh: "你自己", en: "Yourself" }, personality: { zh: "", en: "" }, stance: { zh: "", en: "" }, playable: true, hue: 40 });
  const npc = characters.find((c) => c.id !== "you");
  const opening = (raw.opening && typeof raw.opening === "object" ? raw.opening : {}) as Raw;
  const openingId = text(opening.characterId);
  const minutes = num(raw.minutes);
  const maxTurns = Math.round(num(raw.maxTurns));
  const difficulty = Math.round(num(raw.difficulty));
  const icon = text(raw.icon);
  const scenario = {
    id,
    title: bilingual(raw.title),
    hook: bilingual(raw.hook),
    background: bilingual(raw.background),
    context: (ctxIds.has(text(raw.context)) ? text(raw.context) : "workplace") as ContextId,
    contextType: bilingual(raw.contextType),
    competencies,
    skills,
    ...(relatedSkills.length ? { relatedSkills } : {}),
    relationship: relationship.length ? relationship : (["peer"] as RelationshipType[]),
    difficulty: ([1, 2, 3].includes(difficulty) ? difficulty : 2) as 1 | 2 | 3,
    minutes: Number.isFinite(minutes) && minutes > 0 ? minutes : 4,
    characters,
    objectives: list(raw.objectives).map(bilingual).filter((o) => o.zh.trim()),
    success: bilingual(raw.success),
    failure: bilingual(raw.failure),
    maxTurns: Math.max(6, Math.min(10, Number.isFinite(maxTurns) ? maxTurns : 8)),
    opening: { characterId: openingId && characters.some((c) => c.id === openingId && c.id !== "you") ? openingId : (npc?.id ?? "npc"), text: opening.text ? bilingual(opening.text) : { zh: "……", en: "..." } },
    keywords: strings(raw.keywords),
    // the model picks from the allow-list; anything else falls back to the
    // context default in `scenarioIconName`, so a bad name can never render.
    ...(isScenarioIcon(icon) ? { icon } : {}),
    source: "Learner-described situation (generated)",
    custom: true,
  };
  const parsed = scenarioSchema.safeParse(scenario);
  if (!parsed.success || !scenario.title.zh.trim() || scenarioProblem(parsed.data)) return null;
  return parsed.data as Scenario;
}

/** Turn a situation in the learner's own words into a fully tagged scenario. */
export async function runRehearse(input: RehearseInput, llm: LLM, fastModel: string): Promise<{ scenario: Scenario }> {
  const { description, lang, profile } = input;
  if (!description || description.trim().length < 8) throw new LLMError("Please describe the situation in a bit more detail.", 400);
  const user = `LEARNER: ${profile?.name || "(anonymous)"}; about: ${profile?.bio || "(n/a)"}; target skills: ${(profile?.goals ?? []).join(", ") || "(n/a)"}\n\nSITUATION (in the learner's words):\n"""${description.trim()}"""\n\nProduce the scenario JSON.`;
  for (let attempt = 0; attempt < 2; attempt++) {
    const raw = await jsonCall<Raw>({
      model: fastModel,
      thinking: false,
      maxTokens: 6000,
      system: rehearseSystem(lang),
      user: attempt ? `${user}\n\nThe previous answer was missing required fields. Return the COMPLETE JSON object with at least two characters (including "you"), 2–3 objectives and an NPC opening line.` : user,
    }, llm);
    const scenario = raw && typeof raw === "object" && !Array.isArray(raw) ? repairScenario(raw) : null;
    if (scenario) return { scenario };
  }
  throw new LLMError(lang === "zh" ? "生成的场景不完整，请补充描述后重试。" : "The generated scenario was incomplete. Add a little more detail and try again.", 502);
}
