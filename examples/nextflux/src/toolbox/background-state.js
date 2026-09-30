import { atom } from "nanostores";

// User intent survives a temporary native timer failure; the driver does not.
export const continuousSync = atom(false);
export const nativeTimerEffective = atom(false);
