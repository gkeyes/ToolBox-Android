import test from "node:test";
import assert from "node:assert/strict";
import { freshDom } from "./memory-dom.mjs";
import { createAiProgress } from "../web/ai-progress.mjs";

test("live progress renders plain text, ignores old close events and clears transient reasoning on close or replacement", async () => {
  for (const finish of ["close", "cancel", "pagehide", "replace"]) {
    const { dialog } = freshDom(), config = { provider: "minimax", label: "MiniMax" };
    const api = { network: { openStream() {}, readStream() {}, cancelStream() {} } };
    const progress = createAiProgress({ api, config, dialog, isCurrent: () => dialog.open && dialog.contains(progress.element), onCancel: () => dialog.close() });
    try {
      dialog.append(progress.element); dialog.showModal();
      await dialog.fire("close"); // A previous dialog's queued close must not cancel the new request.
      assert.equal(progress.options.signal.aborted, false);
      const text = '<img src="x" onerror="private()">合成推理';
      progress.options.onReasoning({ text, truncated: true });
      const box = dialog.querySelector('[aria-label="AI 实时推理文字"]');
      assert.equal(box.textContent, text); assert.equal(box.querySelector("img"), null);
      assert.ok(dialog.textContent.includes("24000"));
      if (finish === "close") { dialog.close(); await dialog.fire("close"); }
      else if (finish === "cancel") await dialog.querySelectorAll("button").find(b => b.textContent === "取消本次请求").fire("click");
      else if (finish === "pagehide") await window.fire("pagehide");
      else { dialog.replaceChildren(document.createTextNode("新界面")); await dialog.fire("close"); }
      assert.equal(progress.options.signal.aborted, true);
      progress.dispose(); progress.options.onReasoning({ text: "过期内容", truncated: false });
      assert.equal(box.textContent, "");
      assert.equal((dialog.listeners.get("close") || []).length, 0);
      assert.equal((window.listeners.get("pagehide") || []).length, 0);
    } finally { progress.dispose(); }
  }
});
