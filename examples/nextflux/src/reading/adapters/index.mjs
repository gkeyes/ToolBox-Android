import { adaptTelegramArticle, matchesTelegramArticle } from "./telegram.mjs";

const adapters = [
  {
    id: "telegram",
    matches: matchesTelegramArticle,
    adapt: adaptTelegramArticle,
  },
];

export function adaptArticleSource(context = {}) {
  const source = typeof context.html === "string" ? context.html : "";
  for (const adapter of adapters) {
    if (!adapter.matches(context)) continue;
    const result = adapter.adapt({ ...context, html: source });
    return {
      html: typeof result?.html === "string" ? result.html : source,
      adapter: adapter.id,
      changed: Boolean(result?.changed),
    };
  }
  return { html: source, adapter: "generic", changed: false };
}
