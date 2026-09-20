import { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { PhotoProvider } from "react-photo-view";
import "react-photo-view/dist/react-photo-view.css";
import "./style.css";
import "../../../src/components/ArticleView/ArticleView.css";
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
        <div className="code-block"><span id="code-target">Horizontal code</span></div>
        <input id="range-target" aria-label="Audio position" type="range" />
        <div id="horizontal-container"><div id="horizontal-target">Scrollable article table</div></div>
      </main>
    </>
  );
}

createRoot(document.getElementById("root")).render(<ReadingFixture />);
