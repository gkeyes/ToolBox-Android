import { streamChat } from "@/api/openai.js";
import {
  MAX_BATCH_ARTICLES, MAX_TOPICS, PROMPT_BUDGET, batchArticles, batchTopics,
  expandTopics, mapBatchTopics, mergeTopicsMessages, parseTopics, readTopics,
  topicsMessages,
} from "./board.mjs";

const VERSION = 1;
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
  if (!sameIds || state.version !== VERSION || !Array.isArray(state.articles)
    || state.articles.length !== inputIds.length || typeof state.cut !== "boolean" || typeof state.done !== "boolean"
    || !state.articles.every((article, index) => article?.id === inputIds[index] && article.number === index + 1
      && typeof article.head === "string" && article.head.length <= 163
      && typeof article.excerpt === "string" && article.excerpt.length <= 280)
    || !validSizes(state.batchSizes, inputIds.length, MAX_BATCH_ARTICLES)
    || !integer(state.nextArticleBatch) || state.nextArticleBatch > state.batchSizes.length) throw invalidCheckpoint();
  const batches = partitions(state.articles, state.batchSizes);
  if (batches.some((batch) => topicsMessages(batch, { brief: true })
    .reduce((total, message) => total + message.content.length, 0) > PROMPT_BUDGET)) throw invalidCheckpoint();
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
      || JSON.stringify(merge.batchSizes) !== JSON.stringify(batchTopics(merge.candidates).map((batch) => batch.length))
      || !integer(merge.nextBatch) || merge.nextBatch >= merge.batchSizes.length) throw invalidCheckpoint();
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
  state.merge = {
    round, candidates: state.topics, batchSizes: batchTopics(state.topics).map((batch) => batch.length),
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
  onCheckpoint = NO_CHECKPOINT, stream = streamChat,
}) {
  checkAbort(signal);
  if (!Array.isArray(rawSnapshot) || rawSnapshot.some((article) => !positive(article?.id))) throw invalidCheckpoint();
  const inputIds = rawSnapshot.map((article) => article.id);
  if (new Set(inputIds).size !== inputIds.length) throw invalidCheckpoint();
  let state;
  if (checkpoint !== null) state = restoreCheckpoint(checkpoint, inputIds);
  else {
    const { articles, batches } = batchArticles(copy(rawSnapshot));
    state = {
      version: VERSION, inputIds, articles, batchSizes: batches.map((batch) => batch.length),
      nextArticleBatch: 0, topics: [], cut: false, merge: null, done: !articles.length,
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

  const batches = partitions(state.articles, state.batchSizes);
  let processed = state.batchSizes.slice(0, state.nextArticleBatch).reduce((total, size) => total + size, 0);
  while (state.nextArticleBatch < batches.length) {
    const batch = batches[state.nextArticleBatch];
    const read = await readBatch(batch, topicsMessages, {
      stage: "articles", batchIndex: state.nextArticleBatch + 1, batchCount: batches.length,
      processed, total: state.articles.length,
    });
    checkAbort(signal);
    state.topics.push(...mapBatchTopics(read.topics, batch));
    state.cut ||= read.cut;
    state.nextArticleBatch += 1;
    processed += batch.length;
    if (state.nextArticleBatch === batches.length) startMerge(state, 1);
    await save();
  }
  while (!state.done) {
    const merge = state.merge;
    const groups = partitions(merge.candidates, merge.batchSizes);
    const candidates = groups[merge.nextBatch];
    if (groups.length > 1 && candidates.length === 1) merge.topics.push(...candidates);
    else {
      const read = await readBatch(candidates, mergeTopicsMessages, {
        stage: "merge", batchIndex: merge.nextBatch + 1, batchCount: groups.length,
        round: merge.round, processed, total: state.articles.length,
      });
      checkAbort(signal);
      merge.topics.push(...expandTopics(read.topics, candidates));
      state.cut ||= read.cut;
    }
    merge.nextBatch += 1;
    if (merge.nextBatch === groups.length) {
      if (groups.length > 1 && merge.topics.length >= merge.candidates.length) throw new Error("AI 主题汇总未能收敛，请重新生成。");
      state.topics = merge.topics;
      if (groups.length === 1) { state.done = true; state.merge = null; }
      else startMerge(state, merge.round + 1);
    }
    await save();
  }
  checkAbort(signal);
  return { articles: state.articles, topics: state.topics, cut: state.cut, total: state.articles.length, skipped: 0 };
}
