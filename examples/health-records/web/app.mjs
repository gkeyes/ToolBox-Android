import { TYPES, STATUS_TEXT, HealthError, emptyArchive, normalizeArchive, normalizeRecord, normalizeItem, localDate, newId, buildIndex, outside, specimen, compareReference, mergeArchive, renameMetric, metricKey, assertNoNewDuplicateMetrics, byteSize, MAX_ARCHIVE_BYTES } from "./model.mjs";
import { createStore } from "./store.mjs";
import { h, icon, button, iconButton, field, sectionHeading, emptyState } from "./dom.mjs";
import { createChoice } from "./choice.mjs";
import { trendChart, monthlyChart } from "./charts.mjs";
import { createRecordEditor, createBatchEditor } from "./editor.mjs";
import { runFileWorker, openFile, saveFile, reportImage } from "./io.mjs";
import { backupName } from "./backup.mjs";
import { AI_MODES, AI_PROVIDERS, MINIMAX_MODELS, getAiConfig, aiPayload, requestAi, validateAiReport, validateSuggestions, validateOcr, OCR_PROMPT } from "./ai.mjs";
import { buildNameCatalog, alignRecordNames, MAX_NAME_BATCHES } from "./names.mjs";

const main = document.getElementById("main"), nav = document.getElementById("navigation"), dialog = document.getElementById("dialog");
const api = window.ToolBox;
let store, archive = emptyArchive(), index = buildIndex([]), page = "overview", lastTab = "overview", detailId = null;
let filter = "all", query = "", recordLimit = 24, selectedMetric = null, metricQuery = "", trendLimit = 30;
let trendReturn = { page: "overview", lastTab: "overview", detailId: null };
let editor = null, toastTimer = null, aiBusy = false, operationBusy = false, viewGeneration = 0;
const scrollPositions = new Map();
const tabLabels = { overview: "概览", records: "记录", trends: "趋势", mine: "我的" };

function errorText(error) {
  if (error instanceof HealthError) return error.message;
  const code = error?.code;
  if (["PERMISSION_DENIED", "SYSTEM_PERMISSION_DENIED", "NOT_DECLARED"].includes(code)) return "权限未开启，请在 ToolBox 的本工具权限页开启对应的存储、文件或网络权限后重试";
  if (code === "USER_GESTURE_REQUIRED") return "操作等待过久，请重新点击按钮再试";
  if (code === "RATE_LIMITED") return "操作较频繁，请稍后再试；原数据未改变";
  if (code === "QUOTA_EXCEEDED") return "超出 ToolBox 存储或传输限额，请减少文件大小或导出部分年份";
  if (["CANCELLED", "SESSION_ENDED"].includes(code)) return "操作已取消";
  if (code === "NETWORK_BLOCKED") return "网络被宿主安全策略阻止，请确认网络权限；仅支持所选 Gemini 或 MiniMax 的官方域名";
  if (["TIMEOUT", "NETWORK_TIMEOUT"].includes(code)) return "联网等待超时，尚未得到完整结果。单次请求最多等待 5 分钟；连接失败或服务主动断开可能提前结束，请检查网络后重试";
  if (code === "NETWORK_UNAVAILABLE") return "连接或读取响应失败，尚未得到完整结果。请检查网络、代理连接或服务可用性后重试";
  if (code === "INTERNAL_ERROR") return "ToolBox 宿主内部处理失败，请返回工具列表后重新打开，再尝试整理；原始记录未修改";
  if (code === "UNSUPPORTED") return "当前环境不支持这项操作，请在 ToolBox 0.3.7 或更新版本中使用";
  return "操作未完成，请重试；原数据仍保留";
}

function toast(message) {
  const node = document.getElementById("toast");
  clearTimeout(toastTimer); node.textContent = message; node.hidden = false;
  toastTimer = setTimeout(() => { node.hidden = true; }, 4500);
}

function showDialog(title, content) {
  if (dialog.open) dialog.close();
  dialog.replaceChildren(h("div", { class: "dialog-head" }, h("h2", { id: "dialog-title" }, title), iconButton("关闭弹窗", "close", () => dialog.close())), h("div", { class: "dialog-body" }, content));
  dialog.showModal();
}

function ask(title, description, confirmLabel, action, dangerous = false) {
  const error = h("p", { class: "form-error", role: "alert", hidden: true });
  const confirm = button(confirmLabel, async () => {
    confirm.disabled = true;
    try { await action(); if (dialog.open) dialog.close(); } catch (e) { error.textContent = errorText(e); error.hidden = false; } finally { confirm.disabled = false; }
  }, dangerous ? "button danger" : "button primary");
  showDialog(title, [h("p", { class: "small pre-wrap" }, description), error, h("div", { class: "actions" }, button("取消", () => dialog.close()), confirm)]);
}

function setTheme(theme) {
  if (theme === "system") delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = theme;
}

async function persist(mutator) {
  try {
    archive = await store.update(mutator); index = buildIndex(archive.records); setTheme(archive.settings.theme);
  } catch (e) { throw new HealthError(errorText(e), e.code || "SAVE_FAILED"); }
}

function header(title, subtitle = "", back = null) {
  return h("header", { class: `page-header${back ? " form-header" : ""}` },
    back && iconButton("返回", "back", back),
    h("div", { class: back ? "" : "brand" }, !back && page === "overview" && h("img", { src: "icon-v1.0.3.png", class: "brand-icon", alt: "", width: 44, height: 44 }), h("div", {}, h("h1", {}, title), subtitle && h("p", { class: "page-subtitle" }, subtitle))),
    !back && page === "overview" && iconButton("设置与个人档案", "settings", () => go("mine")));
}

function buildNavigation() {
  nav.replaceChildren(...Object.entries(tabLabels).map(([key, title]) => {
    const navIcon = ({ overview: "grid", records: "edit", trends: "trend", mine: "user" })[key];
    return h("button", { type: "button", class: "nav-button", "data-page": key, onClick: () => go(key) }, icon(navIcon, navIcon === "grid" ? "icon-grid" : ""), h("span", {}, title));
  }));
}

function go(next) {
  if (editor?.isDirty()) { ask("放弃未保存的修改？", "原记录不会改变，当前输入将被丢弃。", "放弃修改", () => { editor = null; navigate(next); }, true); return; }
  navigate(next);
}

