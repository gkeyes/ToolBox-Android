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
// Puts the baseline back if the server still holds the candidate. A rule that
// someone else changed meanwhile is left alone.
async function restoreBaseline({ feedId, candidate, baselineRule, api }) {
  const latest = await api.getFeed(feedId);
  if ((latest.scraper_rules || "") === candidate) {
    await api.updateFeed(feedId, { scraper_rules: baselineRule || "" });
    const verified = await api.getFeed(feedId);
    if ((verified.scraper_rules || "") !== (baselineRule || "")) throw new Error("verify");
  } else if ((latest.scraper_rules || "") !== (baselineRule || "")) {
    throw fail("ROLLBACK_CONFLICT", "测试期间服务器规则又被修改，为避免覆盖他人的设置，已停止自动回滚。请到 Miniflux 核对当前规则。");
  }
}

const PENDING_MESSAGE = "上次测试的规则还没恢复，请检查网络后重新打开全文适配。";

// `journal` keeps a rollback that must still happen. It is saved before the
// candidate reaches the server and cleared once the baseline is back, so a
// test cut short (back, Home, a killed task) is rolled back on the next start.
// The host refuses network requests once it starts closing, so the rollback
// cannot run on the way out; the journal is what carries it.
export async function testScraperRule({ feedId, entryId, candidate, baselineRule, api, journal }) {
  // An unreplayed rollback means the server still holds an old candidate:
  // testing on top of it would record that candidate as the baseline.
  if (journal && (await journal.list()).some((entry) => entry.feedId === Number(feedId))) throw fail("ROLLBACK_PENDING", PENDING_MESSAGE);
  const current = await api.getFeed(feedId);
  if ((current.scraper_rules || "") !== (baselineRule || "")) throw fail("CONFLICT", "服务器采集规则已被其他页面修改，请重新载入后再生成。");
  const same = (baselineRule || "") === candidate;
  let temporary = false, content, testError;
  try {
    if (!same) {
      await journal?.save({ feedId, candidate, baselineRule: baselineRule || "" });
      await api.updateFeed(feedId, { scraper_rules: candidate });
      temporary = true;
    }
    content = await api.fetchEntryContent(entryId);
  } catch (error) { testError = error; }
  let rollbackError = null;
  if (temporary) {
    try { await restoreBaseline({ feedId, candidate, baselineRule, api }); }
    catch (error) { rollbackError = error; }
  }
  // A conflict means someone else owns the rule now: nothing left to replay.
  if (!same && (!rollbackError || rollbackError.code === "ROLLBACK_CONFLICT")) await journal?.clear(feedId).catch(() => {});
  if (rollbackError) throw fail(rollbackError.code || "ROLLBACK_FAILED", rollbackError.message || "测试规则未能安全回滚，请先到 Miniflux 核对规则。");
  if (testError) throw testError;
  return { content, metrics: contentMetrics(content) };
}

// Replays rollbacks a cut-short test left behind. A rollback that fails on the
// network stays for the next try; one the server no longer needs is dropped.
// Returns the rollbacks still owed.
export async function replayScraperRollbacks({ api, journal }) {
  const pending = await journal.list();
  for (const entry of pending) {
    try {
      await restoreBaseline({ ...entry, api });
      await journal.clear(entry.feedId);
    } catch (error) {
      if (error?.code === "ROLLBACK_CONFLICT" || error?.response?.status === 404) await journal.clear(entry.feedId).catch(() => {});
    }
  }
  return journal.list();
}

export function assertNoPendingRollback(pending, feedId) {
  if (pending.some((entry) => entry.feedId === Number(feedId))) throw fail("ROLLBACK_PENDING", PENDING_MESSAGE);
}
export async function applyScraperRule({ feedId, candidate, baselineRule, api, journal }) {
  // An older cut-short test of this feed must not undo the rule saved here.
  await journal?.clear(feedId);
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
