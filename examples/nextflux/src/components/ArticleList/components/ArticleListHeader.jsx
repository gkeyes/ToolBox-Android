import { useParams } from "react-router";
import { useStore } from "@nanostores/react";
import { SidebarTrigger } from "@/components/ui/sidebar.jsx";
import { filter } from "@/stores/articlesStore.js";
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
// Beside the buttons there are two more steps: only the selected tab keeps its
// count, then no tab does.
const INLINE_DENSITIES = [...TAB_DENSITIES, "minimal", "bare"];

// Layout sizes only: offsetWidth/offsetHeight ignore the grow of a pressed tab
// or pill (scrollWidth would count it and make a full capsule look too wide).
function fits(list) {
  const tabs = [...list.querySelectorAll(".tabs__tab")];
  const style = getComputedStyle(list);
  const gap = parseFloat(style.columnGap) || 0;
  const padding = (parseFloat(style.paddingLeft) || 0) + (parseFloat(style.paddingRight) || 0);
  const width = tabs.reduce((sum, tab) => sum + tab.offsetWidth, 0) + gap * Math.max(0, tabs.length - 1) + padding;
  const tall = tabs.some((tab) => {
    const label = tab.querySelector(".article-tab-label") || tab.firstElementChild;
    return label && label.offsetHeight > tab.clientHeight + 1;
  });
  return width <= list.clientWidth + 1 && !tall;
}

/**
 * Fits the filter capsule to the width it is given: drops the tab icons, then
 * tightens the padding, then the text; in the top bar it then keeps only the
 * selected tab's count, then no count. The top bar stays one row whichever
 * tab is selected, so past the smallest density the capsule scrolls sideways
 * instead of running under the buttons on narrow screens, large display
 * sizes or large system fonts.
 */
function useTabsFit(ref, deps) {
  useLayoutEffect(() => {
    const box = ref.current;
    if (!box) return undefined;
    const densities = box.classList.contains("article-list-inline-tabs") ? INLINE_DENSITIES : TAB_DENSITIES;
    const fit = () => {
      const list = box.querySelector(".tabs__list");
      if (!list) return;
      // The list is capped at the width it can use (padding excluded), so it
      // overflows itself when its content does not fit.
      box.dataset.scroll = "false";
      for (const density of densities) {
        box.dataset.fit = density;
        if (fits(list)) return;
      }
      // Nothing fits (very large text or long translations): scroll the
      // capsule sideways rather than hiding a tab, with the selected tab in view.
      box.dataset.scroll = "true";
      const selected = list.querySelector('.tabs__tab[aria-selected="true"]');
      if (selected) {
        const left = selected.offsetLeft;
        const right = left + selected.offsetWidth;
        if (left < list.scrollLeft) list.scrollLeft = Math.max(0, left - 8);
        else if (right > list.scrollLeft + list.clientWidth) list.scrollLeft = right - list.clientWidth + 8;
      }
    };
    fit();
    // A tab switch changes the counts while a finger is still down; measure
    // again once the press has ended.
    let frame = 0;
    const refit = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(fit); };
    const ends = ["touchend", "touchcancel", "pointerup", "pointercancel", "click"];
    for (const type of ends) box.addEventListener(type, refit, true);
    // Watch the list and its tabs too: late fonts, a text-size change or a
    // longer count resize them without changing the space it is given (once
    // the list is capped, only the tabs themselves change size).
    const observer = typeof ResizeObserver === "function" ? new ResizeObserver(fit) : null;
    observer?.observe(box);
    const list = box.querySelector(".tabs__list");
    if (list) observer?.observe(list);
    for (const tab of list?.querySelectorAll(".tabs__tab") || []) observer?.observe(tab);
    return () => {
      observer?.disconnect();
      cancelAnimationFrame(frame);
      for (const type of ends) box.removeEventListener(type, refit, true);
    };
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

  return {
    feedId,
    categoryId,
    title: getTitleText(),
    status: $isSyncing ? $syncProgress || t("common.syncing") : $syncError?.message || getFilteredCount(),
    // Only transient states need a line of their own (the tabs show names only).
    transientStatus: $isSyncing ? $syncProgress || t("common.syncing") : $syncError?.message || "",
    hasError: !$isSyncing && Boolean($syncError?.message),
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
  const { feedId, categoryId, title, transientStatus, hasError } = useArticleListTitle();
  const scoped = Boolean(feedId || categoryId);
  const wrapRef = useRef(null);
  const tabsRef = useRef(null);
  const { i18n } = useTranslation();
  // Not keyed on the filter: the tabs show names only, so a switch changes no
  // width, and the refit after each press keeps the selected tab in view.
  useTabsFit(tabsRef, [scoped, i18n.language]);
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
              <ArticleListTabs />
            </div>
          </>
        )}
        {actions}
      </div>
      {scoped && (
        <div ref={tabsRef} className="article-list-scoped-tabs" data-fit="full">
          <ArticleListTabs />
        </div>
      )}
      {!scoped && caption && <div className="article-list-caption-row">{caption}</div>}
    </div>
  );
}
