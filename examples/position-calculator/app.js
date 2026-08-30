(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const fieldIds = ["capital", "risk", "entry", "stop"];
  let lastResult = "";
  const toolbox = () => window.ToolBox;
  const setError = (message) => { const error = $("form-error"); error.textContent = message; error.hidden = !message; };
  const inputs = () => Object.fromEntries(fieldIds.map((id) => [id, Number($(id).value)]));

  async function notify(message) { try { await toolbox()?.ui?.toast?.(message); } catch (_) {} }
  async function save(values) {
    try { await toolbox()?.storage?.set?.("last-input", values); } catch (_) {}
  }
  async function restore() {
    let saved = null;
    try { await toolbox()?.ready?.(); saved = await toolbox()?.storage?.get?.("last-input"); } catch (_) {}
    if (saved && typeof saved === "object") fieldIds.forEach((id) => { if (Number.isFinite(Number(saved[id]))) $(id).value = String(saved[id]); });
  }
  async function calculate(event) {
    event.preventDefault();
    const { capital, risk, entry, stop } = inputs();
    const perShareRisk = entry - stop;
    if (![capital, risk, entry, stop].every(Number.isFinite) || capital <= 0 || risk <= 0 || entry <= 0 || stop < 0 || perShareRisk <= 0) {
      setError("请填写有效数值，且入场价必须高于止损价。"); return;
    }
    const riskAmount = capital * risk / 100;
    const shares = Math.max(0, Math.floor(riskAmount / perShareRisk / 100) * 100);
    const amount = shares * entry;
    lastResult = `建议仓位：${shares} 股；预计占用资金：${amount.toFixed(2)} 元；单笔最大风险：${riskAmount.toFixed(2)} 元。`;
    $("shares").textContent = `${shares.toLocaleString("zh-CN")} 股`;
    $("amount").textContent = `${amount.toFixed(2)} 元`;
    $("risk-amount").textContent = `${riskAmount.toFixed(2)} 元`;
    $("result").hidden = false;
    setError("");
    await save({ capital, risk, entry, stop });
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
  restore();
})();
