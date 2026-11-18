import { streamChat } from "@/api/openai.js";
import { measureMessages, validInputPolicy } from "../ai-context.mjs";
import {
  MAX_BATCH_ARTICLES, MAX_TOPICS, PROMPT_BUDGET, batchArticles, batchTopics,
  expandTopics, mapBatchTopics, mergeTopicsMessages, parseTopics, readTopics,
  topicsMessages,
} from "./board.mjs";

const LEGACY_VERSION = 1;
const VERSION = 2;
const MAX_CONTEXT_REDUCTIONS = 6;
const NO_CHECKPOINT = async () => {};
const integer = (value) => Number.isSafeInteger(value) && value >= 0;
const positive = (value) => integer(value) && value > 0;
const copy = (value) => JSON.parse(JSON.stringify(value));

function invalidCheckpoint() {
  const error = new Error("看板生成进度与文章快照不匹配，请重新生成。");
  error.code = "BOARD_CHECKPOINT_INVALID";
  return error;
}

function checkAbort(signal) {
  if (!signal?.aborted) return;
  const error = new Error("看板生成已停止。");
  error.name = "AbortError";
  error.code = "ABORT_ERR";
  throw error;
}

// A transport is expected to honour the signal. Still settle this generator
// promptly when cancelled, and ignore any callbacks from a late transport.
function abortable(request, signal) {
  if (!signal) return Promise.resolve(request);
  return new Promise((resolve, reject) => {
    const onAbort = () => {
      signal.removeEventListener("abort", onAbort);
      try { checkAbort(signal); } catch (error) { reject(error); }
    };
    signal.addEventListener("abort", onAbort, { once: true });
    if (signal.aborted) onAbort();
    Promise.resolve(request).then(resolve, reject).finally(() => signal.removeEventListener("abort", onAbort));
  });
}

function partitions(values, sizes) {
  let offset = 0;
  return sizes.map((size) => {
    const batch = values.slice(offset, offset + size);
    offset += size;
    return batch;
  });
}

function validSizes(sizes, count, maximum = Infinity) {
  return Array.isArray(sizes) && sizes.every((size) => positive(size) && size <= maximum)
    && sizes.reduce((total, size) => total + size, 0) === count;
}

const policyFor = (state) => state.version === VERSION ? state.inputPolicy : undefined;
const fits = (messages, policy) => policy
  ? measureMessages(messages, policy) <= policy.budget
  : messages.reduce((total, message) => total + message.content.length, 0) <= PROMPT_BUDGET;

// A rejected request teaches us a smaller size for this unfinished stage. Keep
// the completed prefix exactly as saved, including its formerly larger batches.
function splitRemaining(sizes, next, maximum) {
  const result = sizes.slice(0, next);
  for (const size of sizes.slice(next)) {
    for (let remaining = size; remaining > 0; remaining -= maximum) result.push(Math.min(remaining, maximum));
  }
  return result;
}

function contextLimitError(reason) {
  const error = new Error(reason === "single"
    ? "AI 服务连单条内容也提示超过上下文限制，请检查 AI 设置里的模型与上下文容量后重试。"
    : reason === "merge" ? "AI 服务允许的上下文过小，无法继续合并主题。已完成的进度已保留，请检查 AI 设置后重试。"
    : "AI 服务仍提示超过上下文限制，已停止自动缩小请求。已完成的进度已保留，请检查 AI 设置后重试。");
  error.code = "CONTEXT_LENGTH_EXCEEDED";
  return error;
}

function validTopics(topics, allowed) {
  if (!Array.isArray(topics)) return false;
  const seen = new Set();
  return topics.every((topic) => {
    if (!topic || typeof topic.title !== "string" || !topic.title || topic.title.length > 40
      || typeof topic.gist !== "string" || topic.gist.length > 80
      || typeof topic.summary !== "string" || topic.summary.length > 400
      || !Array.isArray(topic.articles) || !topic.articles.length) return false;
    return topic.articles.every((number) => {
      if (!positive(number) || !allowed.has(number) || seen.has(number)) return false;
      seen.add(number);
      return true;
    });
  });
}

