// Merges per-feed counts returned after a read or star change into the
// sidebar's counts. Those results cover every feed, so feeds hidden from the
// sidebar (hide_globally, while 显示隐藏订阅 is off) are left out, the same
// as when the counts are first loaded.
export function hiddenFeedIds(feeds, showHiddenFeeds) {
  return new Set(showHiddenFeeds ? [] : feeds.filter((feed) => feed.hide_globally).map((feed) => String(feed.id)));
}

export function mergeCounts(current, incoming, hidden) {
  const next = { ...current };
  for (const [id, value] of Object.entries(incoming || {})) if (!hidden.has(String(id))) next[id] = value;
  return next;
}

// The sidebar's publishCounts: merges a change's counts into the unread and
// starred stores, leaving out feeds hidden from the sidebar.
export function createPublishCounts({ unreadCounts, starredCounts, feeds, settings }) {
  return (counts) => {
    const hidden = hiddenFeedIds(feeds.get(), settings.get().showHiddenFeeds);
    unreadCounts.set(mergeCounts(unreadCounts.get(), counts.unread, hidden));
    starredCounts.set(mergeCounts(starredCounts.get(), counts.starred, hidden));
  };
}
