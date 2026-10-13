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
  const positive = (value, fallback) => Number.isFinite(Number(value)) && Number(value) > 0 ? Number(value) : fallback;
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

  let preferences = sanitizePreferences({});
  let history = [];
  let currentScreen = "setup";
  let currentSession = null;
  let frameId = null;
  let wakeLockSentinel = null;
  let pauseMessage = "";
  let shouldResumeAfterEndDialog = false;
  let toastTimer = null;
  let runtimeForeground = false;
  let completionPending = null;
  // Data lives in ToolBox.storage; it is only written after the saved copy was read, so an
  // early or failed read can never overwrite the user's history.
  // States: loading, ready (ToolBox.storage), legacy (storage permission off: keep using
  // the pre-1.0.5 localStorage copy until it can be migrated) and unavailable.
  let storageState = "loading";
  let hostConnected = false;
  // The ToolBox handshake has not settled yet (host haptics may still become usable).
  let hostPending = false;
  let loadWork = null;
  // Preferences as last read from ToolBox.storage (defaults before the first read). Values
  // that differ from it were chosen while the saved copy was unknown and win over it.
  let preferencesBase = sanitizePreferences({});
  // The user cleared history and no empty-history write to ToolBox.storage has been
  // confirmed since: do not bring the stored entries back. A fresh token per clear, so a
  // write that started before the clear cannot settle it.
  let historyCleared = null;
  // The localStorage fallback was used this run, so its copy must be merged on recovery.
  let legacyUsed = false;
  // Fallback copy to remove once everything in memory has reached ToolBox.storage.
  let pendingLegacyDrop = null;
  const dirtyKeys = new Set();
  let writeWork = null;
  let hapticsWarned = false;
  const wakeLockSupported = typeof navigator.wakeLock?.request === "function";

  function hostApi() {
    return window.ToolBox;
  }

  function sanitizePreferences(stored) {
    const value = stored && typeof stored === "object" ? stored : {};
    const selectedMode = [...Object.keys(PRESETS), "custom"].includes(value.selectedMode)
      ? value.selectedMode
      : DEFAULT_PREFERENCES.selectedMode;
    return {
      selectedMode,
      sound: typeof value.sound === "boolean" ? value.sound : DEFAULT_PREFERENCES.sound,
      voice: typeof value.voice === "boolean" ? value.voice : DEFAULT_PREFERENCES.voice,
      vibration: typeof value.vibration === "boolean" ? value.vibration : DEFAULT_PREFERENCES.vibration,
      keepAwake: typeof value.keepAwake === "boolean" ? value.keepAwake : DEFAULT_PREFERENCES.keepAwake,
      volume: clamp(Number.isFinite(value.volume) ? value.volume : DEFAULT_PREFERENCES.volume, 0, 100),
      custom: {
        contract: positive(value.custom?.contract, DEFAULT_PREFERENCES.custom.contract),
        relax: positive(value.custom?.relax, DEFAULT_PREFERENCES.custom.relax),
        reps: positive(value.custom?.reps, DEFAULT_PREFERENCES.custom.reps)
      }
    };
  }

  function sanitizeHistory(stored) {
    if (!Array.isArray(stored)) return [];
    return stored.filter((entry) => (
      entry
      && typeof entry.completedAt === "string"
      && typeof entry.mode === "string"
      && Number.isFinite(entry.reps)
      && Number.isFinite(entry.durationMs)
    ));
  }

  // Union of several history copies, newest first; a session present in more than one copy
  // (same completion time and contents) is kept once.
  function mergeHistory(...lists) {
    const seen = new Set();
    const merged = [];
    for (const entry of lists.flatMap(sanitizeHistory)) {
      const id = `${entry.completedAt}|${entry.mode}|${entry.reps}|${entry.durationMs}`;
      if (seen.has(id)) continue;
      seen.add(id);
      merged.push(entry);
    }
    return merged.sort((a, b) => (a.completedAt < b.completedAt ? 1 : a.completedAt > b.completedAt ? -1 : 0));
  }

  // Versions up to 1.0.4 kept data in WebView localStorage, which the host may not keep.
  function readLegacyStorage() {
    try {
      const storage = window.localStorage;
      if (!storage || typeof storage.getItem !== "function") return null;
      const read = (key) => {
        const value = storage.getItem(key);
        return value ? JSON.parse(value) : null;
      };
      return { storage, preferences: read(STORAGE_KEYS.preferences), history: read(STORAGE_KEYS.history) };
    } catch (_) {
      return null;
    }
  }

  function dropLegacyStorage(legacy) {
    try {
      legacy.storage.removeItem(STORAGE_KEYS.preferences);
      legacy.storage.removeItem(STORAGE_KEYS.history);
    } catch (_) {}
  }

  function preferenceEdits(base, current) {
    const edits = {};
    for (const key of Object.keys(current)) {
      if (key !== "custom" && base[key] !== current[key]) edits[key] = current[key];
    }
    const custom = {};
    for (const key of Object.keys(current.custom)) {
      if (base.custom[key] !== current.custom[key]) custom[key] = current.custom[key];
    }
    if (Object.keys(custom).length) edits.custom = custom;
    return edits;
  }

  function applyPreferenceEdits(saved, edits) {
    return { ...saved, ...edits, custom: { ...saved.custom, ...(edits.custom || {}) } };
  }

  // Returns false when the fallback copy could not be written (quota, blocked storage).
  function writeLegacy() {
    try {
      window.localStorage.setItem(STORAGE_KEYS.preferences, JSON.stringify(preferences));
      window.localStorage.setItem(STORAGE_KEYS.history, JSON.stringify(history));
      return true;
    } catch (_) {
      return false;
    }
  }

  // The fallback copy can no longer be written: say so instead of claiming it is kept.
  // Everything stays in memory and is merged into ToolBox.storage once it is enabled.
  function legacyWriteFailed() {
    storageState = "unavailable";
    dirtyKeys.add(STORAGE_KEYS.preferences);
    dirtyKeys.add(STORAGE_KEYS.history);
    showToast(hostConnected
      ? "网页存储写入失败，训练记录仅保留在本次会话，请开启「存储」权限"
      : "网页存储写入失败，训练记录仅保留在本次会话");
    renderHistory();
  }

  // The storage permission was switched off after data loaded: keep everything in the
  // localStorage fallback (merged back once it is enabled) instead of retrying forever.
  function storagePermissionRevoked() {
    storageState = "legacy";
    legacyUsed = true;
    pendingLegacyDrop = null;
    dirtyKeys.clear();
    if (!writeLegacy()) { legacyWriteFailed(); return; }
    showToast("请在工具权限中开启「存储」，训练记录会自动迁移并长期保存");
    renderHistory();
  }

  // Without a ToolBox host the trainer runs as a plain page: localStorage is its storage.
  function useStandaloneStorage() {
    const legacy = readLegacyStorage();
    if (!legacy) {
      storageState = "unavailable";
      return;
    }
    const changed = adoptLoaded(legacy.preferences, [legacy.history]);
    storageState = "legacy";
    // Edits or sessions made before this point were only marked dirty: write them now,
    // since nothing else flushes in legacy mode.
    if (changed.preferences || changed.history || dirtyKeys.size > 0) {
      if (writeLegacy()) dirtyKeys.clear();
      else legacyWriteFailed();
    }
    syncControls();
    renderSetup();
  }

  // Adopts loaded data while keeping edits and sessions made before it could be read
  // (during the read, or while ToolBox.storage was unavailable).
  function adoptLoaded(savedPreferences, savedHistories) {
    const edits = preferenceEdits(preferencesBase, preferences);
    preferences = applyPreferenceEdits(sanitizePreferences(savedPreferences), edits);
    const before = history.length;
    history = historyCleared ? mergeHistory(history) : mergeHistory(history, ...savedHistories);
    return {
      preferences: Object.keys(edits).length > 0,
      history: Boolean(historyCleared) || before > 0
    };
  }

  function loadStoredData() {
    if (!loadWork) loadWork = readStoredData().finally(() => { loadWork = null; });
    return loadWork;
  }

  async function readStoredData() {
    const api = hostApi();
    let saved;
    try {
      saved = await api.storage.getMany([STORAGE_KEYS.preferences, STORAGE_KEYS.history]);
    } catch (_) {
      // An update from 1.0.4 adds the storage capability switched off. Keep showing and
      // recording data in the old localStorage copy (never deleting it) until it is enabled.
      const legacy = readLegacyStorage();
      const wasLegacy = storageState === "legacy";
      if (legacy) {
        adoptLoaded(legacy.preferences, [legacy.history]);
        storageState = "legacy";
        legacyUsed = true;
        if (!writeLegacy()) legacyWriteFailed();
      } else {
        storageState = "unavailable";
      }
      // legacyWriteFailed() already asked to enable storage; keep its warning visible.
      if (!wasLegacy && (!legacy || storageState === "legacy")) showToast("请在工具权限中开启「存储」，训练记录会自动迁移并长期保存");
      syncControls();
      if (currentScreen === "setup") renderSetup();
      renderHistory();
      return;
    }
    let [savedPreferences, savedHistory] = saved;
    // Sessions recorded in the fallback copy while storage was off are merged even when
    // ToolBox.storage already holds older data.
    const legacy = legacyUsed || (savedPreferences === null && savedHistory === null)
      ? readLegacyStorage() : null;
    const imported = Boolean(legacy && (legacy.preferences !== null || legacy.history !== null));
    if (imported && savedPreferences === null) savedPreferences = legacy.preferences;
    const changed = adoptLoaded(savedPreferences, imported ? [savedHistory, legacy.history] : [savedHistory]);
    preferencesBase = sanitizePreferences(savedPreferences);
    legacyUsed = false;
    storageState = "ready";
    if (imported) {
      const clearing = historyCleared;
      const savedPreferencesCopy = sanitizePreferences(preferences);
      try {
        await api.storage.apply({ set: [
          { key: STORAGE_KEYS.preferences, value: savedPreferencesCopy },
          { key: STORAGE_KEYS.history, value: history }
        ] });
        preferencesBase = savedPreferencesCopy;
        if (historyCleared === clearing) historyCleared = null;
        dropLegacyStorage(legacy);
      } catch (_) {
        // Remove the fallback copy only once a later write has saved everything.
        pendingLegacyDrop = legacy;
        dirtyKeys.add(STORAGE_KEYS.preferences);
        dirtyKeys.add(STORAGE_KEYS.history);
      }
    } else {
      if (changed.preferences) dirtyKeys.add(STORAGE_KEYS.preferences);
      if (changed.history) dirtyKeys.add(STORAGE_KEYS.history);
    }
    void writeDirty();
    syncControls();
    if (currentScreen === "setup") renderSetup();
    renderHistory();
  }

  // Coalesces rapid changes (e.g. dragging the volume slider) into one write per key;
  // changes made while a write is in flight go out in the next write.
  function writeDirty() {
    if (writeWork) return writeWork;
    if (storageState !== "ready" || dirtyKeys.size === 0) return Promise.resolve();
    const keys = [...dirtyKeys];
    dirtyKeys.clear();
    // Snapshot preferences: once saved they become the base that recovery compares against.
    const savedPreferences = keys.includes(STORAGE_KEYS.preferences) ? sanitizePreferences(preferences) : null;
    const clearing = keys.includes(STORAGE_KEYS.history) ? historyCleared : null;
    const set = keys.map((key) => ({
      key,
      value: key === STORAGE_KEYS.preferences ? savedPreferences : history
    }));
    writeWork = hostApi().storage.apply({ set }).then(() => {
      if (savedPreferences) preferencesBase = savedPreferences;
      // The stored history now holds only what is in memory: the clear is settled.
      if (clearing && historyCleared === clearing) historyCleared = null;
      return true;
    }, (error) => {
      if (error?.code === "PERMISSION_DENIED" || error?.code === "NOT_DECLARED") {
        storagePermissionRevoked();
        return false;
      }
      for (const key of keys) dirtyKeys.add(key);
      return false;
    }).then((saved) => {
      writeWork = null;
      if (saved && dirtyKeys.size === 0 && pendingLegacyDrop) {
        dropLegacyStorage(pendingLegacyDrop);
        pendingLegacyDrop = null;
      }
      return saved && dirtyKeys.size ? writeDirty() : undefined;
    });
    return writeWork;
  }

  function persistPreferences() {
    if (storageState === "legacy") { if (!writeLegacy()) legacyWriteFailed(); return; }
    dirtyKeys.add(STORAGE_KEYS.preferences);
    void writeDirty();
  }

  function persistHistory() {
    if (storageState === "legacy") { if (!writeLegacy()) legacyWriteFailed(); return; }
    dirtyKeys.add(STORAGE_KEYS.history);
    void writeDirty();
  }

  async function flushStorage() {
    // Closing during the first read: wait for it so edits and sessions made meanwhile are
    // merged into the saved copy and written, instead of being dropped with the WebView.
    if (loadWork) await loadWork;
    if (storageState !== "ready") return;
    await writeDirty();
    if (dirtyKeys.size) await writeDirty();
    if (dirtyKeys.size) throw new Error("训练数据保存失败");
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

  const HAPTIC_EFFECTS = Object.freeze({ prepare: "click", contract: "confirm", relax: "click", complete: "confirm" });

  // Browser fallback (standalone page or failed handshake), in milliseconds.
  const VIBRATION_PATTERNS = Object.freeze({ click: 20, confirm: [40, 60, 40] });

  function hostHaptics() {
    return hostConnected && typeof hostApi()?.haptics?.perform === "function";
  }

  function browserVibrationSupported() {
    return typeof navigator.vibrate === "function";
  }

  function hapticsSupported() {
    return hostHaptics() || browserVibrationSupported()
      || (hostPending && typeof hostApi()?.haptics?.perform === "function");
  }

  function vibrate(kind) {
    if (!preferences.vibration) return;
    const effect = HAPTIC_EFFECTS[kind] || "click";
    if (!hostHaptics()) {
      if (browserVibrationSupported()) {
        try { navigator.vibrate(VIBRATION_PATTERNS[effect]); } catch (_) {}
      }
      return;
    }
    hostApi().haptics.perform(effect).catch((error) => {
      if (hapticsWarned || (error?.code !== "PERMISSION_DENIED" && error?.code !== "NOT_DECLARED")) return;
      hapticsWarned = true;
      showToast("震动权限未开启，可在工具权限中打开");
    });
  }

  function handlePhaseCue(phase) {
    if (!runtimeForeground || !["prepare", "contract", "relax"].includes(phase)) return;
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
    $("wake-toggle").checked = preferences.keepAwake && wakeLockSupported;
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
    $("vibration-support-text").textContent = hapticsSupported()
      ? "用不同力度提示动作切换"
      : "当前环境不支持，将自动忽略";
  }

  function renderHistory() {
    const list = $("history-list");
    list.replaceChildren();
    $("clear-history-button").disabled = history.length === 0;
    $("history-summary").textContent = storageState === "legacy" && hostConnected
      ? `存储权限未开启：${history.length} 条记录暂存在网页存储，开启后自动迁移`
      : storageState !== "unavailable"
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

  // The training view is painted every animation frame; only touch the DOM when a value
  // actually changes (normally just the ring, plus the countdown once a second).
  const trainingView = {
    stage: $("rhythm-stage"),
    phaseLabel: $("phase-label"),
    phaseGuidance: $("phase-guidance"),
    countdown: $("countdown"),
    ring: $("ring-progress"),
    sessionMode: $("session-mode"),
    sessionRepetition: $("session-repetition"),
    pauseLabel: $("pause-label"),
    pauseNotice: $("pause-notice"),
    activeFeedback: $("active-feedback"),
    repProgress: $("rep-progress")
  };
  const paintedValues = new Map();

  function paint(key, value, apply) {
    if (paintedValues.get(key) === value) return;
    paintedValues.set(key, value);
    apply(value);
  }

  function renderProgressDots(state) {
    const container = trainingView.repProgress;
    const completed = completedRepetitions(state);
    const current = state.status === "complete" ? 0 : state.currentRep;
    paint("dots", `${state.config.reps}|${completed}|${current}`, () => {
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
    });
  }

  function renderTraining(state) {
    if (!runtimeForeground || !state.config || currentScreen !== "training") return;
    const view = trainingView;
    const isPaused = state.status === "paused";
    const phase = isPaused ? "paused" : state.phase;
    const phaseMeta = PHASE_META[phase] || PHASE_META.prepare;
    const remainingSeconds = Math.max(1, Math.ceil(state.remainingMs / 1000));
    const ringProgress = reducedMotion.matches ? 1 : state.progress;

    paint("phase", phase, (value) => {
      view.stage.dataset.phase = value;
      view.phaseLabel.textContent = phaseMeta.label;
      view.phaseGuidance.textContent = phaseMeta.guidance;
    });
    paint("countdown", remainingSeconds, (value) => {
      view.countdown.textContent = String(value);
      view.countdown.setAttribute("aria-label", `剩余 ${value} 秒`);
    });
    paint("ring", String(100 - ringProgress * 100), (value) => { view.ring.style.strokeDashoffset = value; });
    paint("mode", currentSession.fullLabel, (value) => { view.sessionMode.textContent = value; });
    paint("repetition", state.currentRep === 0
      ? `准备开始 · 共 ${state.config.reps} 次`
      : `第 ${state.currentRep} / ${state.config.reps} 次`, (value) => { view.sessionRepetition.textContent = value; });
    paint("pauseLabel", isPaused ? "继续" : "暂停", (value) => { view.pauseLabel.textContent = value; });
    paint("pauseNotice", isPaused ? (pauseMessage || "训练已暂停") : "", (value) => {
      view.pauseNotice.hidden = !isPaused;
      view.pauseNotice.textContent = value;
    });

    const activeFeedback = [];
    if (preferences.sound) activeFeedback.push("提示音");
    if (preferences.voice) activeFeedback.push("中文语音");
    if (preferences.vibration) activeFeedback.push("震动");
    paint("feedback", activeFeedback.length
      ? `${activeFeedback.join(" · ")}已开启`
      : "声音与震动均已关闭", (value) => { view.activeFeedback.textContent = value; });
    renderProgressDots(state);
  }

  function ensureAnimationFrame() {
    if (!runtimeForeground || frameId !== null || engine.status !== "running") return;
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
    if (!runtimeForeground || !preferences.keepAwake || engine.status !== "running" || !wakeLockSupported) return;
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
    completionPending = null;
    currentSession = getConfig();
    pauseMessage = "";
    audioFeedback.voiceFailed = false;
    audioFeedback.unlock();
    paintedValues.clear();
    showScreen("training");
    const state = engine.start(currentSession);
    renderTraining(state);
    requestWakeLock();
    ensureAnimationFrame();
  }

  function pauseSession(message = "训练已暂停", paint = true) {
    const state = engine.pause();
    if (state.status !== "paused") return;
    pauseMessage = message;
    cancelAnimationFrameLoop();
    releaseWakeLock();
    if (paint) renderTraining(state);
  }

  function resumeSession() {
    if (!runtimeForeground || engine.status !== "paused") return;
    pauseMessage = "";
    const state = engine.resume();
    renderTraining(state);
    requestWakeLock();
    ensureAnimationFrame();
  }

  function finishSession(state) {
    cancelAnimationFrameLoop();
    releaseWakeLock();

    const entry = {
      completedAt: new Date().toISOString(),
      mode: currentSession.fullLabel,
      reps: currentSession.reps,
      durationMs: Math.round(state.elapsedMs)
    };
    history = [entry, ...history];
    persistHistory();
    if (!runtimeForeground) {
      completionPending = state;
      return;
    }
    showCompletion(state, true);
  }

  function showCompletion(state, cue) {
    completionPending = null;
    if (cue) {
      audioFeedback.cue("complete");
      vibrate("complete");
    }
    renderHistory();

    $("complete-mode").textContent = currentSession.label;
    $("complete-reps").textContent = `${currentSession.reps} 次`;
    $("complete-duration").textContent = formatDuration(state.elapsedMs);
    showScreen("complete");
  }

  function abandonSession() {
    completionPending = null;
    cancelAnimationFrameLoop();
    releaseWakeLock();
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
    const numeric = Number(value);
    if (!Number.isFinite(numeric) || numeric <= 0 || name === "reps" && !Number.isSafeInteger(numeric)) return;
    preferences.custom[name] = numeric;
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
    // Kept until an empty-history write reaches ToolBox.storage: that write may still fail
    // (e.g. the storage permission was just switched off) and recovery must not merge the
    // stored entries back.
    historyCleared = {};
    persistHistory();
    renderHistory();
    clearDialog.close();
    showToast("训练记录已清空");
  });

  function handleRuntimeState(snapshot) {
    runtimeForeground = snapshot.foreground && !snapshot.closing;
    // Returning from the tool's permission page: retry ToolBox.storage and migrate.
    if (runtimeForeground && hostConnected && (storageState === "legacy" || storageState === "unavailable")) {
      void loadStoredData();
    }
    if (!runtimeForeground) {
      if (engine.status === "running") pauseSession("页面离开时已自动暂停，请手动继续", false);
      cancelAnimationFrameLoop();
      releaseWakeLock();
    } else {
      showForegroundTraining();
    }
  }

  // Back in the foreground (or the host handshake failed): continue a training screen
  // whose frames and wake lock were skipped while not foreground.
  function showForegroundTraining() {
    if (currentScreen !== "training") return;
    if (completionPending) { showCompletion(completionPending, false); return; }
    renderTraining(engine.tick());
    if (engine.status === "running") {
      requestWakeLock();
      ensureAnimationFrame();
    }
  }

  // The setup screen is already rendered; the host only adds lifecycle, storage and haptics.
  // Without a host (or if the handshake fails) the trainer still runs in the foreground.
  function connectHost() {
    const api = hostApi();
    if (typeof api?.ready !== "function") {
      runtimeForeground = true;
      useStandaloneStorage();
      renderHistory();
      return;
    }
    hostPending = true;
    api.ready().then(() => {
      hostPending = false;
      hostConnected = true;
      api.runtime.onStateChanged(handleRuntimeState);
      api.runtime.registerFlushHandler(async () => {
        if (engine.status === "running") pauseSession("页面离开时已自动暂停，请手动继续", false);
        cancelAnimationFrameLoop();
        await releaseWakeLock();
        await flushStorage();
      });
      syncControls();
      return loadStoredData();
    }).catch(() => {
      hostPending = false;
      runtimeForeground = true;
      // No usable host: run as a plain page on the localStorage copy, as without ToolBox.
      if (storageState === "loading") useStandaloneStorage();
      syncControls();
      renderHistory();
      showForegroundTraining();
    });
  }

  reducedMotion.addEventListener?.("change", () => renderTraining(engine.snapshot()));

  if (!wakeLockSupported) $("wake-toggle").closest(".settings-section").hidden = true;
  syncControls();
  renderSetup();
  renderHistory();
  connectHost();
})();
