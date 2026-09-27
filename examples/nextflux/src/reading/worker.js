import { createReadingPipeline } from "./pipeline.mjs";

let current;
self.addEventListener("message", ({ data }) => {
  try {
    if (data.type === "reading:start") { const pipeline = createReadingPipeline(data); current = { id: data.id, parser: pipeline.parser, adapter: pipeline.adapter }; }
    else if (data.type !== "reading:next") return;
    if (!current || data.id !== current.id) return;
    const batch = current.parser.next();
    self.postMessage({ type: "reading:batch", id: current.id, ...batch });
    if (batch.done) current = null;
  } catch {
    self.postMessage({ type: "reading:error", id: data.id, message: "正文安全解析失败，请返回文章列表后重试，或复制文章链接阅读。" });
    current = null;
  }
});
