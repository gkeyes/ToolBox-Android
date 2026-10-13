(() => {
  "use strict";

  const STORAGE_KEY = "stock-monitor-state-v1";
  const TOKEN_KEY = "twelve-data-api-key";
  const TIMER_KEY = "quote-poll";
  const DEFAULT_INTERVAL_MS = 300000;
  const VALID_INTERVALS = new Set([60000, 300000, 900000, 1800000]);
  // Generous per-request deadline: a quote response is a few KB, so a request that has
  // produced nothing for this long is hung. Without it one stuck request would hold the
  // shared refresh promise forever and silently stop every later poll.
  const REQUEST_TIMEOUT_MS = 60000;
  const CHINA_OFFSET_MS = 8 * 60 * 60 * 1000;
  const $ = (id) => document.getElementById(id);
  const toolbox = () => window.ToolBox;

  let state = createInitialState();
  let tokenPresent = false;
  let tokenValue = "";
  let editingId = null;
  // Each open of the editor gets a new token; an in-flight save or delete only closes the
  // editor it was started from. editorBusy blocks a second submit while one is pending.
  let editorToken = 0;
  let editorBusy = false;
  let refreshPromise = null;
  let refreshForced = false;
  let forcedFollowUp = null;
  let bootWork = null;
  let restoreWork = null;
  let restorePending = false;
  let monitorWork = null;
  let monitorAction = null;
  let toastTimer = null;
  let ready = false;
  let stateLoaded = false;
  let savedStateUnreadable = false;
  let savedStateEverRead = false;
  let markStateLoaded = null;
  const stateLoadedWork = new Promise((resolve) => { markStateLoaded = resolve; });
  let liveAvailable = true;
  // The live notification currently shows the "fetching" placeholder from startMonitoring().
  let liveLoading = false;
  let runtimeForeground = false;
  let runtimeClosing = false;
  let statusMessage = "";
  let statusTone = "neutral";
  const dirtyItems = new Set();
  let paintTimer = null;
  let writeChain = Promise.resolve();
  const userActions = new Set();

  function trackUserAction(work) {
    userActions.add(work);
    void work.finally(() => userActions.delete(work)).catch(() => {});
  }

  function createInitialState() {
    return {
      version: 1,
      intervalMs: DEFAULT_INTERVAL_MS,
      monitoring: false,
      sessionId: null,
      lastRefreshAt: null,
      items: [createItem({ provider: "tencent", symbol: "600550", name: "保变电气" })]
    };
  }

  function createItem(input) {
    return {
      id: input.id || `stock-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
      provider: input.provider === "twelve" ? "twelve" : "tencent",
      symbol: String(input.symbol || "").trim().toUpperCase(),
      name: String(input.name || "").trim(),
      upper: finitePositiveOrNull(input.upper),
      lower: finitePositiveOrNull(input.lower),
      enabled: input.enabled !== false,
      price: finitePositiveOrNull(input.price),
      previousClose: finitePositiveOrNull(input.previousClose),
      changePct: finiteNumberOrNull(input.changePct),
      currency: typeof input.currency === "string" ? input.currency : "CNY",
      exchange: typeof input.exchange === "string" ? input.exchange : "",
      sourceTime: typeof input.sourceTime === "string" ? input.sourceTime : "",
      quoteAt: Number.isFinite(input.quoteAt) ? input.quoteAt : null,
      fetchedAt: Number.isFinite(input.fetchedAt) ? input.fetchedAt : null,
      error: null,
      aboveLatched: input.aboveLatched === true,
      belowLatched: input.belowLatched === true
    };
  }

  function finiteNumberOrNull(value) {
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  function finitePositiveOrNull(value) {
    const number = finiteNumberOrNull(value);
    return number !== null && number > 0 ? number : null;
  }

  function sanitizeState(value) {
    if (!value || typeof value !== "object" || !Array.isArray(value.items)) return createInitialState();
    const items = value.items
      .filter((item) => item && typeof item === "object" && typeof item.symbol === "string")
      .map(createItem);
    const intervalMs = VALID_INTERVALS.has(Number(value.intervalMs)) ? Number(value.intervalMs) : DEFAULT_INTERVAL_MS;
    return {
      version: 1,
      intervalMs,
      monitoring: value.monitoring === true,
      sessionId: typeof value.sessionId === "string" ? value.sessionId : null,
      lastRefreshAt: Number.isFinite(value.lastRefreshAt) ? value.lastRefreshAt : null,
      items
    };
  }

  function persistedState(source = state) {
    return {
      version: 1,
      intervalMs: source.intervalMs,
      monitoring: source.monitoring,
      sessionId: source.sessionId,
      lastRefreshAt: source.lastRefreshAt,
      items: source.items.map(({ error, ...item }) => item)
    };
  }

  function persist(source = state) {
    // While the saved watchlist could not be read, never write: the in-memory fallback
    // would otherwise replace the user's real list.
    if (savedStateUnreadable) return Promise.resolve();
    // Nothing may be written before the saved list was read: a snapshot taken from the
    // default list would replace it. Late callers write the loaded state instead.
    if (!stateLoaded) return stateLoadedWork.then(() => (stateLoaded ? persist() : undefined));
    const snapshot = persistedState(source);
    const work = writeChain.catch(() => {}).then(() => toolbox().storage.set(STORAGE_KEY, snapshot));
    writeChain = work;
    return work;
  }

  // User edits must not look saved while the saved list is unread: a later successful
  // read replaces the in-memory list. Refuse them so the handler keeps the old state.
  function persistUserChange(source) {
    if (savedStateUnreadable) {
      retrySavedState();
      return Promise.reject(new Error("已保存的自选列表尚未读取成功，修改未保存，正在重试读取，请稍后再试。"));
    }
    return persist(source);
  }

  function sourceLabel(provider) {
    return provider === "twelve" ? "Twelve Data" : "腾讯行情";
  }

  function marketLabel(item) {
    if (item.provider === "twelve") return item.exchange || "全球市场";
    if (/^[69]/.test(item.symbol)) return "上交所";
    if (/^[03]/.test(item.symbol)) return "深交所";
    if (/^[48]/.test(item.symbol)) return "北交所";
    return "A 股";
  }

  function formatPrice(value, currency) {
    if (!Number.isFinite(value)) return "--";
    const decimals = Math.abs(value) < 1 ? 4 : 2;
    const number = value.toLocaleString("zh-CN", { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
    return currency && currency !== "CNY" ? `${number} ${currency}` : number;
  }

  function formatPercent(value) {
    if (!Number.isFinite(value)) return "--";
    const sign = value > 0 ? "+" : "";
    return `${sign}${value.toFixed(2)}%`;
  }

  function formatTime(value) {
    if (!Number.isFinite(value)) return "尚未更新";
    return `更新于 ${new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(value))}`;
  }

  function chinaMarketIsOpen(now = new Date()) {
    const china = new Date(now.getTime() + CHINA_OFFSET_MS);
    const day = china.getUTCDay();
    if (day === 0 || day === 6) return false;
    const minutes = china.getUTCHours() * 60 + china.getUTCMinutes();
    return (minutes >= 570 && minutes <= 690) || (minutes >= 780 && minutes <= 900);
  }

  // Start of the current closed period (11:31 lunch break, 15:01 close, or the previous
  // weekday's 15:01 overnight / at weekends), or null while the market is open.
  function chinaMarketClosedSince(now = new Date()) {
    if (chinaMarketIsOpen(now)) return null;
    const china = new Date(now.getTime() + CHINA_OFFSET_MS);
    const minutes = china.getUTCHours() * 60 + china.getUTCMinutes();
    const weekday = china.getUTCDay() >= 1 && china.getUTCDay() <= 5;
    const at = (dayOffset, hour, minute) => Date.UTC(
      china.getUTCFullYear(), china.getUTCMonth(), china.getUTCDate() + dayOffset, hour, minute
    ) - CHINA_OFFSET_MS;
    if (weekday && minutes > 690 && minutes < 780) return at(0, 11, 31);
    if (weekday && minutes > 900) return at(0, 15, 1);
    let offset = -1;
    while (true) {
      const day = (china.getUTCDay() + 7 + offset) % 7;
      if (day >= 1 && day <= 5) return at(offset, 15, 1);
      offset -= 1;
    }
  }

  // A-share quotes do not move while the market is closed; polling them all night only
  // costs battery. One refresh after the close is still taken so the closing price lands.
  function skipWhileClosed(item, closedSince) {
    if (item.provider !== "tencent" || closedSince === null) return false;
    if (!Number.isFinite(item.fetchedAt) || item.fetchedAt < closedSince) return false;
    // A crossed threshold whose notification failed is retried on the next poll.
    const abovePending = item.upper !== null && item.price !== null && item.price >= item.upper && !item.aboveLatched;
    const belowPending = item.lower !== null && item.price !== null && item.price <= item.lower && !item.belowLatched;
    return !abovePending && !belowPending;
  }


  function quoteTimeLabel(item) {
    if (!Number.isFinite(item.quoteAt)) return formatTime(item.fetchedAt);
    const stamp = new Intl.DateTimeFormat("zh-CN", {
      month: "numeric",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit"
    }).format(new Date(item.quoteAt));
    if (item.provider === "tencent" && chinaMarketIsOpen() && Date.now() - item.quoteAt > 10 * 60 * 1000) {
      return `行情可能延迟 · ${stamp}`;
    }
    return `行情时间 ${stamp}`;
  }

  function intervalLabel(value) {
    const minutes = Math.round(value / 60000);
    return `${minutes} 分钟`;
  }

  function direction(value) {
    if (!Number.isFinite(value) || value === 0) return "flat";
    return value > 0 ? "up" : "down";
  }

  function liveRequest(loading = false) {
    return window.StockMonitorLive.buildLiveRequest({
      items: state.items,
      sessionId: state.sessionId,
      loading,
      now: Date.now,
      formatPrice,
      formatPercent,
      quoteTimeLabel,
      sourceLabel
    });
  }

  async function publishLiveState(mode = "update", loading = false) {
    if (!state.monitoring || !state.sessionId) return true;
    const request = liveRequest(loading);
    try {
      if (mode === "start") await toolbox().notifications.live.start(request);
      else await toolbox().notifications.live.update(request);
      liveAvailable = true;
      liveLoading = loading;
      return true;
    } catch (error) {
      if (error?.code === "SESSION_ENDED" || error?.code === "INVALID_SESSION") {
        // The background session behind this sessionId is gone; ask the host which
        // session (if any) is current instead of reusing the stale id.
        try {
          await reconcileMonitoring();
          renderSummary();
          if (!state.monitoring) {
            setStatus("后台监控已结束。", "warning");
            return true;
          }
          await toolbox().notifications.live.start(liveRequest(loading));
          liveAvailable = true;
          liveLoading = loading;
          return true;
        } catch (_) {}
      } else if (mode === "update" && error?.code === "NOT_FOUND") {
        try {
          await toolbox().notifications.live.start(request);
          liveAvailable = true;
          liveLoading = loading;
          return true;
        } catch (_) {}
      }
      liveAvailable = false;
      showToast("实时展示不可用，行情监控仍会继续");
      return false;
    }
  }

  function setStatus(message, tone = "neutral") {
    statusMessage = message;
    statusTone = tone;
    if (!runtimeForeground) return;
    $("status").textContent = message;
    $("status-dot").dataset.tone = tone;
  }

  function showToast(message) {
    if (!runtimeForeground) return;
    const node = $("toast");
    node.textContent = message;
    node.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { node.hidden = true; }, 2600);
  }

  function errorMessage(error, fallback) {
    if (error?.code === "PERMISSION_DENIED" || error?.code === "NOT_DECLARED") {
      return "请在工具详情开启所需权限后重试。";
    }
    if (error?.code === "SYSTEM_PERMISSION_DENIED") {
      return "宿主系统权限未开启，请在后台保障或系统设置中授权。";
    }
    if (error?.code === "NETWORK_BLOCKED") return "行情地址被网络策略阻止。";
    if (error?.code === "NETWORK_TIMEOUT") return `行情请求超时（${REQUEST_TIMEOUT_MS / 1000} 秒无响应），下次刷新会重试。`;
    const message = typeof error?.message === "string" ? error.message.trim() : "";
    return message || fallback;
  }

  function quoteFragment(item) {
      const fragment = $("quote-template").content.cloneNode(true);
      const root = fragment.querySelector(".quote-row");
      root.dataset.id = item.id;
      fragment.querySelector(".quote-name").textContent = item.name || item.symbol;
      fragment.querySelector(".quote-symbol").textContent = `${item.symbol} · ${marketLabel(item)}`;
      fragment.querySelector(".quote-price").textContent = formatPrice(item.price, item.currency);
      const change = fragment.querySelector(".quote-change");
      change.textContent = formatPercent(item.changePct);
      change.dataset.direction = direction(item.changePct);
      const price = fragment.querySelector(".quote-price");
      price.dataset.direction = direction(item.changePct);
      const updated = fragment.querySelector(".quote-updated");
      updated.textContent = quoteTimeLabel(item);
      updated.dataset.tone = item.provider === "tencent" && Number.isFinite(item.quoteAt)
        && chinaMarketIsOpen() && Date.now() - item.quoteAt > 10 * 60 * 1000 ? "warning" : "normal";
      fragment.querySelector(".quote-source").textContent = sourceLabel(item.provider);

      const upper = fragment.querySelector(".upper-alert");
      if (item.upper !== null) {
        upper.textContent = `高于 ${formatPrice(item.upper, item.currency)} 提醒`;
        upper.dataset.active = String(item.price !== null && item.price >= item.upper);
      }
      const lower = fragment.querySelector(".lower-alert");
      if (item.lower !== null) {
        lower.textContent = `低于 ${formatPrice(item.lower, item.currency)} 提醒`;
        lower.dataset.active = String(item.price !== null && item.price <= item.lower);
      }

      const toggle = fragment.querySelector(".enabled-toggle");
      toggle.checked = item.enabled;
      toggle.setAttribute("aria-label", `${item.enabled ? "关闭" : "开启"}${item.name || item.symbol}监控`);
      const summary = fragment.querySelector(".quote-summary");
      summary.setAttribute("aria-label", `编辑 ${item.name || item.symbol}`);
      const itemError = fragment.querySelector(".quote-error");
      itemError.hidden = !item.error;
      itemError.textContent = item.error || "";
      return fragment;
  }

  function renderSummary() {
    if (!runtimeForeground) return;
    $("watch-count").textContent = `${state.items.length} 只`;
    $("empty").hidden = state.items.length > 0;
    $("monitor-state").dataset.active = String(state.monitoring);
    $("monitor-state").lastChild.textContent = state.monitoring ? "监控中" : "未监控";
    $("toggle-monitor").dataset.active = String(state.monitoring);
    $("toggle-monitor").disabled = Boolean(monitorAction);
    $("toggle-monitor").setAttribute("aria-busy", String(Boolean(monitorAction)));
    $("toggle-monitor").querySelector("span").textContent = monitorAction === "start" ? "正在启动…"
      : monitorAction === "stop" ? "正在停止…" : state.monitoring ? "停止监控" : "后台监控";
    $("last-refresh").textContent = state.lastRefreshAt ? formatTime(state.lastRefreshAt).replace("更新于 ", "数据更新：") : "尚未更新";
    const providers = [...new Set(state.items.map((item) => sourceLabel(item.provider)))];
    $("provider-summary").textContent = `数据源：${providers.length ? providers.join(" / ") : "未设置"}`;
  }

  function render() {
    if (!runtimeForeground) return;
    clearTimeout(paintTimer);
    paintTimer = null;
    dirtyItems.clear();
    const list = $("watchlist");
    list.replaceChildren(...state.items.map(quoteFragment));
    renderSummary();
    setStatus(statusMessage, statusTone);
    $("refresh").setAttribute("aria-busy", String(Boolean(refreshPromise)));
  }

  function paintDirtyItems() {
    paintTimer = null;
    if (!runtimeForeground) return;
    for (const id of dirtyItems) {
      const item = state.items.find((candidate) => candidate.id === id);
      const node = [...$("watchlist").children].find((candidate) => candidate.dataset.id === id);
      if (!item || !node) { render(); return; }
      node.replaceWith(quoteFragment(item));
    }
    dirtyItems.clear();
  }

  function queueItemPaint(id) {
    dirtyItems.add(id);
    if (runtimeForeground && !paintTimer) paintTimer = setTimeout(paintDirtyItems, 100);
  }

  function tencentCode(symbol) {
    if (!/^\d{6}$/.test(symbol)) throw new Error("腾讯行情需要六位 A 股代码。");
    if (/^[69]/.test(symbol)) return `sh${symbol}`;
    if (/^[03]/.test(symbol)) return `sz${symbol}`;
    if (/^[48]/.test(symbol)) return `bj${symbol}`;
    throw new Error("暂不识别此 A 股代码所属交易所。");
  }

  function parseTencentTimestamp(value) {
    const text = String(value || "");
    if (!/^\d{14}$/.test(text)) return null;
    const iso = `${text.slice(0, 4)}-${text.slice(4, 6)}-${text.slice(6, 8)}T${text.slice(8, 10)}:${text.slice(10, 12)}:${text.slice(12, 14)}+08:00`;
    const timestamp = Date.parse(iso);
    return Number.isFinite(timestamp) ? timestamp : null;
  }

  function withRequestDeadline(work) {
    // Host-side timeoutMs covers the HTTP exchange; this guard also settles the call if the
    // bridge itself never answers, so the shared refresh promise always completes.
    let timer = null;
    const deadline = new Promise((_, reject) => {
      timer = setTimeout(() => reject(Object.assign(new Error("timed out"), { code: "NETWORK_TIMEOUT" })),
        REQUEST_TIMEOUT_MS + 5000);
    });
    return Promise.race([work, deadline]).finally(() => clearTimeout(timer));
  }

  async function requestJson(request) {
    const response = await withRequestDeadline(toolbox().network.request({
      method: "GET",
      timeoutMs: REQUEST_TIMEOUT_MS,
      ...request
    }));
    const ok = response.status >= 200 && response.status < 300;
    if (!ok) {
      let message = "";
      if (response.bodyEncoding === "text") {
        try { message = JSON.parse(response.body)?.message; } catch (_) {}
      }
      throw new Error(typeof message === "string" && message ? message : `行情服务返回 HTTP ${response.status}`);
    }
    if (response.bodyEncoding !== "text") throw new Error("行情响应不是文本 JSON。");
    let data;
    try { data = JSON.parse(response.body); }
    catch (_) { throw new Error("行情数据格式无法解析。"); }
    if (data?.status === "error") throw new Error(typeof data.message === "string" ? data.message : "行情服务返回错误。");
    return data;
  }

  async function fetchTencent(item) {
    const code = tencentCode(item.symbol);
    const data = await requestJson({
      url: `https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=${encodeURIComponent(code)}`,
      headers: { Accept: "application/json" }
    });
    const record = data?.data?.[code];
    const quote = record?.qt?.[code];
    if (!Array.isArray(quote) || quote.length < 35) throw new Error("腾讯行情未返回有效报价。");
    const price = finitePositiveOrNull(quote[3]);
    const previousClose = finitePositiveOrNull(quote[4]);
    if (price === null || previousClose === null) throw new Error("当前报价无有效价格，可能停牌或代码不存在。");
    const changePct = finiteNumberOrNull(quote[32]) ?? ((price - previousClose) / previousClose * 100);
    return {
      name: String(quote[1] || item.name || item.symbol).trim(),
      price,
      previousClose,
      changePct,
      currency: "CNY",
      exchange: marketLabel(item),
      sourceTime: String(quote[30] || ""),
      quoteTimestamp: parseTencentTimestamp(quote[30])
    };
  }

  async function fetchTwelveData(item) {
    if (!tokenValue) throw new Error("请先保存 Twelve Data API Token。");
    if (!/^[A-Z0-9./:_-]+$/.test(item.symbol)) throw new Error("Twelve Data 股票代码格式无效。");
    const data = await requestJson({
      url: `https://api.twelvedata.com/quote?symbol=${encodeURIComponent(item.symbol)}`,
      headers: { Accept: "application/json", Authorization: `apikey ${tokenValue}` }
    });
    const price = finitePositiveOrNull(data.close ?? data.price);
    const previousClose = finitePositiveOrNull(data.previous_close);
    if (price === null) throw new Error("Twelve Data 未返回有效价格。");
    const changePct = finiteNumberOrNull(data.percent_change)
      ?? (previousClose ? ((price - previousClose) / previousClose * 100) : null);
    return {
      name: String(data.name || item.name || item.symbol).trim(),
      price,
      previousClose,
      changePct,
      currency: String(data.currency || item.currency || ""),
      exchange: String(data.exchange || item.exchange || "全球市场"),
      sourceTime: String(data.datetime || ""),
      quoteTimestamp: Number.isFinite(Number(data.timestamp)) ? Number(data.timestamp) * 1000 : null
    };
  }

  async function fetchQuote(item) {
    return item.provider === "twelve" ? fetchTwelveData(item) : fetchTencent(item);
  }

  async function deliverAlert(item, kind, threshold) {
    const above = kind === "above";
    const notificationId = `${item.id}-${kind}`;
    const title = `${item.name || item.symbol}价格提醒`;
    const body = `${item.symbol} 当前 ${formatPrice(item.price, item.currency)}，已${above ? "高于" : "低于"} ${formatPrice(threshold, item.currency)}。`;
    try {
      await toolbox().notifications.post(notificationId, title, body);
      return true;
    } catch (error) {
      item.error = errorMessage(error, "价格已触发，但系统通知发送失败。");
      return false;
    }
  }

  async function evaluateAlerts(item) {
    if (runtimeClosing) return;
    if (item.upper !== null) {
      const active = item.price >= item.upper;
      if (active && !item.aboveLatched) item.aboveLatched = await deliverAlert(item, "above", item.upper);
      if (!active) item.aboveLatched = false;
    } else {
      item.aboveLatched = false;
    }
    if (runtimeClosing) return;
    if (item.lower !== null) {
      const active = item.price <= item.lower;
      if (active && !item.belowLatched) item.belowLatched = await deliverAlert(item, "below", item.lower);
      if (!active) item.belowLatched = false;
    } else {
      item.belowLatched = false;
    }
  }

  async function refreshAll(options = {}) {
    if (runtimeClosing) return;
    if (refreshPromise) {
      // A manual refresh must not be absorbed by a background pass that skipped
      // closed-market items; run it once the current pass ends.
      if (!options.force || refreshForced) return refreshPromise;
      if (!forcedFollowUp) {
        forcedFollowUp = refreshPromise.catch(() => {}).then(() => {
          forcedFollowUp = null;
          return refreshAll(options);
        });
      }
      return forcedFollowUp;
    }
    refreshForced = Boolean(options.force);
    refreshPromise = (async () => {
      const active = state.items.filter((item) => item.enabled);
      if (!active.length) {
        setStatus(
          liveAvailable ? "没有启用的股票。" : "实时展示不可用；没有启用的股票，后台环境仍在运行。",
          "warning"
        );
        return;
      }
      const closedSince = options.force ? null : chinaMarketClosedSince();
      const enabled = active.filter((item) => !skipWhileClosed(item, closedSince));
      if (!enabled.length) {
        // Monitoring started during the closed period: show the cached closing quotes
        // instead of leaving the live notification on its loading text overnight.
        if (liveLoading && !runtimeClosing) await publishLiveState();
        setStatus("A 股休市中，已保留收盘行情；开盘后自动恢复刷新。");
        return;
      }
      if (runtimeForeground) $("refresh").setAttribute("aria-busy", "true");
      setStatus(`正在更新 ${enabled.length} 只股票…`);
      let successCount = 0;
      for (const item of enabled) {
        if (runtimeClosing) break;
        item.error = null;
        try {
          const quote = await fetchQuote(item);
          if (runtimeClosing) break;
          item.name = quote.name || item.name;
          item.price = quote.price;
          item.previousClose = quote.previousClose;
          item.changePct = quote.changePct;
          item.currency = quote.currency;
          item.exchange = quote.exchange;
          item.sourceTime = quote.sourceTime;
          item.quoteAt = quote.quoteTimestamp;
          item.fetchedAt = Date.now();
          successCount += 1;
          await evaluateAlerts(item);
        } catch (error) {
          if (!runtimeClosing) item.error = errorMessage(error, "行情更新失败，请稍后重试。");
        }
        if (runtimeClosing) break;
        queueItemPaint(item.id);
      }
      if (runtimeClosing) {
        await persist();
        return;
      }
      state.lastRefreshAt = Date.now();
      try { await persist(); }
      catch (error) { setStatus(errorMessage(error, "行情已更新，但状态保存失败。"), "warning"); return; }
      if (runtimeClosing) return;
      const liveUpdated = await publishLiveState();
      const failedCount = enabled.length - successCount;
      if (!liveUpdated) setStatus("行情已更新；实时展示失败，后台监控仍继续。", "warning");
      else if (failedCount === 0) setStatus(`${successCount} 只股票已更新。`, "success");
      else if (successCount > 0) setStatus(`${successCount} 只已更新，${failedCount} 只失败。`, "warning");
      else setStatus("行情更新失败，请检查网络权限、代码或 Token。", "error");
      if (options.announce && successCount > 0) showToast("行情已刷新");
    })().finally(() => {
      refreshPromise = null;
      if (runtimeForeground) {
        $("refresh").removeAttribute("aria-busy");
        renderSummary();
      }
    });
    return refreshPromise;
  }

  async function startMonitoring() {
    let session = null;
    try {
      session = await toolbox().background.start({ restoreAfterProcessDeath: true, restoreAfterReboot: true });
      state.monitoring = true;
      state.sessionId = session.sessionId;
      const liveStarted = await publishLiveState("start", true);
      await toolbox().background.setTimer(TIMER_KEY, state.intervalMs);
      await persist();
      render();
      setStatus(
        liveStarted
          ? `后台监控已启动，每 ${intervalLabel(state.intervalMs)}更新。`
          : "后台监控已启动，但实时展示不可用。",
        liveStarted ? "success" : "warning"
      );
      showToast("后台监控已启动");
      return true;
    } catch (error) {
      if (session?.sessionId) {
        try { await toolbox().background.stop(session.sessionId); } catch (_) {}
      }
      state.monitoring = false;
      state.sessionId = null;
      render();
      setStatus(errorMessage(error, "后台监控启动失败。"), "error");
      return false;
    }
  }

  async function stopMonitoring() {
    if (state.sessionId) {
      try { await toolbox().notifications.live.end(state.sessionId); } catch (_) {}
    }
    try { await toolbox().background.cancelTimer(TIMER_KEY); } catch (_) {}
    try {
      if (state.sessionId) await toolbox().background.stop(state.sessionId);
    } catch (error) {
      if (error?.code !== "NOT_FOUND") {
        setStatus(errorMessage(error, "停止后台监控失败。"), "error");
        return;
      }
    }
    state.monitoring = false;
    state.sessionId = null;
    try { await persist(); } catch (_) {}
    render();
    setStatus("后台监控已停止。", "success");
    showToast("后台监控已停止");
  }

  async function reconcileMonitoring() {
    const sessions = await toolbox().background.listSessions();
    if (sessions.length) {
      state.monitoring = true;
      state.sessionId = sessions[0].sessionId;
      await toolbox().background.setTimer(TIMER_KEY, state.intervalMs);
    } else {
      state.monitoring = false;
      state.sessionId = null;
    }
    await persist();
  }

  function selectedProvider() {
    return $("stock-form").elements.provider.value;
  }

  function updateProviderFields() {
    const twelve = selectedProvider() === "twelve";
    $("token-panel").hidden = !twelve;
    $("token-state").textContent = tokenPresent ? "已安全保存" : "尚未保存";
    $("clear-token").disabled = !tokenPresent;
    $("symbol").placeholder = twelve ? "如 AAPL 或 0700:XHKG" : "如 600550";
  }

  function openEditor(item = null) {
    editorToken += 1;
    editingId = item?.id || null;
    $("editor-title").textContent = item ? "编辑监控" : "添加监控";
    $("symbol").value = item?.symbol || "";
    $("stock-name").value = item?.name || "";
    $("upper-price").value = item?.upper ?? "";
    $("lower-price").value = item?.lower ?? "";
    $("enabled").checked = item?.enabled !== false;
    $("interval").value = String(state.intervalMs);
    $("api-token").value = "";
    $("form-error").hidden = true;
    $("delete-stock").hidden = !item;
    const provider = item?.provider || "tencent";
    const radio = $("stock-form").querySelector(`input[name="provider"][value="${provider}"]`);
    if (radio) radio.checked = true;
    updateProviderFields();
    $("editor").showModal();
    setTimeout(() => $("symbol").focus(), 80);
  }

  function closeEditor() {
    editorToken += 1;
    $("editor").close();
    editingId = null;
    $("api-token").value = "";
  }

  function formNumber(id) {
    const value = $(id).value.trim();
    if (!value) return null;
    const number = Number(value);
    return Number.isFinite(number) && number > 0 ? number : NaN;
  }

  function showFormError(message) {
    $("form-error").textContent = message;
    $("form-error").hidden = false;
  }

  async function saveEditor(event) {
    event.preventDefault();
    if (editorBusy) return;
    editorBusy = true;
    try { await saveEditorChanges(editingId, editorToken); }
    finally { editorBusy = false; }
  }

  async function saveEditorChanges(targetId, token) {
    const provider = selectedProvider();
    const symbol = $("symbol").value.trim().toUpperCase();
    const name = $("stock-name").value.trim();
    const upper = formNumber("upper-price");
    const lower = formNumber("lower-price");
    const intervalMs = Number($("interval").value);
    const newToken = $("api-token").value.trim();

    if (!symbol) { showFormError("请输入股票代码。"); return; }
    if (provider === "tencent" && !/^\d{6}$/.test(symbol)) { showFormError("腾讯行情需要六位 A 股代码。"); return; }
    if (provider === "twelve" && !/^[A-Z0-9./:_-]+$/.test(symbol)) { showFormError("Twelve Data 股票代码格式无效。"); return; }
    if (Number.isNaN(upper) || Number.isNaN(lower)) { showFormError("提醒价格必须是大于 0 的数字。"); return; }
    if (upper !== null && lower !== null && lower >= upper) { showFormError("低价提醒必须小于高价提醒。"); return; }
    if (!VALID_INTERVALS.has(intervalMs)) { showFormError("监控间隔无效。"); return; }
    if (provider === "twelve" && !tokenPresent && !newToken) { showFormError("使用 Twelve Data 前请填写 API Token。"); return; }

    try {
      if (newToken) {
        await toolbox().storage.secure.set(TOKEN_KEY, newToken);
        tokenValue = newToken;
        tokenPresent = true;
        $("api-token").value = "";
      }
      const existingIndex = state.items.findIndex((item) => item.id === targetId);
      const existing = existingIndex >= 0 ? state.items[existingIndex] : null;
      const duplicate = state.items.some((item) => item.id !== targetId && item.provider === provider && item.symbol === symbol);
      if (duplicate) { showFormError("这只股票已经在监控列表中。"); return; }
      const updated = createItem({
        ...existing,
        provider,
        symbol,
        name,
        upper,
        lower,
        enabled: $("enabled").checked,
        aboveLatched: existing?.upper === upper ? existing.aboveLatched : false,
        belowLatched: existing?.lower === lower ? existing.belowLatched : false
      });
      const nextItems = state.items.slice();
      if (existingIndex >= 0) nextItems.splice(existingIndex, 1, updated);
      else nextItems.push(updated);
      const intervalChanged = intervalMs !== state.intervalMs;
      // Only show the change once it is saved; a failed write keeps the old list.
      await persistUserChange({ ...state, items: nextItems, intervalMs });
      const currentIndex = state.items.findIndex((item) => item.id === targetId);
      if (currentIndex >= 0) state.items.splice(currentIndex, 1, updated);
      else state.items.push(updated);
      state.intervalMs = intervalMs;
      let timerError = null;
      if (state.monitoring && intervalChanged) {
        try { await toolbox().background.setTimer(TIMER_KEY, intervalMs); }
        catch (error) { timerError = error; }
      }
      if (token === editorToken) closeEditor();
      render();
      showToast(existing ? "监控设置已更新" : "股票已添加");
      await refreshAll({ force: true });
      if (timerError) setStatus(errorMessage(timerError, "设置已保存，但监控间隔更新失败。"), "warning");
    } catch (error) {
      if (token === editorToken) showFormError(errorMessage(error, "保存失败，请检查存储或后台权限。"));
      else setStatus(errorMessage(error, "保存失败，请检查存储或后台权限。"), "error");
    }
  }

  async function deleteEditingStock() {
    const item = state.items.find((candidate) => candidate.id === editingId);
    if (!item || editorBusy) return;
    const token = editorToken;
    editorBusy = true;
    try {
      await persistUserChange({ ...state, items: state.items.filter((candidate) => candidate.id !== item.id) });
      state.items = state.items.filter((candidate) => candidate.id !== item.id);
      for (const kind of ["above", "below"]) {
        const id = `${item.id}-${kind}`;
        try { await toolbox().notifications.cancel(id); } catch (_) {}
      }
      if (token === editorToken) closeEditor();
      render();
      showToast("股票已删除");
    } catch (error) {
      if (token === editorToken) showFormError(errorMessage(error, "删除失败。"));
      else setStatus(errorMessage(error, "删除失败。"), "error");
    } finally {
      editorBusy = false;
    }
  }

  async function clearToken() {
    try {
      await toolbox().storage.secure.remove(TOKEN_KEY);
      tokenValue = "";
      tokenPresent = false;
      $("api-token").value = "";
      updateProviderFields();
      showToast("Token 已清除");
    } catch (error) {
      showFormError(errorMessage(error, "Token 清除失败。"));
    }
  }

  async function changeItemEnabled(event) {
    const toggle = event.target.closest(".enabled-toggle");
    if (!toggle) return;
    const root = event.target.closest(".quote-row");
    const item = state.items.find((candidate) => candidate.id === root?.dataset.id);
    if (!item) return;
    item.enabled = toggle.checked;
    item.error = null;
    try { await persistUserChange(); }
    catch (error) { item.enabled = !toggle.checked; setStatus(errorMessage(error, "设置保存失败。"), "error"); }
    render();
  }

  async function loadSavedState() {
    try {
      state = sanitizeState(await toolbox().storage.get(STORAGE_KEY));
      savedStateUnreadable = false;
      savedStateEverRead = true;
    } catch (_) {
      // Keep the in-memory list so quotes still work, but refuse to persist over the
      // saved list until a later read succeeds.
      savedStateUnreadable = true;
    }
    stateLoaded = true;
    try {
      const savedToken = await toolbox().storage.secure.get(TOKEN_KEY);
      tokenValue = typeof savedToken === "string" ? savedToken : "";
      tokenPresent = Boolean(tokenValue);
    } catch (_) {
      tokenValue = "";
      tokenPresent = false;
    }
  }

  // Timer events and user refreshes can arrive before boot has read the saved watchlist;
  // running them earlier would fetch and persist the default list over the user's own.
  function afterStateLoaded(run) {
    return stateLoadedWork.then(() => (stateLoaded && !runtimeClosing ? run() : undefined));
  }

  async function boot() {
    render();
    if (!toolbox()?.ready) {
      setStatus("ToolBox 宿主 API 不可用。", "error");
      $("runtime-caption").textContent = "宿主 API 不可用";
      markStateLoaded();
      return;
    }
    try {
      const host = await toolbox().ready();
      toolbox().runtime.onStateChanged((snapshot) => {
        runtimeClosing = snapshot.closing;
        runtimeForeground = snapshot.foreground && !snapshot.closing;
        if (runtimeForeground) render();
        else {
          clearTimeout(paintTimer);
          paintTimer = null;
        }
        if (!runtimeClosing && restorePending) resumeRestore();
        else if (runtimeForeground) retrySavedState();
      });
      toolbox().runtime.registerFlushHandler(async () => {
        await Promise.allSettled([bootWork, restoreWork, monitorWork, ...userActions]);
        await refreshPromise?.catch(() => {});
        await persist();
      });
      ready = true;
      $("runtime-caption").textContent = `ToolBox ${host.hostVersion} · API ${host.apiVersion}`;
      await loadSavedState();
      markStateLoaded();
      // The interval and session in memory are defaults until the saved state is read;
      // reconciling now would reset the running timer to them. Retried on resume/timer.
      if (!savedStateUnreadable) {
        try {
          await reconcileMonitoring();
        } catch (error) {
          if (error?.code !== "PERMISSION_DENIED" && error?.code !== "NOT_DECLARED") throw error;
          state.monitoring = false;
          state.sessionId = null;
        }
      }
      render();
      if (savedStateUnreadable) setStatus("读取已保存的自选列表失败，修改暂时无法保存，稍后会自动重试。", "warning");
      else setStatus(state.monitoring ? `后台监控已连接，每 ${intervalLabel(state.intervalMs)}更新。` : "准备就绪。", "success");
      if (runtimeClosing) return;
      if (state.monitoring) await publishLiveState("start");
      await refreshAll();
    } catch (error) {
      markStateLoaded();
      setStatus(errorMessage(error, "初始化失败，请检查工具权限。"), "error");
      render();
    }
  }

  if (toolbox()?.background?.onTimer) {
    toolbox().background.onTimer((event) => {
      if (!runtimeClosing && event?.key === TIMER_KEY) {
        afterStateLoaded(() => (savedStateUnreadable ? retrySavedState() : refreshAll({ background: true }))).catch(() => {});
      }
    });
  }
  // Boot could not read the saved state: read it again (resume or timer) and only then
  // reconcile the background timer and live notification with it.
  function retrySavedState() {
    if (!ready || !stateLoaded || !savedStateUnreadable || runtimeClosing) return;
    restorePending = true;
    resumeRestore();
  }

  function resumeRestore() {
    if (runtimeClosing || restoreWork || !restorePending) return;
    restorePending = false;
    const work = (async () => { try {
        await bootWork;
        if (runtimeClosing) { restorePending = true; return; }
        if (!ready) return;
        const wasDefaults = !savedStateEverRead;
        await loadSavedState();
        if (runtimeClosing) { restorePending = true; return; }
        // Memory still holds defaults (never read): do not reconcile with them.
        if (!savedStateEverRead) {
          setStatus("读取已保存的自选列表失败，修改暂时无法保存，稍后会自动重试。", "warning");
          return;
        }
        await reconcileMonitoring();
        if (runtimeClosing) { restorePending = true; return; }
        render();
        if (wasDefaults) {
          setStatus(state.monitoring ? `后台监控已连接，每 ${intervalLabel(state.intervalMs)}更新。` : "已读取保存的自选列表。", "success");
        }
        if (state.monitoring) await publishLiveState("start");
        await refreshAll({ background: true });
      } catch (error) {
        if (runtimeClosing) restorePending = true;
        setStatus(errorMessage(error, "后台环境恢复失败。"), "error");
      } })();
    restoreWork = work;
    void work.finally(() => {
      if (restoreWork === work) restoreWork = null;
      if (restorePending && !runtimeClosing) resumeRestore();
    }).catch(() => {});
  }
  if (toolbox()?.background?.onRestore) {
    toolbox().background.onRestore(() => {
      restorePending = true;
      resumeRestore();
    });
  }

  $("refresh").addEventListener("click", () => {
    if (runtimeClosing) return;
    if (bootWork && !stateLoaded) showToast("正在读取自选列表…");
    trackUserAction(afterStateLoaded(() => {
      if (!ready) { showToast("ToolBox 尚未连接"); return undefined; }
      return refreshAll({ announce: true, force: true });
    }).catch(() => {}));
  });
  $("add-stock").addEventListener("click", () => openEditor());
  $("toggle-monitor").addEventListener("click", () => {
    if (monitorAction || runtimeClosing) return;
    if (!ready) { showToast("ToolBox 尚未连接"); return; }
    if (!stateLoaded) { showToast("正在读取自选列表…"); return; }
    monitorAction = state.monitoring ? "stop" : "start";
    render();
    const work = (async () => {
      let started = false;
      try {
        if (monitorAction === "stop") await stopMonitoring();
        else started = await startMonitoring();
      } finally {
        monitorAction = null;
        render();
      }
      if (started && !runtimeClosing) await refreshAll();
    })();
    monitorWork = work;
    void work.catch((error) => setStatus(errorMessage(error, "监控操作失败。"), "error"))
      .finally(() => { if (monitorWork === work) monitorWork = null; });
  });
  $("watchlist").addEventListener("click", (event) => {
    const trigger = event.target.closest(".quote-summary, .edit-button");
    if (!trigger) return;
    const root = trigger.closest(".quote-row");
    const item = state.items.find((candidate) => candidate.id === root?.dataset.id);
    if (item) openEditor(item);
  });
  $("watchlist").addEventListener("change", (event) => { if (!runtimeClosing) trackUserAction(afterStateLoaded(() => changeItemEnabled(event))); });
  $("stock-form").addEventListener("change", (event) => {
    if (event.target.name === "provider") updateProviderFields();
  });
  $("stock-form").addEventListener("submit", (event) => {
    event.preventDefault();
    if (!runtimeClosing) trackUserAction(afterStateLoaded(() => saveEditor(event)));
  });
  $("cancel-editor").addEventListener("click", closeEditor);
  $("delete-stock").addEventListener("click", () => { if (!runtimeClosing) trackUserAction(afterStateLoaded(deleteEditingStock)); });
  $("clear-token").addEventListener("click", () => { if (!runtimeClosing) trackUserAction(clearToken()); });
  $("editor").addEventListener("cancel", (event) => { event.preventDefault(); closeEditor(); });

  bootWork = boot();
})();
