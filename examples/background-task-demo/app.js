(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const endpoint = "https://api.github.com/repos/gkeyes/ToolBox-Android";
  const toolbox = () => window.ToolBox;
  const setStatus = (message) => { $("status").textContent = message; };
  const formatTime = (value) => value ? new Intl.DateTimeFormat("zh-CN", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value)) : "暂无结果";
  const showError = (error) => error?.message || "任务操作失败，请检查后台、网络和通知权限。";

  async function requireBackground() {
    if (!toolbox()?.background) throw new Error("当前宿主尚未提供后台任务 API");
    await toolbox().ready();
  }
  async function createHttpTask() {
    try {
      await requireBackground();
      const taskId = await toolbox().background.enqueue({ key: `github-repo-${Date.now()}`, operation: { type: "httpGet", url: endpoint }, constraints: { network: "connected" } });
      setStatus(`已创建 HTTP 任务 ${taskId}`);
      await loadTasks();
    } catch (error) { setStatus(showError(error)); }
  }
  async function createNotificationTask() {
    try {
      await requireBackground();
      const taskId = await toolbox().background.enqueue({ key: `demo-notify-${Date.now()}`, operation: { type: "notify", title: "ToolBox 后台任务", body: "通知任务已由宿主执行。" } });
      setStatus(`已创建通知任务 ${taskId}`);
      await loadTasks();
    } catch (error) { setStatus(showError(error)); }
  }
  function taskOperationLabel(key) { return key.startsWith("github-repo-") ? "HTTP GET · GitHub 仓库信息" : "通知 · ToolBox 后台任务"; }
  async function resultText(taskId) {
    const result = await toolbox().background.getResult(taskId);
    if (!result) return "尚未产生执行结果";
    if (result.outcome === "SUCCEEDED") return `最近成功：${formatTime(result.completedAt)}${result.status ? ` · HTTP ${result.status}` : ""}`;
    return `最近结果：${result.outcome} · ${formatTime(result.completedAt)}${result.error?.message ? ` · ${result.error.message}` : ""}`;
  }
  async function loadTasks() {
    const container = $("tasks");
    container.replaceChildren();
    try {
      await requireBackground();
      const tasks = await toolbox().background.list();
      if (!tasks.length) { setStatus("还没有后台任务"); return; }
      await Promise.all(tasks.map(async (task) => {
        const fragment = $("task-template").content.cloneNode(true);
        const root = fragment.querySelector(".task");
        root.dataset.id = task.taskId;
        fragment.querySelector(".task-key").textContent = task.key;
        fragment.querySelector(".task-state").textContent = task.state;
        fragment.querySelector(".task-type").textContent = taskOperationLabel(task.key);
        fragment.querySelector(".task-result").textContent = await resultText(task.taskId);
        const cancel = fragment.querySelector(".cancel");
        cancel.disabled = task.state === "COMPLETED" || task.state === "CANCELLED";
        container.append(fragment);
      }));
      setStatus(`共 ${tasks.length} 个任务`);
    } catch (error) { setStatus(showError(error)); }
  }
  async function cancel(event) {
    const button = event.target.closest(".cancel");
    const task = event.target.closest(".task");
    if (!button || !task) return;
    try { await toolbox().background.cancel(task.dataset.id); setStatus("任务已取消"); await loadTasks(); }
    catch (error) { setStatus(showError(error)); }
  }
  $("enqueue-http").addEventListener("click", createHttpTask);
  $("enqueue-notification").addEventListener("click", createNotificationTask);
  $("refresh").addEventListener("click", loadTasks);
  $("tasks").addEventListener("click", cancel);
  loadTasks();
})();
