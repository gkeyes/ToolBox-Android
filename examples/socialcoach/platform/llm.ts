import { anthropicArgs, openaiArgs, LLMError, type LLM, type ChatOpts, type TextRun } from '@/lib/llm-core';
import type { ByokConfig } from '@/lib/byok';
import { endpoint, request, requestScope, sse } from './network';
import { FinalTextFilter, finalText } from './reasoning';

// Match exact official hosts, never a lookalike suffix or a third-party gateway.
export function isMiniMax(c: Pick<ByokConfig, 'baseUrl'>): boolean {
  try { return ['api.minimax.io', 'api.minimaxi.com', 'api.minimax.cn'].includes(new URL(c.baseUrl.trim()).hostname); }
  catch { return false; }
}
export function isMiniMaxM3(c: ByokConfig, model = c.smartModel): boolean {
  return isMiniMax(c) && /^MiniMax-M3$/i.test(model.trim());
}

function base(c: ByokConfig) { return c.baseUrl.trim() || (c.provider === 'openai' ? 'https://api.openai.com/v1' : 'https://api.anthropic.com/v1'); }
function apiUrl(c: ByokConfig, path: string) {
  let root = base(c).replace(/\/+$/, '');
  const pathName = new URL(root).pathname;
  // Official MiniMax Anthropic SDK base is /anthropic; the wire route adds /v1.
  if (isMiniMax(c)) {
    if (pathName === '/') root += c.provider === 'openai' ? '/v1' : '/anthropic/v1';
    else if (c.provider === 'anthropic' && pathName === '/anthropic') root += '/v1';
  } else if (c.provider === 'anthropic' && pathName === '/') root += '/v1';
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
function checkBodyError(body: any, c: ByokConfig) {
  if (body?.error || body?.type === 'error') throw new LLMError(safeMessage(body.error?.message || '模型服务返回错误。', c));
  const code = body?.base_resp?.status_code;
  if (code !== undefined && Number(code) !== 0) {
    throw new LLMError(safeMessage(`MiniMax 返回错误（${code}）：${body.base_resp.status_msg || '请检查密钥、额度及接口配置。'}`, c));
  }
}
function contentText(value: unknown): string {
  if (typeof value === 'string') return value;
  if (!Array.isArray(value)) return '';
  // Never merge reasoning_content/reasoning_details or Anthropic thinking blocks.
  return value.filter(b => b?.type === 'text' && typeof b.text === 'string').map(b => b.text).join('');
}
function textFrom(body: any, c: ByokConfig) {
  checkBodyError(body, c);
  if (body?.choices?.[0]?.message?.refusal || body?.stop_reason === 'refusal' || body?.choices?.[0]?.finish_reason === 'content_filter') throw new LLMError('模型拒绝了这个请求。', 422);
  if (body?.choices?.[0]?.finish_reason === 'length' || body?.stop_reason === 'max_tokens') throw new LLMError('模型输出被截断，请更换模型或减少输入内容后重试。', 502);
  return finalText(contentText(c.provider === 'openai' ? body?.choices?.[0]?.message?.content : body?.content));
}
function args(c: ByokConfig, o: ChatOpts) {
  const result: any = c.provider === 'openai' ? openaiArgs(o, c.smartModel, c.tokenParam) : anthropicArgs(o, c.smartModel);
  // Do not send MiniMax extensions to unrelated providers or unverified gateways.
  if (c.provider === 'openai') delete result.reasoning_effort;
  else delete result.output_config;
  if (isMiniMax(c)) {
    if (c.provider === 'openai') result.reasoning_split = true;
    if (isMiniMaxM3(c, o.model ?? c.smartModel)) {
      result.thinking = {type: o.thinking === false ? 'disabled' : 'adaptive'};
      // A bounded allowance for assessment thinking, not the vendor's 128K default.
      const limit = o.thinking === false ? o.maxTokens : Math.max(o.maxTokens, 16384);
      if (c.provider === 'openai') {
        delete result.max_tokens;
        result.max_completion_tokens = limit;
      } else result.max_tokens = limit;
    }
  }
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
      const filter = new FinalTextFilter();
      try {
        const res = await request(apiUrl(c, c.provider === 'openai' ? 'chat/completions' : 'messages'), {method:'POST', headers:headers(c), body:JSON.stringify({...args(c,o), stream:true}), signal:scope.signal});
        await ensureOk(res,c);
        if (res.headers.get('content-type')?.includes('application/json')) { acc = textFrom(await res.json(),c); yield acc; return; }
        let ended = false;
        for await (const raw of sse(res)) {
          if (raw.trim() === '[DONE]') { ended = true; break; }
          let chunk: any;
          try { chunk = JSON.parse(raw); } catch { throw new LLMError('模型返回的流式数据格式不正确。'); }
          checkBodyError(chunk, c);
          let delta: string | undefined;
          if (c.provider === 'openai') {
            const choice = chunk.choices?.[0];
            if (choice?.delta?.refusal || choice?.finish_reason === 'content_filter') refused = true;
            if (choice?.finish_reason === 'length') throw new LLMError('模型输出被截断，请更换模型或减少输入后重试。');
            if (choice?.finish_reason) ended = true;
            delta = contentText(choice?.delta?.content);
          } else {
            if (chunk.type === 'content_block_start' && chunk.content_block?.type === 'text') delta = chunk.content_block.text;
            if (chunk.type === 'content_block_delta' && chunk.delta?.type === 'text_delta') delta = chunk.delta.text;
            if (chunk.type === 'message_delta' && chunk.delta?.stop_reason === 'refusal') refused = true;
            if (chunk.type === 'message_delta' && chunk.delta?.stop_reason === 'max_tokens') throw new LLMError('模型输出被截断，请更换模型或减少输入后重试。');
            if (chunk.type === 'message_stop') ended = true;
          }
          if (typeof delta === 'string' && delta) {
            const visible = filter.write(delta);
            if (visible) { acc += visible; yield visible; }
          }
        }
        if (!ended) throw new LLMError('连接提前结束，回复未完成。请重试，不会把半段回复当成完整结果。');
        const tail = filter.finish();
        if (tail) { acc += tail; yield tail; }
        if (!acc.trim() && !refused) throw new LLMError('模型未返回最终答案，请检查模型设置后重试。');
      } catch (e) { throw byokError(e,c); } finally { scope.dispose(); }
    }
    return {deltas:run(), text:()=>acc, refused:()=>refused};
  };
  return {chatText,chatStream};
}
