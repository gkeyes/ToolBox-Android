import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { createRoot } from "react-dom/client";
import { PhotoProvider } from "react-photo-view";
import { createReadingParser } from "@/reading/parser.js";
import { createReadingRenderer } from "@/reading/renderer.js";
import { extractWithDefuddle } from "@/reading/extractors/defuddle.mjs";
import { fetchWebDocument } from "@/reading/extractors/source.mjs";
import { loadBestFullText } from "@/reading/fulltext.mjs";
import { imageGalleryActive } from "./stores.js";
import { installNativeMediaFixture, pendingRequests, requestCount, respond, resumeReads, snapshot } from "./media-compat-network.js";
import "react-photo-view/dist/react-photo-view.css";
import "./style.css";
import "../../../src/components/ArticleView/ArticleView.css";
import "../../../src/reading/reading.css";

// Set the native boundary before media.js constructs its production transport.
installNativeMediaFixture();
const { default: ArticleImage } = await import("@/components/ArticleView/components/ArticleImage.jsx");
const { clearMediaCache } = await import("@/toolbox/media.js");
const baseUrl = "https://media.example.invalid/articles/story";

function ArticleDocument({ html, baseUrl }) {
  const root = useRef(null);
  const [images, setImages] = useState([]);
  useLayoutEffect(() => {
    const portals = [];
    const renderer = createReadingRenderer(root.current, baseUrl, (operation) => {
      if (operation.type === "image") portals.push(operation);
    });
    const parser = createReadingParser(html, baseUrl);
    for (;;) {
      const batch = parser.next();
      for (const operation of batch.operations) renderer.apply(operation);
      if (batch.done) break;
    }
    renderer.finish();
    setImages(portals);
    return () => renderer.dispose();
  }, [html, baseUrl]);
  return <>
    <section ref={root} data-testid="article-body" data-font-reading-root="" />
    {images.map(({ id, target, attrs }) => createPortal(<ArticleImage imgNode={{ attribs: attrs }} />, target, String(id)))}
  </>;
}

function MediaCompatibilityFixture() {
  const [article, setArticle] = useState({ html: "", baseUrl, generation: 0 });
  const [mounted, setMounted] = useState(true);
  useEffect(() => {
    window.mediaCompatFixture = {
      renderArticle(html, resourceBaseUrl = baseUrl) {
        setArticle((previous) => ({ html, baseUrl: resourceBaseUrl, generation: previous.generation + 1 }));
        setMounted(true);
      },
      unmountArticle: () => setMounted(false),
      mountArticle: () => {
        setArticle((previous) => ({ ...previous, generation: previous.generation + 1 }));
        setMounted(true);
      },
      clearSessionMedia: clearMediaCache,
      extractWithDefuddle,
      loadBestFullText,
      fetchWebDocument({ html, url, finalUrl = url }) {
        return fetchWebDocument(url, () => ({
          async request() {
            return { status: 200, headers: { "x-toolbox-final-url": finalUrl }, body: html };
          },
        }));
      },
      dispose() { setMounted(false); clearMediaCache(); },
      pendingRequests, requestCount, respond, resumeReads, snapshot,
    };
    return () => { delete window.mediaCompatFixture; };
  }, []);
  return <main data-testid="media-compat-surface" data-mounted={String(mounted)} style={{ padding: 20 }}>
    <PhotoProvider onVisibleChange={(visible) => imageGalleryActive.set(visible)}>
      <div className="article-content prose max-w-none">
        {mounted && <ArticleDocument key={article.generation} html={article.html} baseUrl={article.baseUrl} />}
      </div>
    </PhotoProvider>
  </main>;
}

createRoot(document.getElementById("root")).render(<MediaCompatibilityFixture />);
