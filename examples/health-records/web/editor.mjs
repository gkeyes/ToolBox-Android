import { h, button, iconButton, field, sectionHeading } from "./dom.mjs";
import { TYPES, referenceDraft, formatReference, normalizeRecord, assertNoNewDuplicateMetrics, specimen, canonicalUnit } from "./model.mjs";
import { createChoice } from "./choice.mjs";
import { createDatePicker } from "./date-picker.mjs";
import { manualNameCandidates, confirmedNameAliases } from "./names.mjs";

export function referenceEditor(raw = "", onDirty = () => {}) {
  const draft = referenceDraft(raw), controls = h("div", { class: "reference-inputs" }), modes = h("div", { class: "reference-mode", role: "group", "aria-label": "参考范围输入方式" });
  function input(key, label, placeholder) {
    return field(label, h("input", { class: "input", type: "text", inputmode: "decimal", value: draft[key], placeholder, "aria-label": label, onInput: (e) => { draft[key] = e.target.value; onDirty(); } }));
  }
  function render() {
    modes.replaceChildren(...[["range", "区间"], ["single", "单侧"], ["qualitative", "定性"], ["raw", "原文"]].map(([mode, label]) => {
      const control = button(label, () => { draft.mode = mode; onDirty(); render(); }, "");
      control.setAttribute("aria-pressed", String(draft.mode === mode));
      return control;
    }));
    if (draft.mode === "range") controls.replaceChildren(input("min", "参考下限", "下限，可留空"), input("max", "参考上限", "上限，可留空"));
    else if (draft.mode === "single") controls.replaceChildren(
      createChoice("比较方式", ["<", "≤", ">", "≥"].map((operator) => ({ value: operator, label: ({ "<": "小于 <", "≤": "小于等于 ≤", ">": "大于 >", "≥": "大于等于 ≥" })[operator] })), { value: draft.operator, onChange: (value) => { draft.operator = value; onDirty(); } }).element,
      input("limit", "参考限值", "填写数值"));
    else if (draft.mode === "qualitative") {
      const label = createChoice("定性参考", ["阴性", "阳性", "未检出"].map((value) => ({ value, label: value })), { value: draft.qualitative, onChange: (value) => { draft.qualitative = value; onDirty(); } }).element;
      label.classList.add("wide"); controls.replaceChildren(label);
    } else {
      const label = field("报告参考原文", h("input", { class: "input", value: draft.raw, maxlength: 200, placeholder: "特殊范围可按报告原文填写", onInput: (e) => { draft.raw = e.target.value; onDirty(); } }));
      label.classList.add("wide"); controls.replaceChildren(label);
    }
  }
  render();
  return { element: h("div", { class: "reference-editor" }, h("div", { class: "reference-heading" }, h("span", {}, "参考范围"), modes), controls), read: () => formatReference(draft) };
}

function itemFields(item, onDirty) {
  const name = h("input", { class: "input", value: item.name || "", required: true, maxlength: 120, placeholder: "如：白细胞", onInput: onDirty });
  const value = h("input", { class: "input", value: item.value ?? "", required: true, maxlength: 120, placeholder: "数值或文字结果", onInput: onDirty });
  const unit = h("input", { class: "input", value: item.unit || "", maxlength: 80, placeholder: "按报告填写", onInput: onDirty });
  const nameLabel = field("指标名称", name); nameLabel.classList.add("wide");
  const reference = referenceEditor(item.normal, onDirty);
  return {
    element: h("div", { class: "editor-fields" }, nameLabel, field("结果", value), field("单位", unit), reference.element),
    read: () => ({ name: name.value, value: value.value, unit: unit.value, normal: reference.read() }),
    name, unit, nameLabel,
  };
}

