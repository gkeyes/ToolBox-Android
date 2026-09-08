import { settingsState } from "@/stores/settingsStore.js";
import { streamChatCompletion } from "../toolbox/ai-network.js";

const getPlainText = (html) => {
  const template = document.createElement("template");
  template.innerHTML = String(html || "");
  template.content.querySelectorAll("script, style, iframe, object, embed, svg, math, template").forEach(node => node.remove());
  return (template.content.textContent || "").replace(/\s+/g, " ").trim();
};

// 返回字符串 str 中可以安全 emit 的前缀长度：
// 末尾可能是 tag 的部分前缀，需要保留等待后续 chunk 确认
function safeEmitLength(str, tag) {
  for (let i = Math.min(tag.length - 1, str.length); i > 0; i--) {
    if (tag.startsWith(str.slice(str.length - i))) {
      return str.length - i;
    }
  }
  return str.length;
}

// 创建一个有状态的思考内容过滤器
// 过滤 <think>…</think> 标签（DeepSeek-R1、QwQ 等模型的推理过程）
// 同时忽略 reasoning_content 字段（DeepSeek 原生 API）
function createThinkFilter(onChunk) {
  let inThinking = false;
  let pending = "";

  return function push(delta) {
    pending += delta;

    while (pending) {
      if (inThinking) {
        const closeIdx = pending.indexOf("</think>");
        if (closeIdx >= 0) {
          inThinking = false;
          // 跳过 </think> 后紧跟的换行/空格，避免多余空行
          pending = pending.slice(closeIdx + 8).replace(/^\s+/, "");
        } else {
          // 保留末尾可能是 </think> 部分前缀的内容，其余丢弃
          pending = pending.slice(safeEmitLength(pending, "</think>"));
          break;
        }
      } else {
        const openIdx = pending.indexOf("<think>");
        if (openIdx >= 0) {
          if (openIdx > 0) onChunk(pending.slice(0, openIdx));
          inThinking = true;
          pending = pending.slice(openIdx + 7);
        } else {
          // 末尾可能是 <think> 的部分前缀，暂不 emit
          const safe = safeEmitLength(pending, "<think>");
          if (safe > 0) {
            onChunk(pending.slice(0, safe));
            pending = pending.slice(safe);
          }
          break;
        }
      }
    }
  };
}

export const summarizeArticleStream = async (article, { onChunk, onDone, onError, signal }) => {
  const { aiApiKey, aiBaseUrl, aiModel, aiPrompt } = settingsState.get();
  const plainText = getPlainText(article.content || "");
  const title = article.title || "";
  const pushChunk = createThinkFilter(onChunk);
  try {
    await streamChatCompletion({
      baseUrl: aiBaseUrl,
      apiKey: aiApiKey,
      signal,
      onDelta: pushChunk,
      body: {
        model: aiModel,
        messages: [
          { role: "system", content: aiPrompt },
          { role: "user", content: `Please summarize this article:\n\nTitle: ${title}\n\n${plainText.slice(0, 8000)}` },
        ],
        max_tokens: 2000,
        temperature: 0.3,
      },
    });
    onDone();
  } catch (error) {
    onError(error);
  }
};
