import { atom } from "nanostores";

export const initialSpeechState = Object.freeze({
  phase: "idle",
  articleId: null,
  title: "",
  segmentIndex: 0,
  segmentCount: 0,
  generatedCount: 0,
  currentTime: 0,
  duration: 0,
  error: "",
});

export const speechState = atom({ ...initialSpeechState });

export function patchSpeechState(patch) {
  speechState.set({ ...speechState.get(), ...patch });
}

export function resetSpeechState() {
  speechState.set({ ...initialSpeechState });
}