function navigate(next, preserveScroll = false) {
  viewGeneration++;
  if (next === "trends" && page !== "trends" && page !== "editor") trendReturn = { page, lastTab, detailId };
  if (Object.hasOwn(tabLabels, page)) scrollPositions.set(page, window.scrollY);
  page = next; editor = null;
  if (Object.hasOwn(tabLabels, next)) lastTab = next;
  document.body.classList.remove("editing"); nav.hidden = !Object.hasOwn(tabLabels, page);
  for (const control of nav.children) {
    if (control.dataset.page === page) control.setAttribute("aria-current", "page"); else control.removeAttribute("aria-current");
  }
  render();
  if (!preserveScroll) window.scrollTo({ top: scrollPositions.get(page) || 0, behavior: "instant" });
}

function render() {
  main.setAttribute("aria-busy", "false");
  const views = { overview, recordsPage, trendsPage, minePage };
  if (page === "overview") main.replaceChildren(views.overview());
  else if (page === "records") main.replaceChildren(views.recordsPage());
  else if (page === "trends") main.replaceChildren(views.trendsPage());
  else if (page === "mine") main.replaceChildren(views.minePage());
  else if (page === "detail") main.replaceChildren(recordDetail());
  else if (page === "profile") main.replaceChildren(profilePage());
  else if (page === "ai-settings") main.replaceChildren(aiSettingsPage());
  else if (page === "ai") main.replaceChildren(aiPage());
  else if (page === "calendar") main.replaceChildren(calendarPage());
}

function reportRow(record) {
  const count = record.items.filter(outside).length;
  return h("button", { type: "button", class: "report-row", onClick: () => { detailId = record.id; navigate("detail"); } },
    h("span", { class: `report-icon${record.type.endsWith("bio") ? " bio" : ""}` }, icon(record.type.endsWith("bio") ? "flask" : "file")),
    h("span", { class: "report-info" }, h("span", { class: "report-title" }, TYPES[record.type]), h("span", { class: "report-meta" }, `${record.date.replaceAll("-", ".")} · ${record.items.length} 项指标`)),
    h("span", { class: "report-aside" }, count > 0 && h("span", {}, `${count} 项超出参考`), icon("chevron")));
}

function metricCard(metric) {
  const point = metric.points[0], unit = metric.series?.length > 1 ? "多种单位（未换算）" : metric.series?.[0].unit ?? metric.unit;
  return h("div", { class: "surface padded" }, h("div", { class: "metric-header" }, h("h3", {}, metric.name), h("span", { class: "metric-unit" }, `${metric.specimen} · ${unit || "未注明单位"}`)),
    h("div", { class: "metric-value" }, h("strong", {}, point.value), h("span", {}, `最近一次${point.unit ? ` · ${point.unit}` : " · 未注明单位"}`)), trendChart({ ...metric, unit }));
}

function overview() {
  const featured = index.metricGroups.get(index.metrics.get(selectedMetric)?.groupKey) || [...index.metricGroups.values()].find((m) => m.points.length > 1) || index.metricGroups.values().next().value;
  return h("div", {}, header("健康档案", "记录身体，了解变化"),
    h("div", { class: "overview-grid" },
      h("div", { class: "overview-top" }, h("section", { class: "summary-panel", "aria-label": "健康记录统计" },
        h("h2", { class: "summary-title" }, "我的健康记录"),
        h("div", { class: "summary-numbers" }, h("div", { class: "summary-stat" }, h("strong", { class: "summary-number" }, archive.records.length), h("p", { class: "summary-caption" }, "份检验记录")), h("div", { class: "summary-stat" }, h("strong", { class: "summary-number" }, index.metricGroups.size), h("p", { class: "summary-caption" }, "项指标"))),
        h("div", { class: "summary-footer" }, h("span", {}, index.sorted.length ? `最近记录 · ${index.sorted[0].date.slice(5).replace("-", ".")}` : "从第一份记录开始"), h("span", {}, "本地保存"))),
        h("div", { class: "quick-actions" }, button("新增记录", () => openEditor(), "button primary", "plus"), button("识别报告", chooseOcr, "button outline", "scan"))),
      h("section", {}, sectionHeading("最近记录", button("查看全部", () => go("records"), "text-button", "chevron")),
        h("div", { class: "surface" }, index.sorted.length ? index.sorted.slice(0, 2).map(reportRow) : emptyState("还没有检验记录", "手动记一份报告，或导入之前的健康档案。", button("导入旧数据", startImport, "button outline", "upload")))),
      h("section", {}, sectionHeading("指标趋势", button("查看趋势", () => { if (featured) selectedMetric = featured.series[0].key; go("trends"); }, "text-button", "chevron")),
        featured ? metricCard(featured) : h("div", { class: "surface" }, emptyState("变化会在这里留下轨迹", "保存记录后，按项目名称和标本查看指标变化。")))));
}

function recordsPage() {
  const list = h("div", { class: "surface" }), footer = h("div", { class: "list-footer" }), count = h("span", { class: "small muted" });
  function updateList() {
    const term = query.trim().toLocaleLowerCase();
    const found = index.sorted.filter((r) => (filter === "all" || r.type === filter) && (!term || `${r.date} ${TYPES[r.type]} ${r.items.map((i) => i.name).join(" ")}`.toLocaleLowerCase().includes(term)));
    count.textContent = `${found.length} 份记录`;
    list.replaceChildren(...(found.length ? found.slice(0, recordLimit).map(reportRow) : [emptyState("没有找到记录", term || filter !== "all" ? "换一个关键词或检验类型试试。" : "保存一份报告后，会按日期显示在这里。", !term && filter === "all" && button("新增记录", () => openEditor(), "button outline", "plus"))]));
    footer.replaceChildren(...(found.length > recordLimit ? [button(`继续查看（还有 ${found.length - recordLimit} 份）`, () => { recordLimit += 24; updateList(); })] : []));
  }
  const input = h("input", { type: "search", value: query, placeholder: "搜索指标名称或日期", "aria-label": "搜索记录", onInput: (e) => { query = e.target.value; recordLimit = 24; updateList(); } });
  const filters = h("div", { class: "filter-row", role: "group", "aria-label": "检验类型筛选" });
  for (const [key, label] of [["all", "全部"], ...Object.entries(TYPES)]) {
    const control = h("button", { type: "button", class: "filter", "aria-pressed": String(filter === key), onClick: () => { filter = key; recordLimit = 24; for (const b of filters.children) b.setAttribute("aria-pressed", String(b === control)); updateList(); } }, `${label} ${index.counts[key]}`);
    filters.append(control);
  }
  updateList();
  return h("div", { class: "record-layout" }, header("检验记录", "每份报告，按时间妥善收好"), h("div", { class: "toolbar" }, h("div", { class: "search-field" }, icon("search"), input), iconButton("新增记录", "plus", () => openEditor())), filters,
    h("div", { class: "section-heading" }, count, button("导入", startImport, "text-button", "upload")), list, footer);
}

