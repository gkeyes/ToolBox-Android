// These are user-selected context capacities, not exact input-token counts.
// Keep unknown compatible services on the existing character budget rather
// than guessing a larger window from an unrecognised model name.
export const AI_CONTEXT_OPTIONS = [
  { value: "auto", label: "自动" },
  { value: "32000", label: "32K" },
  { value: "64000", label: "64K" },
  { value: "128000", label: "128K" },
  { value: "256000", label: "256K" },
  { value: "1000000", label: "1M" },
];

const contextValues = new Set(AI_CONTEXT_OPTIONS.map(({ value }) => value));

export function isGpt62Model(model) {
  return typeof model === "string" && /^(?:openai\/)?gpt-6\.2-(?:sol|astra)$/.test(model.trim().toLowerCase());
}

export function normalizeAiContextWindow(value) {
  const selected = typeof value === "string" ? value.trim() : typeof value === "number" ? String(value) : "auto";
  return contextValues.has(selected) ? selected : "auto";
}

// Only documented model families and explicit provider prefixes are matched.
// Proxies may impose smaller limits; explicit user settings take precedence.
// OpenAI: developers.openai.com/api/docs/models/gpt-4.1, /gpt-4o-mini and /gpt-6.2-sol
// Google: ai.google.dev/gemini-api/docs/models/gemini-2.5-pro (also flash / flash-lite)
function recognisedContext(model) {
  const name = typeof model === "string" ? model.trim().toLowerCase() : "";
  if (isGpt62Model(name)) return 1050000;
  if (/^(?:openai\/)?gpt-4\.1(?:-(?:mini|nano))?(?:-\d{4}-\d{2}-\d{2})?$/.test(name)) return 1047576;
  if (/^(?:openai\/)?gpt-4o(?:-mini)?(?:-\d{4}-\d{2}-\d{2})?$/.test(name)) return 128000;
  if (/^(?:google\/)?gemini-2\.5-(?:pro|flash(?:-lite)?)(?:-\d{4}-\d{2}-\d{2})?$/.test(name)) return 1048576;
  return null;
}

export function resolveAiContextWindow(settings = {}) {
  const selected = normalizeAiContextWindow(settings?.aiContextWindow);
  return selected === "auto" ? recognisedContext(settings?.aiModel) : Number(selected);
}

export function boardInputPolicy(settings = {}) {
  const context = resolveAiContextWindow(settings);
  // UTF-8 bytes conservatively estimate the input size without shipping a
  // model-specific tokenizer. Reserve space for output and chat framing too.
  return context === null
    ? { unit: "characters", budget: 24000 }
    : { unit: "utf8", budget: Math.floor(context * 0.8) - 1024 };
}

const encoder = new TextEncoder();

export function measureText(text, unit) {
  const value = String(text ?? "");
  return unit === "utf8" ? encoder.encode(value).length : value.length;
}

export function measureMessages(messages, policy) {
  return messages.reduce((size, message) => size + measureText(message?.content, policy?.unit), 0);
}

export function validInputPolicy(policy) {
  return Boolean(policy && typeof policy === "object" && !Array.isArray(policy)
    && (policy.unit === "utf8" || policy.unit === "characters")
    && Number.isSafeInteger(policy.budget) && policy.budget >= 4096 && policy.budget <= 1000000);
}
