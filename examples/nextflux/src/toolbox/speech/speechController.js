import { settingsState, updateSettings } from "@/stores/settingsStore.js";
import { patchSpeechState, resetSpeechState, speechState } from "@/stores/speechStore.js";
import { articleToSpeechText, splitSpeechText } from "./articleSpeech.mjs";
import { synthesizeSpeechSegment } from "./minimaxSpeech.mjs";

const RATE_STEPS = Object.freeze([0.85, 1, 1.15, 1.3, 1.5]);
let audio = null;
let session = null;
let runSerial = 0;
let currentIndex = -1;
let sourceUrl = "";

function fail(message, code = "SPEECH_ERROR") {
  return Object.assign(new Error(message), { code });
}

function sameSession(owner) {
  return owner && session === owner && owner.run === runSerial;
}

function setPlaybackState(value) {
  try {
    if (globalThis.navigator?.mediaSession) navigator.mediaSession.playbackState = value;
  } catch { /* optional platform integration */ }
}

function updateMediaPosition() {
  if (!audio || !globalThis.navigator?.mediaSession?.setPositionState) return;
  const duration = Number(audio.duration);
  const position = Number(audio.currentTime);
  if (!Number.isFinite(duration) || duration <= 0 || !Number.isFinite(position)) return;
  try {
    navigator.mediaSession.setPositionState({
      duration,
      playbackRate: Number(audio.playbackRate) || 1,
      position: Math.min(duration, Math.max(0, position)),
    });
  } catch { /* WebView may expose only part of Media Session */ }
}

function ensureAudio() {
  if (audio) return audio;
  if (typeof Audio !== "function") throw fail("当前 WebView 不支持音频播放。", "AUDIO_UNSUPPORTED");
  audio = new Audio();
  audio.preload = "auto";
  audio.addEventListener("timeupdate", () => {
    if (!session) return;
    patchSpeechState({
      currentTime: Number(audio.currentTime) || 0,
      duration: Number(audio.duration) || speechState.get().duration || 0,
    });
    updateMediaPosition();
  });
  audio.addEventListener("durationchange", () => {
    if (!session) return;
    const duration = Number(audio.duration);
    if (Number.isFinite(duration) && duration > 0) patchSpeechState({ duration });
  });
  audio.addEventListener("play", () => {
    if (!session) return;
    patchSpeechState({ phase: "playing", error: "" });
    setPlaybackState("playing");
  });
  audio.addEventListener("pause", () => {
    if (!session || audio.ended) return;
    if (speechState.get().phase === "playing") patchSpeechState({ phase: "paused" });
    setPlaybackState("paused");
  });
  audio.addEventListener("ended", () => {
    if (!session) return;
    advance().catch((error) => {
      if (!session || error?.code === "CANCELLED") return;
      patchSpeechState({ phase: "error", error: error.message || "下一段语音加载失败。" });
      setPlaybackState("paused");
    });
  });
  audio.addEventListener("error", () => {
    if (!session) return;
    patchSpeechState({ phase: "error", error: "设备无法播放 MiniMax 返回的音频。" });
    setPlaybackState("paused");
  });
  return audio;
}

function mediaAction(name, handler) {
  try { navigator.mediaSession?.setActionHandler?.(name, handler); }
  catch { /* action not supported */ }
}

function configureMediaSession(article) {
  if (!globalThis.navigator?.mediaSession) return;
  try {
    if (typeof MediaMetadata === "function") {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: String(article?.titleText ?? article?.title ?? "文章朗读"),
        artist: String(article?.feed?.title ?? article?.feed_title ?? "NextFlux"),
        album: "NextFlux · MiniMax 朗读",
      });
    }
  } catch { /* metadata support is optional */ }
  mediaAction("play", () => { play().catch(() => {}); });
  mediaAction("pause", pause);
  mediaAction("stop", stop);
  mediaAction("seekbackward", (event) => seekBy(-(Number(event?.seekOffset) || 15)));
  mediaAction("seekforward", (event) => seekBy(Number(event?.seekOffset) || 15));
  mediaAction("previoustrack", () => previousSegment().catch(() => {}));
  mediaAction("nexttrack", () => nextSegment().catch(() => {}));
}

function clearMediaSession() {
  if (!globalThis.navigator?.mediaSession) return;
  for (const action of ["play", "pause", "stop", "seekbackward", "seekforward", "previoustrack", "nexttrack"]) mediaAction(action, null);
  try {
    navigator.mediaSession.metadata = null;
    navigator.mediaSession.playbackState = "none";
  } catch { /* optional platform integration */ }
}

