(() => {
  "use strict";

  const PRESETS = Object.freeze({
    beginner: Object.freeze({
      id: "beginner",
      label: "入门",
      fullLabel: "入门训练",
      prepare: 5,
      contract: 3,
      relax: 3,
      reps: 10,
      description: "适合第一次建立节奏，发力与放松时间相同。"
    }),
    standard: Object.freeze({
      id: "standard",
      label: "标准",
      fullLabel: "标准训练",
      prepare: 5,
      contract: 5,
      relax: 5,
      reps: 10,
      description: "延长保持时间，仍然给放松留出同样的空间。"
    }),
    endurance: Object.freeze({
      id: "endurance",
      label: "耐力",
      fullLabel: "耐力训练",
      prepare: 5,
      contract: 10,
      relax: 10,
      reps: 10,
      description: "适合已熟悉正确发力方式的人，不必追求更大力量。"
    }),
    quick: Object.freeze({
      id: "quick",
      label: "快速",
      fullLabel: "快速训练",
      prepare: 5,
      contract: 1,
      relax: 1,
      reps: 10,
      description: "短促收紧并立即完全松开，重点是动作清晰。"
    })
  });

  const PHASE_META = Object.freeze({
    prepare: Object.freeze({ label: "准备", guidance: "找到舒适姿势，放松肩腹" }),
    contract: Object.freeze({ label: "收紧", guidance: "轻轻向上提，保持呼吸" }),
    relax: Object.freeze({ label: "放松", guidance: "让盆底肌彻底回落" }),
    paused: Object.freeze({ label: "暂停", guidance: "节奏已停，准备好后继续" })
  });

  const DEFAULT_PREFERENCES = Object.freeze({
    selectedMode: "beginner",
    sound: true,
    voice: false,
    vibration: true,
    keepAwake: false,
    volume: 65,
    custom: Object.freeze({ contract: 3, relax: 3, reps: 10 })
  });

  const STORAGE_KEYS = Object.freeze({
    preferences: "wenli.preferences.v1",
    history: "wenli.history.v1"
  });

  const clamp = (value, minimum, maximum) => Math.min(maximum, Math.max(minimum, value));
  const defaultNow = () => {
    if (typeof performance !== "undefined" && typeof performance.now === "function") {
      return performance.now();
    }
    return Date.now();
  };

  class TrainingEngine {
    constructor({ now = defaultNow, onPhase = () => {}, onUpdate = () => {}, onComplete = () => {} } = {}) {
      this.now = now;
      this.onPhase = onPhase;
      this.onUpdate = onUpdate;
      this.onComplete = onComplete;
      this.reset();
    }

    reset() {
      this.status = "idle";
      this.phase = "prepare";
      this.config = null;
      this.currentRep = 0;
      this.phaseDurationMs = 0;
      this.deadline = 0;
      this.remainingWhenPaused = 0;
      this.startedAt = null;
      this.pausedAt = null;
      this.pausedTotal = 0;
      this.endedAt = 0;
    }

    start(config) {
      this.validateConfig(config);
      const now = this.now();
      this.status = "running";
      this.config = { ...config };
      this.currentRep = 0;
      this.startedAt = now;
      this.pausedAt = null;
      this.pausedTotal = 0;
      this.endedAt = 0;
      this.enterPhase("prepare", config.prepare * 1000, now);
      return this.snapshot(now);
    }

    validateConfig(config) {
      const values = [config?.prepare, config?.contract, config?.relax, config?.reps];
      if (!values.every((value) => Number.isFinite(value) && value > 0)) {
        throw new TypeError("Training config values must be positive numbers.");
      }
    }

    enterPhase(phase, durationMs, at) {
      this.phase = phase;
      this.phaseDurationMs = durationMs;
      this.deadline = at + durationMs;
      this.remainingWhenPaused = durationMs;
      this.onPhase(this.snapshot(at));
    }

    tick(at = this.now()) {
      if (this.status !== "running") {
        return this.snapshot(at);
      }

      while (this.status === "running" && at >= this.deadline) {
        this.advance(this.deadline);
      }

      const state = this.snapshot(at);
      this.onUpdate(state);
      return state;
    }

    advance(at) {
      if (this.phase === "prepare") {
        this.currentRep = 1;
        this.enterPhase("contract", this.config.contract * 1000, at);
        return;
      }

      if (this.phase === "contract") {
        this.enterPhase("relax", this.config.relax * 1000, at);
        return;
      }

      if (this.phase === "relax" && this.currentRep < this.config.reps) {
        this.currentRep += 1;
        this.enterPhase("contract", this.config.contract * 1000, at);
        return;
      }

      this.status = "complete";
      this.phase = "complete";
      this.phaseDurationMs = 0;
      this.deadline = at;
      this.endedAt = at;
      const state = this.snapshot(at);
      this.onComplete(state);
    }

    pause(at = this.now()) {
      if (this.status !== "running") return this.snapshot(at);
      this.tick(at);
      if (this.status !== "running") return this.snapshot(at);

      this.status = "paused";
      this.pausedAt = at;
      this.remainingWhenPaused = Math.max(0, this.deadline - at);
      const state = this.snapshot(at);
      this.onUpdate(state);
      return state;
    }

    resume(at = this.now()) {
      if (this.status !== "paused") return this.snapshot(at);
      this.pausedTotal += Math.max(0, at - this.pausedAt);
      this.pausedAt = null;
      this.deadline = at + this.remainingWhenPaused;
      this.status = "running";
      const state = this.snapshot(at);
      this.onUpdate(state);
      return state;
    }

    stop() {
      this.reset();
      return this.snapshot(this.now());
    }

    snapshot(at = this.now()) {
      let effectiveAt = at;
      if (this.status === "paused") effectiveAt = this.pausedAt;
      if (this.status === "complete") effectiveAt = this.endedAt;

      let remainingMs = 0;
      if (this.status === "running") remainingMs = Math.max(0, this.deadline - effectiveAt);
      if (this.status === "paused") remainingMs = this.remainingWhenPaused;

      const progress = this.phaseDurationMs > 0
        ? clamp((this.phaseDurationMs - remainingMs) / this.phaseDurationMs, 0, 1)
        : 1;
      const elapsedMs = this.startedAt !== null
        ? Math.max(0, effectiveAt - this.startedAt - this.pausedTotal)
        : 0;

      return {
        status: this.status,
        phase: this.phase,
        config: this.config ? { ...this.config } : null,
        currentRep: this.currentRep,
        remainingMs,
        phaseDurationMs: this.phaseDurationMs,
        progress,
        elapsedMs
      };
    }
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { TrainingEngine, PRESETS };
  }

  if (typeof document === "undefined") return;

  const $ = (id) => document.getElementById(id);
  const modeInputs = [...document.querySelectorAll('input[name="mode"]')];
  const screens = {
    setup: $("setup-screen"),
    training: $("training-screen"),
    complete: $("complete-screen")
  };
  const settingsDialog = $("settings-dialog");
  const endDialog = $("end-dialog");
  const clearDialog = $("clear-dialog");
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

  let persistentStorage = getPersistentStorage();
  let preferences = loadPreferences();
  let history = loadHistory();
  let currentScreen = "setup";
  let currentSession = null;
  let frameId = null;
  let wakeLockSentinel = null;
  let pauseMessage = "";
  let shouldResumeAfterEndDialog = false;
  let toastTimer = null;

  function getPersistentStorage() {
    try {
      const storage = window.localStorage;
      if (!storage || typeof storage.getItem !== "function" || typeof storage.setItem !== "function") {
        return null;
      }
      return storage;
    } catch (_) {
      return null;
    }
  }

  function readStoredJson(key, fallback) {
    if (!persistentStorage) return fallback;
    try {
      const value = persistentStorage.getItem(key);
      return value ? JSON.parse(value) : fallback;
    } catch (_) {
      persistentStorage = null;
      return fallback;
    }
  }

  function loadPreferences() {
    const stored = readStoredJson(STORAGE_KEYS.preferences, {});
    const selectedMode = [...Object.keys(PRESETS), "custom"].includes(stored.selectedMode)
      ? stored.selectedMode
      : DEFAULT_PREFERENCES.selectedMode;
    return {
      selectedMode,
      sound: typeof stored.sound === "boolean" ? stored.sound : DEFAULT_PREFERENCES.sound,
      voice: typeof stored.voice === "boolean" ? stored.voice : DEFAULT_PREFERENCES.voice,
      vibration: typeof stored.vibration === "boolean" ? stored.vibration : DEFAULT_PREFERENCES.vibration,
      keepAwake: typeof stored.keepAwake === "boolean" ? stored.keepAwake : DEFAULT_PREFERENCES.keepAwake,
      volume: clamp(Number(stored.volume) || DEFAULT_PREFERENCES.volume, 0, 100),
      custom: {
        contract: clamp(Number(stored.custom?.contract) || DEFAULT_PREFERENCES.custom.contract, 1, 10),
        relax: clamp(Number(stored.custom?.relax) || DEFAULT_PREFERENCES.custom.relax, 1, 20),
        reps: clamp(Number(stored.custom?.reps) || DEFAULT_PREFERENCES.custom.reps, 5, 15)
      }
    };
  }

  function loadHistory() {
    const stored = readStoredJson(STORAGE_KEYS.history, []);
    if (!Array.isArray(stored)) return [];
    return stored.filter((entry) => (
      entry
      && typeof entry.completedAt === "string"
      && typeof entry.mode === "string"
      && Number.isFinite(entry.reps)
      && Number.isFinite(entry.durationMs)
    )).slice(0, 30);
  }

  function persistPreferences() {
    if (!persistentStorage) return;
    try {
      persistentStorage.setItem(STORAGE_KEYS.preferences, JSON.stringify(preferences));
    } catch (_) {
      persistentStorage = null;
    }
  }

  function persistHistory() {
    if (!persistentStorage) return;
    try {
      persistentStorage.setItem(STORAGE_KEYS.history, JSON.stringify(history));
    } catch (_) {
      persistentStorage = null;
    }
  }

  function getConfig(mode = preferences.selectedMode) {
    if (mode === "custom") {
      return {
        id: "custom",
        label: "自定义",
        fullLabel: "自定义训练",
        prepare: 5,
        contract: preferences.custom.contract,
        relax: preferences.custom.relax,
        reps: preferences.custom.reps,
        description: "按你的当前节奏训练，完成后不会自动增加次数。"
      };
    }
    return { ...PRESETS[mode] };
  }

  function formatDuration(milliseconds) {
    const totalSeconds = Math.max(0, Math.round(milliseconds / 1000));
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    if (minutes === 0) return `${seconds} 秒`;
    if (seconds === 0) return `${minutes} 分钟`;
    return `${minutes} 分 ${String(seconds).padStart(2, "0")} 秒`;
  }

  function formatApproximateDuration(config) {
    const seconds = config.prepare + (config.contract + config.relax) * config.reps;
    if (seconds < 60) return `约 ${seconds} 秒`;
    const minutes = Math.max(1, Math.round(seconds / 60));
    return `约 ${minutes} 分钟`;
  }

  function formatHistoryDate(isoDate) {
    try {
      return new Intl.DateTimeFormat("zh-CN", {
        month: "numeric",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit"
      }).format(new Date(isoDate));
    } catch (_) {
      return isoDate.slice(0, 10);
    }
  }

  class AudioFeedback {
    constructor(player) {
      this.player = player;
      this.context = null;
      this.voiceFailed = false;
      this.voiceFiles = {
        prepare: "audio/prepare.ogg",
        contract: "audio/contract.ogg",
        relax: "audio/relax.ogg",
        complete: "audio/complete.ogg"
      };
      this.player.src = this.voiceFiles.prepare;
    }

    unlock() {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextClass) return;
      if (!this.context) this.context = new AudioContextClass();
      if (this.context.state === "suspended") this.context.resume().catch(() => {});
    }

    cue(kind) {
      this.unlock();
      if (preferences.sound) this.playTone(kind);
      if (preferences.voice && !this.voiceFailed) this.playVoice(kind);
    }

    playTone(kind) {
      if (!this.context) return;
      const now = this.context.currentTime;
      const volume = (preferences.volume / 100) * 0.16;
      const notes = {
        prepare: [[520, 0, 0.12]],
        contract: [[680, 0, 0.13]],
        relax: [[360, 0, 0.18]],
        complete: [[620, 0, 0.12], [820, 0.17, 0.16]]
      }[kind] || [];

      for (const [frequency, delay, duration] of notes) {
        const oscillator = this.context.createOscillator();
        const gain = this.context.createGain();
        oscillator.type = "sine";
        oscillator.frequency.setValueAtTime(frequency, now + delay);
        gain.gain.setValueAtTime(0.0001, now + delay);
        gain.gain.exponentialRampToValueAtTime(Math.max(0.0002, volume), now + delay + 0.012);
        gain.gain.exponentialRampToValueAtTime(0.0001, now + delay + duration);
        oscillator.connect(gain);
        gain.connect(this.context.destination);
        oscillator.start(now + delay);
        oscillator.stop(now + delay + duration + 0.02);
      }
    }

    playVoice(kind) {
      const source = this.voiceFiles[kind];
      if (!source) return;
      this.player.pause();
      this.player.src = source;
      this.player.currentTime = 0;
      this.player.volume = preferences.volume / 100;
      this.player.play().catch(() => {
        if (this.voiceFailed) return;
        this.voiceFailed = true;
        showToast("中文语音暂时无法播放，提示音仍可使用");
      });
    }
  }

  const audioFeedback = new AudioFeedback($("voice-player"));

  function vibrate(kind) {
    if (!preferences.vibration || typeof navigator.vibrate !== "function") return;
    const patterns = {
      prepare: 35,
      contract: 80,
      relax: [30, 45, 30],
      complete: [70, 60, 110]
    };
    navigator.vibrate(patterns[kind] || 30);
  }

  function handlePhaseCue(phase) {
    if (!["prepare", "contract", "relax"].includes(phase)) return;
    audioFeedback.cue(phase);
    vibrate(phase);
  }

  const engine = new TrainingEngine({
    onPhase: (state) => {
      handlePhaseCue(state.phase);
      renderTraining(state);
    },
    onUpdate: renderTraining,
    onComplete: finishSession
  });

  window.WenliTraining = Object.freeze({ TrainingEngine, PRESETS });

  function showScreen(name) {
    currentScreen = name;
    document.body.dataset.screen = name;
    for (const [screenName, element] of Object.entries(screens)) {
      element.hidden = screenName !== name;
    }
    window.scrollTo({ top: 0, behavior: reducedMotion.matches ? "auto" : "smooth" });
  }

  function selectMode(mode) {
    if (![...Object.keys(PRESETS), "custom"].includes(mode)) return;
    preferences.selectedMode = mode;
    persistPreferences();
    syncControls();
    renderSetup();
  }

  function renderSetup() {
    const config = getConfig();
    $("summary-title").textContent = config.fullLabel;
    $("summary-duration").textContent = formatApproximateDuration(config);
    $("summary-contract").textContent = `${config.contract} 秒`;
    $("summary-relax").textContent = `${config.relax} 秒`;
    $("summary-reps").textContent = `${config.reps} 次`;
    $("summary-description").textContent = config.description;
    renderFeedbackStatus();
  }

  function renderFeedbackStatus() {
    const statuses = [
      [$("sound-status"), preferences.sound, `提示音${preferences.sound ? "开启" : "关闭"}`],
      [$("voice-status"), preferences.voice, `语音${preferences.voice ? "开启" : "关闭"}`],
      [$("vibration-status"), preferences.vibration, `震动${preferences.vibration ? "开启" : "关闭"}`]
    ];
    for (const [element, enabled, label] of statuses) {
      element.dataset.enabled = String(enabled);
      element.lastChild.textContent = label;
    }
  }

  function syncControls() {
    for (const input of modeInputs) input.checked = input.value === preferences.selectedMode;
    $("settings-mode").value = preferences.selectedMode;
    $("sound-toggle").checked = preferences.sound;
    $("voice-toggle").checked = preferences.voice;
    $("vibration-toggle").checked = preferences.vibration;
    $("wake-toggle").checked = preferences.keepAwake;
    $("volume-range").value = String(preferences.volume);
    $("volume-output").textContent = `${preferences.volume}%`;
    $("contract-range").value = String(preferences.custom.contract);
    $("relax-range").value = String(preferences.custom.relax);
    $("reps-range").value = String(preferences.custom.reps);
    $("contract-output").textContent = `${preferences.custom.contract} 秒`;
    $("relax-output").textContent = `${preferences.custom.relax} 秒`;
    $("reps-output").textContent = `${preferences.custom.reps} 次`;
    $("custom-settings").hidden = preferences.selectedMode !== "custom";
    $("running-settings-note").hidden = currentScreen !== "training";
    $("vibration-support-text").textContent = typeof navigator.vibrate === "function"
      ? "用不同节奏提示动作切换"
      : "当前浏览器不支持，将自动忽略";
    $("wake-support-text").textContent = "wakeLock" in navigator
      ? "暂停或离开页面时自动释放"
      : "当前浏览器不支持，将自动忽略";
  }

  function renderHistory() {
    const list = $("history-list");
    list.replaceChildren();
    $("clear-history-button").disabled = history.length === 0;
    $("history-summary").textContent = persistentStorage
      ? (history.length === 0 ? "还没有训练记录" : `已保存 ${history.length} 次完成记录`)
      : (history.length === 0 ? "训练记录只保留在本次会话" : `本次会话已完成 ${history.length} 次`);

    for (const entry of history) {
      const item = document.createElement("li");
      const mode = document.createElement("strong");
      const time = document.createElement("time");
      const detail = document.createElement("span");
      mode.textContent = entry.mode;
      time.dateTime = entry.completedAt;
      time.textContent = formatHistoryDate(entry.completedAt);
      detail.textContent = `${entry.reps} 次 · ${formatDuration(entry.durationMs)}`;
      item.append(mode, time, detail);
      list.append(item);
    }
  }

  function completedRepetitions(state) {
    if (state.status === "complete") return state.config.reps;
    if (state.currentRep === 0) return 0;
    return Math.max(0, state.currentRep - 1);
  }

  function renderProgressDots(state) {
    const container = $("rep-progress");
    const completed = completedRepetitions(state);
    const current = state.status === "complete" ? 0 : state.currentRep;
    if (container.childElementCount !== state.config.reps) {
      container.replaceChildren();
      for (let index = 1; index <= state.config.reps; index += 1) {
        const dot = document.createElement("span");
        dot.className = "rep-dot";
        dot.setAttribute("aria-hidden", "true");
        container.append(dot);
      }
    }
    [...container.children].forEach((dot, index) => {
      const repetition = index + 1;
      dot.classList.toggle("is-complete", repetition <= completed);
      dot.classList.toggle("is-current", repetition === current);
    });
    container.setAttribute("aria-label", `已完成 ${completed} 次，共 ${state.config.reps} 次`);
  }

  function renderTraining(state) {
    if (!state.config || currentScreen !== "training") return;
    const isPaused = state.status === "paused";
    const phase = isPaused ? "paused" : state.phase;
    const phaseMeta = PHASE_META[phase] || PHASE_META.prepare;
    const remainingSeconds = Math.max(1, Math.ceil(state.remainingMs / 1000));
    const ringProgress = reducedMotion.matches ? 1 : state.progress;

    $("rhythm-stage").dataset.phase = phase;
    $("phase-label").textContent = phaseMeta.label;
    $("phase-guidance").textContent = phaseMeta.guidance;
    $("countdown").textContent = String(remainingSeconds);
    $("countdown").setAttribute("aria-label", `剩余 ${remainingSeconds} 秒`);
    $("ring-progress").style.strokeDashoffset = String(100 - ringProgress * 100);
    $("session-mode").textContent = currentSession.fullLabel;
    $("session-repetition").textContent = state.currentRep === 0
      ? `准备开始 · 共 ${state.config.reps} 次`
      : `第 ${state.currentRep} / ${state.config.reps} 次`;
    $("pause-label").textContent = isPaused ? "继续" : "暂停";
    $("pause-notice").hidden = !isPaused;
    $("pause-notice").textContent = isPaused ? (pauseMessage || "训练已暂停") : "";

    const activeFeedback = [];
    if (preferences.sound) activeFeedback.push("提示音");
    if (preferences.voice) activeFeedback.push("中文语音");
    if (preferences.vibration) activeFeedback.push("震动");
    $("active-feedback").textContent = activeFeedback.length
      ? `${activeFeedback.join(" · ")}已开启`
      : "声音与震动均已关闭";
    renderProgressDots(state);
  }

  function ensureAnimationFrame() {
    if (frameId !== null || engine.status !== "running") return;
    frameId = window.requestAnimationFrame(onAnimationFrame);
  }

  function onAnimationFrame(timestamp) {
    frameId = null;
    engine.tick(timestamp);
    ensureAnimationFrame();
  }

  function cancelAnimationFrameLoop() {
    if (frameId === null) return;
    window.cancelAnimationFrame(frameId);
    frameId = null;
  }

  async function requestWakeLock() {
    if (!preferences.keepAwake || engine.status !== "running" || !("wakeLock" in navigator)) return;
    try {
      if (wakeLockSentinel) await wakeLockSentinel.release();
      wakeLockSentinel = await navigator.wakeLock.request("screen");
      wakeLockSentinel.addEventListener("release", () => {
        wakeLockSentinel = null;
      }, { once: true });
    } catch (_) {
      wakeLockSentinel = null;
    }
  }

  async function releaseWakeLock() {
    if (!wakeLockSentinel) return;
    const sentinel = wakeLockSentinel;
    wakeLockSentinel = null;
    try {
      await sentinel.release();
    } catch (_) {}
  }

  function startSession() {
    currentSession = getConfig();
    pauseMessage = "";
    audioFeedback.voiceFailed = false;
    audioFeedback.unlock();
    showScreen("training");
    const state = engine.start(currentSession);
    renderTraining(state);
    requestWakeLock();
    ensureAnimationFrame();
  }

  function pauseSession(message = "训练已暂停") {
    const state = engine.pause();
    if (state.status !== "paused") return;
    pauseMessage = message;
    cancelAnimationFrameLoop();
    releaseWakeLock();
    renderTraining(state);
  }

  function resumeSession() {
    if (engine.status !== "paused") return;
    pauseMessage = "";
    const state = engine.resume();
    renderTraining(state);
    requestWakeLock();
    ensureAnimationFrame();
  }

  function finishSession(state) {
    cancelAnimationFrameLoop();
    releaseWakeLock();
    audioFeedback.cue("complete");
    vibrate("complete");

    const entry = {
      completedAt: new Date().toISOString(),
      mode: currentSession.fullLabel,
      reps: currentSession.reps,
      durationMs: Math.round(state.elapsedMs)
    };
    history = [entry, ...history].slice(0, 30);
    persistHistory();
    renderHistory();

    $("complete-mode").textContent = currentSession.label;
    $("complete-reps").textContent = `${currentSession.reps} 次`;
    $("complete-duration").textContent = formatDuration(state.elapsedMs);
    showScreen("complete");
  }

  function abandonSession() {
    cancelAnimationFrameLoop();
    releaseWakeLock();
    navigator.vibrate?.(0);
    engine.stop();
    currentSession = null;
    pauseMessage = "";
    showScreen("setup");
    renderSetup();
  }

  function openSettings() {
    if (engine.status === "running") {
      pauseSession("打开设置时已暂停，请手动继续");
    }
    syncControls();
    renderHistory();
    settingsDialog.showModal();
  }

  function closeSettings() {
    settingsDialog.close();
    renderSetup();
  }

  function showToast(message) {
    const toast = $("toast");
    window.clearTimeout(toastTimer);
    toast.textContent = message;
    toast.hidden = false;
    toastTimer = window.setTimeout(() => {
      toast.hidden = true;
    }, 2800);
  }

  function updateCustomPreference(name, value) {
    const limits = {
      contract: [1, 10],
      relax: [1, 20],
      reps: [5, 15]
    };
    const [minimum, maximum] = limits[name];
    preferences.custom[name] = clamp(Number(value), minimum, maximum);
    persistPreferences();
    syncControls();
    renderSetup();
  }

  for (const input of modeInputs) {
    input.addEventListener("change", () => selectMode(input.value));
  }

  $("settings-mode").addEventListener("change", (event) => selectMode(event.target.value));
  $("contract-range").addEventListener("input", (event) => updateCustomPreference("contract", event.target.value));
  $("relax-range").addEventListener("input", (event) => updateCustomPreference("relax", event.target.value));
  $("reps-range").addEventListener("input", (event) => updateCustomPreference("reps", event.target.value));

  $("sound-toggle").addEventListener("change", (event) => {
    preferences.sound = event.target.checked;
    persistPreferences();
    renderFeedbackStatus();
  });
  $("voice-toggle").addEventListener("change", (event) => {
    preferences.voice = event.target.checked;
    audioFeedback.voiceFailed = false;
    persistPreferences();
    renderFeedbackStatus();
  });
  $("vibration-toggle").addEventListener("change", (event) => {
    preferences.vibration = event.target.checked;
    if (!preferences.vibration) navigator.vibrate?.(0);
    persistPreferences();
    renderFeedbackStatus();
  });
  $("wake-toggle").addEventListener("change", (event) => {
    preferences.keepAwake = event.target.checked;
    persistPreferences();
    if (preferences.keepAwake) requestWakeLock();
    else releaseWakeLock();
  });
  $("volume-range").addEventListener("input", (event) => {
    preferences.volume = clamp(Number(event.target.value), 0, 100);
    $("volume-output").textContent = `${preferences.volume}%`;
    persistPreferences();
  });

  $("start-button").addEventListener("click", startSession);
  $("repeat-button").addEventListener("click", startSession);
  $("finish-button").addEventListener("click", () => {
    engine.stop();
    currentSession = null;
    showScreen("setup");
    renderSetup();
  });

  $("pause-button").addEventListener("click", () => {
    if (engine.status === "paused") resumeSession();
    else pauseSession();
  });

  $("end-button").addEventListener("click", () => {
    shouldResumeAfterEndDialog = engine.status === "running";
    if (shouldResumeAfterEndDialog) pauseSession("确认是否提前结束");
    endDialog.showModal();
  });
  $("keep-training-button").addEventListener("click", () => {
    endDialog.close();
    if (shouldResumeAfterEndDialog) resumeSession();
    shouldResumeAfterEndDialog = false;
  });
  $("confirm-end-button").addEventListener("click", () => {
    endDialog.close();
    shouldResumeAfterEndDialog = false;
    abandonSession();
  });
  endDialog.addEventListener("cancel", () => {
    if (shouldResumeAfterEndDialog) window.setTimeout(resumeSession, 0);
    shouldResumeAfterEndDialog = false;
  });

  $("settings-button").addEventListener("click", openSettings);
  $("settings-close").addEventListener("click", closeSettings);
  $("settings-done").addEventListener("click", closeSettings);
  settingsDialog.addEventListener("click", (event) => {
    if (event.target === settingsDialog) closeSettings();
  });

  $("clear-history-button").addEventListener("click", () => clearDialog.showModal());
  $("keep-history-button").addEventListener("click", () => clearDialog.close());
  $("confirm-clear-button").addEventListener("click", () => {
    history = [];
    persistHistory();
    renderHistory();
    clearDialog.close();
    showToast("训练记录已清空");
  });

  document.addEventListener("visibilitychange", () => {
    if (document.hidden && engine.status === "running") {
      pauseSession("页面离开时已自动暂停，请手动继续");
    }
  });

  window.addEventListener("pagehide", () => {
    if (engine.status === "running") pauseSession("页面离开时已自动暂停，请手动继续");
    releaseWakeLock();
  });

  reducedMotion.addEventListener?.("change", () => renderTraining(engine.snapshot()));

  syncControls();
  renderSetup();
  renderHistory();
})();
