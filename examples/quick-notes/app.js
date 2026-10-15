(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  let notes = [];
  let editingId = null;
  let loaded = false;
  let saving = false;
  let lastDeleted = null;
  let draftStored = false;
  let saveWork = null;
  let draftChain = Promise.resolve();
  // Notes writes (save, delete, undo) are tracked so draft steps and the flush handler wait for them and see the editor they leave behind.
  const track = (work) => { saveWork = work; work.finally(() => { if (saveWork === work) saveWork = null; }); return work; };
  // Draft writes/clears run one at a time, after any in-flight notes write, so a just-saved or just-deleted text is never kept as a draft.
  const queueDraft = (step) => (draftChain = draftChain.catch(() => {}).then(async () => { await saveWork?.catch(() => {}); return step(); }));
  const toolbox = () => window.ToolBox;
  let readyWork = null;
  const whenReady = () => (readyWork ||= Promise.resolve().then(() => toolbox()?.ready?.()).catch(() => {}));

  async function notify(message) { try { await whenReady(); await toolbox()?.ui?.toast?.(message); } catch (_) {} }
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
  function currentDraft() {
    const text = $("note-input").value;
    if (!text.trim()) return null;
    const original = editingId ? notes.find((note) => note.id === editingId) : null;
    if (original && original.text === text) return null;
    return { text, editingId: original ? editingId : null };
  }
  function writeDraft() { return queueDraft(writeDraftNow); }
  async function writeDraftNow() {
    const storage = toolbox()?.storage;
    if (!loaded || !storage?.set) return;
    const draft = currentDraft();
    if (draft) { await storage.set("draft", draft); draftStored = true; }
    else if (draftStored) { await storage.remove?.("draft"); draftStored = false; }
  }
  function clearDraft() {
    return queueDraft(async () => {
      if (!draftStored) return;
      try { await toolbox()?.storage?.remove?.("draft"); draftStored = false; } catch (_) {}
    });
  }
  async function restore() {
    let saved = null, draft = null;
    try {
      await whenReady();
      if (!toolbox()?.storage?.get) throw new Error("Storage unavailable");
      saved = await toolbox().storage.get("notes");
      notes = safeNotes(saved).sort((a, b) => b.updatedAt - a.updatedAt);
      try { draft = await toolbox().storage.get("draft"); } catch (_) {}
      loaded = true;
    } catch (_) { showError("笔记读取失败，请检查存储权限后重新打开；当前输入会保留。"); }
    if (draft && typeof draft === "object" && typeof draft.text === "string") {
      draftStored = true;
      if (!$("note-input").value) {
        const target = typeof draft.editingId === "string" && notes.some((note) => note.id === draft.editingId) ? draft.editingId : null;
        $("note-input").value = draft.text;
        setEditing(target, target ? "正在编辑（已恢复未保存的修改）" : "已恢复未保存的草稿");
      }
    }
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
  function setEditing(id, label) {
    editingId = id;
    $("editor-state").textContent = label || (id ? "正在编辑" : "新笔记");
    $("cancel-edit").hidden = !id;
  }
  function resetEditor() {
    $("note-input").value = "";
    setEditing(null);
    $("note-input").focus();
  }
  function showUndo(note) {
    lastDeleted = note;
    $("undo-text").textContent = "笔记已删除";
    $("undo-bar").hidden = false;
  }
  function hideUndo() { lastDeleted = null; $("undo-bar").hidden = true; }
  async function save(event) {
    event.preventDefault();
    const input = $("note-input").value;
    const text = input.trim();
    if (!text) { await notify("请输入笔记内容"); return; }
    if (!loaded || saving) { if (!loaded) showError("笔记尚未读取成功，请检查存储权限后重新打开。"); return; }
    const timestamp = Date.now();
    const editing = editingId && notes.some((note) => note.id === editingId);
    const next = editing
      ? notes.map((note) => note.id === editingId ? { ...note, text, updatedAt: timestamp } : note)
      : [{ id: `${timestamp}-${Math.random().toString(36).slice(2, 8)}`, text, updatedAt: timestamp }, ...notes];
    next.sort((a, b) => b.updatedAt - a.updatedAt);
    saving = true;
    let saved = false;
    const work = (async () => {
      try {
        await persist(next); render();
        if ($("note-input").value === input) resetEditor();
        saved = true;
      }
      catch (_) { showError("保存失败，输入已保留；请检查数据大小、存储权限或可用空间后重试。"); }
      finally { saving = false; }
    })();
    await track(work);
    if (!saved) return;
    if (!$("note-input").value) await clearDraft();
    await notify("笔记已保存");
  }
  async function cancelEdit() {
    resetEditor();
    await clearDraft();
  }
  async function copy(text) {
    try {
      if (!toolbox()?.clipboard?.writeText) throw new Error("ToolBox clipboard unavailable");
      await toolbox().clipboard.writeText(text);
      await notify("笔记已复制");
    } catch (_) { await notify("无法复制，请检查剪贴板权限"); }
  }
  async function undoDelete() {
    const note = lastDeleted;
    if (!note || saving) return;
    saving = true;
    let restored = false;
    await track((async () => {
      try {
        const next = [...notes.filter((candidate) => candidate.id !== note.id), note].sort((a, b) => b.updatedAt - a.updatedAt);
        await persist(next);
        hideUndo(); render(); restored = true;
      } catch (_) { showError("恢复失败；请检查数据大小、存储权限或可用空间后重试。"); }
      finally { saving = false; }
    })());
    if (restored) await notify("已恢复笔记");
  }
  async function act(event) {
    const button = event.target.closest("button");
    const item = event.target.closest(".note");
    if (!button || !item || saving) return;
    const note = notes.find((candidate) => candidate.id === item.dataset.id);
    if (!note) return;
    if (button.classList.contains("edit")) {
      // Typed text has no undo; replacing it needs the user's word (currentDraft() is null for this note's unchanged text).
      if (currentDraft() && !window.confirm("编辑区有尚未保存的内容，打开这条笔记会替换它。确定继续吗？")) return;
      $("note-input").value = note.text; setEditing(note.id); $("note-input").focus(); return;
    }
    if (button.classList.contains("copy")) { await copy(note.text); return; }
    if (button.classList.contains("delete")) {
      saving = true;
      let clearedEditor = false;
      const input = $("note-input").value;
      await track((async () => {
        try {
          await persist(notes.filter((candidate) => candidate.id !== note.id));
          render(); showUndo(note);
          // The editor no longer has a note to save into; drop its text and draft so Undo + Save cannot duplicate it.
          // Text typed while the delete was saving is kept as a new-note draft instead of being discarded.
          if (editingId === note.id) {
            if ($("note-input").value === input) { resetEditor(); clearedEditor = true; }
            else setEditing(null, "原笔记已删除，保存将新建笔记");
          }
        } catch (_) { showError("删除失败，原笔记已保留；请检查数据大小、存储权限或可用空间后重试。"); }
        finally { saving = false; }
      })());
      // Queued after the tracked work settles (a draft step waits for it), so a flush meanwhile sees the cleared editor and removes the draft itself.
      if (clearedEditor) await clearDraft();
    }
  }
  $("editor").addEventListener("submit", save);
  $("cancel-edit").addEventListener("click", cancelEdit);
  $("undo").addEventListener("click", undoDelete);
  $("notes").addEventListener("click", act);
  const restoring = restore();
  // Registered before restoration settles so a close during startup still waits for it and keeps what was typed meanwhile.
  try { toolbox()?.runtime?.registerFlushHandler?.(async () => { await restoring; await writeDraft(); }); } catch (_) {}
  // The host flushes only on an orderly close; also keep the draft when the page is hidden, so a background kill does not lose it.
  // A hide while a write is pending queues one more write after it, so text typed between two hides is saved too.
  let hideWrite = null;
  let hideAgain = false;
  const saveOnHide = () => {
    if (hideWrite) { hideAgain = true; return; }
    hideWrite = restoring.then(writeDraft).catch(() => {}).finally(() => {
      hideWrite = null;
      if (hideAgain) { hideAgain = false; saveOnHide(); }
    });
  };
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "hidden") saveOnHide(); });
  window.addEventListener("pagehide", saveOnHide);
  try { toolbox()?.runtime?.onStateChanged?.((state) => { if (state && state.foreground === false) saveOnHide(); }); } catch (_) {}
})();
