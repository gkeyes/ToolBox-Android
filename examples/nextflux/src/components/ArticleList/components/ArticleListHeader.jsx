import { useParams } from "react-router";
import { useStore } from "@nanostores/react";
import { SidebarTrigger } from "@/components/ui/sidebar.jsx";
import { filter, queryTotal } from "@/stores/articlesStore.js";
import { feeds, categories } from "@/stores/feedsStore.js";
import MarkAllReadButton from "./MarkAllReadButton";
import { isSyncing, syncProgress, error } from "@/stores/syncStore.js";
import { useTranslation } from "react-i18next";
import {
  totalStarredCount,
  totalUnreadCount,
  getCategoryCount,
  getFeedCount,
} from "@/stores/feedsStore.js";
import MenuButton from "./MenuButton";
import FeedTitleFilterButton from "./FeedTitleFilterButton.jsx";
import ArticleListTabs from "./ArticleListFooter.jsx";
import { useEffect, useLayoutEffect, useRef } from "react";

const TAB_DENSITIES = ["full", "compact", "tight", "small"];

/**
 * Fits the filter capsule to the width it is given: drops the tab icons, then
 * tightens the padding, then the text. If even that is too wide beside the
 * buttons, the capsule takes a row of its own and steps down again there, so
 * it never runs under the buttons on narrow screens, large display sizes or
 * large system fonts. Past the smallest density it scrolls sideways.
 */
