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
const largePolicy = { unit: "utf8", budget: 1000000 };
const contextFailure = () => Object.assign(new Error("model input is too long"), { code: "CONTEXT_LENGTH_EXCEEDED" });

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
  // The installed v1 generator did not yet store retry counters. A larger
  // current setting must not reinterpret or repeat that original saved plan.
  delete saved.contextReductions;
  delete saved.mergeBatchLimit;
  const sent = [];
  const result = await generateBoard({
    articles: input.map((article) => ({ ...article, text: "现有缓存已经改变" })),
    checkpoint: copy(saved), inputPolicy: largePolicy,
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

for (const count of [168, 1000]) {
  test(`${count} articles use one analysis and no merge when they fit the selected long context`, async () => {
    const input = rows(count).map((article) => ({ ...article, text: "新闻原文预览".repeat(20) }));
    const calls = [];
    let saved;
    const result = await generateBoard({
      articles: input, inputPolicy: largePolicy,
      onCheckpoint: async (value) => { saved = value; },
      stream: async (messages, options) => {
        calls.push(kind(messages));
        assert.equal(refs(messages).length, count);
        return allSources(messages, options);
      },
    });
    assert.deepEqual(calls, ["articles"]);
    assert.equal(saved.version, 2);
    assert.deepEqual(saved.inputPolicy, largePolicy);
    assert.deepEqual(saved.batchSizes, [count]);
    assert.equal(saved.done, true);
    assert.deepEqual(boardArticleIds(result.topics, result.articles), input.map((article) => article.id));
    assert.equal(result.articles.at(-1).excerpt, input.at(-1).text);
  });
}

test("v2 resume keeps its frozen input policy and prepared text when current settings change", async () => {
  const input = rows(451);
  const policy = { unit: "characters", budget: 4096 };
  let saved;
  await assert.rejects(generateBoard({
    articles: input, inputPolicy: policy,
    onCheckpoint: async (value) => { saved = value; },
    stream: () => { throw new Error("offline before first batch"); },
  }), /offline/);
  const planned = [...saved.batchSizes];
  assert.ok(planned.length > 1);
  policy.budget = 1000000;
  const sent = [];
  const result = await generateBoard({
    articles: input.map((article) => ({ ...article, text: "新的缓存预览" })),
    checkpoint: saved, inputPolicy: largePolicy,
    stream: async (messages, options) => {
      if (kind(messages) === "articles") sent.push(refs(messages).length);
      assert.doesNotMatch(messages[1].content, /新的缓存预览/);
      return allSources(messages, options);
    },
  });
  assert.deepEqual(saved.inputPolicy, { unit: "characters", budget: 4096 });
  assert.deepEqual(sent, planned);
  assert.equal(boardArticleIds(result.topics, result.articles).length, input.length);
});

test("explicit context errors shrink the unfinished plan and durably save it before retrying", async () => {
  const events = [];
  const sizes = [];
  const snapshots = [];
  const result = await generateBoard({
    articles: rows(168), inputPolicy: largePolicy,
    onCheckpoint: async (value) => {
      events.push("saving");
      await Promise.resolve();
      snapshots.push(value);
      events.push("saved");
    },
    stream: async (messages, options) => {
      assert.equal(events.at(-1), "saved");
      events.push("request");
      if (kind(messages) === "articles") {
        const size = refs(messages).length;
        sizes.push(size);
        if (size > 50) throw contextFailure();
      }
      return allSources(messages, options);
    },
  });
  assert.deepEqual(sizes, [168, 84, 42, 42, 42, 42]);
  assert.deepEqual(snapshots.slice(0, 3).map((state) => state.batchSizes), [[168], [84, 84], [42, 42, 42, 42]]);
  assert.ok(snapshots.every((state) => state.inputPolicy.budget === largePolicy.budget));
  assert.equal(snapshots.at(-1).contextReductions.articles, 2);
  assert.equal(boardArticleIds(result.topics, result.articles).length, 168);
});

test("a failed context-plan save stops before retrying and leaves the previous saved plan intact", async () => {
  let saved;
  let calls = 0;
  await assert.rejects(generateBoard({
    articles: rows(168), inputPolicy: largePolicy,
    onCheckpoint: async (value) => {
      if (value.contextReductions.articles) throw new Error("缩小计划保存失败");
      saved = value;
    },
    stream: async () => { calls += 1; throw contextFailure(); },
  }), /缩小计划保存失败/);
  assert.equal(calls, 1);
  assert.deepEqual(saved.batchSizes, [168]);
  assert.equal(saved.contextReductions.articles, 0);
  const result = await generateBoard({ articles: rows(168), checkpoint: saved, stream: allSources });
  assert.equal(result.total, 168);
});

test("a context downgrade after a completed batch resumes its smaller tail without repeating the large prefix", async () => {
  const input = rows(1000);
  const controller = new AbortController();
  let saved;
  let originalPlan;
  let requests = 0;
  await assert.rejects(generateBoard({
    articles: input, inputPolicy: { unit: "characters", budget: 4096 }, signal: controller.signal,
    onCheckpoint: async (value) => {
      saved = value;
      originalPlan ??= [...value.batchSizes];
      if (value.contextReductions.articles === 1) controller.abort();
    },
    stream: async (messages, options) => {
      if (++requests === 2) throw contextFailure();
      return allSources(messages, options);
    },
  }), { name: "AbortError" });
  assert.ok(originalPlan.length > 2);
  assert.equal(saved.nextArticleBatch, 1);
  assert.equal(saved.batchSizes[0], originalPlan[0]);
  assert.ok(saved.batchSizes[1] < originalPlan[1]);
  const finished = new Set(input.slice(0, originalPlan[0]).map((article) => article.id));
  const seen = [];
  const result = await generateBoard({
    articles: input, checkpoint: saved, inputPolicy: largePolicy,
    stream: async (messages, options) => {
      if (kind(messages) === "articles") {
        const ids = [...messages[1].content.matchAll(/^\[\d+\] 源 · 文章 (\d+)$/gm)].map((match) => Number(match[1]));
        assert.ok(ids.every((id) => !finished.has(id)), "saved prefix was sent again");
        seen.push(...ids);
      }
      return allSources(messages, options);
    },
  });
  assert.deepEqual(seen, input.slice(originalPlan[0]).map((article) => article.id));
  assert.equal(boardArticleIds(result.topics, result.articles).length, input.length);
});

test("context retries are bounded per stage and the limit survives a resume", async () => {
  let saved;
  let calls = 0;
  await assert.rejects(generateBoard({
    articles: rows(512), inputPolicy: largePolicy,
    onCheckpoint: async (value) => { saved = value; },
    stream: async () => { calls += 1; throw contextFailure(); },
  }), (error) => error.code === "CONTEXT_LENGTH_EXCEEDED" && /已停止自动缩小请求/.test(error.message));
  assert.equal(calls, 7, "one initial attempt and six saved reductions");
  assert.equal(saved.contextReductions.articles, 6);
  calls = 0;
  await assert.rejects(generateBoard({
    articles: rows(512), checkpoint: saved,
    stream: async () => { calls += 1; throw contextFailure(); },
  }), { code: "CONTEXT_LENGTH_EXCEEDED" });
  assert.equal(calls, 1, "resuming must not reset the automatic retry allowance");
  calls = 0;
  await assert.rejects(generateBoard({
    articles: rows(1), inputPolicy: largePolicy,
    stream: async () => { calls += 1; throw contextFailure(); },
  }), (error) => error.code === "CONTEXT_LENGTH_EXCEEDED" && /单条内容/.test(error.message));
  assert.equal(calls, 1);
});

test("a saved smaller merge plan resumes its next group without repeating source analysis or completed groups", async () => {
  const input = rows(1000);
  const controller = new AbortController();
  let saved;
  let rejected = false;
  let originalMergeSize;
  await assert.rejects(generateBoard({
    articles: input, inputPolicy: { unit: "characters", budget: 4096 }, signal: controller.signal,
    onCheckpoint: async (value) => {
      saved = value;
      if (value.contextReductions.merge === 1 && value.merge?.nextBatch === 1) controller.abort();
    },
    stream: async (messages, options) => {
      if (kind(messages) === "merge" && !rejected) {
        rejected = true;
        originalMergeSize = refs(messages).length;
        throw contextFailure();
      }
      return allSources(messages, options);
    },
  }), { name: "AbortError" });
  assert.equal(saved.contextReductions.merge, 1);
  assert.equal(saved.merge.round, 1);
  assert.equal(saved.merge.nextBatch, 1);
  assert.equal(saved.merge.batchSizes.reduce((sum, size) => sum + size, 0), originalMergeSize);
  const expectedFirstSize = saved.merge.batchSizes[1];
  const calls = [];
  const result = await generateBoard({
    articles: input, checkpoint: saved, inputPolicy: largePolicy,
    stream: async (messages, options) => {
      assert.equal(kind(messages), "merge", "source analysis was already saved");
      calls.push(refs(messages).length);
      return allSources(messages, options);
    },
  });
  assert.equal(calls[0], expectedFirstSize);
  assert.equal(boardArticleIds(result.topics, result.articles).length, input.length);
});

test("a learned merge width survives recovery and every later round without exhausting retries again", async () => {
  const input = rows(4096);
  const controller = new AbortController();
  let saved;
  const rejected = { articles: 0, merge: 0 };
  const sevenTopics = (messages, options) => {
    const numbers = refs(messages);
    const groups = Array.from({ length: Math.min(7, numbers.length) }, (_, index) => ({
      title: `主题 ${index + 1}`, gist: "重点", summary: "摘要", articles: [],
    }));
    numbers.forEach((number, index) => groups[index % groups.length].articles.push(number));
    options.onChunk(JSON.stringify({ topics: groups }));
    return { finishReason: "stop" };
  };
  await assert.rejects(generateBoard({
    articles: input, inputPolicy: largePolicy, signal: controller.signal,
    onCheckpoint: async (value) => {
      saved = value;
      if (value.merge?.round === 2) controller.abort();
    },
    stream: async (messages, options) => {
      const stage = kind(messages);
      if (refs(messages).length > (stage === "articles" ? 100 : 20)) {
        rejected[stage] += 1;
        throw contextFailure();
      }
      return sevenTopics(messages, options);
    },
  }), { name: "AbortError" });
  assert.deepEqual(rejected, { articles: 6, merge: 5 });
  assert.equal(saved.mergeBatchLimit, 14);
  assert.equal(saved.merge.round, 2);
  assert.ok(saved.merge.batchSizes.every((size) => size <= 14));
  const rounds = new Set();
  let failedAgain = false;
  const result = await generateBoard({
    articles: input, checkpoint: saved,
    onCheckpoint: async (value) => { saved = value; },
    onProgress: (value) => { if (value.round) rounds.add(value.round); },
    stream: async (messages, options) => {
      assert.equal(kind(messages), "merge", "saved article analysis must not repeat");
      if (refs(messages).length > 20) {
        failedAgain = true;
        throw contextFailure();
      }
      assert.ok(refs(messages).length <= 14, "a later round forgot the learned width");
      return sevenTopics(messages, options);
    },
  });
  assert.equal(failedAgain, false);
  assert.ok(rounds.has(2) && rounds.has(3) && rounds.size >= 3);
  assert.equal(saved.contextReductions.merge, 5, "later rounds must not spend the remaining retry allowance on known oversized requests");
  assert.equal(saved.mergeBatchLimit, 14);
  assert.equal(saved.done, true);
  assert.equal(result.topics.length, 7);
  assert.equal(boardArticleIds(result.topics, result.articles).length, input.length);
});

test("ordinary HTTP, quota and network errors never trigger a context downgrade", async () => {
  for (const error of [
    Object.assign(new Error("bad request"), { code: "HTTP_ERROR", status: 400 }),
    Object.assign(new Error("unauthorized"), { code: "HTTP_ERROR", status: 401 }),
    Object.assign(new Error("rate limited"), { code: "HTTP_ERROR", status: 429 }),
    Object.assign(new Error("timed out"), { code: "NETWORK_TIMEOUT" }),
  ]) {
    let calls = 0;
    let saved;
    await assert.rejects(generateBoard({
      articles: rows(168), inputPolicy: largePolicy,
      onCheckpoint: async (value) => { saved = value; },
      stream: async () => { calls += 1; throw error; },
    }), (caught) => caught === error);
    assert.equal(calls, 1);
    assert.deepEqual(saved.batchSizes, [168]);
    assert.deepEqual(saved.contextReductions, { articles: 0, merge: 0 });
  }
});

test("v2 checkpoint validation rejects invalid frozen budgets and retry state before AI requests", async () => {
  let saved;
  await assert.rejects(generateBoard({
    articles: rows(181), inputPolicy: largePolicy,
    onCheckpoint: async (value) => { saved = value; },
    stream: () => { throw new Error("offline"); },
  }), /offline/);
  for (const changes of [
    { version: 3 },
    { inputPolicy: { unit: "tokens", budget: 1000000 } },
    { inputPolicy: { unit: "characters", budget: 4096 } },
    { contextReductions: { articles: -1, merge: 0 } },
    { contextReductions: { articles: 0, merge: 7 } },
    { contextReductions: null },
    { mergeBatchLimit: 0 },
    { mergeBatchLimit: "14" },
  ]) {
    await assert.rejects(generateBoard({
      articles: rows(181), checkpoint: { ...saved, ...changes },
      stream: () => assert.fail("invalid checkpoint reached the provider"),
    }), { code: "BOARD_CHECKPOINT_INVALID" });
  }
});