function statusLabel(item) { const status = compareReference(item.value, item.normal); return h("span", { class: `status ${status}` }, STATUS_TEXT[status]); }

function recordDetail() {
  const record = archive.records.find((r) => r.id === detailId);
  if (!record) return emptyState("记录不存在", "可能已被删除。", button("返回记录", () => go("records")));
  return h("div", { class: "record-layout" }, header(TYPES[record.type], `${record.date} · ${record.items.length} 项指标`, () => go(lastTab)),
    h("div", { class: "actions" }, button("编辑记录", () => openEditor(record), "button outline", "edit"), button("删除", () => ask("删除这份记录？", `将删除 ${record.date} 的${TYPES[record.type]}（${record.items.length} 项）。建议先导出备份。`, "删除记录", async () => { await persist((draft) => { draft.records = draft.records.filter((r) => r.id !== record.id); }); navigate("records"); toast("记录已删除"); }, true), "button danger", "trash")),
    h("p", { class: "notice" }, "标记仅比较报告中的参考范围，不代表医学诊断。无法可靠比较的结果显示「未判定」。"),
    h("div", { class: "surface" }, record.items.map((item, itemIndex) => h("div", { class: "detail-item" },
      h("div", { class: "detail-item-body" }, h("div", { class: "detail-item-top" }, h("h3", {}, item.name), h("strong", {}, `${item.value} ${item.unit}`)), h("p", { class: "detail-item-meta" }, `参考：${item.normal || "未提供"}`), statusLabel(item)),
      iconButton(`查看${item.name}趋势`, "trend", () => { selectedMetric = metricKey(record.type, item); navigate("trends"); }),
      iconButton(`编辑${item.name}`, "edit", () => editSingle(record.id, itemIndex))))));
}

function editSingle(recordId, itemIndex) {
  const record = archive.records.find((r) => r.id === recordId), item = record.items[itemIndex];
  const metric = { name: item.name, specimen: specimen(record.type), unit: item.unit, points: [{ ...item, recordId, itemIndex, date: record.date, type: record.type }] };
  openBatch(metric, page === "trends" ? "trends" : "detail");
}

function openEditor(record = null, notice = "", nameReview = null) {
  viewGeneration++;
  const openedRevision = store.revision;
  const returnPage = record && page === "detail" ? "detail" : lastTab;
  const current = record || { id: newId(), date: localDate(), type: "blood", items: [] };
  const editing = archive.records.some((r) => r.id === current.id);
  page = "editor"; nav.hidden = true; document.body.classList.add("editing");
  editor = createRecordEditor(current, {
    editing, notice, nameReview,
    cancel: (dirty) => { if (dirty) ask("放弃未保存的修改？", "当前输入尚未保存，原记录不会改变。", "放弃修改", () => { editor = null; navigate(returnPage); }, true); else navigate(returnPage); },
    history: openHistoryPicker,
    save: async (next, aliases) => {
      if (store.revision !== openedRevision) throw new HealthError("档案已被其他操作更新，请返回后重新打开这份记录再编辑");
      await persist((draft) => { const position = draft.records.findIndex((r) => r.id === next.id); if (position >= 0) draft.records[position] = next; else draft.records.push(next); Object.assign(draft.aliasMap, aliases); });
      editor = null; detailId = next.id; navigate("detail"); toast("记录已保存到本机");
    },
  });
  main.replaceChildren(editor.element); window.scrollTo({ top: 0, behavior: "instant" });
}

function openHistoryPicker(type, add) {
  const candidates = [...index.metrics.values()].filter((m) => m.specimen === specimen(type));
  const list = h("div", { class: "stack" });
  function update(term = "") {
    const found = candidates.filter((m) => m.name.toLowerCase().includes(term.toLowerCase())).slice(0, 40);
    list.replaceChildren(...found.map((m) => button(`${m.name} · ${m.unit || "无单位"}`, () => { add(m.points[0]); dialog.close(); toast("已带入历史名称、单位和范围，请按本次报告核对"); }, "button")));
    if (!found.length) list.append(h("p", { class: "muted small" }, "没有匹配的历史指标，可手动添加。"));
  }
  update();
  showDialog("从历史添加", [h("p", { class: "notice" }, "仅显示同类标本。历史参考范围可能不同，带入后请核对。"), h("input", { class: "input", type: "search", placeholder: "搜索历史指标", onInput: (e) => update(e.target.value) }), list]);
}

function openBatch(metric, returnPage = "trends") {
  if (metric.points.length > 80) { toast("记录较多，请在原始记录中逐份编辑；一次批量编辑最多 80 项"); return; }
  viewGeneration++;
  const revision = store.revision;
  page = "editor"; nav.hidden = true; document.body.classList.add("editing");
  editor = createBatchEditor(metric, {
    backLabel: returnPage === "detail" ? "返回记录" : "返回趋势",
    cancel: (dirty) => { if (dirty) ask("放弃未保存的修改？", "原记录不会改变。", "放弃修改", () => { editor = null; navigate(returnPage); }, true); else navigate(returnPage); },
    save: async (changes) => {
      if (revision !== store.revision) throw new HealthError("记录已变化，请重新打开后编辑");
      await persist((draft) => {
        for (const change of changes) {
          const record = draft.records.find((r) => r.id === change.recordId);
          if (!record?.items[change.itemIndex]) throw new HealthError("原指标不存在，请重新打开");
          record.items[change.itemIndex] = normalizeItem(change.item);
        }
        for (const recordId of new Set(changes.map((c) => c.recordId))) {
          assertNoNewDuplicateMetrics(archive.records.find((r) => r.id === recordId), draft.records.find((r) => r.id === recordId));
        }
      });
      editor = null; navigate(returnPage); toast("修改已保存");
    },
  });
  main.replaceChildren(editor.element); window.scrollTo({ top: 0, behavior: "instant" });
}

