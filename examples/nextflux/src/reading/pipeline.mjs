import { adaptArticleSource } from "./adapters/index.mjs";
import { createReadingParser } from "./parser.js";

export function createReadingPipeline({ html, baseUrl, title } = {}) {
  const adapted = adaptArticleSource({ html, baseUrl, title });
  return {
    adapter: adapted.adapter,
    changed: adapted.changed,
    parser: createReadingParser(adapted.html, baseUrl),
  };
}
