import { getFeedUnreadEntries } from "@/api/miniflux.js";
import { markScannedUnreadAsRead } from "@/stores/articlesStore.js";

function fetchPages({ feedId, check, onPage, beforeEntryId }) {
  return getFeedUnreadEntries(feedId, check, onPage, { beforeEntryId });
}

export const titleFilterBackfill = {
  async preview(options) {
    const { previewUnreadTitleFilter } = await import("./title-filter-backfill.mjs");
    return previewUnreadTitleFilter({ ...options, fetchPages });
  },
  async apply(options) {
    const { applyUnreadTitleFilter } = await import("./title-filter-backfill.mjs");
    return applyUnreadTitleFilter({ ...options, fetchPages, markAsRead: markScannedUnreadAsRead });
  },
};
