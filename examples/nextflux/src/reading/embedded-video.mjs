const YOUTUBE_HOSTS = new Map([
  ["youtube.com", "www.youtube.com"],
  ["www.youtube.com", "www.youtube.com"],
  ["youtube-nocookie.com", "www.youtube-nocookie.com"],
  ["www.youtube-nocookie.com", "www.youtube-nocookie.com"],
]);

// Article HTML supplies a video identity, never executable iframe attributes or
// a caller-chosen referrer. The host owns the isolated player and app identity.
export function youtubeEmbedSource(value) {
  if (typeof value !== "string" || /[\u0000-\u0020\u007f]/u.test(value)) return null;
  let source;
  try { source = new URL(value); } catch { return null; }
  const host = YOUTUBE_HOSTS.get(source.hostname);
  const match = /^\/embed\/([A-Za-z0-9_-]{11})\/?$/.exec(source.pathname);
  if (!host || !match || source.protocol !== "https:" || source.username || source.password || source.port) return null;
  const videoId = match[1];
  const embed = new URL(`https://${host}/embed/${videoId}`);
  const watch = new URL(`https://www.youtube.com/watch?v=${videoId}`);
  for (const name of ["start", "end"]) {
    const raw = source.searchParams.get(name);
    if (!raw || !/^\d+$/.test(raw)) continue;
    const seconds = Number(raw);
    if (!Number.isSafeInteger(seconds) || seconds < 0 || seconds > 2_147_483_647) continue;
    if (name === "end" && seconds <= Number(embed.searchParams.get("start") || 0)) continue;
    embed.searchParams.set(name, String(seconds));
    if (name === "start" && seconds > 0) watch.searchParams.set("t", `${seconds}s`);
  }
  return { videoId, embedUrl: embed.href, watchUrl: watch.href };
}