function trendsPage() {
  const choices = [...index.metricGroups.values()];
  if (!index.metrics.has(selectedMetric)) selectedMetric = choices[0]?.series[0].key || null;
  const content = h("div", { class: "stack" });
  const option = (group) => ({ value: group.key, label: `${group.name} · ${group.specimen}`, detail: `${group.points.length} 次记录` });
  const select = createChoice("选择指标", choices.map(option), {
    value: index.metrics.get(selectedMetric)?.groupKey, ariaLabel: "选择趋势指标", listLabel: "趋势指标候选",
    onChange: (value) => { selectedMetric = index.metricGroups.get(value).series[0].key; trendLimit = 30; updateTrend(); },
  });
  function updateOptions() {
    const visible = choices.filter((group) => `${group.name} ${group.series.map((m) => `${m.specimen} ${m.unit}`).join(" ")}`.toLowerCase().includes(metricQuery.toLowerCase()));
    select.setOptions(visible.map(option));
  }
  function updateTrend() {
    const metric = index.metrics.get(selectedMetric), group = index.metricGroups.get(metric?.groupKey);
    if (!group) { content.replaceChildren(emptyState("没有匹配的指标", archive.records.length ? "换个关键词试试。" : "先保存一份检验记录。")); return; }
    const editGroup = () => {
      if (group.series.length === 1) { openBatch(metric); return; }
      showDialog("选择要批量编辑的记录", [h("p", { class: "notice" }, "同名、同标本记录已合并显示。批量编辑仍按原始单位选择，每条结果与参考范围分别保留。"),
        h("div", { class: "stack" }, group.series.map((m) => button(`${m.specimen} · ${m.unit || "未注明单位"} · ${m.points.length} 次记录`, () => { dialog.close(); selectedMetric = m.key; openBatch(m); })))]);
    };
    const rows = group.points.slice(0, trendLimit).map((p) => h("tr", {}, h("td", {}, p.date, h("p", { class: "small muted" }, TYPES[p.type])), h("td", { class: "value" }, p.value, h("p", { class: "small muted" }, p.unit || "未注明单位")), h("td", {}, p.normal || "未提供", h("div", {}, statusLabel(p))), h("td", {}, iconButton(`编辑${p.date}的${p.name}`, "edit", () => editSingle(p.recordId, p.itemIndex)))));
    content.replaceChildren(...[group.series.length > 1 && h("p", { class: "notice warning" }, "这组记录包含不同或缺失单位。图中按原始数值连线，未换算；请先核对单位再比较。"), metricCard(group), h("div", { class: "section-heading" }, h("h2", {}, `原始记录 · ${group.points.length} 次`), button("批量编辑", editGroup, "text-button", "edit")),
      h("div", { class: "surface table-scroll" }, h("table", { class: "data-table" }, h("thead", {}, h("tr", {}, ["日期", "结果", "参考范围", "操作"].map((text) => h("th", { scope: "col" }, text)))), h("tbody", {}, rows))),
      group.points.length > trendLimit ? button("查看更多记录", () => { trendLimit += 30; updateTrend(); }) : h("p", { class: "small muted" }, "同名、同标本记录合并显示，未换算数值或补写单位。")].filter(Boolean));
  }
  updateOptions();
  updateTrend();
  return h("div", { class: "record-layout" }, header("指标趋势", "看见变化，也保留每一次原始结果", () => { lastTab = trendReturn.lastTab; detailId = trendReturn.detailId; go(trendReturn.page); }),
    h("div", { class: "stack metric-selector" }, h("div", { class: "search-field" }, icon("search"), h("input", { type: "search", value: metricQuery, "aria-label": "搜索趋势指标", placeholder: "搜索指标名称、标本或单位", onInput: (e) => { metricQuery = e.target.value; updateOptions(); select.open(); } })), select.element), h("div", { class: "notice" }, "同名、同标本的记录在一张图中展示，血样与尿样分开。单位按原报告保留，不自动换算数值。"), content);
}

function settingsRow(title, description, name, action) {
  return h("button", { type: "button", class: "settings-row", onClick: action }, icon(name), h("span", { class: "row-copy" }, h("strong", {}, title), h("p", {}, description)), icon("chevron"));
}

function minePage() {
  const filled = [archive.profile.gender, archive.profile.age && `${archive.profile.age} 岁`, archive.profile.height && `${archive.profile.height} cm`, archive.profile.weight && `${archive.profile.weight} kg`].filter(Boolean).join(" · ");
  const themes = h("div", { class: "theme-options" }, [["system", "跟随系统"], ["light", "浅色"], ["dark", "深色"]].map(([theme, label]) => {
    const control = button(label, async () => { try { await persist((draft) => { draft.settings.theme = theme; }); render(); } catch (e) { toast(errorText(e)); } }); control.setAttribute("aria-pressed", String(archive.settings.theme === theme)); return control;
  }));
  return h("div", { class: "record-layout" }, header("我的档案", "数据留在本机，由你决定如何使用"),
    h("div", { class: "surface" }, settingsRow("个人健康档案", filled || "填写性别、年龄、身高、体重与既往病史", "user", () => navigate("profile"))),
    sectionHeading("数据管理"), h("div", { class: "surface" },
      settingsRow("导入旧数据", "旧站 JSON、Excel；先预览，再合并", "upload", startImport),
      settingsRow("导出备份", "JSON 完整备份 / Excel；均不含 API 密钥", "download", exportDialog),
      settingsRow("记录天数", "按年查看每月记录天数，同一天不重复计数", "calendar", () => navigate("calendar"))),
    sectionHeading("辅助整理"), h("div", { class: "surface" }, settingsRow("AI 资料助手", "识别、摘要、追溯；每次发送前由你确认", "spark", () => navigate("ai")), settingsRow("AI 设置", "MiniMax / Gemini；密钥单独安全保存", "settings", () => navigate("ai-settings"))),
    sectionHeading("外观"), themes,
    h("p", { class: "privacy-note" }, "健康档案 1.0.7 · 记录工具，不提供医学诊断。", h("br"), `本机档案 ${Math.ceil(byteSize(archive) / 1024)} / ${MAX_ARCHIVE_BYTES / 1024} KiB。卸载工具会删除本机记录，请定期备份。`),
    button("清空健康记录", () => ask("清空所有健康记录？", "将清空检验记录、个人档案、摘要和指标库。AI 密钥与外观设置保留。此操作不可撤销，请先备份。", "确认清空", async () => { await persist((draft) => ({ ...emptyArchive(), settings: draft.settings })); render(); toast("健康记录已清空，已有导出备份不受影响"); }, true), "button danger full"));
}

