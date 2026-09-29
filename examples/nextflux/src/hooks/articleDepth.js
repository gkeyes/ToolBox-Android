const LIST_SELECTOR = ".nextflux-article-list-page";

const clamp01 = (value) => Math.max(0, Math.min(1, value));
const easeOutCubic = (value) => 1 - Math.pow(1 - value, 3);

function setCustomStyle(style, name, value) {
  if (!style) return;
  if (style.setProperty) style.setProperty(name, value);
  else style[name] = value;
}

function removeCustomStyle(style, name) {
  if (!style) return;
  if (style.removeProperty) style.removeProperty(name);
  else delete style[name];
}

function listFor(page) {
  return page?.parentElement?.querySelector?.(LIST_SELECTOR) ??
    page?.ownerDocument?.querySelector?.(LIST_SELECTOR) ??
    null;
}

export function articleDepthState(offset, width) {
  const safeWidth = Math.max(0, Number(width) || 0);
  const progress = safeWidth > 0 ? clamp01((Number(offset) || 0) / safeWidth) : 0;
  const backgroundProgress = easeOutCubic(progress);
  const reveal = clamp01(progress / 0.045);
  const fade = 1 - progress * 0.30;
  return {
    progress,
    listX: -0.23 * safeWidth * (1 - backgroundProgress),
    dim: 0.025 * (1 - backgroundProgress),
    edgeAlpha: 0.045 * reveal * fade,
    seamAlpha: 0.045 * reveal * 0.90,
  };
}

export function applyArticleDepth(page, offset, width) {
  if (!page) return;
  const state = articleDepthState(offset, width);
  const list = listFor(page);
  if (list?.style) {
    list.style.transform = `translate3d(${state.listX}px, 0, 0)`;
    list.style.willChange = state.progress > 0 && state.progress < 1 ? "transform" : "auto";
    setCustomStyle(list.style, "--nextflux-list-dim", state.dim.toFixed(4));
  }
  if (page.style) {
    if (state.progress > 0) {
      setCustomStyle(
        page.style,
        "--nextflux-article-depth-shadow",
        `-1px 0 0 rgb(0 0 0 / ${state.seamAlpha.toFixed(4)}), -10px 0 24px rgb(0 0 0 / ${state.edgeAlpha.toFixed(4)})`,
      );
    } else {
      setCustomStyle(page.style, "--nextflux-article-depth-shadow", "none");
    }
  }
  return state;
}

export function clearArticleDepth(page) {
  const list = listFor(page);
  if (list?.style) {
    list.style.transform = "";
    list.style.willChange = "";
    removeCustomStyle(list.style, "--nextflux-list-dim");
  }
  removeCustomStyle(page?.style, "--nextflux-article-depth-shadow");
}
