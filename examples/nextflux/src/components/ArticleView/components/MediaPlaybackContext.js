import { createContext } from "react";

// Context reaches cached reading portals as their article becomes a preview,
// pauses for a transition, or leaves the foreground without being unmounted.
export const MediaPlaybackContext = createContext(true);