function profilePage() {
  const controls = {};
  const gender = createChoice("性别", ["", "男", "女", "其他 / 不填写"].map((value) => ({ value, label: value || "不填写" })), { value: archive.profile.gender }); controls.gender = gender;
  const input = (key, placeholder, max) => (controls[key] = h("input", { class: "input", type: "number", min: "0", max, step: key === "age" ? "1" : "0.1", value: archive.profile[key], placeholder }));
  const history = h("textarea", { class: "textarea", rows: 5, maxlength: 10000, value: archive.profile.history, placeholder: "可填写既往病史、过敏史等。未使用 AI 时不会发送。" }); controls.history = history;
  const error = h("p", { class: "form-error", role: "alert", hidden: true });
  const submit = h("button", { class: "button primary full", type: "submit" }, "保存档案");
  const form = h("form", { class: "record-layout stack" }, header("个人健康档案", "可选填写，仅保存你希望记录的信息", () => go("mine")), h("div", { class: "form-grid" }, gender.element, field("年龄（岁）", input("age", "可不填", "130")), field("身高（cm）", input("height", "可不填", "300")), field("体重（kg）", input("weight", "可不填", "600"))), field("既往病史与备注", history), error, submit);
  form.addEventListener("submit", async (event) => { event.preventDefault(); submit.disabled = true; try { const profile = Object.fromEntries(Object.entries(controls).map(([key, control]) => [key, control.value])); await persist((draft) => { draft.profile = profile; }); navigate("mine"); toast("档案已保存"); } catch (e) { error.textContent = errorText(e); error.hidden = false; } finally { submit.disabled = false; } });
  return form;
}

function calendarPage() {
  const years = [...new Set([new Date().getFullYear(), ...archive.records.map((r) => Number(r.date.slice(0, 4)))])].sort((a, b) => b - a);
  const graph = h("div", { class: "surface padded" });
  const select = createChoice("统计年份", years.map((year) => ({ value: year, label: `${year} 年` })), { onChange: () => draw() });
  function draw() { graph.replaceChildren(monthlyChart(archive.records, Number(select.value))); }
  draw();
  return h("div", { class: "record-layout stack" }, header("每月记录天数", "同一天有多份报告，只计为一个记录日", () => go("mine")), select.element, graph);
}

async function startImport() {
  if (operationBusy) return;
  operationBusy = true;
  try {
    const file = await openFile(api);
    if (!file) return;
    toast("正在本机解析备份…");
    const incoming = await runFileWorker("read", { name: file.name, bytes: file.bytes.buffer });
    const revision = store.revision;
    let preview;
    const includeProfile = h("input", { type: "checkbox", checked: !Object.values(archive.profile).some(Boolean) });
    const currentIds = new Set(archive.records.map((r) => r.id));
    const hasMatchingIds = incoming.records.some((r) => currentIds.has(r.id));
    const updateMatching = h("input", { type: "checkbox" });
    const summary = h("p", { class: "notice" });
    const error = h("p", { class: "form-error", role: "alert", hidden: true });
    function refreshPreview() {
      try {
        preview = mergeArchive(archive, incoming, includeProfile.checked, newId, updateMatching.checked);
        summary.textContent = `新增 ${preview.added} 份，修订 ${preview.updated} 份，跳过 ${preview.duplicates} 份完全重复记录。${updateMatching.checked ? "仅修订相同 ID、日期和类型的记录；同时替换指标库与别名规则。" : "不同结果保留为独立记录，不覆盖现有值。"}`;
        error.hidden = true; confirm.disabled = false;
      } catch (e) { preview = null; summary.textContent = "当前导入方式无法合并。若这是修订备份，可勾选下方修订选项重新预览。"; error.textContent = errorText(e); error.hidden = false; confirm.disabled = true; }
    }
    const confirm = button("确认导入", async () => {
      if (!preview || confirm.disabled) return;
      const selected = preview;
      const profile = includeProfile.checked, revise = updateMatching.checked;
      confirm.disabled = true;
      includeProfile.disabled = true; updateMatching.disabled = true;
      try {
        if (revision !== store.revision) throw new HealthError("当前记录已改变，请重新选择备份");
        await persist((draft) => mergeArchive(draft, incoming, profile, newId, revise).archive);
        dialog.close(); navigate("records"); toast(`新增 ${selected.added} 份，修订 ${selected.updated} 份，跳过 ${selected.duplicates} 份重复记录`);
      } catch (e) { error.textContent = errorText(e); error.hidden = false; } finally { confirm.disabled = !preview; includeProfile.disabled = false; updateMatching.disabled = false; }
    }, "button primary");
    updateMatching.addEventListener("change", refreshPreview); includeProfile.addEventListener("change", refreshPreview); refreshPreview();
    showDialog("确认导入备份", [h("h3", {}, file.name), summary,
      hasMatchingIds && h("label", { class: "checkbox-field" }, updateMatching, h("span", {}, "使用备份修订相同 ID 的记录，并替换指标库与别名规则（会覆盖对应内容，请先备份）")),
      h("label", { class: "checkbox-field" }, includeProfile, h("span", {}, "同时导入个人档案（勾选将替换本机性别、年龄、身高、体重和病史）")),
      h("p", { class: "small muted" }, "旧文件中的 API 密钥不会导入。备份仅在本机解析，不会上传。"), error,
      h("div", { class: "actions" }, button("取消", () => dialog.close()), confirm)]);
  } catch (e) { toast(errorText(e)); } finally { operationBusy = false; }
}

function exportDialog() {
  const years = [...new Set(archive.records.map((r) => r.date.slice(0, 4)))].sort().reverse();
  const select = createChoice("记录范围", [{ value: "all", label: "全部年份" }, ...years.map((year) => ({ value: year, label: `${year} 年` }))], { ariaLabel: "导出年份" });
  const status = h("p", { class: "small muted", role: "status" });
  const prepared = h("div", { class: "stack" });
  const makeButton = (format, label) => button(label, async () => {
    if (operationBusy) return; operationBusy = true; status.textContent = "正在本机生成备份…"; prepared.replaceChildren();
    try {
      const snapshot = normalizeArchive(archive);
      if (select.value !== "all") snapshot.records = snapshot.records.filter((r) => r.date.startsWith(select.value));
      const data = await runFileWorker(format, { archive: snapshot });
      const name = backupName(format).replace(`.${format}`, select.value === "all" ? `.${format}` : `_${select.value}.${format}`);
      const mime = format === "json" ? "application/json" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      status.textContent = "备份已生成，不含 API 密钥。点击下方按钮选择保存位置。";
      const save = button("选择位置并保存", async () => {
        save.disabled = true;
        try { const token = await saveFile(api, name, mime, data); if (token) { dialog.close(); toast("备份已保存，请妥善保管其中的健康资料"); } else status.textContent = "已取消选择；备份未保存，可以重新选择位置。"; }
        catch (e) { status.textContent = errorText(e); }
        finally { save.disabled = false; }
      }, "button primary full", "download");
      prepared.append(save);
    } catch (e) { status.textContent = errorText(e); } finally { operationBusy = false; }
  });
  showDialog("导出健康备份", [h("p", { class: "small muted" }, "备份含检验记录与个人档案，请存放到可信位置。JSON 适合完整恢复，Excel 便于查看。"), select.element, h("div", { class: "actions" }, makeButton("json", "生成 JSON"), makeButton("xlsx", "生成 Excel")), status, prepared]);
}

