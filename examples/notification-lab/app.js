(function () {
  "use strict";

  const STANDARD_ID = "notification-lab-standard";
  const TIMER_KEY = "notification-lab-live";
  const STORAGE_KEY = "notification-lab-state";
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
    events: []
  };

  let toastTimer = null;
  let updateInFlight = false;

  const $ = (id) => document.getElementById(id);
  const toolbox = () => window.ToolBox;

  function cleanText(value, maxLength) {
    return String(value ?? "").replace(CONTROL_CHARS, " ").replace(/\s+/g, " ").trim().slice(0, maxLength);
  }

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

  function liveRequest() {
    const sample = sampleFor();
    return {
      sessionId: state.sessionId,
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
    const node = $("toast");
    node.textContent = message;
    node.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { node.hidden = true; }, 2500);
  }

  function appendEvent(message, kind = "neutral") {
    state.events.unshift({ time: clock(), message: cleanText(message, 280), kind });
    state.events = state.events.slice(0, 12);
    renderLog();
  }

  function errorLabel(error) {
    const code = error?.code || "UNKNOWN";
    const message = error?.message || String(error || "未知错误");
    return `${code}: ${message}`;
  }

  async function persist() {
    try {
      await toolbox().storage.set(STORAGE_KEY, {
        preset: state.preset,
        tick: state.tick,
        intervalMs: state.intervalMs,
        live: state.live,
        auto: state.auto
      });
    } catch (error) {
      appendEvent(`状态保存失败 · ${errorLabel(error)}`, "error");
    }
  }

  function applyLiveResult(result) {
    state.liveResult = result;
    renderReceipts();
  }

  function renderReceipts() {
    $("standard-result").textContent = state.standardResult;
    const result = state.liveResult;
    $("android-result").textContent = result?.androidLive || "未测试";
    $("hyperos-result").textContent = result?.hyperOsIsland || "未测试";
    $("focus-result").textContent = result
      ? `V${result.hyperOsProtocolVersion} · ${result.hyperOsPermissionReported ? "已报告允许" : "未报告允许"}`
      : "未测试";
  }

  function renderLog() {
    const list = $("event-log");
    list.replaceChildren();
    if (!state.events.length) {
      const empty = document.createElement("li");
      empty.className = "empty-log";
      empty.textContent = "尚无调用记录。";
      list.append(empty);
      return;
    }
    state.events.forEach((event) => {
      const item = document.createElement("li");
      item.dataset.kind = event.kind;
      const time = document.createElement("span");
      time.className = "event-time";
      time.textContent = event.time;
      const message = document.createElement("span");
      message.className = "event-message";
      message.textContent = event.message;
      item.append(time, message);
      list.append(item);
    });
  }

  function render() {
    const sample = sampleFor();
    $("sample-title").textContent = sample.title;
    $("sample-primary").textContent = sample.primaryText;
    $("sample-secondary").textContent = sample.secondaryText;
    $("sample-short").textContent = sample.shortText;
    $("sample-progress").style.width = `${sample.progress}%`;
    $("sample-preview").dataset.tone = sample.tone;
    $("update-count").textContent = `第 ${state.tick} 次`;
    $("interval").value = String(state.intervalMs);
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
    renderLog();
  }

  async function runAction(label, action) {
    if (state.busy) return null;
    state.busy = true;
    render();
    try {
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
    }
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
    await ensureSession();
    const result = await toolbox().notifications.live.start(liveRequest());
    state.live = true;
    applyLiveResult(result);
    await persist();
    return result;
  }

  async function updateLive() {
    if (updateInFlight) return null;
    updateInFlight = true;
    try {
      state.tick += 1;
      const result = await toolbox().notifications.live.update(liveRequest());
      applyLiveResult(result);
      await persist();
      return result;
    } finally {
      updateInFlight = false;
      render();
    }
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

  async function endLive() {
    if (state.auto) await setAuto(false);
    if (state.live && state.sessionId) await toolbox().notifications.live.end(state.sessionId);
    state.live = false;
    state.liveResult = null;
    await persist();
  }

  async function stopBackground() {
    if (state.auto) await setAuto(false);
    if (state.live && state.sessionId) {
      try { await toolbox().notifications.live.end(state.sessionId); } catch (error) {
        if (error?.code !== "NOT_FOUND") throw error;
      }
    }
    if (state.sessionId) await toolbox().background.stop(state.sessionId);
    state.sessionId = null;
    state.live = false;
    state.auto = false;
    state.liveResult = null;
    await persist();
  }

  async function reconcileSession() {
    const sessions = await toolbox().background.listSessions();
    state.sessionId = sessions[0]?.sessionId || null;
    if (!state.sessionId) {
      state.live = false;
      state.auto = false;
      return;
    }
    if (state.live) applyLiveResult(await toolbox().notifications.live.start(liveRequest()));
    if (state.auto) await toolbox().background.setTimer(TIMER_KEY, state.intervalMs);
  }

  async function loadPersisted() {
    const saved = await toolbox().storage.get(STORAGE_KEY);
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
      $("host-caption").textContent = "需要 ToolBox 0.3.2 或更高版本";
      $("runtime-state").textContent = "宿主不可用";
      $("runtime-state").dataset.state = "error";
      return;
    }
    try {
      const ready = await toolbox().ready();
      $("host-caption").textContent = `ToolBox ${ready.hostVersion} · API ${ready.apiVersion}`;
      await loadPersisted();
      await reconcileSession();
      appendEvent("宿主连接完成", "success");
    } catch (error) {
      appendEvent(`初始化失败 · ${errorLabel(error)}`, "error");
    }
    render();
  }

  document.querySelectorAll(".preset").forEach((button) => {
    button.addEventListener("click", () => {
      state.preset = button.dataset.preset;
      render();
      persist();
    });
  });
  $("interval").addEventListener("change", async (event) => {
    state.intervalMs = Number(event.target.value);
    if (state.auto) {
      await runAction("更新后台间隔", () => toolbox().background.setTimer(TIMER_KEY, state.intervalMs));
    }
    await persist();
    render();
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
  $("refresh-session").addEventListener("click", () => runAction("同步后台会话", reconcileSession));
  $("clear-log").addEventListener("click", () => {
    state.events = [];
    renderLog();
  });

  if (toolbox()?.background?.onTimer) {
    toolbox().background.onTimer((event) => {
      if (event?.key !== TIMER_KEY || !state.auto || !state.live) return;
      updateLive()
        .then((result) => {
          if (result) appendEvent(`后台自动更新 #${state.tick}`, "success");
        })
        .catch((error) => appendEvent(`后台自动更新失败 · ${errorLabel(error)}`, "error"));
    });
  }

  if (toolbox()?.background?.onRestore) {
    toolbox().background.onRestore(async (event) => {
      try {
        await loadPersisted();
        await reconcileSession();
        appendEvent(`后台恢复 · ${event?.reason || "unknown"}`, "success");
      } catch (error) {
        appendEvent(`后台恢复失败 · ${errorLabel(error)}`, "error");
      }
      render();
    });
  }

  boot();
})();