function releaseSession(owner) {
  if (!owner) return;
  for (const controller of owner.controllers.values()) controller.abort();
  owner.controllers.clear();
  owner.pending.clear();
  for (const item of owner.cache.values()) {
    try { URL.revokeObjectURL(item.url); } catch { /* already released */ }
  }
  owner.cache.clear();
}

function resetAudioElement() {
  if (!audio) return;
  try { audio.pause(); } catch { /* ignored */ }
  audio.removeAttribute("src");
  try { audio.load(); } catch { /* ignored */ }
  sourceUrl = "";
  currentIndex = -1;
}

function stopInternal() {
  runSerial += 1;
  const owner = session;
  session = null;
  resetAudioElement();
  releaseSession(owner);
  clearMediaSession();
  resetSpeechState();
}

function createSession(article) {
  const settings = { ...settingsState.get() };
  const text = articleToSpeechText(article, { readTitle: settings.speechReadTitle !== false });
  const segments = splitSpeechText(text);
  if (!segments.length) throw fail("这篇文章没有可朗读的正文。", "EMPTY_TEXT");
  return {
    run: runSerial,
    articleId: String(article.id),
    article,
    title: String(article?.titleText ?? article?.title ?? "文章朗读"),
    settings,
    segments,
    cache: new Map(),
    pending: new Map(),
    controllers: new Map(),
  };
}

async function ensureSegment(owner, index) {
  if (!sameSession(owner)) throw fail("语音生成已停止。", "CANCELLED");
  if (owner.cache.has(index)) return owner.cache.get(index);
  if (owner.pending.has(index)) return owner.pending.get(index);
  const controller = new AbortController();
  owner.controllers.set(index, controller);
  const promise = synthesizeSpeechSegment(owner.segments[index], owner.settings, { signal: controller.signal })
    .then((result) => {
      if (!sameSession(owner)) throw fail("语音生成已停止。", "CANCELLED");
      const blob = new Blob([result.bytes], { type: result.mimeType || "audio/mpeg" });
      const item = {
        url: URL.createObjectURL(blob),
        duration: result.durationMs > 0 ? result.durationMs / 1000 : 0,
      };
      owner.cache.set(index, item);
      patchSpeechState({ generatedCount: owner.cache.size });
      return item;
    })
    .finally(() => {
      owner.pending.delete(index);
      owner.controllers.delete(index);
    });
  owner.pending.set(index, promise);
  return promise;
}

function prefetch(owner, index) {
  if (!sameSession(owner) || owner.settings.speechPrefetch === false || index >= owner.segments.length) return;
  ensureSegment(owner, index).catch(() => {});
}

async function loadIndex(owner, index, { autoplay = true, position = 0 } = {}) {
  if (!sameSession(owner)) throw fail("语音生成已停止。", "CANCELLED");
  if (index < 0 || index >= owner.segments.length) return false;
  patchSpeechState({
    phase: owner.cache.has(index) ? "buffering" : "preparing",
    segmentIndex: index,
    error: "",
  });
  const item = await ensureSegment(owner, index);
  if (!sameSession(owner)) throw fail("语音生成已停止。", "CANCELLED");
  const player = ensureAudio();
  currentIndex = index;
  if (sourceUrl !== item.url) {
    sourceUrl = item.url;
    player.src = item.url;
    player.load();
  }
  const rate = Number(settingsState.get().speechPlaybackRate) || 1;
  player.playbackRate = Math.min(2, Math.max(0.5, rate));
  patchSpeechState({
    segmentIndex: index,
    segmentCount: owner.segments.length,
    currentTime: 0,
    duration: item.duration || 0,
    phase: autoplay ? "buffering" : "paused",
  });
  if (position > 0) {
    const setPosition = () => {
      const duration = Number(player.duration);
      if (Number.isFinite(duration) && duration > 0) {
        player.currentTime = Math.min(position, Math.max(0, duration - 0.05));
      }
    };
    if (player.readyState >= 1) setPosition();
    else player.addEventListener("loadedmetadata", setPosition, { once: true });
  }
  prefetch(owner, index + 1);
  if (!autoplay) return true;
  try {
    await player.play();
    return true;
  } catch (error) {
    if (error?.name === "NotAllowedError") {
      patchSpeechState({ phase: "paused", error: "语音已生成；请再点一次播放。" });
      setPlaybackState("paused");
      return true;
    }
    throw fail("音频无法开始播放，请重试。", "PLAY_FAILED");
  }
}

