(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.GitHubWatcherModel = api;
})(typeof globalThis === "object" ? globalThis : this, function () {
  "use strict";

  const ACTIVE_STATUSES = new Set(["in_progress", "queued", "waiting", "pending", "requested"]);
  const TERMINAL_STATUSES = new Set(["completed"]);
  const DEFAULT_STEP_MS = 60_000;

  function clamp(value, minimum, maximum) {
    return Math.min(maximum, Math.max(minimum, value));
  }

  function parseTime(value) {
    const parsed = Date.parse(value || "");
    return Number.isFinite(parsed) ? parsed : null;
  }

  function durationMs(startedAt, completedAt) {
    const start = parseTime(startedAt);
    const end = parseTime(completedAt);
    if (start === null || end === null || end <= start) return null;
    return end - start;
  }

  function average(values) {
    const valid = values.filter((value) => Number.isFinite(value) && value > 0);
    if (!valid.length) return null;
    return valid.reduce((sum, value) => sum + value, 0) / valid.length;
  }

  function runKey(run) {
    return `${run.id}:${Number(run.run_attempt) || 1}`;
  }

  function parseRepositoryLink(input) {
    const raw = String(input || "").trim();
    if (!raw) throw new Error("请输入 GitHub 仓库链接");
    let url;
    try {
      url = new URL(raw.includes("://") ? raw : `https://github.com/${raw.replace(/^\/+/, "")}`);
    } catch (_error) {
      throw new Error("仓库链接格式不正确");
    }
    const host = url.hostname.toLowerCase();
    if (host !== "github.com" && host !== "www.github.com") {
      throw new Error("只支持 github.com 仓库链接");
    }
    const parts = url.pathname.split("/").filter(Boolean);
    if (parts.length < 2) throw new Error("链接中缺少 owner/repo");
    const owner = parts[0];
    const repo = parts[1].replace(/\.git$/i, "");
    const safeSegment = /^[A-Za-z0-9_.-]+$/;
    if (!safeSegment.test(owner) || !safeSegment.test(repo) || !repo) {
      throw new Error("仓库 owner 或名称不正确");
    }
    return { owner, repo, fullName: `${owner}/${repo}` };
  }

  function normalizeJobName(name) {
    return String(name || "")
      .replace(/\s*[\[(][^\])]+[\])]\s*$/, "")
      .replace(/\s*\/\s*[^/]+$/, "")
      .replace(/\s+/g, " ")
      .trim();
  }

  function parseNextLink(linkHeader) {
    const value = String(linkHeader || "");
    for (const part of value.split(",")) {
      const match = part.match(/<([^>]+)>\s*;\s*rel="([^"]+)"/);
      if (match && match[2].split(/\s+/).includes("next")) return match[1];
    }
    return null;
  }

  function classifyApiStatus(status, remaining, retryAfterSeconds) {
    const hasRetryAfter = retryAfterSeconds !== null
      && retryAfterSeconds !== undefined
      && Number.isFinite(Number(retryAfterSeconds));
    if (status === 401) return "invalid_token";
    if (status === 403 && (Number(remaining) === 0 || hasRetryAfter)) return "rate_limit";
    if (status === 403) return "permission";
    if (status === 404) return "not_found";
    if (status >= 400) return "github";
    return null;
  }

  function pollInterval(authenticated, active) {
    if (authenticated) return active ? 15_000 : 60_000;
    return active ? 180_000 : 300_000;
  }

  function selectedRuns(runs, workflowIds, branchMode, branch) {
    const ids = new Set((workflowIds || []).map(Number));
    return (runs || []).filter((run) => {
      if (!ids.has(Number(run.workflow_id))) return false;
      if (branchMode === "all") return true;
      return String(run.head_branch || "") === String(branch || "");
    });
  }

  function validSuccessfulRun(run) {
    return run?.status === "completed"
      && run?.conclusion === "success"
      && durationMs(run.run_started_at || run.created_at, run.updated_at) !== null;
  }

  function newestFirst(a, b) {
    return (parseTime(b.run_started_at || b.created_at) || 0)
      - (parseTime(a.run_started_at || a.created_at) || 0);
  }

  function selectHistoricalRuns(runs, targetRun) {
    const workflowId = Number(targetRun.workflow_id);
    const eligible = (runs || []).filter((run) => Number(run.workflow_id) === workflowId && validSuccessfulRun(run));
    const groups = [
      eligible.filter((run) => run.head_branch === targetRun.head_branch && run.event === targetRun.event),
      eligible.filter((run) => run.event === targetRun.event),
      eligible
    ];
    const chosen = groups.find((group) => group.length) || [];
    return chosen.sort(newestFirst).slice(0, 10);
  }

  function buildTimingModel(samples, jobsByRun) {
    const exact = new Map();
    const normalized = new Map();
    const byJob = new Map();
    const everyStep = [];
    const totalDurations = [];

    function add(map, key, value) {
      if (!map.has(key)) map.set(key, []);
      map.get(key).push(value);
    }

    for (const run of samples || []) {
      const total = durationMs(run.run_started_at || run.created_at, run.updated_at);
      if (total !== null) totalDurations.push(total);
      const jobs = jobsByRun?.[runKey(run)] || [];
      for (const job of jobs) {
        const jobName = String(job.name || "未命名任务");
        const normalizedName = normalizeJobName(jobName);
        for (const step of job.steps || []) {
          const stepDuration = durationMs(step.started_at, step.completed_at);
          if (stepDuration === null) continue;
          const stepName = String(step.name || `步骤 ${step.number || ""}`).trim();
          add(exact, `${jobName}\u0000${stepName}`, stepDuration);
          add(normalized, `${normalizedName}\u0000${stepName}`, stepDuration);
          add(byJob, jobName, stepDuration);
          add(byJob, normalizedName, stepDuration);
          everyStep.push(stepDuration);
        }
      }
    }

    function means(map) {
      return Object.fromEntries([...map.entries()].map(([key, values]) => [key, average(values)]));
    }

    return {
      sampleCount: (samples || []).length,
      totalMeanMs: average(totalDurations),
      exactStepMs: means(exact),
      normalizedStepMs: means(normalized),
      jobStepMeanMs: means(byJob),
      globalStepMeanMs: average(everyStep)
    };
  }

  function expectedStepMs(model, jobName, stepName) {
    if (!model) return DEFAULT_STEP_MS;
    const normalizedName = normalizeJobName(jobName);
    return model.exactStepMs?.[`${jobName}\u0000${stepName}`]
      || model.normalizedStepMs?.[`${normalizedName}\u0000${stepName}`]
      || model.jobStepMeanMs?.[jobName]
      || model.jobStepMeanMs?.[normalizedName]
      || model.globalStepMeanMs
      || DEFAULT_STEP_MS;
  }

  function currentStep(jobs) {
    for (const job of jobs || []) {
      const step = (job.steps || []).find((candidate) => candidate.status === "in_progress");
      if (step) return { job: job.name || "任务", step: step.name || "执行中" };
    }
    const runningJob = (jobs || []).find((job) => job.status === "in_progress");
    if (runningJob) return { job: runningJob.name || "任务", step: "准备步骤" };
    const queuedJob = (jobs || []).find((job) => ACTIVE_STATUSES.has(job.status));
    return queuedJob ? { job: queuedJob.name || "任务", step: "等待运行器" } : { job: "", step: "" };
  }

  function estimateRunProgress(run, jobs, model, now, previousProgress) {
    const terminal = TERMINAL_STATUSES.has(run.status);
    const startedAt = parseTime(run.run_started_at || run.created_at);
    const completedAt = terminal ? parseTime(run.updated_at) : null;
    const elapsedEnd = completedAt ?? Number(now);
    const elapsedMs = startedAt === null ? 0 : Math.max(0, elapsedEnd - startedAt);
    if (terminal) {
      return {
        progress: 100,
        elapsedMs,
        expectedMs: model?.totalMeanMs || null,
        remainingMs: 0,
        overrunMs: 0,
        sampleCount: model?.sampleCount || 0,
        ...currentStep(jobs)
      };
    }

    let totalWeight = 0;
    let completedWeight = 0;
    for (const job of jobs || []) {
      const steps = (job.steps || []).length ? job.steps : [{ name: "执行", status: job.status, started_at: job.started_at }];
      for (const step of steps) {
        const weight = expectedStepMs(model, job.name || "任务", step.name || "执行");
        totalWeight += weight;
        if (step.status === "completed") {
          completedWeight += weight;
        } else if (step.status === "in_progress") {
          const stepStart = parseTime(step.started_at) ?? startedAt ?? Number(now);
          completedWeight += weight * clamp((Number(now) - stepStart) / weight, 0, 0.9);
        }
      }
    }

    let calculated;
    if (totalWeight > 0) {
      calculated = Math.round((completedWeight / totalWeight) * 100);
    } else if (run.status === "in_progress" && model?.totalMeanMs) {
      calculated = Math.round(clamp(elapsedMs / model.totalMeanMs, 0, 0.9) * 100);
    } else {
      calculated = run.status === "in_progress" ? 1 : 0;
    }
    const progress = Math.max(Number(previousProgress) || 0, clamp(calculated, 0, 98));
    const expectedMs = model?.totalMeanMs || null;
    const remainingMs = expectedMs && elapsedMs < expectedMs ? expectedMs - elapsedMs : null;
    const overrunMs = expectedMs && elapsedMs > expectedMs ? elapsedMs - expectedMs : 0;
    return {
      progress,
      elapsedMs,
      expectedMs,
      remainingMs,
      overrunMs,
      sampleCount: model?.sampleCount || 0,
      ...currentStep(jobs)
    };
  }

  function runPriority(run) {
    if (run.status === "in_progress") return 0;
    if (["queued", "waiting", "pending", "requested"].includes(run.status)) return 1;
    return 2;
  }

  function choosePrimaryRun(runs) {
    return [...(runs || [])].sort((a, b) => {
      const priority = runPriority(a) - runPriority(b);
      if (priority) return priority;
      return (parseTime(b.created_at) || 0) - (parseTime(a.created_at) || 0);
    })[0] || null;
  }

  function statePresentation(run, warning) {
    if (warning === "rate_limit" || warning === "offline") {
      return { label: warning === "rate_limit" ? "限流等待" : "网络离线", tone: "warning", color: "#F59E0B" };
    }
    if (!run) return { label: "等待构建", tone: "neutral", color: "#1684FF" };
    if (run.status === "in_progress") return { label: "运行中", tone: "neutral", color: "#1684FF" };
    if (["queued", "waiting", "pending", "requested"].includes(run.status)) return { label: "排队中", tone: "neutral", color: "#1684FF" };
    if (run.conclusion === "success") return { label: "构建成功", tone: "positive", color: "#19A66A" };
    if (["failure", "timed_out", "action_required", "stale"].includes(run.conclusion)) {
      return { label: run.conclusion === "timed_out" ? "构建超时" : "构建失败", tone: "negative", color: "#E94949" };
    }
    if (run.conclusion === "cancelled") return { label: "已取消", tone: "neutral", color: "#7A7F87" };
    return { label: "已结束", tone: "neutral", color: "#7A7F87" };
  }

  function shortSha(run) {
    return String(run?.head_sha || "").slice(0, 7);
  }

  function buildNotificationSummary(repoName, run, estimate, warning) {
    const presentation = statePresentation(run, warning);
    if (!run) {
      return {
        title: `${repoName} · 构建守望`,
        primaryText: presentation.label,
        secondaryText: "正在等待新的 workflow run",
        body: "后台守望保持运行",
        shortText: "等待",
        progress: 0,
        ...presentation
      };
    }
    const workflow = run.name || run.display_title || "GitHub Actions";
    const progressText = run.status === "in_progress" ? `${estimate.progress}%` : presentation.label;
    const current = [estimate.job, estimate.step].filter(Boolean).join(" · ") || presentation.label;
    const branchSha = [run.head_branch, shortSha(run)].filter(Boolean).join(" · ");
    return {
      title: `${repoName} · ${workflow}`,
      primaryText: progressText,
      secondaryText: current,
      body: [current, branchSha].filter(Boolean).join("\n"),
      shortText: run.status === "in_progress" ? `${estimate.progress}%` : presentation.label.slice(0, 12),
      progress: run.status === "completed" ? 100 : estimate.progress,
      ...presentation
    };
  }

  return {
    ACTIVE_STATUSES,
    buildNotificationSummary,
    buildTimingModel,
    classifyApiStatus,
    choosePrimaryRun,
    durationMs,
    estimateRunProgress,
    normalizeJobName,
    parseNextLink,
    parseRepositoryLink,
    pollInterval,
    runKey,
    selectHistoricalRuns,
    selectedRuns,
    statePresentation
  };
});