function createNameReview(entry, fields, getType, catalog, onDirty) {
  const summary = h("p", { class: "name-mapping" }), detail = h("p", { class: "small muted" });
  const pickerHost = h("div", { class: "name-picker", hidden: true });
  let picker = null;
  const restore = button("恢复原名", () => { fields.name.value = entry.originalName; onDirty(); refresh(); }, "text-button");
  const choose = button("选择标准名称", () => {
    picker?.close();
    const candidates = manualNameCandidates(catalog, getType(), fields.unit.value);
    picker = createChoice("本地标准名称", candidates.map((candidate) => ({ value: candidate.id, label: candidate.name, detail: `${candidate.specimen || "标本未标注，仅供手工核对"} · ${candidate.unit || "单位未注明"}` })), {
      value: candidates.find((candidate) => candidate.name === fields.name.value)?.id || "",
      onChange: (id) => { const target = candidates.find((candidate) => candidate.id === id); if (!target) return; fields.name.value = target.name; onDirty(); refresh(); fields.name.focus(); },
    });
    const search = h("input", { class: "input", type: "search", placeholder: "搜索本地标准名称", "aria-label": "搜索本地标准名称", onInput: (event) => {
      const term = event.target.value.normalize("NFKC").trim().toLowerCase();
      picker.setOptions(candidates.filter((candidate) => candidate.name.normalize("NFKC").toLowerCase().includes(term)).map((candidate) => ({ value: candidate.id, label: candidate.name, detail: `${candidate.specimen || "标本未标注"} · ${candidate.unit || "单位未注明"}` }))); picker.open();
    } });
    pickerHost.replaceChildren(search, picker.element); pickerHost.hidden = false; picker.open();
  }, "text-button");
  const element = h("div", { class: "field wide name-review" }, summary, detail, h("div", { class: "name-actions" }, restore, choose), pickerHost);
  function refresh() {
    picker?.close(); pickerHost.hidden = true;
    summary.textContent = fields.name.value === entry.originalName ? `识别原名：${entry.originalName}` : `${entry.originalName} → ${fields.name.value || "未填写"}`;
    detail.textContent = fields.name.value === entry.matchedName ? entry.detail : "已手工调整名称，请对照本次报告核对。";
    restore.disabled = fields.name.value === entry.originalName;
  }
  function contextChanged() {
    if (specimen(getType()) !== entry.specimen || canonicalUnit(fields.unit.value) !== entry.unit) {
      const sampleChanged = specimen(getType()) !== entry.specimen;
      if (sampleChanged && fields.name.value === entry.matchedName) fields.name.value = entry.originalName;
      refresh(); detail.textContent = sampleChanged ? "标本已改变，已恢复识别原名，请重新核对；原对应不会被记住。" : "单位已改变，保留对齐名称供核对；原单位下的对应不会被记住。";
    } else refresh();
  }
  fields.name.addEventListener("input", refresh); fields.unit.addEventListener("input", contextChanged);
  refresh(); return { element, contextChanged };
}