function restoreCheckpoint(saved, inputIds) {
  let state;
  try { state = copy(saved); } catch { throw invalidCheckpoint(); }
  const sameIds = Array.isArray(state?.inputIds) && state.inputIds.length === inputIds.length
    && state.inputIds.every((id, index) => id === inputIds[index]);
  if (!sameIds || ![LEGACY_VERSION, VERSION].includes(state.version)
    || (state.version === VERSION && !validInputPolicy(state.inputPolicy)) || !Array.isArray(state.articles)
    || state.articles.length !== inputIds.length || typeof state.cut !== "boolean" || typeof state.done !== "boolean"
    || !state.articles.every((article, index) => article?.id === inputIds[index] && article.number === index + 1
      && typeof article.head === "string" && article.head.length <= 163
      && typeof article.excerpt === "string" && article.excerpt.length <= 280)
    || !validSizes(state.batchSizes, inputIds.length, state.version === LEGACY_VERSION ? MAX_BATCH_ARTICLES : Infinity)
    || !integer(state.nextArticleBatch) || state.nextArticleBatch > state.batchSizes.length) throw invalidCheckpoint();
  // Original v1 checkpoints predate context-aware retries. Their plan still
  // uses the old character budget, regardless of the caller's new settings.
  if (state.version === LEGACY_VERSION && state.contextReductions === undefined) state.contextReductions = { articles: 0, merge: 0 };
  if (!state.contextReductions || !["articles", "merge"].every((stage) =>
    integer(state.contextReductions[stage]) && state.contextReductions[stage] <= MAX_CONTEXT_REDUCTIONS)) throw invalidCheckpoint();
  // Older checkpoints have no learned width. Once a service rejects a merge,
  // carry the smaller width through later rounds and interrupted runs too.
  state.mergeBatchLimit ??= null;
  if (state.mergeBatchLimit !== null && !positive(state.mergeBatchLimit)) throw invalidCheckpoint();
  const policy = policyFor(state);
  const batches = partitions(state.articles, state.batchSizes);
  if (batches.some((batch) => !fits(topicsMessages(batch, { brief: true }), policy))) throw invalidCheckpoint();
  const processed = state.batchSizes.slice(0, state.nextArticleBatch).reduce((total, size) => total + size, 0);
  const allowed = new Set(Array.from({ length: processed }, (_, index) => index + 1));
  if (!validTopics(state.topics, allowed) || (!state.topics.length && processed > 0)) throw invalidCheckpoint();
  const articleStageComplete = state.nextArticleBatch === state.batchSizes.length;
  if (state.done) {
    if (!articleStageComplete || state.merge !== null || state.topics.length > MAX_TOPICS) throw invalidCheckpoint();
  } else if (state.merge !== null) {
    const merge = state.merge;
    if (!articleStageComplete || state.batchSizes.length <= 1 || !positive(merge?.round)
      || !validTopics(merge.candidates, allowed) || merge.candidates.length <= 1
      || JSON.stringify(merge.candidates) !== JSON.stringify(state.topics)
      || !validSizes(merge.batchSizes, merge.candidates.length)
      || !integer(merge.nextBatch) || merge.nextBatch >= merge.batchSizes.length) throw invalidCheckpoint();
    if (state.mergeBatchLimit !== null && merge.batchSizes.slice(merge.nextBatch).some((size) => size > state.mergeBatchLimit)) throw invalidCheckpoint();
    if (partitions(merge.candidates, merge.batchSizes).some((batch) => !fits(mergeTopicsMessages(batch, { brief: true }), policy))) throw invalidCheckpoint();
    const finished = merge.batchSizes.slice(0, merge.nextBatch).reduce((total, size) => total + size, 0);
    const mergedSources = new Set(merge.candidates.slice(0, finished).flatMap((topic) => topic.articles));
    if (!validTopics(merge.topics, mergedSources) || (!merge.topics.length && finished > 0)) throw invalidCheckpoint();
  } else if (articleStageComplete) {
    throw invalidCheckpoint();
  }
  return state;
}

