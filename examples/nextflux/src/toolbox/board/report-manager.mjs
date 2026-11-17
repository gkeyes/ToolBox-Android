// One automatic report and one accumulating inbox per account. Article read
// state is deliberately outside this manager: generating, opening and expiring
// reports never acknowledges their source articles.
export const REPORT_THRESHOLD = 150;
const TWO_DAYS = 48 * 60 * 60 * 1000;
const validId = (id) => Number.isSafeInteger(id) && id > 0;
const unique = (ids) => [...new Set(ids.filter(validId))];
const copy = (value) => structuredClone(value);
const timestamp = (value, fallback) => Number.isFinite(Number(value)) && value !== null
  ? Number(value) : Number.isFinite(Date.parse(value || "")) ? Date.parse(value) : fallback;
const aborted = () => Object.assign(new Error("看板任务已暂停，已完成的进度会保留。"), { name: "AbortError" });

export function createReportManager({ repository, selectArticles, recoverIds = async () => [],
  generate, writeDigest, configuration, now = Date.now, onChange = () => {} }) {
  let state = null;
  let reports = [];
  let ready = null;
  let writes = Promise.resolve();
  let running = null;
  let controller = null;
  let suspended = false;
  let disposed = false;
  let stateNeedsReload = false;
  let unsavedJobStatus = null;
  let progress = null;
  let error = "";

  const publish = () => { if (disposed) return; onChange({
    status: state ? "ready" : error ? "error" : "loading", reports: copy(reports),
    pendingCount: state?.pendingIds.length || 0,
    job: state?.job ? { id: state.job.id, status: state.job.status, stage: state.job.phase,
      periodStart: state.job.periodStart, periodEnd: state.job.periodEnd,
      total: state.job.articles.length, progress, error: state.job.error || "" } : null,
    error,
  }); };
  const enqueue = (work) => {
    const operation = writes.catch(() => {}).then(work);
    writes = operation;
    return operation;
  };
  const change = (update) => enqueue(async () => {
    if (disposed) throw aborted();
    if (stateNeedsReload) {
      // A lost native commit acknowledgement can follow a durable write.
      // Reload before any later mutation, including its error-status write,
      // so an old in-memory checkpoint cannot overwrite completed batches.
      const saved = await repository.loadState();
      if (disposed) throw aborted();
      validate(saved);
      if (unsavedJobStatus?.id === saved.job?.id) Object.assign(saved.job, unsavedJobStatus);
      state = saved;
      stateNeedsReload = false;
    }
    const next = copy(state);
    await update(next);
    if (disposed) throw aborted();
    try { await repository.saveState(next); }
    catch (failure) {
      if (["REPORT_STORAGE_COMMIT_UNCERTAIN", "REPORT_STORAGE_CONFLICT"].includes(failure?.code)) stateNeedsReload = true;
      throw failure;
    }
    if (disposed) throw aborted();
    state = next;
    unsavedJobStatus = null;
    error = "";
    publish();
  });
  const selected = async (ids) => {
    const allowed = new Set(ids);
    return (await selectArticles(ids)).filter((article) => validId(article.id) && allowed.has(article.id));
  };
  const pruneSeen = (next) => {
    const held = new Set([...next.pendingIds, ...(next.job?.articles || []).map((article) => article.id)]);
    next.seen = next.seen.filter(([id, at]) => at > now() - TWO_DAYS || held.has(id));
  };
  async function ingest(next, event) {
    // A first full sync reports no "new" arrivals, but its complete set of
    // committed IDs still establishes the baseline for overlapping refreshes.
    const ids = unique([...(event.newEntryIds || []), ...(event.initialSync ? event.initialEntryIds || [] : [])]);
    const seen = new Set(next.seen.map(([id]) => id));
    const at = timestamp(event.committedAt, now());
    if (!event.initialSync) next.pendingIds = unique([
      ...next.pendingIds, ...ids.filter((id) => !seen.has(id)),
    ]);
    for (const id of ids) if (!seen.has(id)) { next.seen.push([id, at]); seen.add(id); }
    next.pendingIds = (await selected(next.pendingIds)).map((article) => article.id);
    next.observedThrough = Math.max(next.observedThrough, timestamp(event.syncedAt, next.observedThrough));
    if (event.initialSync && !next.pendingIds.length && !next.job) next.periodStart = at;
    pruneSeen(next);
  }
  function validate(saved) {
    if (!saved || saved.version !== 1 || !Number.isFinite(saved.observedThrough) || !Number.isFinite(saved.periodStart) ||
      !Array.isArray(saved.pendingIds) || saved.pendingIds.some((id) => !validId(id)) ||
      !Array.isArray(saved.seen) || saved.seen.some((item) => !Array.isArray(item) || !validId(item[0]) || !Number.isFinite(item[1])) ||
      (saved.job && (typeof saved.job.id !== "string" || !Array.isArray(saved.job.articles) ||
        saved.job.articles.some((article) => !validId(article?.id)) ||
        !["analysis", "digest", "complete"].includes(saved.job.phase) ||
        !["running", "paused", "error"].includes(saved.job.status) ||
        typeof saved.job.fingerprint !== "string" || !Number.isFinite(saved.job.periodStart) || !Number.isFinite(saved.job.periodEnd)))) {
      throw new Error("看板任务数据无法恢复，请保留数据并重试。");
    }
  }
  function initialize({ baselineAt = now(), knownIds = [] } = {}) {
    if (ready) return ready;
    ready = enqueue(async () => {
      try {
        await repository.prune();
        if (disposed) throw aborted();
        reports = await repository.listReports();
        if (disposed) throw aborted();
        const saved = await repository.loadState();
        if (disposed) throw aborted();
        if (saved) {
          validate(saved);
          state = saved;
          if (state.job?.status === "running") state.job.status = "paused";
          // Recover a committed RSS sync whose event was interrupted before
          // it reached this journal. IDs already held or seen cannot re-enter.
          await ingest(state, { newEntryIds: await recoverIds(state.observedThrough),
            syncedAt: baselineAt, committedAt: now() });
        } else {
          state = { version: 1, observedThrough: timestamp(baselineAt, now()), periodStart: now(),
            pendingIds: [], seen: unique(knownIds).map((id) => [id, now()]), job: null };
        }
        if (disposed) throw aborted();
        await repository.saveState(state);
        if (disposed) throw aborted();
        publish();
      } catch (failure) {
        state = null; error = failure.message; publish(); throw failure;
      }
    });
    return ready;
  }
  async function refresh() {
    await ready;
    if (disposed) return;
    await enqueue(async () => {
      if (disposed) return;
      await repository.prune();
      if (disposed) return;
      reports = await repository.listReports();
      publish();
    });
  }
  async function onSync(event) {
    if (event?.outcome !== "committed" || disposed) return;
    await ready;
    await change((next) => ingest(next, event));
    await refresh();
    void start().catch(fail);
  }
  const fail = (failure) => {
    if (disposed || failure?.name === "AbortError") return;
    error = failure?.message || "看板数据保存失败，请重试。";
    publish();
  };
  const newId = () => globalThis.crypto?.randomUUID?.() || `${now()}-${Math.random().toString(36).slice(2)}`;
  function start({ force = false, retry = false } = {}) {
    if (running) return running;
    if (disposed || suspended) return Promise.resolve(null);
    let completed = false;
    const operation = (async () => {
      await ready;
      let jobId = null;
      await change(async (next) => {
        const config = configuration();
        if (!config.aiReady || (!config.enabled && !force)) return;
        if (next.job) {
          if (next.job.status === "error" && !retry) return;
          if (next.job.fingerprint !== config.fingerprint) {
            if (!retry) {
              next.job.status = "error";
              next.job.error = "AI 或文章筛选设置已改变。重试会使用当前 AI 配置，重新分析本期已选定的文章。";
              return;
            }
            next.job.fingerprint = config.fingerprint;
            next.job.checkpoint = null;
            next.job.result = null;
            next.job.report = null;
            next.job.phase = "analysis";
          }
          next.job.status = "running";
          next.job.error = "";
        } else {
          const articles = await selected(next.pendingIds);
          next.pendingIds = articles.map((article) => article.id);
          if (!articles.length || (!force && articles.length <= REPORT_THRESHOLD)) return;
          const end = now();
          next.job = { id: newId(), kind: "interval", status: "running", phase: "analysis",
            periodStart: next.periodStart, periodEnd: end, articles,
            fingerprint: config.fingerprint, checkpoint: null, result: null, report: null, error: "" };
          next.pendingIds = [];
          next.periodStart = end;
        }
        jobId = next.job.id;
      });
      if (!jobId || suspended || disposed) return null;
      const abort = new AbortController();
      controller = abort;
      const live = () => !disposed && !suspended && !abort.signal.aborted && state?.job?.id === jobId;
      const check = () => { if (!live()) throw aborted(); };
      try {
        // A crash between publishing a report and clearing the job is an
        // acknowledgement recovery, never another provider request.
        const published = await repository.readReport(jobId);
        check();
        if (!published) {
          if (state.job.phase === "analysis") {
            const result = await generate({ articles: copy(state.job.articles), signal: abort.signal,
              checkpoint: copy(state.job.checkpoint),
              onProgress: (value) => { if (live()) { progress = value; publish(); } },
              onCheckpoint: (value) => change((next) => {
                check(); if (next.job?.id === jobId) next.job.checkpoint = value;
              }),
            });
            check();
            await change((next) => { check(); next.job.result = result; next.job.phase = "digest"; });
          }
          if (state.job.phase === "digest") {
            progress = { stage: "digest", total: state.job.articles.length, text: "" }; publish();
            const digest = await writeDigest(state.job.result, abort.signal, (text) => {
              if (live()) { progress = { ...progress, text }; publish(); }
            });
            check();
            if (!String(digest || "").trim()) throw new Error("AI 没有返回报告正文，请重试。");
            await change((next) => {
              check();
              next.job.report = { ...next.job.result, id: jobId, kind: "interval", unseen: true,
                periodStart: next.job.periodStart, periodEnd: next.job.periodEnd,
                completedAt: now(), digest: String(digest), fingerprint: next.job.fingerprint };
              next.job.phase = "complete";
            });
          }
          check();
          await repository.saveReport(state.job.report);
        }
        check();
        await change((next) => { check(); next.job = null; });
        completed = true;
        progress = null;
        await refresh();
        if (disposed) return null;
        const report = await repository.readReport(jobId);
        return disposed ? null : report;
      } catch (failure) {
        if (!disposed && state?.job?.id === jobId) {
          const jobStatus = { id: jobId, status: suspended || abort.signal.aborted ? "paused" : "error",
            error: suspended || abort.signal.aborted ? "" : failure.message || "生成报告失败，请重试。" };
          await change((next) => {
            if (next.job?.id !== jobId) return;
            Object.assign(next.job, jobStatus);
          }).catch((saveFailure) => {
            if (disposed) return;
            // If storage is still unavailable, expose a stopped task rather
            // than a forever-running spinner. This volatile status is merged
            // into the next confirmed snapshot; never persist stale results.
            if (state?.job?.id === jobId) {
              unsavedJobStatus = jobStatus;
              state = copy(state);
              Object.assign(state.job, jobStatus);
            }
            fail(saveFailure);
          });
        }
        if (failure?.name !== "AbortError" && !abort.signal.aborted) fail(failure);
        return null;
      } finally {
        if (controller === abort) controller = null;
      }
    })();
    running = operation.finally(() => {
      running = null;
      if (completed && !disposed && !suspended && state && !state.job && state.pendingIds.length > REPORT_THRESHOLD) {
        queueMicrotask(() => { void start().catch(fail); });
      }
    });
    return running;
  }
  async function pause() {
    suspended = true;
    const active = running;
    controller?.abort();
    await ready?.catch(() => {});
    const pausing = state && !disposed ? change((next) => {
      if (next.job?.status === "running") next.job.status = "paused";
    }) : Promise.resolve();
    // An aborted run can still queue its final paused/error-state write.
    // Drain that cleanup before the native flush acknowledges safe shutdown,
    // including when the first attempt to persist the pause itself failed.
    const stopped = await Promise.allSettled([pausing, active]);
    await repository.flush();
    const failure = stopped.find((result) => result.status === "rejected");
    if (failure) throw failure.reason;
  }
  async function resume({ autoStart = true } = {}) {
    const interrupted = suspended || controller?.signal.aborted;
    suspended = false;
    await ready;
    // A manual request joins an active job through start(), rather than
    // waiting for it and accidentally forcing the next accumulating period.
    if (autoStart || interrupted) await running?.catch(() => {});
    await refresh();
    return autoStart ? start() : null;
  }
  async function readReport(id) {
    await ready;
    if (disposed) return null;
    const report = await repository.readReport(id);
    if (disposed) return null;
    if (!report) { await refresh(); return null; }
    if (report.unseen !== false) await repository.saveReport({ ...report, unseen: false });
    if (disposed) return null;
    await refresh();
    return disposed ? null : { ...report, unseen: false };
  }
  async function saveReport(report) {
    await ready;
    if (disposed) throw aborted();
    const saved = await repository.saveReport(report);
    if (disposed) throw aborted();
    await refresh();
    if (disposed) throw aborted();
    return saved;
  }
  async function removeReport(id) { await ready; if (disposed) return; await repository.removeReport(id); await refresh(); }
  async function dispose({ clear = false } = {}) {
    suspended = true; disposed = true; controller?.abort();
    await running?.catch(() => {});
    await writes.catch(() => {});
    if (clear) await repository.clear();
    else await repository.flush();
  }
  return { initialize, onSync, refresh, start, pause, resume, readReport, saveReport, removeReport, dispose,
    getState: () => copy(state), getReports: () => copy(reports) };
}
