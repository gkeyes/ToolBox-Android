type WorkKind = 'parse' | 'serialize' | 'hostWrite';
type Counter = { count: number; utf16Bytes: number; totalMs: number; maxMs: number; recentMs: number[] };

const counters: Record<WorkKind, Counter> = {
  parse: { count: 0, utf16Bytes: 0, totalMs: 0, maxMs: 0, recentMs: [] },
  serialize: { count: 0, utf16Bytes: 0, totalMs: 0, maxMs: 0, recentMs: [] },
  hostWrite: { count: 0, utf16Bytes: 0, totalMs: 0, maxMs: 0, recentMs: [] },
};

// Numeric, device-local diagnostics only; never retain transcript or credential text.
export function recordWork(kind: WorkKind, utf16Bytes: number, durationMs: number): void {
  const counter = counters[kind];
  counter.count += 1;
  counter.utf16Bytes += utf16Bytes;
  counter.totalMs += durationMs;
  counter.maxMs = Math.max(counter.maxMs, durationMs);
  counter.recentMs.push(durationMs);
  if (counter.recentMs.length > 512) counter.recentMs.shift();
}

export function workSnapshot() {
  return Object.fromEntries(Object.entries(counters).map(([kind, counter]) => {
    const sorted = [...counter.recentMs].sort((a, b) => a - b);
    return [kind, {
      count: counter.count,
      utf16Bytes: counter.utf16Bytes,
      totalMs: counter.totalMs,
      maxMs: counter.maxMs,
      recentP95Ms: sorted.length ? sorted[Math.ceil(sorted.length * 0.95) - 1] : 0,
      sampleCount: sorted.length,
    }];
  })) as Record<WorkKind, { count: number; utf16Bytes: number; totalMs: number; maxMs: number; recentP95Ms: number; sampleCount: number }>;
}

if (typeof window !== 'undefined') {
  Object.defineProperty(window, '__socialcoachPerf', { configurable: true, get: workSnapshot });
}