function startMerge(state, round) {
  if (state.batchSizes.length <= 1 || state.topics.length <= 1) {
    state.done = true;
    state.merge = null;
    return;
  }
  const sizes = batchTopics(state.topics, policyFor(state)).map((batch) => batch.length);
  state.merge = {
    round, candidates: state.topics,
    batchSizes: state.mergeBatchLimit === null ? sizes : splitRemaining(sizes, 0, state.mergeBatchLimit),
    nextBatch: 0, topics: [],
  };
}

/**
 * Generate one immutable board. A checkpoint stores the prepared input and
 * completed article/merge batches, never a partially streamed reply. Saving is
 * awaited before the next request; a resumed run repeats only the unfinished
 * batch. Progress callbacks retain the existing boardStream display contract.
 */
export async function generateBoard({
  articles: rawSnapshot, signal, onProgress = () => {}, checkpoint = null,
  onCheckpoint = NO_CHECKPOINT, stream = streamChat, inputPolicy,
}) {
  checkAbort(signal);
  if (!Array.isArray(rawSnapshot) || rawSnapshot.some((article) => !positive(article?.id))) throw invalidCheckpoint();
  const inputIds = rawSnapshot.map((article) => article.id);
  if (new Set(inputIds).size !== inputIds.length) throw invalidCheckpoint();
  let state;
  if (checkpoint !== null) state = restoreCheckpoint(checkpoint, inputIds);
  else {
    if (inputPolicy !== undefined && !validInputPolicy(inputPolicy)) throw invalidCheckpoint();
    const policy = inputPolicy === undefined ? undefined : copy(inputPolicy);
    const { articles, batches } = batchArticles(copy(rawSnapshot), policy);
    state = {
      version: policy ? VERSION : LEGACY_VERSION,
      ...(policy ? { inputPolicy: policy } : {}),
      inputIds, articles, batchSizes: batches.map((batch) => batch.length),
      nextArticleBatch: 0, topics: [], cut: false, merge: null, done: !articles.length,
      contextReductions: { articles: 0, merge: 0 },
      mergeBatchLimit: null,
    };
  }
  const save = async () => {
    checkAbort(signal);
    // A caller may retain this value while later batches run. Never mutate a
    // previous saved value or share our in-memory arrays with that caller.
    if (onCheckpoint !== NO_CHECKPOINT) await onCheckpoint(copy(state));
    checkAbort(signal);
  };
  await save();

  const reduceContext = async (stage) => {
    checkAbort(signal);
    const plan = stage === "articles" ? state : state.merge;
    const next = stage === "articles" ? state.nextArticleBatch : plan.nextBatch;
    const size = plan.batchSizes[next];
    if (size <= 1 || state.contextReductions[stage] >= MAX_CONTEXT_REDUCTIONS) throw contextLimitError(size <= 1 ? "single" : "retries");
    const maximum = Math.ceil(size / 2);
    if (stage === "merge") state.mergeBatchLimit = Math.min(state.mergeBatchLimit ?? maximum, maximum);
    plan.batchSizes = splitRemaining(plan.batchSizes, next, stage === "merge" ? state.mergeBatchLimit : maximum);
    state.contextReductions[stage] += 1;
    // A failed or uncertain write must stop this run before another paid call.
    await save();
  };

  let activeAsk = null;
  const ask = async (items, messages, progress, first = null) => {
    checkAbort(signal);
    const request = {};
    activeAsk = request;
    let text = "";
    let thinking = "";
    let frame = null;
    let accepting = true;
    const sent = items.map(({ title, feedTitle }) => ({ title, feedTitle }));
    const publish = () => {
      frame = null;
      if (!signal?.aborted && activeAsk === request) onProgress({ sent, skipped: 0, ...progress, thinking, text, first });
    };
    const later = () => { frame ??= setTimeout(publish, 80); };
    publish();
    let read = null;
    let length = false;
    try {
      checkAbort(signal);
      const result = await abortable(stream(messages(items, { brief: Boolean(first) }), {
        signal,
        onChunk: (chunk) => { if (accepting && !signal?.aborted) { text += chunk; later(); } },
        onThink: (chunk) => { if (accepting && !signal?.aborted) { thinking += chunk; later(); } },
      }), signal);
      checkAbort(signal);
      length = result?.finishReason === "length";
    } catch (error) {
      checkAbort(signal);
      if (error?.code === "INCOMPLETE_STREAM" && text) {
        try { read = { topics: parseTopics(text, items.length), cut: true }; } catch { /* no complete topic */ }
      }
      if (!read) {
        if ((text || thinking) && error && typeof error === "object") error.raw ??= { thinking, text };
        throw error;
      }
    } finally {
      accepting = false;
      clearTimeout(frame);
      publish();
      if (activeAsk === request) activeAsk = null;
    }
    checkAbort(signal);
    if (read) return read;
    try {
      const { topics, whole } = readTopics(text, items.length);
      return { topics, cut: length && !whole };
    } catch (error) {
      error.raw = { thinking, text };
      if (length) {
        error.length = true;
        error.message = "AI 的回答太长，在写完第一个主题之前就被截断了。请重新生成；如果一直这样，可以在 AI 设置里换一个输出长度上限更高的模型。";
      }
      throw error;
    }
  };
  const readBatch = async (items, messages, progress) => {
    try { return await ask(items, messages, progress); }
    catch (error) {
      checkAbort(signal);
      if (!error?.length) throw error;
      const first = { text: error.raw?.text || "", thinking: error.raw?.thinking || "" };
      try { return await ask(items, messages, progress, first); }
      catch (again) {
        if (again && typeof again === "object") again.raw = { thinking: "", text: "", ...again.raw, first };
        throw again;
      }
    }
  };

  let processed = state.batchSizes.slice(0, state.nextArticleBatch).reduce((total, size) => total + size, 0);
  while (state.nextArticleBatch < state.batchSizes.length) {
    const batch = state.articles.slice(processed, processed + state.batchSizes[state.nextArticleBatch]);
    let read;
    try {
      read = await readBatch(batch, topicsMessages, {
        stage: "articles", batchIndex: state.nextArticleBatch + 1, batchCount: state.batchSizes.length,
        processed, total: state.articles.length,
      });
    } catch (error) {
      checkAbort(signal);
      if (error?.code !== "CONTEXT_LENGTH_EXCEEDED") throw error;
      await reduceContext("articles");
      continue;
    }
    checkAbort(signal);
    state.topics.push(...mapBatchTopics(read.topics, batch));
    state.cut ||= read.cut;
    state.nextArticleBatch += 1;
    processed += batch.length;
    if (state.nextArticleBatch === state.batchSizes.length) startMerge(state, 1);
    await save();
  }
  while (!state.done) {
    const merge = state.merge;
    const groups = partitions(merge.candidates, merge.batchSizes);
    const candidates = groups[merge.nextBatch];
    if (groups.length > 1 && candidates.length === 1) merge.topics.push(...candidates);
    else {
      let read;
      try {
        read = await readBatch(candidates, mergeTopicsMessages, {
          stage: "merge", batchIndex: merge.nextBatch + 1, batchCount: groups.length,
          round: merge.round, processed, total: state.articles.length,
        });
      } catch (error) {
        checkAbort(signal);
        if (error?.code !== "CONTEXT_LENGTH_EXCEEDED") throw error;
        await reduceContext("merge");
        continue;
      }
      checkAbort(signal);
      merge.topics.push(...expandTopics(read.topics, candidates));
      state.cut ||= read.cut;
    }
    merge.nextBatch += 1;
    if (merge.nextBatch === groups.length) {
      if (groups.length > 1 && merge.topics.length >= merge.candidates.length) {
        if (state.contextReductions.merge && merge.batchSizes.every((size) => size === 1)) throw contextLimitError("merge");
        throw new Error("AI 主题汇总未能收敛，请重新生成。");
      }
      state.topics = merge.topics;
      if (groups.length === 1) { state.done = true; state.merge = null; }
      else startMerge(state, merge.round + 1);
    }
    await save();
  }
  checkAbort(signal);
  return { articles: state.articles, topics: state.topics, cut: state.cut, total: state.articles.length, skipped: 0 };
}
