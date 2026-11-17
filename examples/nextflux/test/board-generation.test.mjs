import assert from "node:assert/strict";
import test from "node:test";
import { register } from "node:module";

// The generator accepts its transport explicitly. Resolve the production
// default without mounting settings, account state or a browser in these tests.
register("data:text/javascript," + encodeURIComponent(`
  export async function resolve(specifier, context, next) {
    if (specifier === "@/api/openai.js") return {
      url: "data:text/javascript," + encodeURIComponent("export const streamChat = (...args) => globalThis.__boardGenerationDefault(...args)"),
      shortCircuit: true,
    };
    return next(specifier, context);
  }
`));
const { generateBoard } = await import("../src/toolbox/board/generate-board.js");
const { boardArticleIds, PROMPT_BUDGET } = await import("../src/toolbox/board/board.mjs");
const copy = (value) => JSON.parse(JSON.stringify(value));
const rows = (count) => Array.from({ length: count }, (_, index) => ({
  id: count - index, title: `文章 ${count - index}`, feedTitle: "源", text: `冻结的原文 ${count - index}`,
}));
const refs = (messages) => [...messages[1].content.matchAll(/^\[(\d+)\] /gm)].map((match) => Number(match[1]));
const kind = (messages) => messages[0].content.includes("候选主题") ? "merge" : "articles";
const answer = (options, numbers, title = "主题") => {
  options.onChunk(JSON.stringify({ topics: [{ title, gist: "重点", summary: "摘要", articles: numbers }] }));
  return { finishReason: "stop" };
};
const allSources = async (messages, options) => answer(options, refs(messages));

test("input is durably frozen before any AI request, and saves are awaited between batches", async () => {
  const input = rows(181);
  const events = [];
  const snapshots = [];
  const result = await generateBoard({
    articles: input,
    onCheckpoint: async (value) => {
      const label = `${value.nextArticleBatch}:${value.merge?.nextBatch ?? "-"}:${value.done}`;
      events.push(`saving:${label}`);
      await new Promise((resolve) => setTimeout(resolve, 1));
      snapshots.push(value);
      events.push(`saved:${label}`);
      if (snapshots.length === 1) input[0].text = "外部改动不能替换冻结输入";
    },
    stream: async (messages, options) => {
      assert.match(events.at(-1), /^saved:/);
      assert.ok(messages.reduce((sum, item) => sum + item.content.length, 0) <= PROMPT_BUDGET);
      events.push(`request:${kind(messages)}`);
      assert.doesNotMatch(messages[1].content, /外部改动/);
      return allSources(messages, options);
    },
  });
  assert.equal(snapshots.length, 4, "initial freeze, two article batches, final merge");
  assert.equal(snapshots[0].nextArticleBatch, 0);
  assert.deepEqual(snapshots[0].topics, [], "later batches do not mutate earlier saved values");
  assert.equal(snapshots.at(-1).done, true);
  assert.equal(result.articles[0].text, "冻结的原文 181");
  assert.equal(result.articles.at(-1).number, 181);
  assert.equal(result.total, 181);
  assert.equal(result.skipped, 0);
  assert.equal(boardArticleIds(result.topics, result.articles).length, 181);
});

test("a resumed article stage skips every saved batch and keeps the frozen source text", async () => {
  const input = rows(451);
  let saved;
  let request = 0;
  await assert.rejects(generateBoard({
    articles: input,
    onCheckpoint: async (value) => { saved = value; },
    stream: async (messages, options) => {
      request += 1;
      if (request === 3) throw new Error("第三批网络中断");
      return allSources(messages, options);
    },
  }), /第三批网络中断/);
  assert.equal(saved.nextArticleBatch, 2);
  assert.equal(saved.done, false);
  const sent = [];
  const result = await generateBoard({
    articles: input.map((article) => ({ ...article, text: "现有缓存已经改变" })),
    checkpoint: copy(saved),
    stream: async (messages, options) => {
      if (kind(messages) === "articles") sent.push(...[...messages[1].content.matchAll(/^\[\d+\] 源 · 文章 (\d+)$/gm)].map((match) => Number(match[1])));
      assert.doesNotMatch(messages[1].content, /现有缓存已经改变/);
      return allSources(messages, options);
    },
  });
  assert.deepEqual(sent, Array.from({ length: 151 }, (_, index) => 151 - index));
  assert.equal(boardArticleIds(result.topics, result.articles).length, 451);
  assert.equal(result.articles[0].excerpt, "冻结的原文 451");
});