function useTabsFit(ref, deps) {
  useLayoutEffect(() => {
    const box = ref.current;
    if (!box) return undefined;
    // A capsule already on its own row (inside a feed) has nowhere to move.
    const canStack = box.classList.contains("article-list-inline-tabs");
    const fit = () => {
      const list = box.querySelector(".tabs__list");
      if (!list) return;
      // The list is capped at the width it can use (padding excluded), so it
      // overflows itself when its content does not fit.
      box.dataset.scroll = "false";
      for (const stacked of canStack ? [false, true] : [false]) {
        box.dataset.stacked = String(stacked);
        for (const density of TAB_DENSITIES) {
          box.dataset.fit = density;
          if (list.scrollWidth <= list.clientWidth + 1) return;
        }
      }
      // Nothing fits (very large text or long translations): scroll the
      // capsule sideways rather than hiding a tab.
      box.dataset.scroll = "true";
    };
    fit();
    // Watch the list and its tabs too: late fonts, a text-size change or a
    // longer count resize them without changing the space it is given (once
    // the list is capped, only the tabs themselves change size).
    const observer = typeof ResizeObserver === "function" ? new ResizeObserver(fit) : null;
    observer?.observe(box);
    const list = box.querySelector(".tabs__list");
    if (list) observer?.observe(list);
    for (const tab of list?.querySelectorAll(".tabs__tab") || []) observer?.observe(tab);
    return () => observer?.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}

/** Title and status line shared by the collapsed top bar and the large title. */
export function useArticleListTitle() {
  const { feedId, categoryId } = useParams();
  const $filter = useStore(filter);
  const $feeds = useStore(feeds);
  const $categories = useStore(categories);
  const $isSyncing = useStore(isSyncing);
  const $syncProgress = useStore(syncProgress);
  const $syncError = useStore(error);
  const $totalStarredCount = useStore(totalStarredCount);
  const $totalUnreadCount = useStore(totalUnreadCount);
  const $getCategoryCount = useStore(getCategoryCount);
  const $getFeedCount = useStore(getFeedCount);
  const { t } = useTranslation();

  const getTitleText = () => {
    if (feedId) {
      const feed = $feeds.find((f) => f.id === parseInt(feedId));
      return feed?.title || "";
    }
    if (categoryId) {
      const category = $categories.find((c) => c.id === parseInt(categoryId));
      return category?.title || "";
    }
    switch ($filter) {
      case "unread":
        return t("articleList.unread");
      case "starred":
        return t("articleList.starred");
      default:
        return t("articleList.all");
    }
  };

  const getCountNumber = () => {
    if (!feedId && !categoryId) {
      return $filter === "starred" ? $totalStarredCount : $totalUnreadCount;
    } else if (feedId) {
      return $getFeedCount(feedId);
    } else if (categoryId) {
      return $getCategoryCount(categoryId);
    }
  };

  const getFilteredCount = () => {
    switch ($filter) {
      case "starred": {
        const starredCount = getCountNumber();
        return starredCount > 0
          ? `${starredCount} ${t("articleList.starredItems")}`
          : `${t("articleList.noStarred")}`;
      }
      case "unread":
      case "all": {
        const unreadCount = getCountNumber();
        return unreadCount > 0
          ? `${unreadCount} ${t("articleList.unreadItems")}`
          : `${t("articleList.noUnread")}`;
      }
      default:
        return "";
    }
  };

  const $queryTotal = useStore(queryTotal);
  // The number shown beside the active tab: live unread count for "unread",
  // otherwise the total of the current query.
  const activeCount = $filter === "unread" ? getCountNumber() : $queryTotal;

  return {
    feedId,
    categoryId,
    title: getTitleText(),
    status: $isSyncing ? $syncProgress || t("common.syncing") : $syncError?.message || getFilteredCount(),
    // Only transient states need a line of their own; counts live on the tabs.
    transientStatus: $isSyncing ? $syncProgress || t("common.syncing") : $syncError?.message || "",
    hasError: !$isSyncing && Boolean($syncError?.message),
    activeCount: Number.isFinite(activeCount) ? activeCount : null,
  };
}

/** Expanded 30px title. It scrolls with the list, so collapsing never re-lays out the list. */
export function ArticleListLargeTitle() {
  const { title, status, hasError } = useArticleListTitle();
  return (
    <div className="ds-large-title-block article-list-large-title">
      <h1 className="ds-large-title line-clamp-2 break-words">{title}</h1>
      <p
        role="status"
        className={hasError ? "ds-subhead ds-tnum mt-0.5 line-clamp-2 text-danger" : "ds-subhead ds-tnum mt-0.5 line-clamp-2"}
      >
        {status}
      </p>
    </div>
  );
}

// Reader layout: the compact bar always carries the title and the live sync
// status; there is no large title above the list. The hairline appears once
// the list scrolls under the bar.
// Reader layout. In the smart views (no feed/category) the tabs ARE the
// title: one row — sidebar, 收藏 / 未读 N / 全部, actions. Inside a feed or
// category, its name gets a compact row and the tabs sit below it. The count
// appears only beside the active tab. Sync progress/errors show as a caption.
export default function ArticleListHeader({ collapsed = false }) {
  const { feedId, categoryId, title, transientStatus, hasError, activeCount } = useArticleListTitle();
  const scoped = Boolean(feedId || categoryId);
  const wrapRef = useRef(null);
  const tabsRef = useRef(null);
  const { i18n } = useTranslation();
  useTabsFit(tabsRef, [scoped, activeCount, i18n.language]);
  // The header floats over the list (so it can slide away without relayout);
  // publish its height so the list reserves exactly that much space on top.
  useEffect(() => {
    const wrap = wrapRef.current;
    const page = wrap?.closest(".nextflux-article-list-page");
    if (!wrap || !page) return undefined;
    const apply = () => page.style.setProperty("--list-header-h", `${wrap.offsetHeight}px`);
    apply();
    const observer = typeof ResizeObserver === "function" ? new ResizeObserver(apply) : null;
    // Border box: the status-bar inset arrives later as padding, which leaves
    // the content box unchanged.
    observer?.observe(wrap, { box: "border-box" });
    return () => observer?.disconnect();
  }, []);
  const actions = (
    <div className="article-list-actions ml-auto flex shrink-0">
      {feedId && <FeedTitleFilterButton feedId={feedId} feedTitle={title} />}
      <MarkAllReadButton />
      <MenuButton />
    </div>
  );
  const caption = transientStatus ? (
    <span
      className={hasError ? "article-list-caption ds-tnum truncate text-danger" : "article-list-caption ds-tnum truncate"}
      role="status"
      aria-live="polite"
    >
      {transientStatus}
    </span>
  ) : null;
  return (
    <div ref={wrapRef} className="article-list-header-wrap z-10 standalone:pt-safe" data-scoped={scoped ? "true" : "false"}>
      <div
        className="article-list-header ds-topbar w-full"
        data-collapsed={collapsed ? "true" : "false"}
      >
        <SidebarTrigger className="nextflux-toolbar-button" />
        {scoped ? (
          <div className="ds-topbar-title article-list-title grid text-left">
            <h1 className="truncate">{title}</h1>
            {caption}
          </div>
        ) : (
          <>
            <h1 className="sr-only">{title}</h1>
            <div ref={tabsRef} className="article-list-inline-tabs min-w-0 flex-1" data-fit="full">
              <ArticleListTabs activeCount={activeCount} />
            </div>
          </>
        )}
        {actions}
      </div>
      {scoped && (
        <div ref={tabsRef} className="article-list-scoped-tabs" data-fit="full">
          <ArticleListTabs activeCount={activeCount} />
        </div>
      )}
      {!scoped && caption && <div className="article-list-caption-row">{caption}</div>}
    </div>
  );
}
