import { HealthError, byteSize, specimen } from "./model.mjs";
import { requestAi, validateAiReport } from "./ai.mjs";
import { historyReportLimits } from "./ai-contract.mjs";

export const HISTORY_BATCH_BYTES = 12 * 1024;
export const MAX_HISTORY_BATCHES = 12;
const MAX_BATCH_BYTES = 64 * 1024;
const columns = ["date", "type", "value", "normal"];
export const HISTORY_PROMPT = "整理这一组完整历史资料。series 按原始项目名、标本、原单位分开，points 各列严格对应 columns；同名不同单位不直接比较，不猜测缺失单位或作单位换算。previousPoint（如有）只是同组上一段末点，供跨段衔接，不是新增记录。只依据本批资料填写一个章节，概述有日期和原单位的变化、参考范围变化及无法比较的情况，不逐项重抄所有行，不诊断或推测原因。没有本批未提供资料的全局结论。程序会按批次顺序合成全部章节，不需要你重建全部历史。";

export function buildHistoryPlan(archive) {
  const grouped = new Map();
  for (const record of [...archive.records].sort((a, b) => a.date.localeCompare(b.date))) {
    for (const item of record.items) {
      const key = JSON.stringify([item.name, specimen(record.type), item.unit]);
      if (!grouped.has(key)) grouped.set(key, { name: item.name, specimen: specimen(record.type), unit: item.unit, points: [] });
      grouped.get(key).points.push([record.date, record.type, item.value, item.normal]);
    }
  }
  if (!grouped.size) throw new HealthError("没有可整理的历史记录");
  const makeBatch = index => ({ part: { index, total: MAX_HISTORY_BATCHES }, columns, ...(index === 1 ? { profile: structuredClone(archive.profile) } : {}), series: [] });
  let batches, budget = HISTORY_BATCH_BYTES;
  while (budget <= MAX_BATCH_BYTES) {
    batches = []; let batch = makeBatch(1), overflow = false;
    outer: for (const series of grouped.values()) {
      let segment;
      for (let i = 0; i < series.points.length; i++) {
        if (!segment) {
          segment = { ...series, points: [], ...(i ? { previousPoint: [...series.points[i - 1]] } : {}) };
          batch.series.push(segment);
        }
        segment.points.push([...series.points[i]]);
        if (byteSize(batch) <= budget) continue;
        segment.points.pop(); if (!segment.points.length) batch.series.pop();
        if (!batch.series.length || batches.length >= MAX_HISTORY_BATCHES - 1) { overflow = true; break outer; }
        batches.push(batch); batch = makeBatch(batches.length + 1); segment = undefined; i--;
      }
    }
    if (!overflow) { batches.push(batch); break; }
    budget = budget < MAX_BATCH_BYTES ? Math.min(MAX_BATCH_BYTES, budget * 2) : MAX_BATCH_BYTES + 1;
  }
  if (budget > MAX_BATCH_BYTES) throw new HealthError("完整历史超过本次分组预算，未截断或发送任何资料，请按年份导出后分别整理", "QUOTA_EXCEEDED");
  for (const payload of batches) payload.part.total = batches.length;
  const items = archive.records.reduce((sum, record) => sum + record.items.length, 0);
  return { batches, records: archive.records.length, items, series: grouped.size, budget };
}

export function createHistoryRun(plan) {
  return { plan, reports: Array(plan.batches.length).fill(null) };
}

export async function runHistory(api, settings, state, onStage = () => {}, options = {}) {
  const current = () => {
    if (options.signal?.aborted || options.isCurrent && !options.isCurrent()) throw new HealthError("已取消本次历史整理，原记录未修改", "CANCELLED");
  };
  for (const [index, payload] of state.plan.batches.entries()) {
    current(); if (state.reports[index]) continue;
    const prefix = `历史第 ${index + 1}/${state.plan.batches.length} 组`;
    options.onReasoning?.({ text: "", truncated: false });
    const report = await requestAi(api, settings, HISTORY_PROMPT, payload, null, stage => onStage(`${prefix} · ${stage}`), { ...options, mode: "trace" });
    current(); onStage(`${prefix} · 校验填写格式`);
    state.reports[index] = validateAiReport(report, historyReportLimits);
    options.onBatch?.({ index: index + 1, total: state.plan.batches.length, completed: state.reports.filter(Boolean).length });
  }
  current();
  // No model call to merge or reinterpret reports: retain every validated chapter in input order.
  return validateAiReport({
    summary: `已分 ${state.plan.batches.length} 组整理全部 ${state.plan.records} 份报告、${state.plan.items} 条结果。以下为分组归纳，不作跨组推断。\n` + state.reports.map((report, index) => `第 ${index + 1} 组：${report.summary}`).join("\n"),
    sections: state.reports.flatMap(report => report.sections),
  });
}
