(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  let notes = [];
  let editingId = null;
  let loaded = false;
  let saving = false;
  const toolbox = () => window.ToolBox;

  async function notify(message) { try { await toolbox()?.ui?.toast?.(message); } catch (_) {} }
  function safeNotes(value) {
    return Array.isArray(value) ? value.filter((note) => note && typeof note.id === "string" && typeof note.text === "string" && Number.isFinite(note.updatedAt)) : [];
  }
  function showError(message) { $("save-error").textContent = message; }
  async function persist(next) {
    if (!toolbox()?.storage?.set) throw new Error("Storage unavailable");
    await toolbox().storage.set("notes", next);
    notes = next;
    showError("");
  }
  async function restore() {
    let saved = null;
    try {
      await toolbox()?.ready?.();
      if (!toolbox()?.storage?.get) throw new Error("Storage unavailable");
      saved = await toolbox().storage.get("notes");
      notes = safeNotes(saved).sort((a, b) => b.updatedAt - a.updatedAt);
      loaded = true;
    } catch (_) { showError("笔记读取失败，请检查存储权限后重新打开；当前输入会保留。"); }
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
    const input = $("note-input").value;
    const text = input.trim();
    if (!text) { await notify("请输入笔记内容"); return; }
    if (!loaded || saving) { if (!loaded) showError("笔记尚未读取成功，请检查存储权限后重新打开。"); return; }
    const timestamp = Date.now();
    const next = editingId
      ? notes.map((note) => note.id === editingId ? { ...note, text, updatedAt: timestamp } : note)
      : [{ id: `${timestamp}-${Math.random().toString(36).slice(2, 8)}`, text, updatedAt: timestamp }, ...notes];
    next.sort((a, b) => b.updatedAt - a.updatedAt);
    saving = true;
    try {
      await persist(next); render();
      if ($("note-input").value === input) resetEditor();
      await notify("笔记已保存");
    }
    catch (_) { showError("保存失败，输入已保留；请检查数据大小、存储权限或可用空间后重试。"); }
    finally { saving = false; }
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
    if (!button || !item || saving) return;
    const note = notes.find((candidate) => candidate.id === item.dataset.id);
    if (!note) return;
    if (button.classList.contains("edit")) {
      editingId = note.id; $("note-input").value = note.text; $("editor-state").textContent = "正在编辑"; $("note-input").focus(); return;
    }
    if (button.classList.contains("copy")) { await copy(note.text); return; }
    if (button.classList.contains("delete")) {
      saving = true;
      try {
        await persist(notes.filter((candidate) => candidate.id !== note.id));
        if (editingId === note.id) resetEditor();
        render(); await notify("笔记已删除");
      } catch (_) { showError("删除失败，原笔记已保留；请检查数据大小、存储权限或可用空间后重试。"); }
      finally { saving = false; }
    }
  }
  $("editor").addEventListener("submit", save);
  $("notes").addEventListener("click", act);
  restore();
})();
