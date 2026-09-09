import { memo, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Chip, Link } from "@heroui/react";
import ArticleImage from "./ArticleImage.jsx";
import Iframe from "./Iframe.jsx";
import CodeBlock from "./CodeBlock.jsx";
import { createReadingDom } from "@/lib/reading-dom.js";
import { createHighlightQueue, startProgressiveReading } from "@/lib/reading-client.js";
import { sameReadingSource } from "@/lib/articleReadingState.js";

function ReadingPortal({ item, highlights }) {
  useEffect(() => {
    const root = item.target.closest("[data-font-reading-root]");
    if (root) window.dispatchEvent(new CustomEvent("nextflux:reading-content", { detail: { root, blocks: [item.target] } }));
  }, [item]);
  let content;
  if (item.type === "image") content = <ArticleImage imgNode={{ attribs: item.attrs }} />;
  else if (item.type === "media") content = <Iframe domNode={{ name: item.kind, attribs: { "data-media-kind": item.kind, "data-media-url": item.url } }} />;
  else if (item.type === "code") content = <CodeBlock code={item.code} language={item.language} highlights={highlights} plainElement={item.plainElement} container={item.container} />;
  else content = <div className="flex justify-center"><Chip color="accent" variant="soft" className="cursor-pointer my-2"><a href={item.href} className="border-none!" rel="noopener noreferrer" target="_blank">{new URL(item.href).hostname}</a><Link.Icon /></Chip></div>;
  return createPortal(content, item.target, String(item.id));
}

function ProgressiveArticle({ articleId, html, baseUrl, shownOriginal }) {
  const rootRef = useRef(null);
  const domRef = useRef(null);
  const [portals, setPortals] = useState([]);
  const [state, setState] = useState({ complete: false, error: null });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const root = rootRef.current;
    const highlights = createHighlightQueue();
    const items = [];
    let mounted = true, lastPortalCount = 0;
    const dom = createReadingDom(root, baseUrl, (item) => items.push(<ReadingPortal key={item.id} item={item} highlights={highlights} />));
    domRef.current = dom;
    setState({ complete: false, error: null });
    setPortals([]);
    // Keep the scroll range while original/feed content replaces this body's
    // nodes. No delayed scrollTo can overwrite the user's own new scrolling.
    if (root.firstChild) root.style.minHeight = `${root.getBoundingClientRect().height}px`;
    root.replaceChildren();
    const notify = (blocks, extra = {}) => window.dispatchEvent(new CustomEvent("nextflux:reading-content", { detail: { root, blocks, ...extra } }));
    const title = root.closest(".article-scroll-area")?.querySelector(".article-title");
    notify([root], { reset: true, title });
    const request = startProgressiveReading({ html, baseUrl,
      apply: (operation) => dom.apply(operation),
      onBatch() {
        if (!mounted) return;
        if (lastPortalCount !== items.length) {
          lastPortalCount = items.length;
          setPortals([...items]);
        }
        notify(dom.takeDirtyBlocks());
      },
      onDone() { if (mounted) { dom.finish(); root.style.minHeight = ""; setState({ complete: true, error: null }); } },
      onError(error) { if (mounted) { root.style.minHeight = ""; setState({ complete: false, error: error.message }); } },
    });
    return () => {
      mounted = false;
      request.cancel();
      highlights.dispose();
      dom.dispose();
      if (domRef.current === dom) domRef.current = null;
      notify([], { removed: true });
    };
  }, [articleId, html, baseUrl, shownOriginal, attempt]);
  return <>
    <div ref={rootRef} className="article-body" data-font-reading-root="" data-font-article-id={articleId} data-font-block="" data-reading-complete={String(state.complete)} onClick={(event) => {
      const anchor = event.target.closest?.("a[data-reading-local-anchor]");
      if (!anchor || !event.currentTarget.contains(anchor)) return;
      event.preventDefault(); event.stopPropagation();
      domRef.current?.navigateAnchor(anchor.getAttribute("data-reading-local-anchor"));
    }} />
    {portals}
    {state.error ? <div role="alert" className="text-sm text-danger"><p>{state.error}</p><button type="button" className="min-h-12 px-4 text-accent" onClick={() => setAttempt((value) => value + 1)}>重新准备正文</button></div> : !state.complete && <p role="status" className="text-sm text-muted">正在准备正文…</p>}
  </>;
}

export default memo(ProgressiveArticle, sameReadingSource);
