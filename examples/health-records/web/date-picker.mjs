import { h, button, iconButton, icon } from "./dom.mjs";
import { validDate, HealthError } from "./model.mjs";
import { createChoice } from "./choice.mjs";

let nextId = 0;
const monthDays = (year, month) => new Date(Date.UTC(year, month, 0)).getUTCDate();
const dateText = ([year, month, day]) => `${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;

export function createDatePicker(value, onChange = () => {}) {
  if (!validDate(value)) throw new HealthError("检验日期无效，请按报告核对");
  let selected = value, draft = value.split("-").map(Number), year, month;
  const id = `report-date-${++nextId}`, caption = h("span", {}, value);
  const trigger = h("button", { type: "button", class: "choice-trigger date-trigger", "aria-label": "检验日期", "aria-controls": id, "aria-expanded": "false" }, caption, icon("calendar"));
  const selectors = h("div", { class: "date-selectors" });
  const days = h("div", { class: "date-days", role: "group", "aria-label": "选择日期" });
  const pending = h("p", { class: "small muted", "aria-live": "polite" });
  const previous = iconButton("上个月", "back", () => shift(-1));
  const next = iconButton("下个月", "chevron", () => shift(1));
  const cancel = button("取消", close); cancel.setAttribute("aria-label", "取消日期选择");
  const panel = h("section", { id, class: "date-calendar", "aria-label": "日期选择", hidden: true },
    h("div", { class: "date-heading" }, previous, h("strong", {}, "选择检验日期"), next), selectors,
    h("div", { class: "date-weekdays", "aria-hidden": "true" }, ["一", "二", "三", "四", "五", "六", "日"].map((day) => h("span", {}, day))), days, pending,
    h("div", { class: "actions" }, cancel, button("确认日期", () => {
      const value = dateText(draft);
      if (!validDate(value)) return;
      const changed = value !== selected; selected = value; caption.textContent = value;
      close(); if (changed) onChange(value);
    }, "button primary")));

  function close() {
    panel.hidden = true; trigger.setAttribute("aria-expanded", "false"); year?.close(); month?.close(); trigger.focus();
  }
  function renderDays() {
    draft[2] = Math.min(draft[2], monthDays(draft[0], draft[1]));
    const offset = (new Date(Date.UTC(draft[0], draft[1] - 1, 1)).getUTCDay() + 6) % 7;
    days.replaceChildren(...Array.from({ length: offset }, () => h("span", { "aria-hidden": "true" })),
      ...Array.from({ length: monthDays(draft[0], draft[1]) }, (_, index) => {
        const day = index + 1;
        const control = h("button", { type: "button", class: "date-day", "aria-label": dateText([draft[0], draft[1], day]), "aria-pressed": String(day === draft[2]), onClick: () => {
          draft[2] = day;
          for (const node of days.querySelectorAll("button")) node.setAttribute("aria-pressed", String(node === control));
          pending.textContent = `待确认：${dateText(draft)}`;
        } }, day);
        return control;
      }));
    previous.disabled = draft[0] === 1900 && draft[1] === 1;
    next.disabled = draft[0] === 2200 && draft[1] === 12;
    pending.textContent = `待确认：${dateText(draft)}`;
  }
  function render() {
    year?.close(); month?.close();
    year = createChoice("年份", Array.from({ length: 301 }, (_, i) => ({ value: 1900 + i, label: `${1900 + i} 年` })), { value: draft[0], scrollToSelected: true, onChange: (value) => { draft[0] = Number(value); renderDays(); } });
    month = createChoice("月份", Array.from({ length: 12 }, (_, i) => ({ value: i + 1, label: `${i + 1} 月` })), { value: draft[1], scrollToSelected: true, onChange: (value) => { draft[1] = Number(value); renderDays(); } });
    selectors.replaceChildren(year.element, month.element); renderDays();
  }
  function shift(delta) {
    const date = new Date(Date.UTC(draft[0], draft[1] - 1 + delta, 1));
    if (date.getUTCFullYear() < 1900 || date.getUTCFullYear() > 2200) return;
    draft = [date.getUTCFullYear(), date.getUTCMonth() + 1, draft[2]]; render();
  }
  trigger.addEventListener("click", () => {
    if (!panel.hidden) { close(); return; }
    draft = selected.split("-").map(Number); render(); panel.hidden = false; trigger.setAttribute("aria-expanded", "true");
  });
  for (const node of [trigger, panel]) node.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !panel.hidden) { event.preventDefault(); event.stopPropagation(); close(); }
  });
  return { element: h("div", { class: "field report-date-field" }, h("span", { class: "field-label" }, "检验日期"), trigger), panel, get value() { return selected; } };
}
