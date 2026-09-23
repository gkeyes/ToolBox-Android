import { anthropicArgs, openaiArgs, LLMError, type LLM, type ChatOpts, type TextRun } from '@/lib/llm-core';
import type { ByokConfig } from '@/lib/byok';
import { endpoint, request, requestScope, sse } from './network';

function base(c: ByokConfig) { return c.baseUrl.trim() || (c.provider === 'openai' ? 'https://api.openai.com/v1' : 'https://api.anthropic.com/v1'); }
function apiUrl(c: ByokConfig, path: string) {
  let root = base(c).replace(/\/+$/, '');
  // Anthropic SDK accepted both origin and /v1. Preserve that affordance.
  if (c.provider === 'anthropic' && new URL(root).pathname === '/') root += '/v1';
  return endpoint(root, path);
}
function headers(c: ByokConfig): Record<string, string> {
  return c.provider === 'openai'
    ? {'Content-Type': 'application/json', Authorization: `Bearer ${c.apiKey.trim()}`}
    : {'Content-Type': 'application/json', 'x-api-key': c.apiKey.trim(), 'anthropic-version': '2023-06-01'};
}
function safeMessage(value: string, c: ByokConfig) {
  const key = c.apiKey.trim();
  return (key ? value.split(key).join('[redacted]') : value).slice(0, 500);
}
export function byokError(e: unknown, c: ByokConfig): Error {
  if (e instanceof Error && e.name === 'AbortError') return e;
  if (e instanceof LLMError) return e;
  return new LLMError(safeMessage(e instanceof Error ? e.message : '模型服务连接失败，请检查网络、权限与 API 地址。', c), 503);
}
async function ensureOk(res: Response, c: ByokConfig) {
  if (res.ok) return;
  let detail = '';
  try { const body = await res.json(); detail = body?.error?.message || body?.message || ''; } catch {}
  const messages: Record<number,string> = {401:'API Key 无效或已过期',403:'权限不足或模型服务拒绝访问',404:'模型名称或 API 地址不正确',429:'额度不足或请求过于频繁'};
  throw new LLMError(safeMessage(`${messages[res.status] || `模型服务返回 ${res.status}`}${detail ? `：${detail}` : ''}`, c), res.status);
}
function textFrom(body: any, c: ByokConfig) {
  if (body?.error) throw new LLMError(safeMessage(body.error.message || '模型服务返回错误。', c));
  if (body?.choices?.[0]?.message?.refusal || body?.stop_reason === 'refusal' || body?.choices?.[0]?.finish_reason === 'content_filter') throw new LLMError('模型拒绝了这个请求。', 422);
  if (body?.choices?.[0]?.finish_reason === 'length' || body?.stop_reason === 'max_tokens') throw new LLMError('模型输出被截断，请更换模型或减少输入内容后重试。', 502);
  const text = c.provider === 'openai' ? body?.choices?.[0]?.message?.content : body?.content?.filter((b: any) => b.type === 'text').map((b: any) => b.text).join('\n');
  if (typeof text !== 'string' || !text.trim()) throw new LLMError('模型返回了空内容，请核对模型及令牌参数。', 502);
  return text;
}
function args(c: ByokConfig, o: ChatOpts) {
  const result: any = c.provider === 'openai' ? openaiArgs(o, c.smartModel, c.tokenParam) : anthropicArgs(o, c.smartModel);
  // Reasoning options vary between compatible endpoints; do not send provider-specific optional fields.
  if (c.provider === 'openai') delete result.reasoning_effort;
  else delete result.output_config;
  return result;
}
export async function listModels(c: ByokConfig): Promise<string[]> {
  const scope = requestScope();
  try {
    const res = await request(apiUrl(c, 'models'), {headers: headers(c), signal: scope.signal});
    await ensureOk(res, c);
    const body = await res.json();
    if (!Array.isArray(body?.data)) throw new Error('服务未提供模型列表，可直接填写模型名称。');
    return body.data.map((x: any) => x?.id).filter((x: unknown): x is string => typeof x === 'string' && !/embed|tts|whisper|speech|dall-?e|image|moderation|rerank/i.test(x)).sort();
  } catch (e) { throw byokError(e,c); } finally { scope.dispose(); }
}
export function makeByokLLM(c: ByokConfig, parent?: AbortSignal): LLM {
  const chatText = async (o: ChatOpts) => {
    const scope = requestScope(parent);
    try {
      const res = await request(apiUrl(c, c.provider === 'openai' ? 'chat/completions' : 'messages'), {method:'POST', headers:headers(c), body:JSON.stringify(args(c,o)), signal:scope.signal});
      await ensureOk(res,c); return textFrom(await res.json(), c);
    } catch (e) { throw byokError(e,c); } finally { scope.dispose(); }
  };
  const chatStream = (o: ChatOpts): TextRun => {
    let acc = '', refused = false;
    async function* run() {
      const scope = requestScope(parent);
      try {
        const res = await request(apiUrl(c, c.provider === 'openai' ? 'chat/completions' : 'messages'), {method:'POST', headers:headers(c), body:JSON.stringify({...args(c,o), stream:true}), signal:scope.signal});
        await ensureOk(res,c);
        if (res.headers.get('content-type')?.includes('application/json')) { acc = textFrom(await res.json(),c); yield acc; return; }
        let ended = false;
        for await (const raw of sse(res)) {
          if (raw.trim() === '[DONE]') { ended = true; break; }
          let chunk: any;
          try { chunk = JSON.parse(raw); } catch { throw new LLMError('模型返回的流式数据格式不正确。'); }
          if (chunk.error || chunk.type === 'error') throw new LLMError(safeMessage(chunk.error?.message || '模型流式响应中断。',c));
          let delta: string | undefined;
          if (c.provider === 'openai') {
            const choice = chunk.choices?.[0];
            if (choice?.delta?.refusal || choice?.finish_reason === 'content_filter') refused = true;
            if (choice?.finish_reason === 'length') throw new LLMError('模型输出被截断，请更换模型或减少输入后重试。');
            if (choice?.finish_reason) ended = true;
            delta = choice?.delta?.content;
          } else {
            if (chunk.type === 'content_block_delta' && chunk.delta?.type === 'text_delta') delta = chunk.delta.text;
            if (chunk.type === 'message_delta' && chunk.delta?.stop_reason === 'refusal') refused = true;
            if (chunk.type === 'message_delta' && chunk.delta?.stop_reason === 'max_tokens') throw new LLMError('模型输出被截断，请更换模型或减少输入后重试。');
            if (chunk.type === 'message_stop') ended = true;
          }
          if (typeof delta === 'string' && delta) { acc += delta; yield delta; }
        }
        if (!ended) throw new LLMError('连接提前结束，回复未完成。请重试，不会把半段回复当成完整结果。');
        if (!acc.trim() && !refused) throw new LLMError('模型返回了空内容，请检查模型设置。');
      } catch (e) { throw byokError(e,c); } finally { scope.dispose(); }
    }
    return {deltas:run(), text:()=>acc, refused:()=>refused};
  };
  return {chatText,chatStream};
}
