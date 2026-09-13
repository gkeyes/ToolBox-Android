import { HealthError, TYPES } from "./model.mjs";

export const reportLimits = Object.freeze({});
export const historyReportLimits = Object.freeze({ minSections: 1 });
const text = (extra = {}) => ({ type: "string", ...extra });
const object = properties => ({ type: "object", properties, required: Object.keys(properties), additionalProperties: false });
const array = items => ({ type: "array", items });
const ids = (values, empty = false) => text(values.length ? { enum: [...new Set([...(empty ? [""] : []), ...values])] } : {});

// These are fill-in documents, not executable tools. Only this one formatter is declared per request.
export function aiContract(mode, payload = {}) {
  let parameters, template, rule = "数组可按需要重复同一种条目结构，不得增加、改名或省略字段。";
  if (mode === "summary" || mode === "trace") {
    const limits = mode === "trace" && payload.part ? historyReportLimits : reportLimits;
    parameters = object({ summary: text(), sections: { ...array(object({ title: text(), text: text() })), ...(limits.minSections ? { minItems: limits.minSections } : {}) } });
    template = { summary: "", sections: [{ title: "", text: "" }] };
    rule += "按资料主题填写摘要和章节；没有相关资料时说明缺失，不编造章节内容。";
  } else if (mode === "ocr") {
    parameters = object({ date: text({ pattern: "^(?:|[0-9]{4}-[0-9]{2}-[0-9]{2})$" }), type: text({ enum: Object.keys(TYPES) }), items: { ...array(object({ name: text(), value: text(), unit: text(), normal: text() })), minItems: 1 } });
    template = { date: "", type: "", items: [{ name: "", value: "", unit: "", normal: "" }] };
    rule += "type 只填 blood、urine、blood_bio 或 urine_bio。日期、单位、参考范围缺失时填空字符串；结果也是字符串，包括百分号和比较符号。不允许 null 或数字类型代替字符串。";
  } else if (mode === "cleanup") {
    const metricIds = (payload.metrics || []).map(item => item.id);
    parameters = object({ suggestions: array(object({ sourceId: ids(metricIds), targetId: ids(metricIds), reason: text({ minLength: 1 }) })) });
    template = { suggestions: [{ sourceId: metricIds[0] || "", targetId: "", reason: "" }] };
    rule += '只为确定需要修改的项填写条目，sourceId 和 targetId 都只能复制本次 metrics 的 id；没有确定建议则填写 {"suggestions":[]}，不要返回未填写的模板行。';
  } else if (mode === "classify") {
    parameters = object({ suggestions: array(object({ recordId: ids((payload.records || []).map(record => record.id)), type: text({ enum: Object.keys(TYPES) }), evidenceItemIds: { ...array(text()), minItems: 1, uniqueItems: true }, reason: text({ minLength: 1 }) })) });
    template = { suggestions: [{ recordId: payload.records?.[0]?.id || "", type: "", evidenceItemIds: [], reason: "" }] };
    rule += '只填写确定需要修正的记录；recordId 和 evidenceItemIds 必须来自本次同一记录，type 只选已列出的类型；没有确定建议则填写 {"suggestions":[]}。';
  } else if (mode === "names") {
    const groups = payload.groups || [], sourceIds = groups.flatMap(group => group.items.map(item => item.id)), targetIds = groups.flatMap(group => group.candidates.map(item => item.id));
    parameters = object({ matches: array(object({ sourceId: ids(sourceIds), targetId: ids(targetIds, true) })) });
    template = { matches: sourceIds.map(sourceId => ({ sourceId, targetId: "" })) };
    rule = "sourceId 已由程序预填，保持这些来源标识，不发明或改写来源。只填写每一项 targetId：确定同义时选择该来源所在分组的候选 id；未知、无法确定或不兼容时保留空字符串。不得增加其他字段或改变原始数据。";
  } else throw new HealthError("未知的 AI 输出格式，未发送请求", "AI_INVALID_CONTRACT");
  const name = `submit_health_${mode}`;
  const example = '包含 "引号" 的合成示例\n第二行\\反斜杠';
  const prompt = `\n以下是程序生成的填写模板。你只负责填入值，不负责设计格式。${rule}\n填写模板：\n${JSON.stringify(template)}\n字符串转义范例（纯合成格式示例，不是本次资料，不要复制这些值）：\n${JSON.stringify(example)}\n`;
  return { name, parameters, prompt };
}

export function exactFields(value, fields) {
  return Boolean(value && typeof value === "object" && !Array.isArray(value) && Object.keys(value).length === fields.length && fields.every(key => Object.hasOwn(value, key)));
}
