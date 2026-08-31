(() => {
  "use strict";

  const STORAGE_KEY = "stock-monitor-state-v1";
  const TOKEN_KEY = "twelve-data-api-key";
  const TIMER_KEY = "quote-poll";
  const MAX_RESPONSE_BYTES = 131072;
  const DEFAULT_INTERVAL_MS = 300000;
  const VALID_INTERVALS = new Set([60000, 300000, 900000, 1800000]);
  const $ = (id) => document.getElementById(id);
  const toolbox = () => window.ToolBox;

  let state = createInitialState();
  let tokenPresent = false;
  let tokenValue = "";
  let editingId = null;
  let refreshPromise = null;
  let toastTimer = null;
  let ready = false;
  let liveAvailable = true;

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
      currency: typeof input.currency === "string" ? input.currency.slice(0, 12) : "CNY",
      exchange: typeof input.exchange === "string" ? input.exchange.slice(0, 30) : "",
      sourceTime: typeof input.sourceTime === "string" ? input.sourceTime.slice(0, 40) : "",
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

  function persistedState() {
    return {
      version: 1,
      intervalMs: state.intervalMs,
      monitoring: state.monitoring,
      sessionId: state.sessionId,
      lastRefreshAt: state.lastRefreshAt,
      items: state.items.map(({ error, ...item }) => item)
    };
  }

  async function persist() {
    await toolbox().storage.set(STORAGE_KEY, persistedState());
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
    const china = new Date(now.toLocaleString("en-US", { timeZone: "Asia/Shanghai" }));
    const day = china.getDay();
    if (day === 0 || day === 6) return false;
    const minutes = china.getHours() * 60 + china.getMinutes();
    return (minutes >= 570 && minutes <= 690) || (minutes >= 780 && minutes <= 900);
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

  function firstEnabledItem() {
    return state.items.find((item) => item.enabled) || null;
  }

  function liveTone(item) {
    if (!Number.isFinite(item?.changePct) || item.changePct === 0) {
      return { tone: "neutral", accentColor: "#0A84FF" };
    }
    return item.changePct > 0
      ? { tone: "positive", accentColor: "#E53935" }
      : { tone: "negative", accentColor: "#00A870" };
  }

  function liveRequest(item, loading = false) {
    const target = item || { name: "行情哨兵", symbol: "", provider: "tencent" };
    const displayName = target.name || target.symbol || "行情哨兵";
    const title = target.symbol ? `${displayName} · ${target.symbol}` : displayName;
    const updatedAt = target.quoteAt || target.fetchedAt || Date.now();
    const price = loading ? "正在获取行情" : formatPrice(target.price, target.currency);
    const change = loading ? "等待最新报价" : `${formatPercent(target.changePct)} · ${quoteTimeLabel(target)}`;
    const shortText = loading || !Number.isFinite(target.price)
      ? "获取中"
      : Number(target.price).toLocaleString("zh-CN", { maximumFractionDigits: 4 }).slice(0, 12);
    const tone = liveTone(target);
    return {
      sessionId: state.sessionId,
      title: title.slice(0, 64),
      primaryText: price.slice(0, 32),
      secondaryText: change.slice(0, 96),
      body: `${sourceLabel(target.provider)} · ${title} · ${price} · ${change}`.slice(0, 256),
      shortText,
      updatedAt,
      accentColor: tone.accentColor,
      tone: tone.tone
    };
  }

  async function publishLiveState(item, mode = "update", loading = false) {
    if (!state.monitoring || !state.sessionId) return true;
    const request = liveRequest(item, loading);
    try {
      if (mode === "start") await toolbox().notifications.live.start(request);
      else await toolbox().notifications.live.update(request);
      liveAvailable = true;
      return true;
    } catch (error) {
      if (mode === "update" && error?.code === "NOT_FOUND") {
        try {
          await toolbox().notifications.live.start(request);
          liveAvailable = true;
          return true;
        } catch (_) {}
      }
      liveAvailable = false;
      showToast("实时展示不可用，行情监控仍会继续");
      return false;
    }
  }

  function setStatus(message, tone = "neutral") {
    $("status").textContent = message;
    $("status-dot").dataset.tone = tone;
  }

  function showToast(message) {
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
    if (error?.code === "RATE_LIMITED") return "请求过于频繁，请稍后重试。";
    const message = typeof error?.message === "string" ? error.message.trim() : "";
    return message && message.length <= 160 ? message : fallback;
  }

  function render() {
    const list = $("watchlist");
    list.replaceChildren();
    $("watch-count").textContent = `${state.items.length} 只`;
    $("empty").hidden = state.items.length > 0;

    state.items.forEach((item) => {
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
      list.append(fragment);
    });

    $("monitor-state").dataset.active = String(state.monitoring);
    $("monitor-state").lastChild.textContent = state.monitoring ? "监控中" : "未监控";
    $("toggle-monitor").dataset.active = String(state.monitoring);
    $("toggle-monitor").querySelector("span").textContent = state.monitoring ? "停止监控" : "后台监控";
    $("last-refresh").textContent = state.lastRefreshAt ? formatTime(state.lastRefreshAt).replace("更新于 ", "数据更新：") : "尚未更新";
    const providers = [...new Set(state.items.map((item) => sourceLabel(item.provider)))];
    $("provider-summary").textContent = `数据源：${providers.length ? providers.join(" / ") : "未设置"}`;
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

  async function requestJson(request) {
    const response = await toolbox().network.request({
      method: "GET",
      timeoutMs: 20000,
      maxResponseBytes: MAX_RESPONSE_BYTES,
      ...request
    });
    if (response.bodyEncoding !== "text") throw new Error("行情响应不是文本 JSON。");
    let data;
    try { data = JSON.parse(response.body); }
    catch (_) { throw new Error("行情数据格式无法解析。"); }
    if (response.status < 200 || response.status >= 300) {
      throw new Error(typeof data?.message === "string" ? data.message : `行情服务返回 HTTP ${response.status}`);
    }
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
    if (!/^[A-Z0-9./:_-]{1,32}$/.test(item.symbol)) throw new Error("Twelve Data 股票代码格式无效。");
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
      currency: String(data.currency || item.currency || "").slice(0, 12),
      exchange: String(data.exchange || item.exchange || "全球市场").slice(0, 30),
      sourceTime: String(data.datetime || ""),
      quoteTimestamp: Number.isFinite(Number(data.timestamp)) ? Number(data.timestamp) * 1000 : null
    };
  }

  async function fetchQuote(item) {
    return item.provider === "twelve" ? fetchTwelveData(item) : fetchTencent(item);
  }

  async function deliverAlert(item, kind, threshold) {
    const above = kind === "above";
    const notificationId = `${item.id}-${kind}`.slice(0, 64);
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
    if (item.upper !== null) {
      const active = item.price >= item.upper;
      if (active && !item.aboveLatched) item.aboveLatched = await deliverAlert(item, "above", item.upper);
      if (!active) item.aboveLatched = false;
    } else {
      item.aboveLatched = false;
    }
    if (item.lower !== null) {
      const active = item.price <= item.lower;
      if (active && !item.belowLatched) item.belowLatched = await deliverAlert(item, "below", item.lower);
      if (!active) item.belowLatched = false;
    } else {
      item.belowLatched = false;
    }
  }

  async function refreshAll(options = {}) {
    if (refreshPromise) return refreshPromise;
    refreshPromise = (async () => {
      const enabled = state.items.filter((item) => item.enabled);
      if (!enabled.length) {
        setStatus(
          liveAvailable ? "没有启用的股票。" : "实时展示不可用；没有启用的股票，后台环境仍在运行。",
          "warning"
        );
        return;
      }
      $("refresh").setAttribute("aria-busy", "true");
      setStatus(`正在更新 ${enabled.length} 只股票…`);
      let successCount = 0;
      for (const item of enabled) {
        item.error = null;
        try {
          const quote = await fetchQuote(item);
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
          item.error = errorMessage(error, "行情更新失败，请稍后重试。");
        }
        render();
      }
      state.lastRefreshAt = Date.now();
      try { await persist(); }
      catch (error) { setStatus(errorMessage(error, "行情已更新，但状态保存失败。"), "warning"); return; }
      const liveUpdated = await publishLiveState(firstEnabledItem());
      const failedCount = enabled.length - successCount;
      if (!liveUpdated) setStatus("行情已更新；实时展示失败，后台监控仍继续。", "warning");
      else if (failedCount === 0) setStatus(`${successCount} 只股票已更新。`, "success");
      else if (successCount > 0) setStatus(`${successCount} 只已更新，${failedCount} 只失败。`, "warning");
      else setStatus("行情更新失败，请检查网络权限、代码或 Token。", "error");
      if (options.announce && successCount > 0) showToast("行情已刷新");
    })().finally(() => {
      refreshPromise = null;
      $("refresh").removeAttribute("aria-busy");
      render();
    });
    return refreshPromise;
  }

  async function startMonitoring() {
    let session = null;
    try {
      session = await toolbox().background.start({ restoreAfterProcessDeath: true, restoreAfterReboot: true });
      state.monitoring = true;
      state.sessionId = session.sessionId;
      const liveStarted = await publishLiveState(firstEnabledItem(), "start", true);
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
      await refreshAll();
    } catch (error) {
      if (session?.sessionId) {
        try { await toolbox().background.stop(session.sessionId); } catch (_) {}
      }
      state.monitoring = false;
      state.sessionId = null;
      render();
      setStatus(errorMessage(error, "后台监控启动失败。"), "error");
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
    const provider = selectedProvider();
    const symbol = $("symbol").value.trim().toUpperCase();
    const name = $("stock-name").value.trim();
    const upper = formNumber("upper-price");
    const lower = formNumber("lower-price");
    const intervalMs = Number($("interval").value);
    const newToken = $("api-token").value.trim();

    if (!symbol) { showFormError("请输入股票代码。"); return; }
    if (provider === "tencent" && !/^\d{6}$/.test(symbol)) { showFormError("腾讯行情需要六位 A 股代码。"); return; }
    if (provider === "twelve" && !/^[A-Z0-9./:_-]{1,32}$/.test(symbol)) { showFormError("Twelve Data 股票代码格式无效。"); return; }
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
      const existingIndex = state.items.findIndex((item) => item.id === editingId);
      const existing = existingIndex >= 0 ? state.items[existingIndex] : null;
      const duplicate = state.items.some((item) => item.id !== editingId && item.provider === provider && item.symbol === symbol);
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
      if (existingIndex >= 0) state.items.splice(existingIndex, 1, updated);
      else state.items.push(updated);
      state.intervalMs = intervalMs;
      if (state.monitoring) await toolbox().background.setTimer(TIMER_KEY, intervalMs);
      await persist();
      closeEditor();
      render();
      showToast(existing ? "监控设置已更新" : "股票已添加");
      await refreshAll();
    } catch (error) {
      showFormError(errorMessage(error, "保存失败，请检查存储或后台权限。"));
    }
  }

  async function deleteEditingStock() {
    const item = state.items.find((candidate) => candidate.id === editingId);
    if (!item) return;
    state.items = state.items.filter((candidate) => candidate.id !== editingId);
    for (const kind of ["above", "below"]) {
      const id = `${item.id}-${kind}`.slice(0, 64);
      try { await toolbox().notifications.cancel(id); } catch (_) {}
    }
    try {
      await persist();
      closeEditor();
      render();
      showToast("股票已删除");
    } catch (error) {
      showFormError(errorMessage(error, "删除失败。"));
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
    try { await persist(); }
    catch (error) { item.enabled = !toggle.checked; setStatus(errorMessage(error, "设置保存失败。"), "error"); }
    render();
  }

  async function boot() {
    render();
    if (!toolbox()?.ready) {
      setStatus("请在 ToolBox 0.3.1 或更高版本中运行。", "error");
      $("runtime-caption").textContent = "宿主 API 不可用";
      return;
    }
    try {
      const host = await toolbox().ready();
      ready = true;
      $("runtime-caption").textContent = `ToolBox ${host.hostVersion} · API ${host.apiVersion}`;
      state = sanitizeState(await toolbox().storage.get(STORAGE_KEY));
      try {
        const savedToken = await toolbox().storage.secure.get(TOKEN_KEY);
        tokenValue = typeof savedToken === "string" ? savedToken : "";
        tokenPresent = Boolean(tokenValue);
      } catch (_) {
        tokenValue = "";
        tokenPresent = false;
      }
      try {
        await reconcileMonitoring();
      } catch (error) {
        if (error?.code !== "PERMISSION_DENIED" && error?.code !== "NOT_DECLARED") throw error;
        state.monitoring = false;
        state.sessionId = null;
      }
      render();
      setStatus(state.monitoring ? `后台监控已连接，每 ${intervalLabel(state.intervalMs)}更新。` : "准备就绪。", "success");
      if (state.monitoring) await publishLiveState(firstEnabledItem(), "start");
      await refreshAll();
    } catch (error) {
      setStatus(errorMessage(error, "初始化失败，请检查工具权限。"), "error");
      render();
    }
  }

  if (toolbox()?.background?.onTimer) {
    toolbox().background.onTimer((event) => {
      if (event?.key === TIMER_KEY) refreshAll({ background: true }).catch(() => {});
    });
  }
  if (toolbox()?.background?.onRestore) {
    toolbox().background.onRestore(async () => {
      try {
        state = sanitizeState(await toolbox().storage.get(STORAGE_KEY));
        const savedToken = await toolbox().storage.secure.get(TOKEN_KEY);
        tokenValue = typeof savedToken === "string" ? savedToken : "";
        tokenPresent = Boolean(tokenValue);
        await reconcileMonitoring();
        render();
        if (state.monitoring) await publishLiveState(firstEnabledItem(), "start");
        await refreshAll({ background: true });
      } catch (error) {
        setStatus(errorMessage(error, "后台环境恢复失败。"), "error");
      }
    });
  }

  $("refresh").addEventListener("click", () => refreshAll({ announce: true }));
  $("add-stock").addEventListener("click", () => openEditor());
  $("toggle-monitor").addEventListener("click", async () => {
    if (!ready) { showToast("ToolBox 尚未连接"); return; }
    if (state.monitoring) await stopMonitoring(); else await startMonitoring();
  });
  $("watchlist").addEventListener("click", (event) => {
    const trigger = event.target.closest(".quote-summary, .edit-button");
    if (!trigger) return;
    const root = trigger.closest(".quote-row");
    const item = state.items.find((candidate) => candidate.id === root?.dataset.id);
    if (item) openEditor(item);
  });
  $("watchlist").addEventListener("change", changeItemEnabled);
  $("stock-form").addEventListener("change", (event) => {
    if (event.target.name === "provider") updateProviderFields();
  });
  $("stock-form").addEventListener("submit", saveEditor);
  $("cancel-editor").addEventListener("click", closeEditor);
  $("delete-stock").addEventListener("click", deleteEditingStock);
  $("clear-token").addEventListener("click", clearToken);
  $("editor").addEventListener("cancel", (event) => { event.preventDefault(); closeEditor(); });

  boot();
})();
