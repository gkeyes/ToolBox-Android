import { LLMError } from '@/lib/llm-core';

const TAGS = ['<think>', '</think>', '<thinking>', '</thinking>'];

/** Filter before rendering/JSON parsing, not afterwards. Keep at most a tag prefix,
 * never the reasoning body. Split SSE chunks cannot briefly expose a hidden span.
 * These tags are reserved transport delimiters in model output, not user input.
 */
export class FinalTextFilter {
  private pending = '';
  private depth = 0;

  write(chunk: string): string {
    let out = '';
    for (const char of chunk) {
      this.pending += char;
      while (this.pending) {
        const lower = this.pending.toLowerCase();
        if (TAGS.includes(lower)) {
          if (lower.startsWith('</')) {
            if (!this.depth) throw new LLMError('模型的思考分隔格式不完整，请重试。', 502);
            this.depth--;
          } else this.depth++;
          this.pending = '';
          break;
        }
        if (TAGS.some(tag => tag.startsWith(lower))) break;
        if (!this.depth) out += this.pending[0];
        this.pending = this.pending.slice(1);
      }
    }
    return out;
  }

  finish(): string {
    if (this.depth || /^<\/?th/i.test(this.pending)) {
      throw new LLMError('模型的思考内容未结束，尚未收到完整答案。请重试。', 502);
    }
    const tail = this.pending;
    this.pending = '';
    return tail;
  }
}

export function finalText(raw: string): string {
  const filter = new FinalTextFilter();
  const text = filter.write(raw) + filter.finish();
  if (!text.trim()) throw new LLMError('模型未返回最终答案，请检查模型设置后重试。', 502);
  return text;
}