function aiSettingsPage() {
  let provider = archive.settings.aiProvider, model, statusVersion = 0, saving = false;
  const modelDrafts = { gemini: archive.settings.model, minimax: archive.settings.minimaxModel };
  const info = h("p", { class: "notice pre-wrap" }), modelSlot = h("div", { class: "stack" });
  const key = h("input", { class: "input", type: "password", maxlength: 512, autocomplete: "off", spellcheck: "false", placeholder: "留空保留已保存的密钥" });
  const status = h("p", { class: "small muted", role: "status" });
  const error = h("p", { class: "form-error", role: "alert", hidden: true });
  const submit = h("button", { class: "button primary full", type: "submit" }, "保存 AI 设置");
  function checkKeyStatus() {
    const version = ++statusVersion, definition = AI_PROVIDERS[provider];
    status.textContent = `正在检查 ${definition.label} 密钥状态…`;
    api.storage.secure.get(definition.keyName).then((value) => {
      if (version === statusVersion) status.textContent = value ? `已安全保存 ${definition.label} 密钥，不会回显或随备份导出。` : `尚未保存 ${definition.label} 密钥；日常记录与备份不需要密钥。`;
    }).catch((e) => { if (version === statusVersion) status.textContent = errorText(e); });
  }
  function showProvider(next) {
    if (model) modelDrafts[provider] = model.value;
    const discardedKey = Boolean(key.value); key.value = ""; provider = next; error.hidden = true;
    const definition = AI_PROVIDERS[provider];
    info.textContent = `仅向 ${definition.label}（${definition.host}）发送经你确认的资料。两家密钥分别保存在 ToolBox 安全存储中，不互相使用，也不进入备份。`;
    if (provider === "minimax") {
      model = createChoice("模型名称", MINIMAX_MODELS.map((value) => ({ value, label: value, detail: value === "MiniMax-M3" ? "报告图片识别与文字整理（推荐）" : "仅文字整理，不能识别图片" })), { value: modelDrafts.minimax });
      modelSlot.replaceChildren(model.element, h("p", { class: "field-hint" }, "使用 MiniMax 中国站密钥；订阅 Key 与按量付费 Key 的额度不同，模型是否可用取决于账号权限。"));
    } else {
      model = h("input", { class: "input", value: modelDrafts.gemini, maxlength: 100, pattern: "[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}", placeholder: "填写你在 AI Studio 中可用的模型名", required: true });
      modelSlot.replaceChildren(field("模型名称", model, "使用支持图片输入的 Gemini 模型。旧模型停用时可在此修改。"));
    }
    checkKeyStatus();
    if (discardedKey) toast("已清空尚未保存的密钥，请输入所选服务的密钥");
  }
  const select = createChoice("AI 服务", [{ value: "minimax", label: "MiniMax" }, { value: "gemini", label: "Google Gemini" }], { value: provider, onChange: showProvider });
  const remove = button("移除所选服务的密钥", () => {
    const definition = AI_PROVIDERS[provider];
    ask(`移除 ${definition.label} 密钥？`, "仅移除所选服务的密钥，不影响另一家密钥、健康记录或备份。", "移除密钥", async () => {
      saving = true; controls.disabled = true; ++statusVersion;
      try { await api.storage.secure.remove(definition.keyName); key.value = ""; checkKeyStatus(); }
      finally { saving = false; controls.disabled = false; }
    }, true);
  }, "button danger full");
  const controls = h("fieldset", { class: "editor-controls stack" }, header("AI 设置", "选择服务与模型，不会自动发送资料", () => go("mine")), select.element, info, modelSlot, field("API 密钥", key), status, error, submit, remove);
  const form = h("form", { class: "record-layout stack" }, controls);
  showProvider(provider);
  form.addEventListener("submit", async (event) => {
    event.preventDefault(); if (saving) return;
    error.hidden = true;
    try {
      const definition = AI_PROVIDERS[provider], selectedModel = model.value.trim();
      const config = getAiConfig({ ...archive.settings, aiProvider: provider, [definition.modelField]: selectedModel });
      const enteredKey = key.value.trim();
      if (enteredKey && (key.value.length > 512 || !/^[\x21-\x7e]+$/.test(enteredKey) || /[\r\n]/.test(key.value))) throw new HealthError("密钥过长或包含空格、换行等无效字符，请只粘贴 API Key");
      saving = true; controls.disabled = true; ++statusVersion; form.setAttribute("aria-busy", "true"); submit.textContent = "正在保存…";
      if (enteredKey) { await api.storage.secure.set(config.keyName, enteredKey); key.value = ""; }
      await persist((draft) => { draft.settings.aiProvider = config.provider; draft.settings[config.modelField] = config.model; });
      navigate("mine"); toast("AI 设置已保存，尚未发送任何资料");
    } catch (e) { error.textContent = errorText(e); error.hidden = false; checkKeyStatus(); }
    finally { saving = false; controls.disabled = false; form.setAttribute("aria-busy", "false"); submit.textContent = "保存 AI 设置"; }
  });
  return form;
}

function aiPage() {
  return h("div", { class: "record-layout" }, header("AI 资料助手", "先确认发送内容，再使用辅助整理", () => go("mine")),
    h("p", { class: "notice warning" }, "AI 可能出错，只用于资料整理，不是诊断或用药建议。识别、改名和分类结果需你核对后才能保存。"),
    h("div", { class: "surface" }, settingsRow("识别报告图片", "原图不超过 5 MiB，自动压缩后识别并核对", "scan", chooseOcr),
      Object.entries(AI_MODES).map(([mode, data]) => settingsRow(data.title, data.scope, mode === "trace" ? "trend" : "spark", () => prepareAi(mode)))),
    archive.healthSummary.text && h("section", {}, sectionHeading("已保存的资料摘要"), h("div", { class: "surface padded report-text" }, h("p", {}, archive.healthSummary.text), h("p", { class: "small muted" }, archive.healthSummary.time))),
    button(`AI 设置 · 当前 ${AI_PROVIDERS[archive.settings.aiProvider].label}`, () => navigate("ai-settings"), "text-button", "settings"));
}

