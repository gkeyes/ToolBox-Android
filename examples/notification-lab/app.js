(function () {
  "use strict";

  const STANDARD_ID = "notification-lab-standard";
  const TIMER_KEY = "notification-lab-live";
  const STORAGE_KEY = "notification-lab-state";
  const MAX_EVENTS = 200;
  // Background ticks are checkpointed at most this often, so a restore after process death resumes close to the live count.
  const TICK_CHECKPOINT_MS = 30000;
  const CONTROL_CHARS = /[\u0000-\u001F\u007F-\u009F]/g;

  const state = {
    preset: "market",
    tick: 0,
    intervalMs: 2000,
    sessionId: null,
    live: false,
    auto: false,
    busy: false,
    standardResult: "未测试",
    liveResult: null,
    events: new Array(MAX_EVENTS),
    eventCount: 0,
    eventNext: 0
  };

  let toastTimer = null;
  let updateInFlight = false;
  let liveUpdateWork = null;
  let actionWork = null;
  let restoreWork = null;
  let bootWork = null;
  let writeChain = Promise.resolve();
  let lastPersistAt = 0;
  const userActions = new Set();
  let restorePending = false;
  let restoreReason = "unknown";
  let runtimeForeground = false;
  let runtimeClosing = false;
  let booted = false;
  let liveStartedFor = null;
  // Bumped when the user ends the live notification or stops the background, so an update still awaiting the host
  // does not republish or restart what the user just stopped.
  let liveGeneration = 0;

  const $ = (id) => document.getElementById(id);
  const toolbox = () => window.ToolBox;

  function cleanText(value, max = Infinity) {
    const text = String(value ?? "").replace(CONTROL_CHARS, " ").replace(/\s+/g, " ").trim();
    if (!(max > 0) || text.length <= max) return text;
    const chars = Array.from(text);
    return chars.length <= max ? text : `${chars.slice(0, Math.max(0, max - 1)).join("").trimEnd()}…`;
  }

  const SESSION_LOST = new Set(["SESSION_ENDED", "INVALID_SESSION"]);
  const sessionLost = (error) => SESSION_LOST.has(error?.code);

  function clock(value = Date.now()) {
    return new Intl.DateTimeFormat("zh-CN", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false
    }).format(new Date(value));
  }

  function signed(value) {
    return `${value >= 0 ? "+" : ""}${value.toFixed(2)}%`;
  }

  function sampleFor(preset = state.preset, tick = state.tick) {
    const updated = clock();
    if (preset === "countdown") {
      const remaining = Math.max(0, 45 - (tick % 46));
      return {
        title: "专注倒计时",
        primaryText: `${remaining} 分钟`,
        secondaryText: `已进行 ${tick} 次更新 · ${updated}`,
        body: `专注任务正在进行 · 剩余 ${remaining} 分钟 · 更新时间 ${updated}`,
        shortText: `${remaining}分`,
        progress: Math.round(((45 - remaining) / 45) * 100),
        tone: remaining <= 8 ? "warning" : "neutral",
        accentColor: remaining <= 8 ? "#F59E0B" : "#1684FF"
      };
    }
    if (preset === "trip") {
      const progress = Math.min(100, (tick * 7) % 101);
      const minutes = Math.max(0, 28 - Math.floor(progress / 4));
      return {
        title: "行程动态",
        primaryText: `距到达 ${minutes} 分钟`,
        secondaryText: `进度 ${progress}% · ${updated}`,
        body: `前往机场的行程样本 · 预计 ${minutes} 分钟后到达 · 更新时间 ${updated}`,
        shortText: `${minutes}分`,
        progress,
        tone: "neutral",
        accentColor: "#7C5CFC"
      };
    }
    const wave = Math.sin(tick / 2.4);
    const price = 10.88 + wave * 0.34;
    const change = wave * 2.18;
    const positive = change >= 0;
    return {
      title: "行情动态样本",
      primaryText: `¥${price.toFixed(2)}`,
      secondaryText: `${signed(change)} · ${updated}`,
      body: `示例股票 600000 · 当前价 ${price.toFixed(2)} · 涨跌 ${signed(change)} · 更新时间 ${updated}`,
      shortText: price.toFixed(2),
      progress: (tick * 9) % 101,
      tone: positive ? "positive" : "negative",
      accentColor: positive ? "#E94949" : "#19A66A"
    };
  }

  function liveRequest(sessionId = state.sessionId) {
    const sample = sampleFor();
    return {
      sessionId,
      title: cleanText(sample.title, 64),
      primaryText: cleanText(sample.primaryText, 32),
      secondaryText: cleanText(sample.secondaryText, 96),
      body: cleanText(sample.body, 256),
      shortText: cleanText(sample.shortText, 12),
      updatedAt: Date.now(),
      progress: sample.progress,
      accentColor: sample.accentColor,
      tone: sample.tone
    };
  }

  function showToast(message) {
    if (!runtimeForeground) return;
    const node = $("toast");
    node.textContent = message;
    node.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { node.hidden = true; }, 2500);
  }

  function appendEvent(message, kind = "neutral") {
    const event = { time: clock(), message: cleanText(message), kind };
    state.events[state.eventNext] = event;
    state.eventNext = (state.eventNext + 1) % MAX_EVENTS;
    state.eventCount = Math.min(MAX_EVENTS, state.eventCount + 1);
    if (!runtimeForeground) return;
    const list = $("event-log");
    list.querySelector(".empty-log")?.remove();
    list.prepend(logItem(event));
    if (list.children.length > MAX_EVENTS) list.lastElementChild.remove();
  }

  function errorLabel(error) {
    const code = error?.code || "UNKNOWN";
    const message = error?.message || String(error || "未知错误");
    return `${code}: ${message}`;
  }

  async function persist(required = false) {
    const snapshot = {
        preset: state.preset,
        tick: state.tick,
        intervalMs: state.intervalMs,
        live: state.live,
        auto: state.auto
      };
    lastPersistAt = Date.now();
    const work = writeChain.catch(() => {}).then(() => toolbox().storage.set(STORAGE_KEY, snapshot));
    writeChain = work;
    try {
      await work;
    } catch (error) {
      appendEvent(`状态保存失败 · ${errorLabel(error)}`, "error");
      if (required) throw error;
    }
  }

  function applyLiveResult(result) {
    state.liveResult = result;
    renderReceipts();
  }

  function renderReceipts() {
    if (!runtimeForeground) return;
    $("standard-result").textContent = state.standardResult;
    const result = state.liveResult;
    $("android-result").textContent = result?.androidLive || "未测试";
    $("hyperos-result").textContent = result?.hyperOsIsland || "未测试";
    $("focus-result").textContent = result
      ? `V${result.hyperOsProtocolVersion} · ${result.hyperOsPermissionReported ? "已报告允许" : "未报告允许"}`
      : "未测试";
  }

  function logItem(event) {
    const item = document.createElement("li");
    item.dataset.kind = event.kind;
    const time = document.createElement("span");
    time.className = "event-time";
    time.textContent = event.time;
    const message = document.createElement("span");
    message.className = "event-message";
    message.textContent = event.message;
    item.append(time, message);
    return item;
  }

  function renderLog() {
    if (!runtimeForeground) return;
    const list = $("event-log");
    list.replaceChildren();
    if (!state.eventCount) {
      const empty = document.createElement("li");
      empty.className = "empty-log";
      empty.textContent = "尚无调用记录。";
      list.append(empty);
      return;
    }
    for (let offset = 1; offset <= state.eventCount; offset += 1) {
      list.append(logItem(state.events[(state.eventNext - offset + MAX_EVENTS) % MAX_EVENTS]));
    }
  }

  function render() {
    if (!runtimeForeground) return;
    const sample = sampleFor();
    $("sample-title").textContent = sample.title;
    $("sample-primary").textContent = sample.primaryText;
    $("sample-secondary").textContent = sample.secondaryText;
    $("sample-short").textContent = sample.shortText;
    $("sample-progress").style.width = `${sample.progress}%`;
    $("sample-preview").dataset.tone = sample.tone;
    $("update-count").textContent = `第 ${state.tick} 次`;
    $("interval").value = String(state.intervalMs);
    $("interval").disabled = state.busy;
    document.querySelectorAll(".preset").forEach((button) => {
      button.classList.toggle("active", button.dataset.preset === state.preset);
    });
    const runtimeState = $("runtime-state");
    runtimeState.textContent = state.sessionId ? (state.auto ? "后台更新中" : "后台已启动") : "未启动";
    runtimeState.dataset.state = state.sessionId ? "active" : "idle";
    $("auto-toggle").textContent = state.auto ? "停止自动更新" : "开始自动更新";
    $("live-update").disabled = !state.live || state.busy;
    $("live-end").disabled = !state.live || state.busy;
    $("stop-background").disabled = !state.sessionId || state.busy;
    document.querySelectorAll("button").forEach((button) => {
      if (!button.id || !["live-update", "live-end", "stop-background"].includes(button.id)) {
        button.disabled = state.busy;
      }
    });
    renderReceipts();
  }

  async function runAction(label, action) {
    if (state.busy || runtimeClosing) return null;
    state.busy = true;
    render();
    const work = (async () => { try {
      const result = await action();
      appendEvent(`${label}成功`, "success");
      return result;
    } catch (error) {
      appendEvent(`${label}失败 · ${errorLabel(error)}`, "error");
      showToast(`${label}失败：${error?.code || "UNKNOWN"}`);
      return null;
    } finally {
      state.busy = false;
      render();
    } })();
    actionWork = work;
    try { return await work; }
    finally { if (actionWork === work) actionWork = null; }
  }

  async function ensureSession() {
    if (state.sessionId) return state.sessionId;
    const session = await toolbox().background.start({
      restoreAfterProcessDeath: true,
      restoreAfterReboot: true
    });
    state.sessionId = session.sessionId;
    appendEvent(`后台会话 ${session.sessionId.slice(0, 8)}… 已启动`, "success");
    return state.sessionId;
  }

  // SESSION_ENDED/INVALID_SESSION: the old sessionId can never be reused, so drop it and start a new one.
  async function reacquireSession(error, staleId) {
    if (state.sessionId === staleId) state.sessionId = null;
    if (liveStartedFor === staleId) liveStartedFor = null;
    appendEvent(`后台会话已失效（${error.code}），正在重新取得`, "neutral");
    const sessionId = await ensureSession();
    if (state.auto) await toolbox().background.setTimer(TIMER_KEY, state.intervalMs);
    return sessionId;
  }

  async function startLiveIn(sessionId) {
    const result = await toolbox().notifications.live.start(liveRequest(sessionId));
    liveStartedFor = sessionId;
    return result;
  }

  async function publishStandard(update) {
    state.tick += update ? 1 : 0;
    const sample = sampleFor();
    const body = cleanText(`${sample.primaryText} · ${sample.secondaryText} · ${sample.body}`, 256);
    if (update) await toolbox().notifications.update(STANDARD_ID, sample.title, body);
    else await toolbox().notifications.post(STANDARD_ID, sample.title, body);
    state.standardResult = update ? "UPDATED" : "POSTED";
    await persist();
  }

  async function startLive() {
    const sessionId = await ensureSession();
    let result;
    try { result = await startLiveIn(sessionId); } catch (error) {
      if (!sessionLost(error)) throw error;
      result = await startLiveIn(await reacquireSession(error, sessionId));
    }
    state.live = true;
    applyLiveResult(result);
    await persist();
    return result;
  }

  async function updateLive() {
    if (runtimeClosing) return null;
    if (updateInFlight) return liveUpdateWork;
    updateInFlight = true;
    const generation = liveGeneration;
    const work = (async () => { try {
      state.tick += 1;
      const sessionId = await ensureSession();
      let result;
      try { result = await toolbox().notifications.live.update(liveRequest(sessionId)); } catch (error) {
        if (!sessionLost(error)) throw error;
        // Stopped while this update was in flight: the old session ending is expected, so do not start a new one.
        if (generation !== liveGeneration || !state.live) return null;
        // The live notification belonged to the ended session; publish it again in the new one.
        result = await startLiveIn(await reacquireSession(error, sessionId));
      }
      if (generation !== liveGeneration) return null;
      applyLiveResult(result);
      // The tick lives in memory; it is saved on flush, on leaving the page, with other state changes and
      // by a throttled checkpoint, because process death skips the flush and restore would republish an old tick.
      if (Date.now() - lastPersistAt >= TICK_CHECKPOINT_MS) await persist();
      return result;
    } finally {
      updateInFlight = false;
      render();
    } })();
    liveUpdateWork = work;
    try { return await work; }
    finally { if (liveUpdateWork === work) liveUpdateWork = null; }
  }

  async function setAuto(enabled) {
    if (enabled) {
      if (!state.live) await startLive();
      await toolbox().background.setTimer(TIMER_KEY, state.intervalMs);
      state.auto = true;
    } else {
      try { await toolbox().background.cancelTimer(TIMER_KEY); } catch (error) {
        if (error?.code !== "NOT_FOUND") throw error;
      }
      state.auto = false;
    }
    await persist();
  }

  async function endLiveNotification() {
    if (!state.live || !state.sessionId) return;
    try { await toolbox().notifications.live.end(state.sessionId); } catch (error) {
      if (sessionLost(error)) state.sessionId = null;
      else if (error?.code !== "NOT_FOUND") throw error;
    }
    liveStartedFor = null;
  }

  async function endLive() {
    liveGeneration += 1;
    if (state.auto) await setAuto(false);
    await endLiveNotification();
    state.live = false;
    state.liveResult = null;
    await persist();
  }

  async function stopBackground() {
    liveGeneration += 1;
    // Inactive before the first await: a queued timer callback must not start an update during the waits
    // below, and an update already in flight must not reacquire the session this stop is closing.
    const wasAuto = state.auto;
    const wasLive = state.live;
    state.auto = false;
    state.live = false;
    try {
      if (wasAuto) {
        try { await toolbox().background.cancelTimer(TIMER_KEY); } catch (error) {
          if (error?.code !== "NOT_FOUND") throw error;
        }
      }
      if (wasLive && state.sessionId) {
        try { await toolbox().notifications.live.end(state.sessionId); } catch (error) {
          if (sessionLost(error)) state.sessionId = null;
          else if (error?.code !== "NOT_FOUND") throw error;
        }
      }
    } catch (error) {
      state.auto = wasAuto;
      state.live = wasLive;
      throw error;
    }
    if (state.sessionId) {
      try { await toolbox().background.stop(state.sessionId); } catch (error) {
        if (!sessionLost(error) && error?.code !== "NOT_FOUND") throw error;
      }
    }
    state.sessionId = null;
    liveStartedFor = null;
    state.live = false;
    state.auto = false;
    state.liveResult = null;
    await persist();
  }

  async function reconcileSession(force = false) {
    const sessions = await toolbox().background.listSessions();
    state.sessionId = sessions[0]?.sessionId || null;
    if (!state.sessionId) {
      state.live = false;
      state.auto = false;
      liveStartedFor = null;
      return;
    }
    // Boot and onRestore can both reconcile the same session; publish the live notification once.
    if (state.live && (force || liveStartedFor !== state.sessionId)) applyLiveResult(await startLiveIn(state.sessionId));
    if (state.auto) await toolbox().background.setTimer(TIMER_KEY, state.intervalMs);
  }

  async function loadPersisted() {
    let saved;
    try {
      saved = await toolbox().storage.get(STORAGE_KEY);
    } catch (error) {
      appendEvent(`状态读取失败 · ${errorLabel(error)}`, "error");
      return;
    }
    if (!saved || typeof saved !== "object" || Array.isArray(saved)) return;
    if (["market", "countdown", "trip"].includes(saved.preset)) state.preset = saved.preset;
    if (Number.isInteger(saved.tick) && saved.tick >= 0) state.tick = saved.tick;
    if ([1000, 2000, 5000].includes(saved.intervalMs)) state.intervalMs = saved.intervalMs;
    state.live = saved.live === true;
    state.auto = saved.auto === true;
  }

  async function boot() {
    render();
    if (!toolbox()?.ready) {
      $("host-caption").textContent = "ToolBox 宿主 API 不可用";
      $("runtime-state").textContent = "宿主不可用";
      $("runtime-state").dataset.state = "error";
      return;
    }
    try {
      const ready = await toolbox().ready();
      toolbox().runtime.onStateChanged((snapshot) => {
        const wasForeground = runtimeForeground;
        runtimeClosing = snapshot.closing;
        runtimeForeground = snapshot.foreground && !snapshot.closing;
        if (wasForeground && !runtimeForeground && !runtimeClosing) persist();
        if (runtimeForeground) {
          render();
          renderLog();
        }
        if (!runtimeClosing && restorePending) resumeRestore();
      });
      toolbox().runtime.registerFlushHandler(async () => {
        await Promise.allSettled([bootWork, actionWork, liveUpdateWork, restoreWork, ...userActions]);
        await persist(true);
      });
      $("host-caption").textContent = `ToolBox ${ready.hostVersion} · API ${ready.apiVersion}`;
      await loadPersisted();
      await reconcileSession();
      appendEvent("宿主连接完成", "success");
    } catch (error) {
      appendEvent(`初始化失败 · ${errorLabel(error)}`, "error");
    }
    booted = true;
    render();
    if (restorePending && !runtimeClosing) resumeRestore();
  }

  document.querySelectorAll(".preset").forEach((button) => {
    button.addEventListener("click", () => {
      state.preset = button.dataset.preset;
      render();
      persist();
    });
  });
  $("interval").addEventListener("change", (event) => {
    if (runtimeClosing) return;
    const work = (async () => {
      const previous = state.intervalMs;
      state.intervalMs = Number(event.target.value);
      if (state.auto) {
        const updated = await runAction("更新后台间隔", async () => { await toolbox().background.setTimer(TIMER_KEY, state.intervalMs); return true; });
        // The timer still runs at the old interval; show and keep that one instead of the rejected choice.
        if (updated !== true) { state.intervalMs = previous; render(); return; }
      }
      await persist();
      render();
    })();
    userActions.add(work);
    void work.finally(() => userActions.delete(work)).catch(() => {});
  });
  $("standard-post").addEventListener("click", () => runAction("发布普通通知", () => publishStandard(false)));
  $("standard-update").addEventListener("click", () => runAction("更新普通通知", () => publishStandard(true)));
  $("standard-cancel").addEventListener("click", () => runAction("取消普通通知", async () => {
    await toolbox().notifications.cancel(STANDARD_ID);
    state.standardResult = "CANCELLED";
  }));
  $("live-start").addEventListener("click", () => runAction("开始实时展示", startLive));
  $("live-update").addEventListener("click", () => runAction("更新实时展示", updateLive));
  $("auto-toggle").addEventListener("click", () => runAction(
    state.auto ? "停止自动更新" : "开始自动更新",
    () => setAuto(!state.auto)
  ));
  $("live-end").addEventListener("click", () => runAction("结束实时展示", endLive));
  $("stop-background").addEventListener("click", () => runAction("停止后台环境", stopBackground));
  $("refresh-session").addEventListener("click", () => runAction("同步后台会话", () => reconcileSession(true)));
  $("clear-log").addEventListener("click", () => {
    state.events = new Array(MAX_EVENTS);
    state.eventCount = 0;
    state.eventNext = 0;
    renderLog();
  });

  if (toolbox()?.background?.onTimer) {
    toolbox().background.onTimer((event) => {
      if (runtimeClosing || event?.key !== TIMER_KEY || !state.auto || !state.live) return;
      updateLive()
        .then((result) => {
          if (result) appendEvent(`后台自动更新 #${state.tick}`, "success");
        })
        .catch((error) => appendEvent(`后台自动更新失败 · ${errorLabel(error)}`, "error"));
    });
  }

  function resumeRestore() {
      // Wait for boot: it loads and reconciles the same state, so running both at once double-starts.
      if (!booted || runtimeClosing || restoreWork || !restorePending) return;
      restorePending = false;
      const work = (async () => { try {
        await loadPersisted();
        if (runtimeClosing) { restorePending = true; return; }
        await reconcileSession();
        appendEvent(`后台恢复 · ${restoreReason}`, "success");
      } catch (error) {
        if (runtimeClosing) restorePending = true;
        appendEvent(`后台恢复失败 · ${errorLabel(error)}`, "error");
      }
      render();
      })();
      restoreWork = work;
      void work.finally(() => {
        if (restoreWork === work) restoreWork = null;
        if (restorePending && !runtimeClosing) resumeRestore();
      }).catch(() => {});
  }
  if (toolbox()?.background?.onRestore) {
    toolbox().background.onRestore((event) => {
      restorePending = true;
      restoreReason = event?.reason || "unknown";
      resumeRestore();
    });
  }

  // Test hook: only tests define window.__TOOLBOX_TEST__ before loading this script,
  // so production never exposes internals or changes startup.
  const testHook = window.__TOOLBOX_TEST__;
  if (testHook) {
    testHook.api = {
      state, cleanText, appendEvent, renderLog, updateLive, startLive, endLive, render,
      get bootWork() { return bootWork; },
      setForeground: (value) => { runtimeForeground = value; },
    };
  }
  if (testHook?.boot !== false) bootWork = boot();
})();
