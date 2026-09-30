import { memo, useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Chip, Link } from "@heroui/react";
import ArticleImage from "./ArticleImage.jsx";
import Iframe from "./Iframe.jsx";
import CodeBlock from "./CodeBlock.jsx";
import { createReadingRenderer } from "@/reading/renderer.js";
import { createHighlightQueue, startProgressiveReading } from "@/reading/client.js";
import { sameReadingSource } from "@/lib/articleReadingState.js";

function ReadingPortal({ item, highlights }) {
  useEffect(() => {
    const root = item.target.closest("[data-font-reading-root]");
    if (root) window.dispatchEvent(new CustomEvent("nextflux:reading-content", { detail: { root, blocks: [item.target] } }));
  }, [item]);
  let content;
  if (item.type === "image") content = <ArticleImage imgNode={{ attribs: item.attrs }} />;
  else if (item.type === "media") content = <Iframe sources={item.sources} domNode={{ name: item.kind, attribs: { "data-media-kind": item.kind, "data-media-url": item.url } }} />;
  else if (item.type === "code") content = <CodeBlock code={item.code} language={item.language} highlights={highlights} plainElement={item.plainElement} container={item.container} />;
  else content = <div className="flex justify-center"><Chip color="accent" variant="soft" className="cursor-pointer my-2"><a href={item.href} className="border-none!" rel="noopener noreferrer" target="_blank">{new URL(item.href).hostname}</a><Link.Icon /></Chip></div>;
  return createPortal(content, item.target, String(item.id));
}

function ProgressiveArticle({ articleId, html, baseUrl, title, shownOriginal, preview = false, paused = false }) {
  const rootRef = useRef(null);
  const domRef = useRef(null);
  const requestRef = useRef(null);
  const readyNotifiedRef = useRef(false);
  const modeRef = useRef({ preview, paused });
  modeRef.current = { preview, paused };
  const [portals, setPortals] = useState([]);
  const [state, setState] = useState({ complete: false, ready: false, error: null });
  const [attempt, setAttempt] = useState(0);
  useLayoutEffect(() => {
    readyNotifiedRef.current = false;
    if (rootRef.current) rootRef.current.dataset.readingReady = "false";
  }, [articleId, html, baseUrl, title, shownOriginal, attempt]);
  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!state.ready || root?.dataset.readingReady !== "true" || readyNotifiedRef.current) return;
    readyNotifiedRef.current = true;
    // Portals and the readiness state have now committed together. In
    // particular an image/media placeholder must exist before uncovering it.
    window.dispatchEvent(new CustomEvent("nextflux:article-ready", { detail: { root, articleId } }));
  }, [articleId, state.ready, portals]);
  useEffect(() => {
    const root = rootRef.current;
    const highlights = createHighlightQueue();
    const items = [];
    let mounted = true, lastPortalCount = 0, firstScreenReady = false;
    const dom = createReadingRenderer(root, baseUrl, (item) => items.push(<ReadingPortal key={item.id} item={item} highlights={highlights} />));
    domRef.current = dom;
    root.dataset.readingReady = "false";
    root.dataset.readingComplete = "false";
    setState({ complete: false, ready: false, error: null });
    setPortals([]);
    if (root.firstChild) root.style.minHeight = `${root.getBoundingClientRect().height}px`;
    root.replaceChildren();
    const notify = (blocks, extra = {}) => window.dispatchEvent(new CustomEvent("nextflux:reading-content", { detail: { root, blocks, ...extra } }));
    const titleElement = root.closest(".article-scroll-area")?.querySelector(".article-title");
    notify([root], { reset: true, title: titleElement });
    const viewport = root.closest(".nextflux-continuous-next-page") ?? root.closest(".article-scroll-area");
    const firstScreenHeight = Math.max(1, viewport?.clientHeight || window.innerHeight);
    const ready = () => {
      if (firstScreenReady || !mounted) return;
      firstScreenReady = true;
      root.dataset.readingReady = "true";
      setState((previous) => ({ ...previous, ready: true }));
    };
    const request = startProgressiveReading({ html, baseUrl, title,
      maxOperations: () => modeRef.current.preview || modeRef.current.paused ? 12 : 48,
      shouldPause: () => modeRef.current.paused || (modeRef.current.preview && firstScreenReady),
      apply: (operation) => dom.apply(operation),
      onBatch() {
        if (!mounted) return;
        if (lastPortalCount !== items.length) {
          lastPortalCount = items.length;
          setPortals([...items]);
        }
        notify(dom.takeDirtyBlocks());
        // One layout read per initial batch; no reads once the first screen is
        // ready. Previews keep the worker backpressured instead of mounting the
        // rest of a long article behind an offscreen, viewport-sized layer.
        if (!firstScreenReady) {
          const last = root.lastElementChild;
          const renderedHeight = last ? last.getBoundingClientRect().bottom - root.getBoundingClientRect().top : root.offsetHeight;
          if (renderedHeight >= firstScreenHeight + 48) ready();
        }
      },
      onDone() { if (mounted) { dom.finish(); root.style.minHeight = ""; ready(); setState({ complete: true, ready: true, error: null }); } },
      onError(error) { if (mounted) { root.style.minHeight = ""; ready(); setState({ complete: false, ready: true, error: error.message }); } },
    });
    requestRef.current = request;
    return () => {
      mounted = false;
      request.cancel();
      if (requestRef.current === request) requestRef.current = null;
      highlights.dispose();
      dom.dispose();
      if (domRef.current === dom) domRef.current = null;
      notify([], { removed: true });
    };
  }, [articleId, html, baseUrl, title, shownOriginal, attempt]);
  useEffect(() => { requestRef.current?.resume(); }, [preview, paused]);
  return <>
    <div ref={rootRef} className="article-body article-content" data-font-reading-root="" data-font-article-id={articleId} data-font-block="" data-reading-ready={String(state.ready)} data-reading-complete={String(state.complete)} onClick={(event) => {
      const anchor = event.target.closest?.("a[data-reading-local-anchor]");
      if (!anchor || !event.currentTarget.contains(anchor)) return;
      event.preventDefault(); event.stopPropagation();
      domRef.current?.navigateAnchor(anchor.getAttribute("data-reading-local-anchor"));
    }} />
    {portals}
    {state.error ? <div role="alert" className="text-sm text-danger"><p>{state.error}</p><button type="button" className="min-h-12 px-4 text-accent" onClick={() => setAttempt((value) => value + 1)}>重新准备正文</button></div> : !state.complete && !preview && !state.ready && <p role="status" className="text-sm text-muted">正在准备正文…</p>}
  </>;
}

export default memo(ProgressiveArticle, (previous, next) => sameReadingSource(previous, next) &&
  previous.preview === next.preview && previous.paused === next.paused);