export function createRecordEditor(record, { save, cancel, history, editing = false, notice = "", nameReview = null }) {
  let dirty = false, saving = false;
  const rows = [], items = h("div", { id: "editor-items" });
  const date = createDatePicker(record.date, () => { dirty = true; });
  const type = createChoice("检验类型", Object.entries(TYPES).map(([value, label]) => ({ value, label })), { value: record.type, onChange: () => rows.forEach((row) => row.reviewControl?.contextChanged()) });
  const error = h("div", { class: "form-error", role: "alert", hidden: true });
  const markDirty = () => { dirty = true; };
  function reindex() { rows.forEach((row, i) => { row.title.textContent = `指标 ${i + 1}`; row.remove.setAttribute("aria-label", `删除指标 ${i + 1}`); }); }
  function add(item = { name: "", value: "", unit: "", normal: "" }, fromHistory = false, review = null) {
    if (saving) return;
    if (rows.length >= 120) { error.textContent = "每份报告最多 120 项指标"; error.hidden = false; return; }
    const fields = itemFields(item, markDirty), title = h("h3", {}, `指标 ${rows.length + 1}`);
    const row = { fields, title, element: null, remove: null, review, reviewControl: null };
    if (review) {
      row.reviewControl = createNameReview(review, fields, () => type.value, nameReview.catalog, markDirty);
      fields.nameLabel.after(row.reviewControl.element);
    }
    row.remove = iconButton(`删除指标 ${rows.length + 1}`, "trash", () => { if (saving) return; rows.splice(rows.indexOf(row), 1); row.element.remove(); markDirty(); reindex(); });
    row.element = h("section", { class: "surface editor-item" }, h("div", { class: "editor-item-heading" }, title, row.remove), fields.element);
    rows.push(row); items.append(row.element);
    if (fromHistory) { markDirty(); row.element.scrollIntoView({ block: "nearest" }); }
  }
  for (const [index, item] of (record.items.length ? record.items : [{}]).entries()) add(item, false, nameReview?.review[index]);
  const remember = h("input", { type: "checkbox" }); remember.checked = false;
  const saveButton = h("button", { type: "submit", class: "button primary full" }, editing ? "保存修改" : "保存记录");
  const controls = h("fieldset", { class: "editor-controls" },
    h("header", { class: "page-header form-header" }, iconButton("返回", "back", () => { if (!saving) cancel(dirty); }), h("div", {}, h("h1", {}, editing ? "编辑记录" : "新增记录"), h("p", { class: "page-subtitle" }, "按报告填写，保存每一次变化"))),
    notice && h("p", { class: "notice warning" }, notice),
    h("div", { class: "surface padded form-grid" }, date.element, type.element, date.panel),
    sectionHeading("检验指标", button("从历史添加", () => history(type.value, (item) => add({ ...item, value: "" }, true)), "text-button")),
    items, button("添加指标", () => { if (!saving) { add(); markDirty(); } }, "button outline full", "plus"),
    nameReview && h("div", { class: "surface padded" }, h("label", { class: "checkbox-field" }, remember, "记住已确认的名称对应"), h("p", { class: "small muted" }, "默认不记住。勾选后按本次标本和原单位保存已确认的对应（单位为空也单独记住）；目标仍须是兼容的本地名称，与报告一起保存，不改历史记录。")),
    error,
    h("footer", { class: "editor-footer" }, h("p", { class: "small muted" }, "参考范围请以本次检验报告为准；区间分开填，无需输入横线。"), saveButton));
  const form = h("form", { class: "editor-container", onInput: markDirty, onChange: (event) => { if (!date.panel.contains(event.target)) markDirty(); } }, controls);
  form.addEventListener("submit", async (event) => {
    event.preventDefault(); if (saving) return;
    error.hidden = true;
    try {
      const next = normalizeRecord({ ...record, date: date.value, type: type.value, items: rows.map((r) => r.fields.read()) });
      assertNoNewDuplicateMetrics(editing ? record : { ...record, items: [] }, next);
      const aliases = nameReview && remember.checked ? confirmedNameAliases(nameReview.catalog, next.type, rows.map((row, index) => ({ item: next.items[index], review: row.review }))) : {};
      saving = true; controls.disabled = true; form.setAttribute("aria-busy", "true"); saveButton.disabled = true; saveButton.textContent = "正在保存…";
      await save(next, aliases);
      dirty = false;
    } catch (e) { error.textContent = e.message || "保存失败，输入内容仍然保留"; error.hidden = false; error.scrollIntoView({ block: "nearest" }); }
    finally { saving = false; controls.disabled = false; form.setAttribute("aria-busy", "false"); saveButton.disabled = false; saveButton.textContent = editing ? "保存修改" : "保存记录"; }
  });
  return { element: form, isDirty: () => dirty };
}

export function createBatchEditor(metric, { save, cancel, backLabel = "返回趋势" }) {
  let dirty = false, busy = false;
  const error = h("p", { class: "form-error", role: "alert", hidden: true });
  const rows = metric.points.map((point) => ({ point, fields: itemFields(point, () => { dirty = true; }) }));
  const single = rows.length === 1;
  const submit = h("button", { class: "button primary full", type: "submit" }, single ? "保存修改" : "保存全部修改");
  const controls = h("fieldset", { class: "editor-controls" },
    h("header", { class: "page-header form-header" }, iconButton(backLabel, "back", () => { if (!busy) cancel(dirty); }), h("div", {}, h("h1", {}, single ? "编辑指标" : "批量编辑"), h("p", { class: "page-subtitle" }, `${metric.name} · ${metric.specimen} · ${metric.unit || "未注明单位"}`))),
    h("p", { class: "notice" }, "仅编辑当前标本和单位的记录。每一项参考范围仍需与当次报告一致。"),
    rows.map(({ point, fields }) => h("section", { class: "surface editor-item" }, h("h3", {}, `${point.date} · ${TYPES[point.type]}`), fields.element)),
    error, h("footer", { class: "editor-footer" }, submit));
  const form = h("form", { class: "editor-container" }, controls);
  form.addEventListener("submit", async (event) => {
    event.preventDefault(); if (busy) return;
    error.hidden = true;
    try {
      const changes = rows.map(({ point, fields }) => ({ recordId: point.recordId, itemIndex: point.itemIndex, item: fields.read() }));
      busy = true; controls.disabled = true; submit.disabled = true; form.setAttribute("aria-busy", "true"); submit.textContent = "正在保存…";
      await save(changes); dirty = false;
    } catch (e) { error.textContent = e.message || "保存失败，原记录未改变"; error.hidden = false; error.scrollIntoView({ block: "nearest" }); }
    finally { busy = false; controls.disabled = false; submit.disabled = false; form.setAttribute("aria-busy", "false"); submit.textContent = single ? "保存修改" : "保存全部修改"; }
  });
  return { element: form, isDirty: () => dirty };
}
