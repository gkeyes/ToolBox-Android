/**
 * Keep unread entries ahead of lower-priority synchronization work.
 * Incremental phases are disjoint by status, so a changed entry is downloaded
 * once instead of through overlapping unread/changed/new queries.
 */
export async function runPrioritizedArticleSync({
  initial,
  unread,
  starred,
  readChanged,
  setProgress = () => {},
}) {
  setProgress("正在优先同步未读文章…");
  await unread();

  if (initial) {
    setProgress("正在同步已读收藏…");
    await starred();
    return;
  }

  setProgress("正在同步已读状态与收藏…");
  await readChanged();
}
