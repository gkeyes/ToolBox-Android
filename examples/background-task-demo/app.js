(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const endpoint = "https://api.github.com/repos/gkeyes/ToolBox-Android";
  const toolbox = () => window.ToolBox;
  const setStatus = (message) => { $("status").textContent = message; };
  const formatTime = (value) => value ? new Intl.DateTimeFormat("zh-CN", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value)) : "暂无结果";
  const showError = (error) => error?.message || "任务操作失败，请检查后台、网络和通知权限。";
  let loadSequence = 0;
  const cancelling = new Set();

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
    let result;
    // One unreadable result must not hide the rest of the list.
    try { result = await toolbox().background.getResult(taskId); }
    catch (error) { return `结果读取失败：${error?.code ? `${error.code} · ` : ""}${error?.message || "未知错误"}`; }
    if (!result) return "尚未产生执行结果";
    if (result.outcome === "SUCCEEDED") return `最近成功：${formatTime(result.completedAt)}${result.status ? ` · HTTP ${result.status}` : ""}`;
    return `最近结果：${result.outcome} · ${formatTime(result.completedAt)}${result.error?.message ? ` · ${result.error.message}` : ""}`;
  }
  async function loadTasks() {
    const sequence = ++loadSequence;
    const container = $("tasks");
    setStatus("正在读取任务…");
    try {
      await requireBackground();
      const tasks = await toolbox().background.list();
      if (sequence !== loadSequence) return;
      const fragments = await Promise.all(tasks.map(async (task) => {
        const fragment = $("task-template").content.cloneNode(true);
        const root = fragment.querySelector(".task");
        root.dataset.id = task.taskId;
        fragment.querySelector(".task-key").textContent = task.key;
        fragment.querySelector(".task-state").textContent = task.state;
        fragment.querySelector(".task-type").textContent = taskOperationLabel(task.key);
        fragment.querySelector(".task-result").textContent = await resultText(task.taskId);
        const cancel = fragment.querySelector(".cancel");
        cancel.disabled = task.state === "COMPLETED" || task.state === "CANCELLED" || cancelling.has(task.taskId);
        if (cancelling.has(task.taskId)) cancel.textContent = "正在取消…";
        return fragment;
      }));
      if (sequence !== loadSequence) return;
      container.replaceChildren(...fragments);
      setStatus(tasks.length ? `共 ${tasks.length} 个任务` : "还没有后台任务");
    } catch (error) { if (sequence === loadSequence) setStatus(showError(error)); }
  }
  async function cancel(event) {
    const button = event.target.closest(".cancel");
    const task = event.target.closest(".task");
    const taskId = task?.dataset.id;
    if (!button || !task || button.disabled || cancelling.has(taskId)) return;
    cancelling.add(taskId);
    button.disabled = true;
    button.textContent = "正在取消…";
    try { await toolbox().background.cancel(taskId); cancelling.delete(taskId); setStatus("任务已取消"); await loadTasks(); }
    catch (error) {
      cancelling.delete(taskId);
      if (button.isConnected !== false) { button.disabled = false; button.textContent = "取消任务"; }
      setStatus(showError(error));
    }
  }
  function watchForeground() {
    let foreground = true;
    try {
      toolbox()?.runtime?.onStateChanged?.((snapshot) => {
        const visible = Boolean(snapshot?.foreground && !snapshot?.closing);
        if (visible && !foreground) loadTasks();
        foreground = visible;
      });
    } catch (_) {}
  }
  $("enqueue-http").addEventListener("click", createHttpTask);
  $("enqueue-notification").addEventListener("click", createNotificationTask);
  $("refresh").addEventListener("click", loadTasks);
  $("tasks").addEventListener("click", cancel);
  watchForeground();
  loadTasks();
})();