test("multi-level merge resumes at its next saved group without repeating source or merge requests", async () => {
  const input = rows(1201);
  const checkpoints = [];
  let saved;
  let progress;
  const completedRequests = [];
  const stream = async (messages, options) => {
    const position = `${progress.stage}:${progress.round || 0}:${progress.batchIndex}`;
    if (kind(messages) === "articles") {
      const numbers = refs(messages);
      const picked = [...new Set([1, 2, 3, 4, 5, 6, numbers.at(-1)].filter((n) => n <= numbers.length))];
      options.onChunk(JSON.stringify({ topics: picked.map((number, index) => ({
        title: `候选 ${progress.batchIndex}-${index}`, gist: "要点".repeat(40), summary: "摘要".repeat(200), articles: [number],
      })) }));
      completedRequests.push(position);
      return { finishReason: "stop" };
    }
    if (progress.round === 1 && progress.batchIndex === 2) throw new Error("第二个汇总批次断网");
    completedRequests.push(position);
    return allSources(messages, options);
  };
  await assert.rejects(generateBoard({
    articles: input, onProgress: (value) => { progress = value; },
    onCheckpoint: async (value) => { saved = value; checkpoints.push(value); }, stream,
  }), /第二个汇总批次断网/);
  assert.equal(saved.nextArticleBatch, saved.batchSizes.length);
  assert.equal(saved.merge.round, 1);
  assert.equal(saved.merge.nextBatch, 1);
  const resumed = [];
  const result = await generateBoard({
    articles: input, checkpoint: copy(saved),
    onProgress: (value) => { progress = value; },
    onCheckpoint: async (value) => { saved = value; },
    stream: async (messages, options) => {
      const position = `${progress.stage}:${progress.round || 0}:${progress.batchIndex}`;
      assert.ok(!completedRequests.includes(position), `saved request repeated: ${position}`);
      resumed.push(position);
      return allSources(messages, options);
    },
  });
  assert.equal(resumed[0], "merge:1:2");
  assert.ok(resumed.some((value) => value.startsWith("merge:2:")));
  assert.ok(boardArticleIds(result.topics, result.articles).includes(1), "last source survives both levels");
  assert.equal(saved.done, true);
  const completed = await generateBoard({
    articles: input, checkpoint: copy(saved),
    stream: () => { throw new Error("completed checkpoint must not call AI"); },
  });
  assert.deepEqual(completed, result);
  assert.equal(checkpoints[0].nextArticleBatch, 0);
});

test("checkpoint validation rejects changed IDs, versions and corrupt numbering before calling AI", async () => {
  const input = rows(181);
  let saved;
  await assert.rejects(generateBoard({
    articles: input, onCheckpoint: async (value) => { saved = value; },
    stream: () => { throw new Error("offline"); },
  }), /offline/);
  const cases = [
    { articles: [...input].reverse(), checkpoint: saved },
    { articles: input.slice(1), checkpoint: saved },
    { articles: input, checkpoint: { ...saved, version: 2 } },
    { articles: input, checkpoint: { ...saved, nextArticleBatch: 20 } },
    { articles: input, checkpoint: { ...saved, articles: saved.articles.map((a, index) => index ? a : { ...a, number: 5 }) } },
    { articles: input, checkpoint: { ...saved, batchSizes: [181] } },
    { articles: input, checkpoint: { ...saved, topics: [{ title: "假主题", gist: "", summary: "", articles: [1] }] } },
  ];
  for (const options of cases) {
    await assert.rejects(generateBoard({
      ...options, stream: () => { assert.fail("invalid checkpoint reached AI"); },
    }), { code: "BOARD_CHECKPOINT_INVALID" });
  }
});

test("a failed checkpoint write stops later requests and a previous checkpoint remains resumable", async () => {
  const input = rows(181);
  let saved;
  let saves = 0;
  let requests = 0;
  await assert.rejects(generateBoard({
    articles: input,
    onCheckpoint: async (value) => { if (++saves === 2) throw new Error("存储失败"); saved = value; },
    stream: async (messages, options) => { requests += 1; return allSources(messages, options); },
  }), /存储失败/);
  assert.equal(requests, 1);
  assert.equal(saved.nextArticleBatch, 0);
  await assert.rejects(generateBoard({
    articles: input, onCheckpoint: async () => { throw new Error("首次冻结失败"); },
    stream: () => { assert.fail("request sent before durable freeze"); },
  }), /首次冻结失败/);
  let first;
  await generateBoard({
    articles: input, checkpoint: saved,
    onProgress: (value) => { first ??= value; }, stream: allSources,
  });
  assert.equal(first.batchIndex, 1, "only the unsaved batch is repeated");
});

test("abort prevents new requests, late progress and checkpoint writes even when transport ignores its signal", async () => {
  const controller = new AbortController();
  let options;
  let release;
  const progress = [];
  const checkpoints = [];
  let entered;
  const waiting = new Promise((resolve) => { entered = resolve; });
  const pending = generateBoard({
    articles: rows(301), signal: controller.signal,
    onProgress: (value) => progress.push(value),
    onCheckpoint: async (value) => checkpoints.push(value),
    stream: async (_messages, callbacks) => {
      options = callbacks;
      entered();
      return new Promise((resolve) => { release = resolve; });
    },
  });
  await waiting;
  controller.abort();
  await assert.rejects(pending, { name: "AbortError" });
  const publications = progress.length;
  options.onThink("late thinking");
  options.onChunk('{"topics":[{"title":"迟到主题","articles":[1]}]}');
  release({ finishReason: "stop" });
  await new Promise((resolve) => setTimeout(resolve, 100));
  assert.equal(progress.length, publications);
  assert.equal(checkpoints.length, 1);
  assert.equal(checkpoints[0].nextArticleBatch, 0);
});

