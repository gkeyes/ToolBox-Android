import { FILTER_FIELD, inspectFeed, existingServerRules, hasFeedRules, keywordsFromText, saveTitleKeywords } from "./title-filter-rules.mjs";
import { createTitleFilterApi } from "./title-filter-api.mjs";

/** Dependency injection keeps the exact reader React/HeroUI/auth instances.
 * This ordinary ES module can also be imported by a source build; no extra library.
 */
export function createFeedTitleFilterControl({ React, createPortal, Button, loadModal, authState, feeds, serverUrl }) {
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
  function RuleEditor({ feedId, feedTitle, auth, isContextCurrent, onClose, onSaved }) {
    const live = useRef(false);
    const requestId = useRef(0);
    const saving = useRef(false);
    const [state, setState] = useState({ phase: "loading", feed: null, snapshot: null, text: "", error: "", code: "" });
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
    function close() { if (!saving.current) onClose(); }
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
        onSaved(result.words.length);
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
    const footer = h(Fragment, null,
      h(Button, { variant: "tertiary", fullWidth: true, isDisabled: pending, onPress: close }, "取消"),
      h(Button, { type: "submit", form: `nf-title-filter-${feedId}`, fullWidth: true, isPending: pending, isDisabled: disabled || Boolean(inputError) || needsReload }, pending ? "正在保存…" : "保存"));
    return h(Modal, { open: true, onOpenChange: next => { if (!next) close(); }, title: "标题过滤", footer },
      h("form", { id: `nf-title-filter-${feedId}`, className: "nf-title-filter-form", onSubmit: submit, "aria-busy": pending || state.phase === "loading" },
        h("p", { className: "nf-title-filter-feed", title: state.feed?.title || feedTitle }, state.feed?.title || feedTitle || "当前订阅"),
        state.phase === "loading" && h("p", { role: "status", className: "nf-title-filter-note" }, "正在读取服务器规则…"),
        state.feed && h("p", { className: "nf-title-filter-note", "data-testid": "nf-server-rule-status" },
          serverRules.length ? "已读取服务器原有规则，继续保留，无需重新设置。" : "已读取当前订阅的服务器规则。"),
        serverRules.length > 0 && h("details", { className: "nf-server-rules", "data-testid": "nf-server-rules" },
          h("summary", null, `查看服务器原有规则（${serverRules.length} 类）`),
          h("p", { className: "nf-title-filter-note" }, "以下为服务器原文，只读展示；不会自动转换成关键词或被本输入框替换。"),
          ...serverRules.map(rule => h("section", { key: rule.field, className: "nf-server-rule", "data-rule-field": rule.field },
            h("p", { className: "nf-server-rule-label" }, rule.label),
            h("pre", { className: "nf-server-rule-text" }, rule.raw)))),
        state.snapshot && h(Fragment, null,
          h("label", { htmlFor: `nf-title-words-${feedId}`, className: "nf-title-filter-label" }, "屏蔽关键词"),
          serverRules.length > 0 && h("p", { className: "nf-title-filter-note" }, "此框只回显本入口保存的标题关键词；留空不代表服务器没有过滤规则。"),
          h("textarea", { id: `nf-title-words-${feedId}`, name: "keywords", rows: 3, className: "nf-title-filter-input", placeholder: "广告|抽奖|推广", value: state.text, disabled, spellCheck: false, autoComplete: "off", autoCapitalize: "none", "aria-describedby": `nf-title-help-${feedId}`, "aria-invalid": Boolean(inputError), onChange: event => setState(previous => ({ ...previous, text: event.target.value, error: needsReload ? previous.error : "", code: needsReload ? previous.code : "" })) }),
          h("div", { className: "nf-title-filter-meta" },
            h("span", null, `${count} 个关键词`),
            h(Button, { type: "button", variant: "ghost", size: "sm", isDisabled: disabled || !state.text, onPress: () => setState(previous => ({ ...previous, text: "" })) }, "清空")),
          h("p", { className: "nf-title-filter-note", id: `nf-title-help-${feedId}` }, "用 | 分隔；标题包含任一关键词即屏蔽，英文不区分大小写。"),
          h("p", { className: "nf-title-filter-note" }, "保存后用于后续抓取，旧条目不会自动删除。清空并保存只移除本入口的关键词，不删除原有规则。")),
        state.feed && h("p", { className: "nf-title-filter-note" }, "Miniflux 全局规则仍由服务器执行，本页不读取或修改。"),
        (state.error || inputError) && h("p", { role: "alert", className: "nf-title-filter-error" }, inputError || state.error),
        !pending && state.phase !== "loading" && (state.phase === "error" || needsReload) && h(Button, { type: "button", variant: "tertiary", onPress: load }, needsReload ? "重新载入服务器规则" : "重试读取")));
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
