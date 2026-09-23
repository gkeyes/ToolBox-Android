import {draftStorage} from "@platform/storage";
const key = "socialcoach.arena.location";

export function rememberArenaLocation(query: string) {
  try { draftStorage.setItem(key, `/arena${query ? `?${query}` : ""}`); } catch {}
}

export function arenaReturnPath() {
  try {
    const path = draftStorage.getItem(key);
    if (path === "/arena" || path?.startsWith("/arena?")) return path;
  } catch {}
  return "/arena";
}
