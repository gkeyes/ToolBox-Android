import { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { PhotoProvider } from "react-photo-view";
import "react-photo-view/dist/react-photo-view.css";
import "./style.css";
import "../../../src/components/ArticleView/ArticleView.css";
import "../../../src/reading/reading.css";
import "../../../src/reading/typography.css";
import { createReadingParser } from "@/reading/parser.js";
import { createReadingRenderer } from "@/reading/renderer.js";
import ArticleImage from "@/components/ArticleView/components/ArticleImage.jsx";
import { useSwipeGesture } from "@/hooks/useSwipeGesture.js";
import { clearMediaCache } from "@/toolbox/media.js";
import { imageDimensions } from "@/toolbox/imageDimensions.js";
import { imageGalleryActive, isModalOpen } from "./stores.js";
import { pendingRequests, requestCount, settleImage } from "./media-transport.js";

const initialSource = "https://reader.example.invalid/proxy/image?signature=a";

function ReadingFixture() {
  const [attribs, setAttribs] = useState({ src: initialSource, alt: "Fixture article image" });
  const [mounted, setMounted] = useState(true);
  const [generation, setGeneration] = useState(0);
  const [returns, setReturns] = useState(0);
  const [accountEpoch, setAccountEpoch] = useState(0);

  useSwipeGesture({ onSwipeRight: () => setReturns((value) => value + 1) });

  useEffect(() => {
    window.readingFixture = {
      setImage: (next) => setAttribs({ alt: "Fixture article image", ...next }),
      unmountImage: () => setMounted(false),
      mountImage: () => { setGeneration((value) => value + 1); setMounted(true); },
      clearSessionMedia: () => {
        // This is the production media cleanup invoked by authStore.logout().
        // Account persistence and login are intentionally outside this fixture.
        clearMediaCache();
        setAccountEpoch(imageDimensions.epoch);
      },
      pendingRequests,
      requestCount,
      settleImage,
      dimensions: (source) => imageDimensions.get(source),
      setGalleryActive: (value) => imageGalleryActive.set(value),
      setModalOpen: (value) => isModalOpen.set(value),
      renderArticle: (html, baseUrl) => {
        const root = document.getElementById("live-render-surface");
        root.replaceChildren();
        const renderer = createReadingRenderer(root, baseUrl, () => {});
        const parser = createReadingParser(html, baseUrl);
        for (;;) {
          const batch = parser.next();
          for (const operation of batch.operations) renderer.apply(operation);
          if (batch.done) break;
        }
        renderer.finish();
        return {
          roles: [...root.querySelectorAll("[data-semantic-role]")].map((node) => {
            const style = getComputedStyle(node);
            return {
              tag: node.tagName.toLowerCase(),
              role: node.getAttribute("data-semantic-role"),
              textLength: String(node.textContent || "").replace(/\\s+/g, " ").trim().length,
              display: style.display,
              marginTop: style.marginTop,
              marginBottom: style.marginBottom,
              paddingLeft: style.paddingLeft,
              fontWeight: style.fontWeight,
              borderLeftWidth: style.borderLeftWidth,
            };
          }),
        };
      },
    };
    return () => { delete window.readingFixture; };
  }, []);

  return (
    <>
      <output data-testid="returns">{returns}</output>
      <main id="reading-surface" data-account-epoch={accountEpoch}>
        <p id="selectable-text">Select part of this article without navigating away.</p>
        <PhotoProvider onVisibleChange={(visible) => imageGalleryActive.set(visible)}>
          <section className="article-content prose max-w-none" data-testid="image-fixture" data-mounted={String(mounted)}>
            {mounted && <ArticleImage key={generation} imgNode={{ attribs }} />}
            <p data-testid="after-image">The article continues here.</p>
          </section>
        </PhotoProvider>
        <section id="live-render-surface" className="article-content prose max-w-none" data-font-reading-root="" />
        <div className="code-block"><span id="code-target">Horizontal code</span></div>
        <input id="range-target" aria-label="Audio position" type="range" />
        <div id="horizontal-container"><div id="horizontal-target">Scrollable article table</div></div>
      </main>
    </>
  );
}

createRoot(document.getElementById("root")).render(<ReadingFixture />);
