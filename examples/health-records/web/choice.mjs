import { h, icon } from "./dom.mjs";

let nextChoiceId = 0, activeChoice = null;
document.addEventListener("pointerdown", (event) => {
  if (activeChoice && !activeChoice.element.contains(event.target)) activeChoice.close();
});

export function createChoice(label, items, { value, onChange = () => {}, ariaLabel = label, listLabel = `${label}候选`, scrollToSelected = false } = {}) {
  const id = `choice-${++nextChoiceId}`;
  let options = items.map((item) => ({ ...item, value: String(item.value) }));
  let selected = String(value ?? options[0]?.value ?? "");
  const caption = h("span", { class: "choice-caption" }, options.find((item) => item.value === selected)?.label || "请选择");
  const list = h("div", { id, class: "choice-list", role: "listbox", "aria-label": listLabel, hidden: true });
  const trigger = h("button", { type: "button", class: "choice-trigger", role: "combobox", "aria-label": ariaLabel, "aria-controls": id, "aria-expanded": "false", "aria-haspopup": "listbox" }, caption, icon("chevron", "choice-chevron"));
  const element = h("div", { class: "field choice" }, h("span", { class: "field-label" }, label), trigger, list);
  function close() {
    list.hidden = true; trigger.setAttribute("aria-expanded", "false");
    if (activeChoice === control) activeChoice = null;
  }
  function renderOptions() {
    list.replaceChildren(...options.map((item) => h("button", {
      type: "button", role: "option", class: "choice-option", "aria-selected": String(item.value === selected), tabindex: -1,
      onClick: (event) => {
        event.preventDefault();
        const changed = selected !== item.value;
        selected = item.value; caption.textContent = item.label;
        close(); trigger.focus();
        if (changed) { onChange(selected); element.dispatchEvent(new Event("change", { bubbles: true })); }
      },
    }, h("span", {}, item.label), item.detail && h("small", {}, item.detail))));
    if (!options.length) list.append(h("p", { class: "choice-empty", role: "status" }, "没有匹配的选项，请换个关键词"));
  }
  function open(focus = false) {
    if (activeChoice && activeChoice !== control) activeChoice.close();
    renderOptions(); list.hidden = false; trigger.setAttribute("aria-expanded", "true"); activeChoice = control;
    if (scrollToSelected) list.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: "nearest" });
    if (focus) (list.querySelector('[aria-selected="true"]') || list.querySelector('[role="option"]'))?.focus();
  }
  const control = {
    element, open, close,
    get value() { return selected; },
    setOptions(items) {
      options = items.map((item) => ({ ...item, value: String(item.value) }));
      const current = options.find((item) => item.value === selected);
      if (current) caption.textContent = current.label;
      if (!list.hidden) renderOptions();
    },
  };
  trigger.addEventListener("click", () => { if (list.hidden) open(); else close(); });
  element.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !list.hidden) { event.preventDefault(); event.stopPropagation(); close(); trigger.focus(); return; }
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    if (list.hidden) { open(true); return; }
    const buttons = [...list.querySelectorAll('[role="option"]')];
    const current = buttons.indexOf(document.activeElement);
    const next = event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1 : event.key === "ArrowDown" ? Math.min(current + 1, buttons.length - 1) : Math.max(current - 1, 0);
    buttons[next]?.focus();
  });
  element.addEventListener("focusout", (event) => { if (event.relatedTarget && !element.contains(event.relatedTarget)) close(); });
  return control;
}
