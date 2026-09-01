(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.StockMonitorLive = api;
})(typeof globalThis === "object" ? globalThis : this, function () {
  "use strict";

  function toneFor(item) {
    if (!Number.isFinite(item?.changePct) || item.changePct === 0) {
      return { tone: "neutral", accentColor: "#0A84FF" };
    }
    return item.changePct > 0
      ? { tone: "positive", accentColor: "#E53935" }
      : { tone: "negative", accentColor: "#00A870" };
  }

  function liveLine(item, options) {
    const name = item.name || item.symbol || "股票";
    const price = options.formatPrice(item.price, item.currency);
    const change = options.formatPercent(item.changePct);
    return `${name} ${price} ${change}`;
  }

  function buildLiveRequest(options) {
    const enabled = options.items.filter((item) => item.enabled);
    const target = enabled[0]
      || { name: "行情哨兵", symbol: "", provider: "tencent" };
    const title = enabled.length ? `行情哨兵 · ${enabled.length} 只` : "行情哨兵";
    const timestamps = enabled
      .flatMap((item) => [item.quoteAt, item.fetchedAt])
      .filter(Number.isFinite);
    const updatedAt = timestamps.length ? Math.max(...timestamps) : options.now();
    const primaryText = options.loading
      ? `正在获取 ${enabled.length || 1} 只股票`
      : liveLine(target, options);
    const remaining = enabled.slice(1);
    const secondaryText = options.loading
      ? enabled.map((item) => item.name || item.symbol).join("、") || "等待最新报价"
      : remaining.length
        ? `${liveLine(remaining[0], options)}${remaining.length > 1 ? ` · 另 ${remaining.length - 1} 只` : ""}`
        : options.quoteTimeLabel(target);
    const shortText = options.loading || !Number.isFinite(target.price)
      ? "获取中"
      : Number(target.price).toLocaleString("zh-CN", { maximumFractionDigits: 4 }).slice(0, 12);
    const quoteLines = enabled.map((item) => liveLine(item, options));
    const body = options.loading
      ? secondaryText
      : [...quoteLines, `${options.sourceLabel(target.provider)} · ${options.quoteTimeLabel(target)}`].join("\n");
    const tone = toneFor(target);
    return {
      sessionId: options.sessionId,
      title: title.slice(0, 64),
      primaryText: primaryText.slice(0, 32),
      secondaryText: secondaryText.slice(0, 96),
      body: body.slice(0, 256),
      shortText,
      updatedAt,
      accentColor: tone.accentColor,
      tone: tone.tone
    };
  }

  return { buildLiveRequest };
});
