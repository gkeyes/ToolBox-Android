// Miniflux's bookmark endpoint is a toggle (204, no body). Once it has been
// acknowledged the server state has changed, so the expected new state is
// committed through the acknowledged path first; a follow-up read only
// confirms or corrects it (another client may have toggled too). A failed or
// malformed read never reports the toggle as failed, so a retry cannot undo it.
export async function toggleStarAcknowledged({ current, toggle, fetchEntry, save }) {
  await toggle();
  const expected = current.starred === 1 ? 0 : 1;
  await save(expected);
  let server;
  try { server = await fetchEntry(); } catch { return expected; }
  if (typeof server?.starred !== "boolean") return expected;
  const actual = server.starred ? 1 : 0;
  if (actual !== expected) await save(actual);
  return actual;
}
