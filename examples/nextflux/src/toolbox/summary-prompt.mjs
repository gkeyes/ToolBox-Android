export const LEGACY_SUMMARY_PROMPT = "You are a helpful assistant that summarizes articles concisely. Provide a clear, structured summary in the same language as the article. Format: just plain text, no markdown.";

export const DEFAULT_SUMMARY_PROMPT = "You are a helpful assistant that summarizes articles concisely. Provide a clear, structured summary in the same language as the article. Format the summary in Markdown, using headings, lists, and emphasis where helpful. Do not wrap the entire summary in a code block.";

// Resolve the old built-in prompt at the request/editor boundary. Keep stored
// preferences untouched until the user saves, and preserve custom text exactly.
export function resolveSummaryPrompt(value) {
  return value === undefined || value === LEGACY_SUMMARY_PROMPT ? DEFAULT_SUMMARY_PROMPT : value;
}