function prepareAi(mode) {
  if (aiBusy) { toast("已有 AI 请求正在处理，请等待完成"); return; }
  if (!archive.records.length) { toast("请先添加或导入检验记录"); return; }
  let config;
  try { config = getAiConfig(archive.settings); } catch (e) { navigate("ai-settings"); toast(errorText(e)); return; }
  const snapshot = normalizeArchive(archive), revision = store.revision, payload = aiPayload(snapshot, mode), definition = AI_MODES[mode];
  const confirm = button("同意发送并整理", () => { dialog.close(); runAi(mode, snapshot, revision, payload); }, "button primary full");
  showDialog(`确认发送到 ${config.label}`, [h("p", { class: "notice warning" }, `将发送至 ${config.label}（${config.host}）：${definition.scope}。模型：${config.model}。可能产生 API 费用或消耗套餐额度。`),
    h("details", {}, h("summary", {}, "查看本次发送的数据"), h("pre", {}, JSON.stringify(payload, null, 2))), h("p", { class: "small muted" }, "仅此次同意，不会在后台持续同步。发送后关闭页面不能撤回已发送的资料。"), confirm]);
}

async function runAi(mode, snapshot, revision, payload) {
  aiBusy = true;
  const config = getAiConfig(snapshot.settings), started = Date.now();
  let stage = "准备请求";
  const progressText = h("p", {}, `正在准备 ${config.label} 请求，原始记录不会自动改写。`);
  const progress = h("div", { class: "loading-screen" }, h("span", { class: "loading-indicator" }), progressText);
  const isCurrent = () => dialog.open && dialog.contains(progress);
  showDialog("正在整理资料", [progress]);
  try {
    const output = await requestAi(api, snapshot.settings, AI_MODES[mode].prompt, payload, null, (next) => {
      stage = next;
      if (isCurrent()) progressText.textContent = `${config.label} · ${stage}。单次最多等待 5 分钟。关闭弹窗后不再接收本次结果，原始记录不会自动改写。`;
    });
    if (!isCurrent()) return;
    stage = "校验整理结果";
    if (mode === "summary" || mode === "trace") {
      const report = validateAiReport(output);
      const save = button("保存此摘要", async () => {
        save.disabled = true;
        try {
          if (revision !== store.revision) throw new HealthError("档案在分析期间已变化，请重新整理后保存");
          await persist((draft) => { draft.healthSummary = { text: report.summary + "\n\n" + report.sections.map((s) => `${s.title}\n${s.text}`).join("\n\n"), time: new Date().toLocaleString("zh-CN") }; }); dialog.close(); if (!editor) navigate("ai"); toast("摘要已保存，检验记录未改动");
        } catch (e) { toast(errorText(e)); } finally { save.disabled = false; }
      }, "button primary full");
      showDialog(AI_MODES[mode].title, [h("p", { class: "notice warning" }, "AI 生成，仅供资料整理，请对照原报告核实。"), h("article", { class: "report-text" }, h("p", {}, report.summary), report.sections.map((s) => h("section", {}, h("h3", {}, s.title), h("p", {}, s.text)))), save]);
    } else showSuggestions(validateSuggestions(output, snapshot, mode), mode, revision);
  } catch (e) {
    if (isCurrent()) {
      const publicCodes = ["UNSUPPORTED", "INVALID_REQUEST", "INVALID_SESSION", "WRONG_ORIGIN", "NOT_MAIN_FRAME", "NOT_DECLARED", "PERMISSION_DENIED", "SYSTEM_PERMISSION_DENIED", "USER_GESTURE_REQUIRED", "BUSY", "RATE_LIMITED", "QUOTA_EXCEEDED", "CANCELLED", "SESSION_ENDED", "NOT_FOUND", "DUPLICATE_TASK", "NETWORK_BLOCKED", "NETWORK_UNAVAILABLE", "NETWORK_TIMEOUT", "INTERNAL_ERROR"];
      const code = e instanceof HealthError ? e.code : publicCodes.includes(e?.code) ? e.code : "UNEXPECTED_ERROR";
      showDialog("未能完成整理", [h("p", { class: "notice warning" }, errorText(e)),
        h("p", { class: "small pre-wrap" }, `${AI_MODES[mode].title} · ${config.label} / ${config.model}\n阶段：${stage} · 已耗时 ${((Date.now() - started) / 1000).toFixed(1)} 秒\n错误码：${code}`),
        h("p", { class: "small muted" }, "这次操作失败，不等于“没有修改建议”。原始记录未修改；可把上方阶段和错误码用于排查，不需要发送密钥或病史。")]);
    }
  }
  finally { aiBusy = false; }
}

function showSuggestions(suggestions, mode, revision) {
  const selected = new Map(), error = h("p", { class: "form-error", role: "alert", hidden: true });
  const rows = suggestions.map((suggestion, i) => {
    const checked = h("input", { type: "checkbox", onChange: (e) => selected.set(i, e.target.checked) });
    const title = mode === "cleanup" ? `${suggestion.source} → ${suggestion.target}` : `${suggestion.date}：${TYPES[suggestion.oldType]} → ${TYPES[suggestion.type]}`;
    return h("div", { class: "suggestion-row" }, h("label", { class: "checkbox-field" }, checked, h("strong", {}, title)), h("p", {}, suggestion.reason), mode === "cleanup" && h("p", {}, `${suggestion.specimen} · ${suggestion.unit || "无单位"}`));
  });
  const apply = button("应用已勾选的建议", async () => {
    apply.disabled = true; error.hidden = true;
    try {
      if (revision !== store.revision) throw new HealthError("记录已变化，请重新生成建议");
      const accepted = suggestions.filter((_, i) => selected.get(i));
      if (!accepted.length) throw new HealthError("请先逐条核对并勾选需要应用的建议");
      await persist((draft) => {
        for (const suggestion of accepted) {
          if (mode === "cleanup") renameMetric(draft, suggestion.key, suggestion.target);
          else draft.records.find((r) => r.id === suggestion.recordId).type = suggestion.type;
        }
      });
      dialog.close(); if (!editor) navigate("ai"); toast(`已应用 ${accepted.length} 条建议`);
    } catch (e) { error.textContent = errorText(e); error.hidden = false; } finally { apply.disabled = false; }
  }, "button primary full");
  showDialog(rows.length ? "核对整理建议" : "整理完成，暂无修改建议", [
    h("p", { class: rows.length ? "notice warning" : "notice" }, rows.length ? "请先备份再修改。AI 建议默认不勾选，只有你选择的项目会改动。数值不会被重算。" : "已收到 AI 的有效回复，返回 0 条可应用的修改建议；这不是请求失败。"),
    rows.length ? rows : h("p", { class: "small muted" }, mode === "cleanup" ? "本次只检查同标本下的确定同义名称，单位仅作辅助，仍排除不同方法或数量/比例冲突。没有建议不代表全部数据或医学结论正确。" : "本次只检查能确定的检验类型修正，不跨血样、尿样推断。没有建议不代表全部数据或医学结论正确。"), error, rows.length > 0 && apply]);
}

