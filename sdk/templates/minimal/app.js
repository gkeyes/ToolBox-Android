(async function () {
  "use strict";
  const draft = document.getElementById("draft");
  const status = document.getElementById("status");
  const save = document.getElementById("save");
  const copy = document.getElementById("copy");
  const api = window.ToolBox;

  function showError(error) {
    status.textContent = (error.code || "ERROR") + "：" + (error.message || "操作失败");
  }

  if (!api) {
    status.textContent = "请打包为 .tbx 并在 ToolBox 中打开；普通浏览器没有原生接口。";
    return;
  }
  try {
    await api.ready();
    const saved = await api.storage.get("draft");
    draft.value = typeof saved === "string" ? saved : "";
    save.disabled = false;
    copy.disabled = false;
    status.textContent = "已就绪。保存后退出重开，内容仍会保留。";
  } catch (error) {
    showError(error);
    return;
  }

  save.addEventListener("click", async function () {
    try {
      await api.storage.set("draft", draft.value);
      status.textContent = "已保存";
    } catch (error) { showError(error); }
  });
  copy.addEventListener("click", async function () {
    if (!draft.value) {
      status.textContent = "请输入要复制的内容";
      return;
    }
    try {
      await api.clipboard.writeText(draft.value);
      status.textContent = "已复制";
    } catch (error) { showError(error); }
  });
})();
