(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const fieldIds = ["capital", "risk", "entry", "stop"];
  let lastResult = "";
  const toolbox = () => window.ToolBox;
  const setError = (message) => { const error = $("form-error"); error.textContent = message; error.hidden = !message; };
  const touched = new Set();
  const inputs = () => Object.fromEntries(fieldIds.map((id) => [id, $(id).value.trim()]));
  const clearResult = () => { lastResult = ""; $("result").hidden = true; };
  // Exact decimal arithmetic: each value becomes { n: BigInt, scale } meaning n / 10^scale.
  // Validation, not a cap: no real price, amount or percentage needs this many significant digits or decimal places,
  // and a pasted value beyond it would make the BigInt work below freeze the page.
  const MAX_DIGITS = 1000;
  function splitDecimal(text) {
    const match = /^\+?(\d*)(?:\.(\d*))?(?:e([+-]?\d+))?$/i.exec(String(text).trim());
    if (!match || !(match[1] || match[2])) return null;
    // Anything outside double range (|exponent| beyond ~324) is not a real price or amount,
    // and expanding 10^exponent for it would freeze the page.
    const exponent = Number(match[3] || 0);
    if (!Number.isFinite(Number(text)) || Math.abs(exponent) > 400) return null;
    // Trailing fractional zeros and leading zeros do not change the value; drop them (plain loops, no backtracking).
    const fraction = match[2] || "";
    let end = fraction.length;
    while (end > 0 && fraction.charCodeAt(end - 1) === 48) end -= 1;
    const all = (match[1] || "") + fraction.slice(0, end);
    let start = 0;
    while (start < all.length && all.charCodeAt(start) === 48) start += 1;
    return { digits: all.slice(start), scale: end - exponent };
  }
  const tooPrecise = (parts) => parts.digits.length > MAX_DIGITS || parts.scale > MAX_DIGITS;
  function parseDecimal(text) {
    const parts = splitDecimal(text);
    if (!parts || tooPrecise(parts)) return null;
    let { scale } = parts, n = BigInt(parts.digits || "0");
    if (scale < 0) { n *= 10n ** BigInt(-scale); scale = 0; }
    return { n, scale };
  }
  const pow10 = (k) => 10n ** BigInt(k);
  const atScale = (value, scale) => value.n * pow10(scale - value.scale);
  // Formats num/den yuan with two decimals, rounding half up.
  function money(num, den) {
    const cents = (num * 200n + den) / (den * 2n);
    const whole = cents / 100n, frac = String(cents % 100n).padStart(2, "0");
    return `${whole.toLocaleString("zh-CN")}.${frac}`;
  }
  function computePosition(raw) {
    const capital = parseDecimal(raw.capital), risk = parseDecimal(raw.risk), entry = parseDecimal(raw.entry), stop = parseDecimal(raw.stop);
    if (!capital || !risk || !entry || !stop) return null;
    const priceScale = Math.max(entry.scale, stop.scale);
    const entryN = atScale(entry, priceScale), stopN = atScale(stop, priceScale);
    const perShare = entryN - stopN;
    if (capital.n <= 0n || risk.n <= 0n || entryN <= 0n || perShare <= 0n) return null;
    const P = pow10(priceScale), C = pow10(capital.scale), R = pow10(risk.scale);
    // Risk budget in yuan = capital * risk / 100 = riskNum / riskDen.
    const riskNum = capital.n * risk.n, riskDen = C * R * 100n;
    const riskLots = (riskNum * P) / (riskDen * perShare * 100n);
    const capitalLots = (capital.n * P) / (C * entryN * 100n);
    const lots = riskLots < capitalLots ? riskLots : capitalLots;
    const shares = lots * 100n;
    return {
      shares, lots, riskLots, capitalLots,
      capitalBound: capitalLots < riskLots,
      amount: money(shares * entryN, P),
      riskAmount: money(riskNum, riskDen),
      actualRisk: money(shares * perShare, P),
      lotCost: money(100n * entryN, P),
      lotRisk: money(100n * perShare, P),
    };
  }
  function explain(r) {
    if (r.lots === 0n) {
      if (r.capitalLots === 0n && r.riskLots > 0n) return `账户资金不足以买入一手（一手需 ${r.lotCost} 元），因此建议为 0 股。`;
      if (r.capitalLots === 0n) return `账户资金不足以买入一手（一手需 ${r.lotCost} 元），且风险预算 ${r.riskAmount} 元也低于一手的止损风险 ${r.lotRisk} 元，因此建议为 0 股。`;
      return `风险预算 ${r.riskAmount} 元低于一手（100 股）的止损风险 ${r.lotRisk} 元，因此建议为 0 股。可提高风险比例或收窄止损。`;
    }
    if (r.capitalBound) return `按风险可买 ${(r.riskLots * 100n).toLocaleString("zh-CN")} 股，但账户资金只够买 ${r.shares.toLocaleString("zh-CN")} 股，已按资金上限计算；实际止损风险 ${r.actualRisk} 元。`;
    return "";
  }

  async function notify(message) { try { await toolbox()?.ui?.toast?.(message); } catch (_) {} }
  async function save(values) {
    try { await toolbox()?.storage?.set?.("last-input", values); } catch (_) {}
  }
  async function restore() {
    let saved = null;
    try { await toolbox()?.ready?.(); saved = await toolbox()?.storage?.get?.("last-input"); } catch (_) {}
    if (saved && typeof saved === "object") fieldIds.forEach((id) => {
      // Newer versions save the exact typed strings; older ones saved numbers.
      const value = saved[id];
      if (touched.has(id) || (typeof value !== "string" && typeof value !== "number")) return;
      if (parseDecimal(value)) $(id).value = String(value);
    });
  }
  async function calculate(event) {
    event.preventDefault();
    clearResult();
    const raw = inputs();
    if (fieldIds.some((id) => { const parts = splitDecimal(raw[id]); return parts && tooPrecise(parts); })) {
      setError(`数值位数过多：有效数字和小数位都不能超过 ${MAX_DIGITS} 位，请检查输入。`);
      return;
    }
    const result = computePosition(raw);
    if (!result) { setError("请填写有效数值，且入场价必须高于止损价。"); return; }
    const note = explain(result);
    lastResult = `建议仓位：${result.shares} 股；预计占用资金：${result.amount} 元；单笔最大风险：${result.riskAmount} 元。${note}`;
    $("shares").textContent = `${result.shares.toLocaleString("zh-CN")} 股`;
    $("amount").textContent = `${result.amount} 元`;
    $("risk-amount").textContent = `${result.riskAmount} 元`;
    $("result-note").textContent = note;
    $("result-note").hidden = !note;
    $("result").hidden = false;
    setError("");
    await save(raw);
    try { await toolbox()?.haptics?.perform?.("confirm"); } catch (_) {}
  }
  async function copyResult() {
    if (!lastResult) { await notify("请先完成一次计算"); return; }
    try {
      if (!toolbox()?.clipboard?.writeText) throw new Error("ToolBox clipboard unavailable");
      await toolbox().clipboard.writeText(lastResult);
      await notify("计算结果已复制");
    } catch (_) { await notify("无法复制，请检查剪贴板权限"); }
  }
  $("calculator").addEventListener("submit", calculate);
  $("copy").addEventListener("click", copyResult);
  fieldIds.forEach((id) => $(id).addEventListener("input", () => { touched.add(id); clearResult(); setError(""); }));
  restore();
})();