async function chooseOcr() {
  if (operationBusy || aiBusy) { toast("请等待当前操作完成"); return; }
  const settings = { ...archive.settings }; let config;
  try { config = getAiConfig(settings, true); } catch (e) { navigate("ai-settings"); toast(errorText(e)); return; }
  const generation = viewGeneration, catalog = buildNameCatalog(archive);
  operationBusy = true;
  try {
    const file = await openFile(api, true); if (!file) return;
    if (viewGeneration !== generation || dialog.open) return;
    const image = await reportImage(file.bytes, file.mimeType);
    if (viewGeneration !== generation || dialog.open) return;
    const preview = h("img", { src: `data:${image.mimeType};base64,${image.data}`, alt: "实际发送的报告图片", class: "report-image-preview" });
    const imageInfo = h("p", { class: "notice", role: "status" }, `原图 ${(image.originalBytes / 1024 / 1024).toFixed(2)} MiB → 实际发送 ${Math.ceil(image.outputBytes / 1024)} KiB · ${image.width} × ${image.height} 像素。${image.compressed ? "已在本机压缩为 JPEG，原文件未改动。请检查下方文字是否清晰。" : "保留原图编码和清晰度，原文件未改动。"}`);
    const status = h("p", { class: "notice", role: "status", hidden: true });
    const isCurrent = () => viewGeneration === generation && dialog.open && dialog.contains(confirm);
    const confirm = button("同意发送这张图片", async () => {
      if (aiBusy) return; aiBusy = true; confirm.disabled = true;
      status.textContent = "正在识别图片，单次最多等待 5 分钟，尚未保存记录…"; status.hidden = false; status.scrollIntoView({ block: "nearest" });
      try {
        const output = await requestAi(api, settings, OCR_PROMPT, {}, image);
        if (!isCurrent()) return;
        const result = validateOcr(output);
        const notice = `AI 识别结果尚未保存，请逐项核对名称、数值、单位和参考范围。${result.missingDate ? "未识别日期，暂填今天，请按报告修正。" : ""}`;
        let aligned;
        try {
          aligned = await alignRecordNames(result.record, catalog, { isCurrent, request: (prompt, payload) => requestAi(api, settings, prompt, payload), onProgress: (index, total) => {
            status.textContent = `图片已识别，正在对齐本地名称（${index}/${total}）… 单次最多等待 5 分钟，尚未保存记录。`; status.scrollIntoView({ block: "nearest" });
          } });
        } catch {
          if (isCurrent()) { dialog.close(); openEditor(result.record, `${notice}名称匹配未完成，已保留识别草稿。`); }
          return;
        }
        if (!aligned || !isCurrent()) return;
        const { aiRequests, localMatches, aiMatches, unresolved } = aligned.stats;
        const matching = ` 本地匹配 ${localMatches} 项；AI 名称匹配尝试 ${aiRequests} 次，对齐 ${aiMatches} 项。`;
        dialog.close(); openEditor(aligned.record, `${notice}${matching}${unresolved ? ` ${unresolved} 项名称未自动对齐，原因见各指标下方。` : " 名称已与本地目录核对，仍请检查识别结果。"}`, aligned);
      } catch (e) { if (isCurrent()) { status.textContent = errorText(e); status.scrollIntoView({ block: "nearest" }); } } finally { aiBusy = false; confirm.disabled = false; }
    }, "button primary full");
    showDialog("确认发送报告图片", [h("p", { class: "notice warning pre-wrap" }, `将把下方完整图片及其附带信息发送到 ${config.label}（${config.host}）。模型：${config.model}。图片可能包含姓名等敏感信息，建议先遮挡无关个人信息、移除照片的位置等附带信息。${catalog.candidates.length ? `识别后自动对齐本地名称：先使用已确认的对应，其余仅发送识别名称、标本、单位及同组候选目录，不再次发图，不发送历史结果、参考范围、日期或病史。名称匹配最多额外调用 ${MAX_NAME_BATCHES} 次，可能产生额外 API 费用或消耗套餐额度。` : "当前没有本地标准目录，本次仅识别图片，可能产生 API 费用或消耗套餐额度。"}`),
      catalog.candidates.length > 0 && h("details", {}, h("summary", {}, `查看本地标准目录（${catalog.candidates.length} 项）`), h("p", { class: "small muted" }, "只发送同标本且方法、数量性质兼容的候选。单位缺失或不同不会直接排除同义名称；未注明标本的指标库条目仅供手工选择。"), h("pre", {}, catalog.candidates.map((entry) => `${entry.name} · ${entry.specimen || "标本未标注"} · ${entry.unit || "单位未注明"}`).join("\n"))),
      imageInfo, preview, h("p", { class: "small muted" }, "关闭弹窗后不再填入本次结果；尚未发出的匹配请求也会停止，但无法撤回已经发送的资料。"), confirm, status]);
  } catch (e) { toast(errorText(e)); } finally { operationBusy = false; }
}

async function start() {
  try {
    if (!api?.ready || !api?.storage?.set) throw new HealthError("请将 health-records.tbx 导入 ToolBox 后打开。普通浏览器不能直接保存 ToolBox 数据。", "HOST_REQUIRED");
    await api.ready();
    store = createStore(api.storage); archive = await store.load(); index = buildIndex(archive.records);
    setTheme(archive.settings.theme); buildNavigation(); navigate("overview");
  } catch (e) {
    main.setAttribute("aria-busy", "false"); main.replaceChildren(emptyState("暂时无法打开档案", errorText(e), button("重新读取", start, "button outline")));
  }
}

window.addEventListener("beforeunload", (event) => { if (editor?.isDirty()) { event.preventDefault(); event.returnValue = ""; } });
start();
