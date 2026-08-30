(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  let notes = [];
  let editingId = null;
  const toolbox = () => window.ToolBox;

  async function notify(message) { try { await toolbox()?.ui?.toast?.(message); } catch (_) {} }
  function safeNotes(value) {
    return Array.isArray(value) ? value.filter((note) => note && typeof note.id === "string" && typeof note.text === "string" && Number.isFinite(note.updatedAt)).slice(0, 100) : [];
  }
  async function persist() {
    try { await toolbox()?.storage?.set?.("notes", notes); } catch (_) {}
  }
  async function restore() {
    let saved = null;
    try { await toolbox()?.ready?.(); saved = await toolbox()?.storage?.get?.("notes"); } catch (_) {}
    notes = safeNotes(saved).sort((a, b) => b.updatedAt - a.updatedAt);
    render();
  }
  function formatTime(timestamp) {
    return new Intl.DateTimeFormat("zh-CN", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(timestamp));
  }
  function render() {
    const container = $("notes");
    container.replaceChildren();
    $("empty").hidden = notes.length > 0;
    notes.forEach((note) => {
      const fragment = $("note-template").content.cloneNode(true);
      const item = fragment.querySelector(".note");
      item.dataset.id = note.id;
      fragment.querySelector(".note-body").textContent = note.text;
      fragment.querySelector(".note-time").textContent = `更新于 ${formatTime(note.updatedAt)}`;
      container.append(fragment);
    });
  }
  function resetEditor() {
    editingId = null;
    $("note-input").value = "";
    $("editor-state").textContent = "新笔记";
    $("note-input").focus();
  }
  async function save(event) {
    event.preventDefault();
    const text = $("note-input").value.trim();
    if (!text) { await notify("请输入笔记内容"); return; }
    const timestamp = Date.now();
    if (editingId) {
      notes = notes.map((note) => note.id === editingId ? { ...note, text, updatedAt: timestamp } : note);
    } else {
      notes.unshift({ id: `${timestamp}-${Math.random().toString(36).slice(2, 8)}`, text, updatedAt: timestamp });
    }
    notes.sort((a, b) => b.updatedAt - a.updatedAt);
    await persist(); render(); resetEditor(); await notify("笔记已保存");
  }
  async function copy(text) {
    try {
      if (!toolbox()?.clipboard?.writeText) throw new Error("ToolBox clipboard unavailable");
      await toolbox().clipboard.writeText(text);
      await notify("笔记已复制");
    } catch (_) { await notify("无法复制，请检查剪贴板权限"); }
  }
  async function act(event) {
    const button = event.target.closest("button");
    const item = event.target.closest(".note");
    if (!button || !item) return;
    const note = notes.find((candidate) => candidate.id === item.dataset.id);
    if (!note) return;
    if (button.classList.contains("edit")) {
      editingId = note.id; $("note-input").value = note.text; $("editor-state").textContent = "正在编辑"; $("note-input").focus(); return;
    }
    if (button.classList.contains("copy")) { await copy(note.text); return; }
    if (button.classList.contains("delete")) {
      notes = notes.filter((candidate) => candidate.id !== note.id);
      if (editingId === note.id) resetEditor();
      await persist(); render(); await notify("笔记已删除");
    }
  }
  $("editor").addEventListener("submit", save);
  $("notes").addEventListener("click", act);
  restore();
})();
