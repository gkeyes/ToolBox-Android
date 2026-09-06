import { h, button } from "./dom.mjs";
import { supportsAiStream } from "./ai-stream.mjs";

export function aiStreamConsent(api, config) {
  return supportsAiStream(api, config) ? "本次启用实时推理，可能增加等待时间和用量；推理文字仅临时显示，不保存。" : "当前服务或宿主不提供实时推理，等待时仅显示处理阶段。";
}

export function createAiProgress({ api, config, dialog, isCurrent, onCancel }) {
  const controller = new AbortController(), started = Date.now(), streaming = supportsAiStream(api, config);
  const status = h("p", { class: "small", role: "status" }, "准备请求");
  const elapsed = h("p", { class: "small muted" }, "已耗时 0 秒 · 单次最多等待 5 分钟");
  const thinking = h("div", { class: "ai-thinking pre-wrap", tabindex: "0", "aria-label": "AI 实时推理文字" }, streaming ? "等待服务返回推理文字…" : "当前服务或宿主不提供实时推理，仅显示处理阶段。");
  const clipped = h("p", { class: "small muted", hidden: true }, "推理较长，仅展示前 24000 字符；最终结果仍继续完整接收和校验。");
  const element = h("div", { class: "ai-progress" }, h("div", { class: "ai-progress-status" }, h("span", { class: "loading-indicator", "aria-hidden": "true" }), status), elapsed,
    h("details", { open: true }, h("summary", {}, streaming ? `${config.label} 实时推理` : "请求进度"), thinking, clipped),
    h("p", { class: "small muted" }, "以上为服务返回的未核对内容，不是诊断结论，不保存到档案。取消会停止接收，但不能撤回已发送的资料或保证服务停止计费。"),
    button("取消本次请求", () => { controller.abort(); onCancel(); }, "button outline full"));
  let disposed = false;
  const cancelIfObsolete = () => { if (!isCurrent()) controller.abort(); };
  const close = () => cancelIfObsolete();
  const unload = () => controller.abort();
  dialog.addEventListener("close", close);
  window.addEventListener("pagehide", unload);
  const timer = setInterval(() => {
    cancelIfObsolete();
    if (isCurrent()) elapsed.textContent = `已耗时 ${Math.floor((Date.now() - started) / 1000)} 秒 · 单次最多等待 5 分钟`;
  }, 1000);
  return {
    element,
    stage(value) { if (!disposed && isCurrent()) status.textContent = `${config.label} · ${value}`; },
    options: { signal: controller.signal, onReasoning(value) {
      if (disposed || !isCurrent()) return;
      const following = thinking.scrollHeight - thinking.scrollTop - thinking.clientHeight < 48;
      thinking.textContent = value.text || "等待服务返回推理文字…";
      clipped.hidden = !value.truncated;
      if (following) thinking.scrollTop = thinking.scrollHeight;
    } },
    dispose() {
      if (disposed) return; disposed = true;
      clearInterval(timer); dialog.removeEventListener("close", close); window.removeEventListener("pagehide", unload);
      thinking.textContent = "";
    },
  };
}