test("aborting after a completed batch is saved resumes without repeating that batch", async () => {
  const controller = new AbortController();
  let saved;
  await assert.rejects(generateBoard({
    articles: rows(181), signal: controller.signal,
    onCheckpoint: async (value) => { saved = value; if (value.nextArticleBatch === 1) controller.abort(); },
    stream: allSources,
  }), { name: "AbortError" });
  assert.equal(saved.nextArticleBatch, 1);
  let first;
  await generateBoard({ articles: rows(181), checkpoint: saved, onProgress: (value) => { first ??= value; }, stream: allSources });
  assert.equal(first.stage, "articles");
  assert.equal(first.batchIndex, 2);
  assert.equal(first.processed, 150);
});

test("a too-long batch retries once with its first response retained in progress and errors", async () => {
  let request = 0;
  const progress = [];
  const result = await generateBoard({
    articles: rows(2), onProgress: (value) => progress.push(value),
    stream: async (messages, options) => {
      request += 1;
      if (request === 1) { options.onThink("第一次思考"); options.onChunk('{"topics":['); return { finishReason: "length" }; }
      assert.match(messages[0].content, /这次回答要短/);
      return answer(options, [1]);
    },
  });
  assert.equal(request, 2);
  assert.equal(result.cut, false);
  assert.deepEqual(progress.find((value) => value.first)?.first, { thinking: "第一次思考", text: '{"topics":[' });
  request = 0;
  await assert.rejects(generateBoard({
    articles: rows(2),
    stream: async (_messages, options) => { options.onChunk(`{"topics":[{"title":"第${++request}次`); return { finishReason: "length" }; },
  }), (error) => {
    assert.equal(request, 2);
    assert.equal(error.length, true);
    assert.match(error.raw.first.text, /第1次/);
    assert.match(error.raw.text, /第2次/);
    return true;
  });
});

test("stream interruption keeps complete topics and the cut flag survives a saved resume", async () => {
  let saved;
  const result = await generateBoard({
    articles: rows(181), onCheckpoint: async (value) => { saved = value; },
    stream: async (messages, options) => {
      if (kind(messages) === "articles" && refs(messages).length === 150) {
        options.onChunk('{"topics":[{"title":"甲","gist":"g","summary":"s","articles":[1]},{"title":"乙","articles":[2');
        throw Object.assign(new Error("流中断"), { code: "INCOMPLETE_STREAM" });
      }
      return allSources(messages, options);
    },
  });
  assert.equal(result.cut, true);
  const restored = await generateBoard({ articles: rows(181), checkpoint: saved, stream: () => assert.fail("done checkpoint calls AI") });
  assert.equal(restored.cut, true);
  assert.deepEqual(restored.topics, result.topics);
});

test("progress is throttled during a stream and includes final text plus all existing display fields", async () => {
  const progress = [];
  const result = await generateBoard({
    articles: rows(1), onProgress: (value) => progress.push(value),
    stream: async (_messages, options) => {
      options.onThink("思"); options.onThink("考");
      options.onChunk('{"topics":');
      await new Promise((resolve) => setTimeout(resolve, 110));
      options.onChunk('[{"title":"主题","articles":[1]}]}');
    },
  });
  assert.equal(result.topics.length, 1);
  assert.equal(progress.length, 3, "initial, one scheduled update and final flush");
  assert.equal(progress[1].thinking, "思考");
  assert.equal(progress[1].text, '{"topics":');
  assert.equal(progress.at(-1).text, '{"topics":[{"title":"主题","articles":[1]}]}');
  assert.deepEqual(Object.keys(progress[0]).sort(), ["batchCount", "batchIndex", "first", "processed", "sent", "skipped", "stage", "text", "thinking", "total"].sort());
});

test("empty snapshots complete without AI and the production transport default remains usable", async () => {
  let saved;
  const empty = await generateBoard({ articles: [], onCheckpoint: async (value) => { saved = value; }, stream: () => assert.fail("empty request") });
  assert.deepEqual(empty, { articles: [], topics: [], cut: false, total: 0, skipped: 0 });
  assert.equal(saved.done, true);
  assert.deepEqual(await generateBoard({ articles: [], checkpoint: saved, stream: () => assert.fail("empty resume") }), empty);
  globalThis.__boardGenerationDefault = allSources;
  assert.equal((await generateBoard({ articles: rows(1) })).total, 1);
  delete globalThis.__boardGenerationDefault;
});
