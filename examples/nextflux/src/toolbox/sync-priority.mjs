/**
 * Keep unread entries ahead of all lower-priority synchronization work.
 * The caller owns staging/commit; this helper only controls request order.
 */
export async function runPrioritizedArticleSync({
  initial,
  unread,
  starred,
  changed,
  fresh,
  setProgress = () => {},
}) {
  setProgress("正在优先同步未读文章…");
  await unread();

  if (initial) {
    setProgress("正在同步已读收藏…");
    await starred();
    return;
  }

  setProgress("正在同步文章状态…");
  await Promise.all([changed(), fresh()]);
}