async function start(article) {
  stopInternal();
  const owner = createSession(article);
  session = owner;
  configureMediaSession(article);
  patchSpeechState({
    phase: "preparing",
    articleId: owner.articleId,
    title: owner.title,
    segmentIndex: 0,
    segmentCount: owner.segments.length,
    generatedCount: 0,
    currentTime: 0,
    duration: 0,
    error: "",
  });
  try {
    await loadIndex(owner, 0, { autoplay: true });
  } catch (error) {
    if (!sameSession(owner) || error?.code === "CANCELLED") throw error;
    patchSpeechState({ phase: "error", error: error.message || "语音生成失败。" });
    setPlaybackState("paused");
    throw error;
  }
}

export async function play() {
  if (!session) return false;
  const player = ensureAudio();
  if (!sourceUrl) return loadIndex(session, Math.max(0, currentIndex), { autoplay: true });
  player.playbackRate = Math.min(2, Math.max(0.5, Number(settingsState.get().speechPlaybackRate) || 1));
  try {
    await player.play();
    return true;
  } catch {
    throw fail("音频无法开始播放，请重试。", "PLAY_FAILED");
  }
}

export function pause() {
  if (!audio || !session) return;
  audio.pause();
}

async function advance() {
  if (!session) return false;
  const next = currentIndex + 1;
  if (next >= session.segments.length) {
    patchSpeechState({ phase: "ended", currentTime: speechState.get().duration || 0 });
    setPlaybackState("paused");
    return false;
  }
  return loadIndex(session, next, { autoplay: true });
}

export async function nextSegment() {
  if (!session) return false;
  const next = Math.min(session.segments.length - 1, currentIndex + 1);
  return loadIndex(session, next, { autoplay: speechState.get().phase === "playing" });
}

export async function previousSegment() {
  if (!session) return false;
  const previous = Math.max(0, currentIndex - 1);
  return loadIndex(session, previous, { autoplay: speechState.get().phase === "playing" });
}

export function seekTo(seconds) {
  if (!audio || !session) return;
  const duration = Number(audio.duration);
  if (!Number.isFinite(duration) || duration <= 0) return;
  audio.currentTime = Math.min(duration, Math.max(0, Number(seconds) || 0));
  patchSpeechState({ currentTime: audio.currentTime });
  updateMediaPosition();
}

export function seekBy(delta) {
  if (!audio || !session) return;
  const duration = Number(audio.duration);
  if (!Number.isFinite(duration) || duration <= 0) return;
  const target = audio.currentTime + Number(delta || 0);
  if (target >= duration - 0.05 && currentIndex < session.segments.length - 1) {
    nextSegment().catch(() => {});
    return;
  }
  if (target < 0 && currentIndex > 0) {
    const shouldPlay = speechState.get().phase === "playing";
    const owner = session;
    loadIndex(owner, currentIndex - 1, { autoplay: shouldPlay }).then(() => {
      if (!sameSession(owner) || !audio) return;
      const d = Number(audio.duration);
      if (Number.isFinite(d) && d > 0) seekTo(Math.max(0, d + target));
    }).catch(() => {});
    return;
  }
  seekTo(target);
}

export async function cyclePlaybackRate() {
  const current = Number(settingsState.get().speechPlaybackRate) || 1;
  let index = RATE_STEPS.findIndex((rate) => Math.abs(rate - current) < 0.01);
  index = (index + 1) % RATE_STEPS.length;
  const next = RATE_STEPS[index];
  await updateSettings({ speechPlaybackRate: next });
  if (audio) {
    audio.playbackRate = next;
    updateMediaPosition();
  }
  return next;
}

export async function toggle(article) {
  if (!article?.id) throw fail("当前没有可朗读的文章。", "NO_ARTICLE");
  const id = String(article.id);
  const state = speechState.get();
  if (!session || session.articleId !== id) return start(article);
  if (["preparing", "buffering"].includes(state.phase)) {
    stop();
    return false;
  }
  if (state.phase === "playing") {
    pause();
    return true;
  }
  if (state.phase === "ended") return loadIndex(session, 0, { autoplay: true });
  return play();
}

export function stop() {
  stopInternal();
}

export const speechController = Object.freeze({
  toggle,
  play,
  pause,
  stop,
  seekTo,
  seekBy,
  nextSegment,
  previousSegment,
  cyclePlaybackRate,
});
