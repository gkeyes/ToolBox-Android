(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const readNumber = (id) => Number($(id).value);
  let lastText = "";

  async function restore() {
    try {
      await window.ToolBox.ready();
      const saved = await window.ToolBox.storage.get("last-input");
      if (saved && typeof saved === "object") {
        for (const key of ["capital", "risk", "entry", "stop"]) {
          if (saved[key] !== undefined) $(key).value = String(saved[key]);
        }
      }
    } catch (_) {
      // Browser preview mode: the host bridge may not exist.
    }
  }

  async function calculate() {
    const capital = readNumber("capital");
    const risk = readNumber("risk");
    const entry = readNumber("entry");
    const stop = readNumber("stop");
    const perShareRisk = entry - stop;
    if (![capital, risk, entry, stop].every(Number.isFinite) || capital <= 0 || risk <= 0 || perShareRisk <= 0) {
      window.ToolBox?.ui?.toast?.("请检查输入：入场价必须高于止损价");
      return;
    }
    const riskAmount = capital * risk / 100;
    const shares = Math.floor(riskAmount / perShareRisk / 100) * 100;
    const amount = shares * entry;
    lastText = `建议仓位：${shares} 股，预计占用资金：${amount.toFixed(2)} 元`;
    $("shares").textContent = `建议仓位：${shares} 股`;
    $("amount").textContent = `预计占用资金：${amount.toFixed(2)} 元`;
    $("result").hidden = false;
    try {
      await window.ToolBox.storage.set("last-input", { capital, risk, entry, stop });
      await window.ToolBox.haptics.perform("confirm");
    } catch (_) {}
  }

  $("calculate").addEventListener("click", calculate);
  $("copy").addEventListener("click", async () => {
    if (!lastText) return;
    try {
      await window.ToolBox.clipboard.writeText(lastText);
      await window.ToolBox.ui.toast("已复制");
    } catch (_) {}
  });
  restore();
})();
