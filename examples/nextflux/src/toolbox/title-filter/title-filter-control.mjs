import { FILTER_FIELD, inspectFeed, existingServerRules, hasFeedRules, keywordsFromText, saveTitleKeywords } from "./title-filter-rules.mjs";
import { createTitleFilterApi } from "./title-filter-api.mjs";

/** Dependency injection keeps the exact reader React/HeroUI/auth instances.
 * This ordinary ES module can also be imported by a source build; no extra library.
 */
export function createFeedTitleFilterControl({ React, createPortal, Button, loadModal, authState, feeds, serverUrl, backfill }) {
  const { createElement: h, Fragment, Suspense, lazy, useEffect, useRef, useState, useSyncExternalStore } = React;
  const Modal = lazy(async () => ({ default: await loadModal() }));
  const authSubscribe = notify => authState.listen(notify);
  const getAuth = () => authState.get();
  const feedSubscribe = notify => feeds.listen(notify);
  const getFeeds = () => feeds.get();
  const errorText = error => error?.code ? error.message : "操作未完成，请检查网络后重试。";
  // A blurred/transformed toolbar traps fixed descendants. Portal notices to the
  // page root and track the visible viewport instead of adjusting toolbar offsets.
  function Notice({ children }) {
    const viewportInset = () => {
      const viewport = window.visualViewport;
      return viewport ? Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop) : 0;
    };
    const [inset, setInset] = useState(viewportInset);
    useEffect(() => {
      const viewport = window.visualViewport;
      const update = () => setInset(viewportInset());
      update();
      viewport?.addEventListener("resize", update);
      viewport?.addEventListener("scroll", update);
      window.addEventListener("resize", update);
      return () => {
        viewport?.removeEventListener("resize", update);
        viewport?.removeEventListener("scroll", update);
        window.removeEventListener("resize", update);
      };
    }, []);
    return createPortal(h("span", {
      role: "status", "aria-live": "polite", "aria-atomic": true,
      className: "nf-title-filter-toast", style: { "--nf-toast-viewport-inset": `${inset}px` },
    }, children), document.body);
  }
  function Funnel() {
    return h("svg", { width: 20, height: 20, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 1.7, strokeLinecap: "round", strokeLinejoin: "round", "aria-hidden": true },
      h("path", { d: "M4 4h16l-6.5 7.5V19l-3 2v-9.5L4 4Z" }));
  }
  function Chevron() {
    return h("svg", { className: "nf-server-rules-chevron", width: 16, height: 16, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round", "aria-hidden": true },
      h("path", { d: "m6 9 6 6 6-6" }));
  }
  function MatchList({ matches }) {
    const [limit, setLimit] = useState(30);
    return h(Fragment, null,
      h("ul", { className: "nf-title-filter-matches", "aria-label": "命中的文章" },
        matches.slice(0, limit).map(match => h("li", { key: match.id },
          h("p", { className: "nf-title-filter-match-title" }, match.title || "无标题"),
          h("p", { className: "nf-title-filter-note" }, match.reason)))),
      matches.length > limit && h(Button, { type: "button", variant: "secondary", onPress: () => setLimit(limit + 30) },
        `再显示 ${Math.min(30, matches.length - limit)} 篇`));
  }
  function RuleEditor({ feedId, feedTitle, auth, isContextCurrent, onClose, onSaved }) {
    const live = useRef(false);
    const requestId = useRef(0);
    const saving = useRef(false);
    const backfillRequest = useRef(0);
    const applying = useRef(false);
    const reviewPanel = useRef(null);
    const scanButton = useRef(null);
    const restoreScanFocus = useRef(false);
    const [state, setState] = useState({ phase: "loading", feed: null, snapshot: null, text: "", error: "", code: "" });
    const [review, setReview] = useState(null);
    const reviewPhase = review?.phase;
    const stateRef = useRef(state); stateRef.current = state;
    const isCurrent = () => live.current && authState.get() === auth && isContextCurrent();
    const api = useRef(null);
    function getApi() {
      if (!api.current) api.current = createTitleFilterApi({ feedId, authState, auth, serverUrl, isCurrent });
      return api.current;
    }
    async function load() {
      if (saving.current) return;
      const ticket = ++requestId.current;
      setState(previous => ({ ...previous, phase: "loading", feed: null, snapshot: null, error: "", code: "" }));
      let loadedFeed = null;
      try {
        const feed = await getApi().read();
        if (!isCurrent() || ticket !== requestId.current) return;
        if (Number(feed?.id) === Number(feedId)) loadedFeed = feed;
        const snapshot = inspectFeed(feed, feedId);
        setState({ phase: "ready", feed, snapshot, text: snapshot.words.join("|"), error: "", code: "" });
      } catch (error) {
        if (isCurrent() && ticket === requestId.current) setState(previous => ({ ...previous, phase: "error", feed: loadedFeed, snapshot: null, error: errorText(error), code: error?.code || "ERROR" }));
      }
    }
    useEffect(() => {
      live.current = true;
      void load();
      return () => { live.current = false; requestId.current++; };
    }, []);
    useEffect(() => {
      if (reviewPhase) {
        reviewPanel.current?.closest(".nextflux-modal-body")?.scrollTo({ top: 0, behavior: "instant" });
        reviewPanel.current?.focus({ preventScroll: true });
      } else if (restoreScanFocus.current) {
        restoreScanFocus.current = false;
        scanButton.current?.focus();
      }
    }, [reviewPhase]);
    function close() { if (!saving.current && !applying.current) onClose(); }
    function returnToRules() {
      if (applying.current) return;
      backfillRequest.current++;
      restoreScanFocus.current = true;
      setReview(null);
    }
    async function scan(afterSave = false) {
      const ticket = ++backfillRequest.current;
      const check = () => isCurrent() && ticket === backfillRequest.current;
      setReview({ phase: "scanning", progress: null, afterSave });
      try {
        const preview = await backfill.preview({
          feedId, readFeed: () => getApi().read(), check,
          onProgress: progress => {
            if (check()) setReview({ phase: "scanning", progress, afterSave });
          },
        });
        if (check()) setReview({ phase: "preview", preview, markedCount: 0, afterSave });
      } catch (error) {
        if (check()) setReview({ phase: "scan-error", error: errorText(error), afterSave });
      }
    }
    async function apply() {
      if (!review?.preview || applying.current || !isCurrent()) return;
      applying.current = true;
      const ticket = ++backfillRequest.current;
      const check = () => isCurrent() && ticket === backfillRequest.current;
      const { preview, markedCount, afterSave } = review;
      setReview({ phase: "applying", preview, markedCount, afterSave });
      try {
        const result = await backfill.apply({ preview, readFeed: () => getApi().read(), check });
        if (check()) setReview({ phase: "done", afterSave, markedCount: markedCount + result.markedCount, skippedCount: Math.max(0, result.skippedCount - markedCount) });
      } catch (error) {
        if (check()) setReview({
          phase: error?.code === "BACKFILL_RULES_CHANGED" ? "rules-changed" : "apply-error",
          preview, afterSave, error: errorText(error?.cause ?? error), markedCount: markedCount + (error?.markedCount || 0),
        });
      } finally {
        applying.current = false;
      }
    }
    async function submit(event) {
      event.preventDefault();
      const current = stateRef.current;
      if (!isCurrent() || saving.current || !current.snapshot || current.phase === "loading" || current.code === "UNCONFIRMED" || current.code === "CONFLICT") return;
      saving.current = true;
      setState(previous => ({ ...previous, phase: "saving", error: "", code: "" }));
      try {
        const result = await saveTitleKeywords({ feedId, text: current.text, baselineLine: current.snapshot.line, api: getApi(), check: isCurrent });
        if (!isCurrent()) return;
        // Update only this projection field; do not mutate articles, counters or credentials.
        feeds.set(feeds.get().map(feed => Number(feed.id) === Number(feedId) ? { ...feed, [FILTER_FIELD]: result.feed[FILTER_FIELD] } : feed));
        if (!backfill) {
          onSaved(result.words.length);
          return;
        }
        setState({ phase: "ready", feed: result.feed, snapshot: inspectFeed(result.feed, feedId), text: result.words.join("|"), error: "", code: "" });
        void scan(true);
      } catch (error) {
        if (isCurrent()) setState(previous => ({ ...previous, phase: "ready", error: errorText(error), code: error?.code || "ERROR" }));
      } finally { saving.current = false; }
    }
    let count = 0, inputError = "";
    try { count = keywordsFromText(state.text).length; } catch (error) { inputError = error.message; }
    const pending = state.phase === "saving";
    const disabled = pending || state.phase === "loading" || !state.snapshot;
    const needsReload = state.code === "UNCONFIRMED" || state.code === "CONFLICT";
    const serverRules = existingServerRules(state.feed);
    const feedName = state.feed?.title || feedTitle || "当前订阅";
    const feedLabel = h("p", { className: "nf-title-filter-feed", title: feedName }, feedName);
    if (review) {
      const { phase, preview, progress, markedCount, skippedCount, error, afterSave } = review;
      const scanning = phase === "scanning";
      const writing = phase === "applying";
      const done = phase === "done";
      const needsScan = phase === "scan-error" || phase === "rules-changed";
      const matchCount = preview?.matches.length ?? 0;
      const confirmation = needsScan ? "重新扫描" : phase === "apply-error" ? "重试标记" : `将 ${matchCount} 篇标为已读`;
      const footer = done || (phase === "preview" && matchCount === 0)
        ? h(Button, { fullWidth: true, onPress: close }, "完成")
        : h(Fragment, null,
          h(Button, { variant: "tertiary", fullWidth: true, isDisabled: writing, onPress: returnToRules }, scanning ? "取消扫描" : "返回规则"),
          writing && h(Button, { fullWidth: true, isPending: true, isDisabled: true }, "正在标记…"),
          !writing && !scanning && (needsScan || matchCount > 0) && h(Button, { fullWidth: true, onPress: needsScan ? () => scan(afterSave) : apply }, confirmation));
      return h(Modal, { open: true, onOpenChange: next => { if (!next) close(); }, title: "现有未读扫描结果", footer, locked: writing },
        h("div", { ref: reviewPanel, tabIndex: -1, className: "nf-title-filter-form nf-title-filter-review" }, feedLabel,
          afterSave && h("p", { className: "nf-title-filter-note" }, "规则已保存。"),
          scanning && h("p", { role: "status", className: "nf-title-filter-note nf-title-filter-loading" },
            h("span", { className: "ds-spinner", "aria-hidden": true }),
            progress ? `正在扫描现有未读… 已扫描 ${progress.scanned} 篇` : "正在扫描现有未读…"),
          writing && h("p", { role: "status", className: "nf-title-filter-note nf-title-filter-loading" },
            h("span", { className: "ds-spinner", "aria-hidden": true }), "正在复核规则、未读和收藏状态并标记…"),
          error && h("p", { role: "alert", className: "nf-title-filter-error" },
            markedCount > 0 && `已确认 ${markedCount} 篇标为已读。`, error),
          done && h("p", { role: "status", className: "nf-title-filter-result" },
            `已将 ${markedCount} 篇标为已读。`, skippedCount > 0 && ` 跳过 ${skippedCount} 篇状态或标题已变化的文章。`),
          preview && !done && h(Fragment, null,
            h("p", { role: "status", className: "nf-title-filter-result" }, preview.hasRules === false
              ? "当前订阅没有可用于扫描的标题屏蔽规则。"
              : `已扫描 ${preview.scanned} 篇未读，命中 ${matchCount} 篇，已跳过 ${preview.skippedStarred} 篇命中的收藏。`),
            h("div", { className: "nf-title-filter-note" },
              h("p", null, "本次未参与扫描的规则"),
              h("ul", { className: "nf-title-filter-skipped-rules" },
                preview.skippedRules.map(rule => h("li", { key: rule }, rule)),
                h("li", null, "Miniflux 全局规则（未读取）"))),
            matchCount > 0 && h(Fragment, null,
              h("p", { className: "nf-title-filter-note" }, "标为已读会同步到其他客户端。较旧的条目可能按 Miniflux 的设置归档。"),
              h(MatchList, { matches: preview.matches })))));
    }
    const footer = h(Fragment, null,
      h(Button, { variant: "tertiary", fullWidth: true, isDisabled: pending, onPress: close }, "取消"),
      h(Button, { type: "submit", form: `nf-title-filter-${feedId}`, fullWidth: true, isPending: pending, isDisabled: disabled || Boolean(inputError) || needsReload }, pending ? "正在保存…" : "保存"));
    return h(Modal, { open: true, onOpenChange: next => { if (!next) close(); }, title: "标题过滤", footer, locked: pending },
      h("form", { id: `nf-title-filter-${feedId}`, className: "nf-title-filter-form", onSubmit: submit, "aria-busy": pending || state.phase === "loading" },
        feedLabel,
        state.phase === "loading" && h("p", { role: "status", className: "nf-title-filter-note nf-title-filter-loading" }, h("span", { className: "ds-spinner", "aria-hidden": true }), "正在读取服务器规则…"),
        state.feed && h("p", { className: "nf-title-filter-note", "data-testid": "nf-server-rule-status" },
          serverRules.length ? "已读取服务器原有规则，继续保留，无需重新设置。" : "已读取当前订阅的服务器规则。"),
        serverRules.length > 0 && h("details", { className: "nf-server-rules", "data-testid": "nf-server-rules" },
          h("summary", null, h("span", null, `查看服务器原有规则（${serverRules.length} 类）`), h(Chevron)),
          h("p", { className: "nf-title-filter-note" }, "以下为服务器原文，只读展示；不会自动转换成关键词或被本输入框替换。"),
          ...serverRules.map(rule => h("section", { key: rule.field, className: "nf-server-rule", "data-rule-field": rule.field },
            h("p", { className: "nf-server-rule-label" }, rule.label),
            h("pre", { className: "nf-server-rule-text" }, rule.raw)))),
        state.snapshot && h(Fragment, null,
          h("label", { htmlFor: `nf-title-words-${feedId}`, className: "nf-title-filter-label" }, "屏蔽关键词"),
          serverRules.length > 0 && h("p", { className: "nf-title-filter-note" }, "此框只回显本入口保存的标题关键词；留空不代表服务器没有过滤规则。"),
          h("textarea", { id: `nf-title-words-${feedId}`, name: "keywords", rows: 3, className: "nf-title-filter-input", placeholder: "广告|抽奖|推广", value: state.text, disabled, spellCheck: false, autoComplete: "off", autoCapitalize: "none", "aria-describedby": `nf-title-help-${feedId}`, "aria-invalid": Boolean(inputError), onChange: event => setState(previous => ({ ...previous, text: event.target.value, error: needsReload ? previous.error : "", code: needsReload ? previous.code : "" })) }),
          h("div", { className: "nf-title-filter-meta" },
            h("span", { className: "ds-tnum" }, `${count} 个关键词`),
            h(Button, { type: "button", variant: "ghost", size: "sm", isDisabled: disabled || !state.text, onPress: () => setState(previous => ({ ...previous, text: "" })) }, "清空")),
          h("p", { className: "nf-title-filter-note", id: `nf-title-help-${feedId}` }, "用 | 分隔；标题包含任一关键词即屏蔽，英文不区分大小写。"),
          h("p", { className: "nf-title-filter-note" }, backfill
            ? "保存后自动扫描此订阅的现有未读，你确认后才会将命中的文章标为已读。清空并保存只移除此处的关键词。"
            : "保存后用于后续抓取，旧条目不会自动删除。清空并保存只移除本入口的关键词，不删除原有规则。")),
        state.feed && h("p", { className: "nf-title-filter-note" }, "Miniflux 全局规则仍由服务器执行，本页不读取或修改。"),
        backfill && state.snapshot && state.text !== state.snapshot.words.join("|") &&
          h("p", { className: "nf-title-filter-note" }, "扫描使用服务器已保存的规则。要应用当前输入，请先保存。"),
        backfill && state.feed && h(Button, { ref: scanButton, type: "button", variant: "secondary", isDisabled: pending || needsReload, onPress: () => scan() }, "扫描现有未读"),
        (state.error || inputError) && h("p", { role: "alert", className: "nf-title-filter-error" }, inputError || state.error),
        !pending && state.phase !== "loading" && (state.phase === "error" || needsReload) && h(Button, { type: "button", variant: "secondary", fullWidth: true, onPress: load }, needsReload ? "重新载入服务器规则" : "重试读取")));
  }
  return function FeedTitleFilterButton({ feedId, feedTitle, contextKey = "feed", isContextCurrent = () => true }) {
    const auth = useSyncExternalStore(authSubscribe, getAuth, getAuth);
    const storedFeeds = useSyncExternalStore(feedSubscribe, getFeeds, getFeeds);
    const [opened, setOpened] = useState(false);
    const [notice, setNotice] = useState("");
    const openedScope = useRef(null);
    useEffect(() => { setOpened(false); setNotice(""); }, [feedId, auth, contextKey]);
    useEffect(() => {
      if (!notice) return;
      const timer = setTimeout(() => setNotice(""), 3500);
      return () => clearTimeout(timer);
    }, [notice]);
    const id = Number(feedId);
    if (!Number.isSafeInteger(id) || id <= 0) return null;
    const storedFeed = storedFeeds.find(feed => Number(feed.id) === id);
    const displayTitle = feedTitle || storedFeed?.title || "当前订阅";
    // A blank managed-keyword input does not mean there are no server rules.
    const active = hasFeedRules(storedFeed);
    return h(Fragment, null,
      h(Button, { variant: "ghost", size: "sm", isIconOnly: true, className: "nextflux-toolbar-button nf-title-filter-trigger", "aria-label": "标题过滤", title: "标题过滤", "aria-haspopup": "dialog", "data-filter-active": active ? "true" : "false", "data-feed-id": String(id), onPress: () => { if (!isContextCurrent()) return; openedScope.current = { auth: authState.get(), feedId: id, contextKey }; setNotice(""); setOpened(true); } }, h(Funnel)),
      notice && h(Notice, null, notice),
      opened && openedScope.current?.auth === auth && openedScope.current?.feedId === id && openedScope.current?.contextKey === contextKey && h(Suspense, { fallback: h(Notice, null, "正在打开标题过滤…") },
        h(RuleEditor, { key: `${id}:${contextKey}`, feedId: id, feedTitle: displayTitle, auth, isContextCurrent, onClose: () => setOpened(false), onSaved: count => { setOpened(false); setNotice(count ? `已保存 ${count} 个标题关键词` : "已移除标题关键词，其他规则保留"); } })));
  };
}
