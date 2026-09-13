import { HealthError, specimen } from "./model.mjs";
import { requestAi, validateAiReport } from "./ai.mjs";
import { historyReportLimits } from "./ai-contract.mjs";

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
  // A complete metric series is a semantic unit; retain every point and every group.
  const batches = [...grouped.values()].map((series, index) => ({
    part: { index: index + 1, total: grouped.size }, columns,
    ...(index === 0 ? { profile: structuredClone(archive.profile) } : {}), series: [series],
  }));
  const items = archive.records.reduce((sum, record) => sum + record.items.length, 0);
  return { batches, records: archive.records.length, items, series: grouped.size };
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
