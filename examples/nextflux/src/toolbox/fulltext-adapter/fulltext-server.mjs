import { analyzeArticle } from "../../reading/analyze.mjs";
function fail(code, message, extra = {}) { return Object.assign(new Error(message), { code, ...extra }); }
export function contentMetrics(html) {
  const source = String(html || "");
  const text = source.replace(/<script[\s\S]*?<\/script>/gi," ").replace(/<style[\s\S]*?<\/style>/gi," ").replace(/<[^>]+>/g," ").replace(/&nbsp;|&#160;/gi," ").replace(/&[a-z0-9#]+;/gi," ").replace(/\s+/g," ").trim();
  const structure = analyzeArticle(source);
  return {
    ...structure,
    textChars: text.length,
    images: (source.match(/<img\b/gi)||[]).length,
    sample: text.slice(0, 500),
  };
}
export async function testScraperRule({ feedId, entryId, candidate, baselineRule, api }) {
  const current = await api.getFeed(feedId);
  if ((current.scraper_rules || "") !== (baselineRule || "")) throw fail("CONFLICT", "服务器采集规则已被其他页面修改，请重新载入后再生成。");
  const same = (baselineRule || "") === candidate;
  let temporary = false, content, testError;
  try {
    if (!same) { await api.updateFeed(feedId, { scraper_rules: candidate }); temporary = true; }
    content = await api.fetchEntryContent(entryId);
  } catch (error) { testError = error; }
  let rollbackError = null;
  if (temporary) {
    try {
      const latest = await api.getFeed(feedId);
      if ((latest.scraper_rules || "") === candidate) {
        await api.updateFeed(feedId, { scraper_rules: baselineRule || "" });
        const verified = await api.getFeed(feedId);
        if ((verified.scraper_rules || "") !== (baselineRule || "")) throw new Error("verify");
      } else if ((latest.scraper_rules || "") !== (baselineRule || "")) {
        throw fail("ROLLBACK_CONFLICT", "测试期间服务器规则又被修改，为避免覆盖他人的设置，已停止自动回滚。请到 Miniflux 核对当前规则。");
      }
    } catch (error) { rollbackError = error; }
  }
  if (rollbackError) throw fail(rollbackError.code || "ROLLBACK_FAILED", rollbackError.message || "测试规则未能安全回滚，请先到 Miniflux 核对规则。");
  if (testError) throw testError;
  return { content, metrics: contentMetrics(content) };
}
export async function applyScraperRule({ feedId, candidate, baselineRule, api }) {
  const current = await api.getFeed(feedId);
  if ((current.scraper_rules || "") !== (baselineRule || "") && (current.scraper_rules || "") !== candidate) throw fail("CONFLICT", "服务器采集规则已变化，请重新载入，避免覆盖其他修改。");
  if ((current.scraper_rules || "") === candidate) return current;
  const saved = await api.updateFeed(feedId, { scraper_rules: candidate });
  if ((saved?.scraper_rules || "") !== candidate) {
    const verify = await api.getFeed(feedId);
    if ((verify.scraper_rules || "") !== candidate) throw fail("UNCONFIRMED", "服务器没有确认规则已保存，请重新载入核对。");
    return verify;
  }
  return saved;
}
