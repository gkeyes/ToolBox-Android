(function () {
  "use strict";

  const API_ROOT = "https://api.github.com";
  const API_VERSION = "2026-03-10";
  const USER_AGENT = "ToolBox-GitHub-Actions-Watcher/1.0.1";
  const STORAGE_KEY = "github-actions-watcher-state-v1";
  const TOKEN_KEY = "github-actions-watcher-token";
  const POLL_TIMER = "github-actions-watcher-poll";
  const CLOCK_TIMER = "github-actions-watcher-clock";
  const CLOCK_INTERVAL_MS = 10_000;
  const TERMINAL_HOLD_MS = 120_000;
  const MAX_PERSISTED_RECENT_RUNS = 20;
  const model = window.GitHubWatcherModel;

  const state = {
    ready: false,
    busy: false,
    monitoring: false,
    sessionId: null,
    liveActive: false,
    repository: null,
    workflows: [],
    branches: [],
    branchCatalogComplete: false,
    discoveryRuns: [],
    config: null,
    runs: [],
    jobsByRun: {},
    timingModels: {},
    progressByRun: {},
    trackedRunKeys: [],
    terminalStates: {},
    watchStartedAt: null,
    lastPollAt: null,
    nextPollAt: null,
    rateRemaining: null,
    rateResetAt: null,
    warning: null,
    warningMessage: "",
    hasToken: false,
    token: null,
    pollInFlight: false,
    liveInFlight: false
  };

  const $ = (id) => document.getElementById(id);
  const toolbox = () => window.ToolBox;
  let toastTimer = null;
  let foregroundClock = null;

  function cleanText(value, maxLength) {
    return String(value ?? "")
      .replace(/[\u0000-\u001F\u007F-\u009F]/g, " ")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, maxLength);
  }

  function headerValue(headers, name) {
    const target = name.toLowerCase();
    for (const [key, value] of Object.entries(headers || {})) {
      if (key.toLowerCase() === target) return String(value);
    }
    return null;
  }

  function createError(kind, message, status) {
    const error = new Error(message);
    error.kind = kind;
    error.status = status;
    return error;
  }

  function errorLabel(error) {
    if (error?.kind === "invalid_token") return "Token 无效，请检查后重试";
    if (error?.kind === "permission") return "Token 缺少目标仓库的 Actions: read 权限";
    if (error?.kind === "not_found") return "仓库不存在，或当前 Token 无权读取";
    if (error?.kind === "rate_limit") return "GitHub API 额度已用尽，正在等待恢复";
    if (error?.kind === "offline") return "网络暂时不可用，后台守望会继续重试";
    if (error?.code) return `${error.code}: ${error.message || "调用失败"}`;
    return cleanText(error?.message || error || "未知错误", 180);
  }

  function showToast(message) {
    const node = $("toast");
    node.textContent = cleanText(message, 180);
    node.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { node.hidden = true; }, 2800);
  }

  function setLoading(visible, title, detail) {
    $("loading-overlay").hidden = !visible;
    if (title) $("loading-title").textContent = title;
    if (detail) $("loading-detail").textContent = detail;
  }

  function setBusy(value) {
    state.busy = value;
    document.querySelectorAll("button").forEach((button) => {
      button.disabled = value || button.dataset.forceDisabled === "true";
    });
    $("branch-select").disabled = value || document.querySelector('input[name="branch-mode"]:checked')?.value === "all";
    updateStartButton();
  }

  function formatClock(value) {
    if (!Number.isFinite(value)) return "--";
    return new Intl.DateTimeFormat("zh-CN", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false
    }).format(new Date(value));
  }

  function formatDuration(milliseconds, compact = false) {
    if (!Number.isFinite(milliseconds) || milliseconds < 0) return "--";
    const totalSeconds = Math.floor(milliseconds / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (compact) {
      if (hours) return `${hours}时${minutes}分`;
      if (minutes) return `${minutes}分${seconds}秒`;
      return `${seconds}秒`;
    }
    return [hours, minutes, seconds].map((value) => String(value).padStart(2, "0")).join(":");
  }

  function runStatus(run) {
    return run?.status === "completed" ? run.conclusion : run?.status;
  }

  function resultLabel(run) {
    const value = runStatus(run);
    const labels = {
      in_progress: "运行中",
      queued: "排队中",
      waiting: "等待中",
      pending: "等待中",
      requested: "已请求",
      success: "成功",
      failure: "失败",
      timed_out: "超时",
      cancelled: "已取消",
      skipped: "已跳过",
      neutral: "中立",
      action_required: "需要处理",
      stale: "已过期"
    };
    return labels[value] || "已结束";
  }

  function isActiveRun(run) {
    return model.ACTIVE_STATUSES.has(run?.status);
  }

  function configWorkflowName(run) {
    return state.config?.workflowNames?.[String(run.workflow_id)] || run.name || run.display_title || "GitHub Actions";
  }

  function timingKey(run) {
    return `${run.workflow_id}:${run.head_branch || ""}:${run.event || ""}`;
  }

  function persistableJobs() {
    const keep = new Set(state.runs.filter(isActiveRun).map(model.runKey));
    const primary = chooseDisplayedRun();
    if (primary) keep.add(model.runKey(primary));
    return Object.fromEntries(Object.entries(state.jobsByRun).filter(([key]) => keep.has(key)));
  }

  function persistableRuns() {
    const unique = new Map();
    for (const run of [...state.runs.filter(isActiveRun), ...state.runs.slice(0, MAX_PERSISTED_RECENT_RUNS)]) {
      unique.set(model.runKey(run), run);
    }
    return [...unique.values()];
  }

  async function persist(required = false) {
    if (!state.ready) return;
    try {
      await toolbox().storage.set(STORAGE_KEY, {
        repository: state.repository,
        workflows: state.workflows,
        branches: state.branches,
        branchCatalogComplete: state.branchCatalogComplete,
        config: state.config,
        monitoring: state.monitoring,
        sessionId: state.sessionId,
        liveActive: state.liveActive,
        runs: persistableRuns(),
        jobsByRun: persistableJobs(),
        timingModels: state.timingModels,
        progressByRun: state.progressByRun,
        trackedRunKeys: state.trackedRunKeys,
        terminalStates: state.terminalStates,
        watchStartedAt: state.watchStartedAt,
        lastPollAt: state.lastPollAt,
        nextPollAt: state.nextPollAt,
        rateRemaining: state.rateRemaining,
        rateResetAt: state.rateResetAt
      });
    } catch (error) {
      if (required) throw error;
      state.warningMessage = `状态保存失败：${errorLabel(error)}`;
    }
  }

  function restoreObject(saved) {
    if (!saved || typeof saved !== "object" || Array.isArray(saved)) return;
    if (saved.repository && typeof saved.repository === "object") state.repository = saved.repository;
    if (Array.isArray(saved.workflows)) state.workflows = saved.workflows;
    if (Array.isArray(saved.branches)) state.branches = saved.branches;
    state.branchCatalogComplete = saved.branchCatalogComplete === true;
    if (saved.config && typeof saved.config === "object") state.config = saved.config;
    state.monitoring = saved.monitoring === true;
    state.sessionId = typeof saved.sessionId === "string" ? saved.sessionId : null;
    state.liveActive = saved.liveActive === true;
    if (Array.isArray(saved.runs)) state.runs = saved.runs;
    if (saved.jobsByRun && typeof saved.jobsByRun === "object") state.jobsByRun = saved.jobsByRun;
    if (saved.timingModels && typeof saved.timingModels === "object") state.timingModels = saved.timingModels;
    if (saved.progressByRun && typeof saved.progressByRun === "object") state.progressByRun = saved.progressByRun;
    if (Array.isArray(saved.trackedRunKeys)) state.trackedRunKeys = saved.trackedRunKeys;
    if (saved.terminalStates && typeof saved.terminalStates === "object") state.terminalStates = saved.terminalStates;
    state.watchStartedAt = Number.isFinite(saved.watchStartedAt) ? saved.watchStartedAt : null;
    state.lastPollAt = Number.isFinite(saved.lastPollAt) ? saved.lastPollAt : null;
    state.nextPollAt = Number.isFinite(saved.nextPollAt) ? saved.nextPollAt : null;
    state.rateRemaining = Number.isFinite(saved.rateRemaining) ? saved.rateRemaining : null;
    state.rateResetAt = Number.isFinite(saved.rateResetAt) ? saved.rateResetAt : null;
  }

  async function loadToken() {
    try {
      const saved = await toolbox().storage.secure.get(TOKEN_KEY);
      state.token = typeof saved === "string" && saved.trim() ? saved.trim() : null;
    } catch (error) {
      if (!["PERMISSION_DENIED", "NOT_DECLARED"].includes(error?.code)) throw error;
      state.token = null;
    }
    state.hasToken = Boolean(state.token);
    renderTokenState();
  }

  function renderTokenState() {
    $("token-state").textContent = state.hasToken
      ? "已安全保存 Token；留空将继续使用"
      : "公共仓库可不填；私有仓库需 Actions: read";
    $("clear-token").hidden = !state.hasToken;
  }

  function updateRateState(headers) {
    const remainingHeader = headerValue(headers, "x-ratelimit-remaining");
    const resetHeader = headerValue(headers, "x-ratelimit-reset");
    const remaining = remainingHeader === null ? Number.NaN : Number(remainingHeader);
    const resetSeconds = resetHeader === null ? Number.NaN : Number(resetHeader);
    if (Number.isFinite(remaining)) state.rateRemaining = remaining;
    if (Number.isFinite(resetSeconds) && resetSeconds > 0) state.rateResetAt = resetSeconds * 1000;
  }

  async function apiRequest(url, tokenOverride) {
    const headers = {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": API_VERSION,
      "User-Agent": USER_AGENT
    };
    const token = tokenOverride === undefined ? state.token : tokenOverride;
    if (token === state.token && state.rateRemaining === 0 && state.rateResetAt && Date.now() < state.rateResetAt) {
      throw createError("rate_limit", "GitHub API rate limit exceeded", 403);
    }
    if (token) headers.Authorization = `Bearer ${token}`;
    let response;
    try {
      response = await toolbox().network.request({
        url,
        method: "GET",
        headers,
        timeoutMs: 30_000,
        maxResponseBytes: 1_048_576
      });
    } catch (error) {
      if (error?.code === "NETWORK_BLOCKED" || error?.code === "PERMISSION_DENIED") throw error;
      throw createError("offline", "GitHub API 网络请求失败");
    }
    updateRateState(response.headers);
    const remainingHeader = headerValue(response.headers, "x-ratelimit-remaining");
    const responseRemaining = remainingHeader === null ? Number.NaN : Number(remainingHeader);
    const retryHeader = headerValue(response.headers, "retry-after");
    const retryAfterSeconds = retryHeader === null ? Number.NaN : Number(retryHeader);
    if (Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0) {
      state.rateResetAt = Date.now() + retryAfterSeconds * 1000;
    }
    const failure = model.classifyApiStatus(response.status, responseRemaining, retryAfterSeconds);
    if (failure === "invalid_token") throw createError(failure, "GitHub Token 无效", response.status);
    if (failure === "rate_limit") throw createError(failure, "GitHub API rate limit exceeded", response.status);
    if (failure === "permission") throw createError(failure, "GitHub Actions read permission required", response.status);
    if (failure === "not_found") throw createError(failure, "Repository or Actions resource not found", response.status);
    if (failure) throw createError(failure, `GitHub API 返回 ${response.status}`, response.status);
    if (!response.body) return {};
    try {
      return { data: JSON.parse(response.body), headers: response.headers };
    } catch (_error) {
      throw createError("github", "GitHub API 返回了无法解析的数据", response.status);
    }
  }

  async function fetchPaged(firstUrl, collectionKey, tokenOverride, maxPages = 5) {
    const items = [];
    let url = firstUrl;
    let page = 0;
    while (url && page < maxPages) {
      const response = await apiRequest(url, tokenOverride);
      const pageItems = collectionKey ? response.data?.[collectionKey] : response.data;
      if (!Array.isArray(pageItems)) throw createError("github", `GitHub 响应缺少 ${collectionKey || "列表"}`);
      items.push(...pageItems);
      url = model.parseNextLink(headerValue(response.headers, "link"));
      page += 1;
    }
    return items;
  }

  function repoUrl(path) {
    return `${API_ROOT}/repos/${encodeURIComponent(state.config?.owner || state.repository?.owner)}/${encodeURIComponent(state.config?.repo || state.repository?.repo)}${path}`;
  }

  async function readRepository() {
    if (!state.ready || state.busy) return;
    let parsed;
    try {
      parsed = model.parseRepositoryLink($("repo-input").value);
    } catch (error) {
      showToast(error.message);
      return;
    }
    const typedToken = $("token-input").value.trim();
    const candidateToken = typedToken || state.token;
    setBusy(true);
    setLoading(true, "读取仓库", "正在验证仓库、workflow、分支和近期构建");
    try {
      const base = `${API_ROOT}/repos/${encodeURIComponent(parsed.owner)}/${encodeURIComponent(parsed.repo)}`;
      const repositoryResponse = await apiRequest(base, candidateToken);
      const workflows = await fetchPaged(`${base}/actions/workflows?per_page=100`, "workflows", candidateToken, 5);
      const runsResponse = await apiRequest(`${base}/actions/runs?per_page=100`, candidateToken);
      const runs = Array.isArray(runsResponse.data?.workflow_runs) ? runsResponse.data.workflow_runs : [];
      let repositoryBranches = [];
      let branchCatalogComplete = false;
      try {
        repositoryBranches = await fetchPaged(`${base}/branches?per_page=100`, null, candidateToken, 5);
        branchCatalogComplete = true;
      } catch (_error) {
        repositoryBranches = [];
      }
      const activeWorkflows = workflows.filter((workflow) => workflow.state === "active");
      if (!activeWorkflows.length) throw createError("github", "仓库没有启用中的 GitHub Actions workflow");
      if (typedToken) {
        await toolbox().storage.secure.set(TOKEN_KEY, typedToken);
        state.token = typedToken;
        state.hasToken = true;
        $("token-input").value = "";
      }
      const repo = repositoryResponse.data;
      state.repository = {
        owner: parsed.owner,
        repo: parsed.repo,
        fullName: cleanText(repo.full_name || parsed.fullName, 120),
        defaultBranch: cleanText(repo.default_branch || "main", 200),
        private: repo.private === true,
        description: cleanText(repo.description || "", 220)
      };
      state.workflows = activeWorkflows.map((workflow) => ({
        id: Number(workflow.id),
        name: cleanText(workflow.name || workflow.path || "Workflow", 120),
        path: cleanText(workflow.path || "", 220)
      }));
      state.discoveryRuns = runs;
      state.branches = model.branchCandidates(state.repository.defaultBranch, repositoryBranches, runs, 100);
      state.branchCatalogComplete = branchCatalogComplete;
      state.warning = null;
      state.warningMessage = "";
      renderTokenState();
      renderSetup();
      showToast(`已读取 ${state.workflows.length} 个 workflow`);
    } catch (error) {
      showToast(errorLabel(error));
    } finally {
      setLoading(false);
      setBusy(false);
    }
  }

  function renderWorkflowChoices() {
    const list = $("workflow-list");
    list.replaceChildren();
    const selected = new Set((state.config?.selectedWorkflowIds || []).map(Number));
    for (const workflow of state.workflows) {
      const label = document.createElement("label");
      label.className = "workflow-row";
      const input = document.createElement("input");
      input.type = "checkbox";
      input.name = "workflow";
      input.value = String(workflow.id);
      input.checked = selected.has(workflow.id);
      input.addEventListener("change", updateStartButton);
      const copy = document.createElement("span");
      copy.className = "workflow-copy";
      const name = document.createElement("strong");
      name.textContent = workflow.name;
      const path = document.createElement("span");
      path.textContent = workflow.path;
      copy.append(name, path);
      label.append(input, copy);
      list.append(label);
    }
  }

  function renderBranchChoices() {
    const select = $("branch-select");
    const options = $("branch-options");
    options.replaceChildren();
    const chosen = state.config?.branch || state.repository?.defaultBranch || "main";
    const branches = state.branches.includes(chosen) ? state.branches : [chosen, ...state.branches];
    select.value = chosen;
    $("branch-selected-text").textContent = chosen;
    setBranchPickerOpen(false);
    for (const branch of branches) {
      const option = document.createElement("button");
      option.type = "button";
      option.className = "branch-option";
      option.value = branch;
      option.textContent = branch;
      option.setAttribute("aria-pressed", String(branch === chosen));
      option.addEventListener("click", () => {
        select.value = branch;
        $("branch-selected-text").textContent = branch;
        $("branch-manual").value = "";
        options.querySelectorAll("button").forEach((item) => {
          item.setAttribute("aria-pressed", String(item.value === branch));
        });
        setBranchPickerOpen(false);
        select.focus();
        updateStartButton();
      });
      options.append(option);
    }
    $("branch-count").textContent = state.branchCatalogComplete
      ? `下拉列表已读取 ${state.branches.length} 个仓库分支`
      : `下拉列表含默认及近期构建分支，其他分支可手动输入`;
    const mode = state.config?.branchMode || "branch";
    document.querySelectorAll('input[name="branch-mode"]').forEach((input) => {
      input.checked = input.value === mode;
    });
    $("branch-select").disabled = mode === "all";
    $("branch-manual").disabled = mode === "all";
  }

  function setBranchPickerOpen(open) {
    $("branch-options").hidden = !open;
    $("branch-select").setAttribute("aria-expanded", String(open));
  }

  function renderSetup() {
    $("setup-screen").hidden = state.monitoring;
    $("watch-screen").hidden = !state.monitoring;
    if (!state.repository || !state.workflows.length) {
      $("selection-surface").hidden = true;
      return;
    }
    $("selection-surface").hidden = false;
    $("repository-name").textContent = state.repository.fullName;
    const privacy = state.repository.private ? "私有仓库" : "公开仓库";
    $("repository-detail").textContent = [privacy, state.repository.defaultBranch, state.repository.description].filter(Boolean).join(" · ");
    renderWorkflowChoices();
    renderBranchChoices();
    updateStartButton();
  }

  function selectedWorkflowIds() {
    return [...document.querySelectorAll('input[name="workflow"]:checked')].map((input) => Number(input.value));
  }

  function updateStartButton() {
    const button = $("start-watching");
    if (!button) return;
    const mode = document.querySelector('input[name="branch-mode"]:checked')?.value || "branch";
    const branch = $("branch-manual")?.value.trim() || $("branch-select")?.value || "";
    const disabled = state.busy || selectedWorkflowIds().length === 0 || (mode === "branch" && !branch);
    button.dataset.forceDisabled = disabled ? "true" : "false";
    button.disabled = disabled;
  }

  function currentPollInterval() {
    const active = state.runs.some(isActiveRun);
    return model.pollInterval(state.hasToken, active);
  }

  async function configureTimers() {
    if (!state.monitoring) return;
    const interval = currentPollInterval();
    await toolbox().background.setTimer(POLL_TIMER, interval);
    await toolbox().background.setTimer(CLOCK_TIMER, CLOCK_INTERVAL_MS);
    state.nextPollAt = Date.now() + interval;
  }

  async function ensureSession() {
    if (state.sessionId) {
      const status = await toolbox().background.status(state.sessionId);
      if (status) return state.sessionId;
    }
    const session = await toolbox().background.start({
      restoreAfterProcessDeath: true,
      restoreAfterReboot: true
    });
    state.sessionId = session.sessionId;
    return state.sessionId;
  }

  async function releaseRuntimeResources() {
    let firstError = null;
    for (const key of [POLL_TIMER, CLOCK_TIMER]) {
      try {
        await toolbox().background.cancelTimer(key);
      } catch (error) {
        if (error?.code !== "NOT_FOUND" && !firstError) firstError = error;
      }
    }
    if (state.liveActive && state.sessionId) {
      try {
        await toolbox().notifications.live.end(state.sessionId);
      } catch (error) {
        if (error?.code !== "NOT_FOUND" && !firstError) firstError = error;
      }
    }
    if (state.sessionId) {
      try {
        await toolbox().background.stop(state.sessionId);
      } catch (error) {
        if (error?.code !== "NOT_FOUND" && !firstError) firstError = error;
      }
    }
    return firstError;
  }

  async function startWatching() {
    if (state.busy || !state.repository) return;
    const workflowIds = selectedWorkflowIds();
    const branchMode = document.querySelector('input[name="branch-mode"]:checked')?.value || "branch";
    const branch = $("branch-manual").value.trim() || $("branch-select").value;
    if (!workflowIds.length || (branchMode === "branch" && !branch)) {
      showToast("请选择至少一个 workflow 和分支范围");
      return;
    }
    setBusy(true);
    setLoading(true, "启动守望", "正在创建可恢复的后台环境");
    try {
      state.config = {
        owner: state.repository.owner,
        repo: state.repository.repo,
        fullName: state.repository.fullName,
        selectedWorkflowIds: workflowIds,
        workflowNames: Object.fromEntries(state.workflows.map((workflow) => [String(workflow.id), workflow.name])),
        branchMode,
        branch: branchMode === "branch" ? branch : ""
      };
      state.monitoring = true;
      state.watchStartedAt = Date.now();
      state.trackedRunKeys = state.discoveryRuns.filter(isActiveRun).map(model.runKey);
      state.terminalStates = {};
      state.warning = null;
      state.warningMessage = "";
      await ensureSession();
      await configureTimers();
      await persist(true);
      renderAll();
      await pollGitHub(true);
    } catch (error) {
      await releaseRuntimeResources();
      state.monitoring = false;
      state.sessionId = null;
      state.liveActive = false;
      showToast(`启动失败：${errorLabel(error)}`);
      renderAll();
    } finally {
      setLoading(false);
      setBusy(false);
    }
  }

  async function fetchJobs(run) {
    const key = model.runKey(run);
    const jobs = await fetchPaged(
      repoUrl(`/actions/runs/${run.id}/attempts/${Number(run.run_attempt) || 1}/jobs?per_page=100`),
      "jobs",
      undefined,
      10
    );
    state.jobsByRun[key] = jobs;
    return jobs;
  }

  async function ensureTimingModel(run) {
    const key = timingKey(run);
    if (state.timingModels[key]) return state.timingModels[key];
    try {
      const response = await apiRequest(repoUrl(`/actions/workflows/${run.workflow_id}/runs?status=success&per_page=100`));
      const candidates = Array.isArray(response.data?.workflow_runs) ? response.data.workflow_runs : [];
      const samples = model.selectHistoricalRuns(candidates, run);
      const sampleJobs = {};
      for (const sample of samples) {
        sampleJobs[model.runKey(sample)] = await fetchPaged(
          repoUrl(`/actions/runs/${sample.id}/attempts/${Number(sample.run_attempt) || 1}/jobs?per_page=100`),
          "jobs",
          undefined,
          10
        );
      }
      state.timingModels[key] = model.buildTimingModel(samples, sampleJobs);
    } catch (error) {
      if (["rate_limit", "offline"].includes(error?.kind)) throw error;
      state.timingModels[key] = model.buildTimingModel([], {});
    }
    return state.timingModels[key];
  }

  function calculateEstimate(run, now = Date.now()) {
    const key = model.runKey(run);
    const timing = state.timingModels[timingKey(run)] || null;
    const jobs = state.jobsByRun[key] || [];
    const previous = state.progressByRun[key] || 0;
    const estimate = model.estimateRunProgress(run, jobs, timing, now, previous);
    state.progressByRun[key] = estimate.progress;
    return estimate;
  }

  function trackDiscoveredRuns(runs) {
    const tracked = new Set(state.trackedRunKeys);
    for (const run of runs) {
      const createdAt = Date.parse(run.created_at || "") || 0;
      if (isActiveRun(run) || createdAt >= (state.watchStartedAt || Date.now())) tracked.add(model.runKey(run));
    }
    state.trackedRunKeys = [...tracked].slice(-100);
  }

  function watchedRuns() {
    const tracked = new Set(state.trackedRunKeys);
    return state.runs.filter((run) => tracked.has(model.runKey(run)));
  }

  function chooseDisplayedRun() {
    const now = Date.now();
    const eligible = watchedRuns().filter((run) => {
      if (isActiveRun(run)) return true;
      const terminal = state.terminalStates[model.runKey(run)];
      return terminal && !terminal.posted && terminal.holdUntil > now;
    });
    return model.choosePrimaryRun(eligible);
  }

  function registerTerminalRuns() {
    const now = Date.now();
    for (const run of watchedRuns()) {
      const key = model.runKey(run);
      if (run.status === "completed" && !state.terminalStates[key]) {
        state.terminalStates[key] = { holdUntil: now + TERMINAL_HOLD_MS, posted: false };
      }
    }
  }

  async function pollGitHub(manual) {
    if (!state.monitoring || state.pollInFlight) return;
    if (state.rateResetAt && state.rateRemaining === 0 && Date.now() < state.rateResetAt) {
      state.warning = "rate_limit";
      state.warningMessage = `额度将在 ${formatClock(state.rateResetAt)} 恢复`;
      renderDashboard();
      return;
    }
    state.pollInFlight = true;
    if (manual) setLoading(true, "同步构建", "正在读取 workflow runs、jobs 和 step 状态");
    try {
      const response = await apiRequest(repoUrl("/actions/runs?per_page=100"));
      const allRuns = Array.isArray(response.data?.workflow_runs) ? response.data.workflow_runs : [];
      const filtered = model.selectedRuns(allRuns, state.config.selectedWorkflowIds, state.config.branchMode, state.config.branch)
        .sort((a, b) => (Date.parse(b.created_at || "") || 0) - (Date.parse(a.created_at || "") || 0));
      trackDiscoveredRuns(filtered);
      state.runs = filtered;
      const active = state.runs.filter(isActiveRun);
      for (const run of active) {
        await fetchJobs(run);
      }
      for (const run of active) {
        await ensureTimingModel(run);
        calculateEstimate(run);
      }
      registerTerminalRuns();
      state.warning = null;
      state.warningMessage = "";
      state.lastPollAt = Date.now();
      state.rateResetAt = state.rateRemaining === 0 ? state.rateResetAt : null;
      await configureTimers();
      await updateLiveNotification();
      await processTerminalResults();
      await persist();
    } catch (error) {
      state.warning = error?.kind === "rate_limit" ? "rate_limit" : "offline";
      state.warningMessage = errorLabel(error);
      if (["invalid_token", "permission", "not_found"].includes(error?.kind)) {
        state.warning = error.kind;
      }
      await updateLiveNotification();
      if (manual) showToast(errorLabel(error));
      await persist();
    } finally {
      state.pollInFlight = false;
      if (manual) setLoading(false);
      renderDashboard();
    }
  }

  function liveRequestFor(run) {
    const estimate = run ? calculateEstimate(run) : { progress: 0, elapsedMs: 0, remainingMs: null, overrunMs: 0, sampleCount: 0, job: "", step: "" };
    const warning = ["rate_limit", "offline"].includes(state.warning) ? state.warning : null;
    const summary = model.buildNotificationSummary(state.config.fullName, run, estimate, warning);
    let primaryText = summary.primaryText;
    let secondaryText = summary.secondaryText;
    let body = summary.body;
    if (run?.status === "in_progress") {
      primaryText = `${estimate.progress}% · 已用 ${formatDuration(estimate.elapsedMs, true)}`;
      const eta = estimate.remainingMs !== null
        ? `剩余约 ${formatDuration(estimate.remainingMs, true)}`
        : estimate.overrunMs > 0
          ? `超均值 ${formatDuration(estimate.overrunMs, true)}`
          : "正在估算时间";
      secondaryText = [estimate.job, estimate.step, eta].filter(Boolean).join(" · ");
      body = [run.head_branch, String(run.head_sha || "").slice(0, 7), `基于最近 ${estimate.sampleCount} 次构建`].filter(Boolean).join(" · ");
    }
    return {
      sessionId: state.sessionId,
      title: cleanText(summary.title, 64),
      primaryText: cleanText(primaryText, 32),
      secondaryText: cleanText(secondaryText, 96),
      body: cleanText(body, 256),
      shortText: cleanText(summary.shortText, 12),
      updatedAt: Date.now(),
      progress: summary.progress,
      accentColor: summary.color,
      tone: summary.tone
    };
  }

  async function updateLiveNotification() {
    if (!state.monitoring || !state.sessionId || state.liveInFlight) return;
    const run = chooseDisplayedRun();
    if (!run && !state.liveActive) return;
    if (!run && state.liveActive) return;
    state.liveInFlight = true;
    try {
      const request = liveRequestFor(run);
      if (state.liveActive) {
        try {
          await toolbox().notifications.live.update(request);
        } catch (error) {
          if (!["NOT_FOUND", "INVALID_SESSION"].includes(error?.code)) throw error;
          await toolbox().notifications.live.start(request);
        }
      } else {
        await toolbox().notifications.live.start(request);
        state.liveActive = true;
      }
    } catch (error) {
      state.warningMessage = `实时展示失败：${errorLabel(error)}`;
    } finally {
      state.liveInFlight = false;
    }
  }

  async function processTerminalResults() {
    const now = Date.now();
    let endedPrimary = false;
    for (const run of watchedRuns()) {
      const key = model.runKey(run);
      const terminal = state.terminalStates[key];
      if (!terminal || terminal.posted || terminal.holdUntil > now) continue;
      const workflow = configWorkflowName(run);
      const title = `${workflow} · ${resultLabel(run)}`;
      const body = `${state.config.fullName} · ${run.head_branch || "未知分支"} · ${String(run.head_sha || "").slice(0, 7)}`;
      try {
        await toolbox().notifications.post(`github-watch-result-${run.id}-${run.run_attempt || 1}`, cleanText(title, 64), cleanText(body, 256));
        terminal.posted = true;
        endedPrimary = true;
      } catch (error) {
        state.warningMessage = `结果通知失败：${errorLabel(error)}`;
      }
    }
    if (endedPrimary && !state.runs.some(isActiveRun) && state.liveActive) {
      try {
        await toolbox().notifications.live.end(state.sessionId);
        state.liveActive = false;
      } catch (error) {
        if (error?.code !== "NOT_FOUND") state.warningMessage = `结束实时展示失败：${errorLabel(error)}`;
      }
    }
  }

  async function clockTick(background) {
    if (!state.monitoring) return;
    for (const run of state.runs.filter(isActiveRun)) calculateEstimate(run);
    renderDashboard();
    if (background) {
      await updateLiveNotification();
      await processTerminalResults();
      await persist();
    }
  }

  async function stopWatching() {
    if (state.busy || !state.monitoring) return;
    setBusy(true);
    setLoading(true, "停止守望", "正在清理后台时钟和实时展示");
    try {
      const cleanupError = await releaseRuntimeResources();
      state.monitoring = false;
      state.sessionId = null;
      state.liveActive = false;
      state.nextPollAt = null;
      state.warning = null;
      state.warningMessage = "";
      await persist();
      renderAll();
      showToast(cleanupError ? `守望已停止；部分清理失败：${errorLabel(cleanupError)}` : "守望已停止，后台资源已清理");
    } catch (error) {
      showToast(`停止失败：${errorLabel(error)}`);
    } finally {
      setLoading(false);
      setBusy(false);
    }
  }

  function emptyNode(message) {
    const node = document.createElement("p");
    node.className = "empty-state";
    node.textContent = message;
    return node;
  }

  function stateDot(value) {
    const dot = document.createElement("span");
    dot.className = "state-dot";
    dot.dataset.state = value || "idle";
    dot.setAttribute("aria-hidden", "true");
    return dot;
  }

  function renderActiveRuns() {
    const list = $("active-runs");
    list.replaceChildren();
    const active = state.runs.filter(isActiveRun);
    $("active-count").textContent = `${active.length} 个`;
    if (!active.length) {
      list.append(emptyNode("当前没有活动构建，后台将继续等待。"));
      return;
    }
    for (const run of active) {
      const estimate = calculateEstimate(run);
      const card = document.createElement("article");
      card.className = "run-card";
      const header = document.createElement("div");
      header.className = "run-card-header";
      const title = document.createElement("div");
      title.className = "run-card-title";
      const strong = document.createElement("strong");
      strong.textContent = configWorkflowName(run);
      const sub = document.createElement("span");
      sub.textContent = `${run.head_branch || "未知分支"} · ${String(run.head_sha || "").slice(0, 7)} · #${run.run_number || run.id}`;
      title.append(strong, sub);
      const percent = document.createElement("span");
      percent.className = "run-percent";
      percent.textContent = run.status === "in_progress" ? `${estimate.progress}%` : resultLabel(run);
      header.append(title, percent);
      const meta = document.createElement("p");
      meta.className = "run-card-meta";
      meta.textContent = [estimate.job, estimate.step, `已用 ${formatDuration(estimate.elapsedMs, true)}`].filter(Boolean).join(" · ");
      card.append(header, meta);
      list.append(card);
    }
  }

  function renderJobs(run) {
    const list = $("job-list");
    list.replaceChildren();
    if (!run) {
      list.append(emptyNode("发现运行中的构建后，将显示 job 与 step。"));
      return;
    }
    const jobs = state.jobsByRun[model.runKey(run)] || [];
    if (!jobs.length) {
      list.append(emptyNode(run.status === "queued" ? "构建仍在排队，GitHub 尚未生成 job。" : "尚未读取到 job 详情。"));
      return;
    }
    for (const job of jobs) {
      const details = document.createElement("details");
      if (job.status === "in_progress") details.open = true;
      const summary = document.createElement("summary");
      summary.append(stateDot(runStatus(job)));
      const name = document.createElement("strong");
      name.textContent = cleanText(job.name || "未命名 job", 180);
      const status = document.createElement("span");
      status.className = "result-label";
      status.dataset.result = runStatus(job) || "idle";
      status.textContent = resultLabel(job);
      summary.append(name, status);
      const steps = document.createElement("ol");
      steps.className = "step-list";
      for (const step of job.steps || []) {
        const row = document.createElement("li");
        row.className = "step-row";
        row.append(stateDot(runStatus(step)));
        const stepName = document.createElement("span");
        stepName.textContent = cleanText(step.name || `步骤 ${step.number || ""}`, 180);
        const duration = document.createElement("span");
        const actual = model.durationMs(step.started_at, step.completed_at);
        duration.textContent = actual === null ? resultLabel(step) : formatDuration(actual, true);
        row.append(stepName, duration);
        steps.append(row);
      }
      details.append(summary, steps);
      list.append(details);
    }
  }

  function renderRecentRuns() {
    const list = $("recent-runs");
    list.replaceChildren();
    const recent = state.runs.slice(0, 10);
    if (!recent.length) {
      list.append(emptyNode("所选范围内暂无 workflow run。"));
      return;
    }
    for (const run of recent) {
      const row = document.createElement("div");
      row.className = "recent-row";
      const main = document.createElement("div");
      main.className = "recent-main";
      const title = document.createElement("strong");
      title.textContent = configWorkflowName(run);
      const meta = document.createElement("span");
      meta.textContent = `${run.head_branch || "未知分支"} · #${run.run_number || run.id} · ${formatClock(Date.parse(run.created_at || ""))}`;
      main.append(title, meta);
      const result = document.createElement("span");
      result.className = "result-label";
      result.dataset.result = runStatus(run) || "idle";
      result.textContent = resultLabel(run);
      row.append(main, result);
      list.append(row);
    }
  }

  function renderWarning() {
    const banner = $("network-banner");
    if (!state.warning && !state.warningMessage) {
      banner.hidden = true;
      return;
    }
    banner.hidden = false;
    const messages = {
      rate_limit: state.rateResetAt ? `GitHub API 限流，${formatClock(state.rateResetAt)} 后自动恢复` : "GitHub API 限流，正在等待恢复",
      offline: "网络暂时不可用，后台会按计划继续重试",
      invalid_token: "Token 无效；请停止守望并更新 Token",
      permission: "Token 缺少 Actions: read 权限",
      not_found: "仓库不可见，或 Token 无权读取"
    };
    $("network-message").textContent = state.warningMessage || messages[state.warning] || "同步状态异常";
  }

  function renderHero(run) {
    const estimate = run ? calculateEstimate(run) : null;
    const presentation = model.statePresentation(run, ["rate_limit", "offline"].includes(state.warning) ? state.warning : null);
    $("hero-card").dataset.tone = presentation.tone;
    $("watch-title").textContent = run ? presentation.label : "等待构建";
    $("run-repository").textContent = state.config?.fullName || "";
    $("run-workflow").textContent = run ? configWorkflowName(run) : "正在等待新的 workflow run";
    $("current-step").textContent = run
      ? [estimate.job, estimate.step].filter(Boolean).join(" · ") || presentation.label
      : "后台守望已启动";
    $("run-branch").textContent = run?.head_branch || (state.config?.branchMode === "all" ? "全部分支" : state.config?.branch || "--");
    $("run-sha").textContent = run?.head_sha ? String(run.head_sha).slice(0, 7) : "--";
    const progress = estimate?.progress || 0;
    $("progress-ring").style.setProperty("--progress", `${progress * 3.6}deg`);
    $("progress-ring").setAttribute("aria-valuenow", String(progress));
    $("progress-value").textContent = run ? `${progress}%` : "--";
    $("elapsed-time").textContent = estimate ? formatDuration(estimate.elapsedMs) : "--";
    if (!estimate?.sampleCount) {
      $("remaining-time").textContent = run ? "等权步骤" : "等待样本";
      $("sample-caption").textContent = "暂无可用历史样本，当前按等权步骤估算。";
    } else if (estimate.remainingMs !== null) {
      $("remaining-time").textContent = `约 ${formatDuration(estimate.remainingMs, true)}`;
      $("sample-caption").textContent = `估算基于同类最近 ${estimate.sampleCount} 次成功构建的算术平均值。`;
    } else if (estimate.overrunMs > 0) {
      $("remaining-time").textContent = `超均值 ${formatDuration(estimate.overrunMs, true)}`;
      $("sample-caption").textContent = `已超过最近 ${estimate.sampleCount} 次构建的历史均值。`;
    } else {
      $("remaining-time").textContent = "即将完成";
      $("sample-caption").textContent = `估算基于同类最近 ${estimate.sampleCount} 次成功构建。`;
    }
  }

  function renderDashboard() {
    if (!state.monitoring) return;
    const run = chooseDisplayedRun() || model.choosePrimaryRun(state.runs.filter(isActiveRun));
    renderHero(run);
    renderWarning();
    renderActiveRuns();
    renderJobs(run);
    renderRecentRuns();
    const interval = currentPollInterval();
    const tokenLabel = state.hasToken ? "认证" : "匿名";
    const rate = Number.isFinite(state.rateRemaining) ? ` · 剩余额度 ${state.rateRemaining}` : "";
    $("poll-caption").textContent = state.lastPollAt
      ? `上次同步 ${formatClock(state.lastPollAt)} · ${tokenLabel}轮询 ${formatDuration(interval, true)}${rate}`
      : `等待首次同步 · ${tokenLabel}轮询 ${formatDuration(interval, true)}`;
  }

  function renderRuntimeChip() {
    const chip = $("runtime-chip");
    if (!state.ready) {
      chip.textContent = "宿主不可用";
      chip.dataset.state = "error";
    } else if (state.monitoring) {
      chip.textContent = state.warning ? "守望异常" : "后台守望中";
      chip.dataset.state = state.warning ? "warning" : "active";
    } else if (state.repository) {
      chip.textContent = "待启动";
      chip.dataset.state = "idle";
    } else {
      chip.textContent = "未配置";
      chip.dataset.state = "idle";
    }
  }

  function renderAll() {
    renderRuntimeChip();
    renderSetup();
    if (state.monitoring) renderDashboard();
  }

  async function reconcileSession() {
    if (!state.monitoring) return;
    const sessions = await toolbox().background.listSessions();
    const existing = sessions.find((session) => session.sessionId === state.sessionId) || sessions[0];
    state.sessionId = existing?.sessionId || null;
    await ensureSession();
    await configureTimers();
  }

  async function boot() {
    renderAll();
    if (!toolbox()?.ready || !model) {
      $("host-caption").textContent = "需要 ToolBox 0.3.2 或更高版本";
      renderRuntimeChip();
      return;
    }
    try {
      const ready = await toolbox().ready();
      state.ready = true;
      $("host-caption").textContent = `ToolBox ${ready.hostVersion} · API ${ready.apiVersion}`;
      const saved = await toolbox().storage.get(STORAGE_KEY);
      restoreObject(saved);
      await loadToken();
      if (state.repository) $("repo-input").value = `https://github.com/${state.repository.fullName}`;
      if (state.monitoring) {
        await reconcileSession();
        renderAll();
        await pollGitHub(false);
      }
    } catch (error) {
      state.ready = false;
      $("host-caption").textContent = errorLabel(error);
    }
    renderAll();
  }

  $("load-repository").addEventListener("click", readRepository);
  $("repo-input").addEventListener("keydown", (event) => {
    if (event.key === "Enter") readRepository();
  });
  $("clear-token").addEventListener("click", async () => {
    try {
      await toolbox().storage.secure.remove(TOKEN_KEY);
      state.token = null;
      state.hasToken = false;
      $("token-input").value = "";
      renderTokenState();
      showToast("已清除保存的 Token");
    } catch (error) {
      showToast(errorLabel(error));
    }
  });
  $("change-repository").addEventListener("click", () => {
    state.repository = null;
    state.workflows = [];
    state.branches = [];
    state.branchCatalogComplete = false;
    state.discoveryRuns = [];
    state.config = null;
    $("repo-input").value = "";
    renderAll();
    persist().catch((error) => showToast(errorLabel(error)));
    $("repo-input").focus();
  });
  document.querySelectorAll('input[name="branch-mode"]').forEach((input) => {
    input.addEventListener("change", () => {
      const all = input.value === "all" && input.checked;
      $("branch-select").disabled = all;
      $("branch-manual").disabled = all;
      setBranchPickerOpen(false);
      updateStartButton();
    });
  });
  $("branch-select").addEventListener("click", () => {
    setBranchPickerOpen($("branch-options").hidden);
  });
  $("branch-picker").addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      setBranchPickerOpen(false);
      $("branch-select").focus();
    }
    if (event.key === "ArrowDown" && event.target === $("branch-select")) {
      event.preventDefault();
      setBranchPickerOpen(true);
      $("branch-options").querySelector('[aria-pressed="true"]')?.focus();
    }
  });
  document.addEventListener("click", (event) => {
    if (!$("branch-picker").contains(event.target)) setBranchPickerOpen(false);
  });
  $("branch-manual").addEventListener("input", updateStartButton);
  $("start-watching").addEventListener("click", startWatching);
  $("refresh-now").addEventListener("click", () => pollGitHub(true));
  $("stop-watching").addEventListener("click", stopWatching);

  if (toolbox()?.background?.onTimer) {
    toolbox().background.onTimer((event) => {
      if (event.key === POLL_TIMER) pollGitHub(false);
      if (event.key === CLOCK_TIMER) clockTick(true);
    });
  }
  if (toolbox()?.background?.onRestore) {
    toolbox().background.onRestore(async () => {
      try {
        const saved = await toolbox().storage.get(STORAGE_KEY);
        restoreObject(saved);
        await loadToken();
        await reconcileSession();
        await pollGitHub(false);
      } catch (error) {
        state.warning = "offline";
        state.warningMessage = `恢复后同步失败：${errorLabel(error)}`;
        renderAll();
      }
    });
  }

  function startForegroundClock() {
    if (!foregroundClock) foregroundClock = setInterval(() => clockTick(false), 1000);
  }

  function stopForegroundClock() {
    if (!foregroundClock) return;
    clearInterval(foregroundClock);
    foregroundClock = null;
  }

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) stopForegroundClock();
    else startForegroundClock();
  });
  window.addEventListener("pagehide", stopForegroundClock);
  window.addEventListener("pageshow", startForegroundClock);
  startForegroundClock();
  boot();
})();
