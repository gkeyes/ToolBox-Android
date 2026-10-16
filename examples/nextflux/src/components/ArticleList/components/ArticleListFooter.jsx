import { filter } from "@/stores/articlesStore";
import { CircleDot, Star, Text } from "lucide-react";
import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";
import { memo, useEffect, useId, useLayoutEffect, useRef, useState } from "react";

const TABS = [
  { id: "starred", key: "articleList.starred", icon: <Star className="size-3 fill-current" /> },
  { id: "unread", key: "articleList.unread", icon: <CircleDot className="size-3 p-px fill-current" /> },
  { id: "all", key: "articleList.all", icon: <Text strokeWidth={4} className="size-3" /> },
];

// A CSS time ("460ms", ".46s") in milliseconds.
const milliseconds = (value) => {
  const number = Number.parseFloat(value);
  if (!Number.isFinite(number)) return 0;
  return /ms\s*$/.test(value) ? number : number * 1000;
};

// The filter capsule. It draws the same markup and classes as HeroUI's Tabs
// (so tabs.css, glass-buttons.css and ios27.css style it unchanged) without
// React Aria's collection and shared-element machinery, which took about
// 100 ms of main thread per tap on a slowed phone. The pill slides from the
// old tab the way SharedElementTransition did, measured while the page is
// still laid out from the previous frame.
//
// The tab moves at once; the list follows after the next paint, so the
// rebuild of the list no longer holds the pill still.
// Each tab shows only its name: the counts under the names were dropped at
// the reader's request (1.17.0).
// Memoised: it reads the filter itself, so the header's re-render on each
// switch does not need to redraw it.
export default memo(ArticleListFooter);
function ArticleListFooter() {
  const { t } = useTranslation();
  const $filter = useStore(filter);
  const [selected, setSelected] = useState($filter);
  const latest = useRef($filter);
  const pending = useRef(0);
  const listRef = useRef(null);
  const slide = useRef(null);
  const id = useId();
  useEffect(() => { latest.current = $filter; setSelected($filter); }, [$filter]);

  const select = (value) => {
    if (value === latest.current && value === selected) return;
    const list = listRef.current;
    const from = list?.querySelector(".tabs__indicator");
    const to = list?.querySelector(`[data-key="${value}"]`);
    if (from && to && from.parentElement !== to) {
      const tab = from.parentElement;
      const style = getComputedStyle(list);
      slide.current = {
        // Centre to centre: the pill scales about its centre.
        dx: tab.offsetLeft + tab.offsetWidth / 2 - (to.offsetLeft + to.offsetWidth / 2),
        dy: tab.offsetTop + tab.offsetHeight / 2 - (to.offsetTop + to.offsetHeight / 2),
        sx: tab.offsetWidth / to.offsetWidth,
        sy: tab.offsetHeight / to.offsetHeight,
        width: tab.offsetWidth,
        height: tab.offsetHeight,
        duration: milliseconds(style.getPropertyValue("--ds-dur-spatial")),
        easing: style.getPropertyValue("--ds-ease-spatial").trim() || "ease",
      };
    }
    setSelected(value);
    latest.current = value;
    if (pending.current) return;
    // After the next paint: a frame callback, then a task. A second tap
    // before then only changes which tab the list follows.
    pending.current = requestAnimationFrame(() => {
      setTimeout(() => {
        pending.current = 0;
        if (filter.get() !== latest.current) filter.set(latest.current);
      }, 0);
    });
  };

  useLayoutEffect(() => {
    const move = slide.current;
    slide.current = null;
    const pill = listRef.current?.querySelector(".tabs__indicator");
    if (!move || !pill || !move.duration || typeof pill.animate !== "function") return undefined;
    // Start where the old pill was and glide to the new tab. Move and scale
    // only, so the compositor runs the slide even while the list below is
    // rebuilt on the main thread. Labels of very different widths (English)
    // would squash the rounded ends, so those animate the width instead.
    const scaled = Math.abs(move.sx - 1) <= 0.15 && Math.abs(move.sy - 1) <= 0.15;
    const start = scaled
      ? { translate: `${move.dx}px ${move.dy}px`, scale: `${move.sx} ${move.sy}` }
      : { translate: `${move.dx + (pill.offsetWidth - move.width) / 2}px ${move.dy + (pill.offsetHeight - move.height) / 2}px`, width: `${move.width}px`, height: `${move.height}px` };
    const end = scaled
      ? { translate: "0px 0px", scale: "1 1" }
      : { translate: "0px 0px", width: `${pill.offsetWidth}px`, height: `${pill.offsetHeight}px` };
    const animation = pill.animate([start, end], { duration: move.duration, easing: move.easing });
    return () => animation.cancel();
  }, [selected]);

  const keyDown = (event) => {
    const index = TABS.findIndex((tab) => tab.id === selected);
    const next = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: TABS.length - 1 }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    const tab = TABS[(next + TABS.length) % TABS.length];
    select(tab.id);
    const element = listRef.current?.querySelector(`[data-key="${tab.id}"]`);
    element?.focus();
    // Keep the chosen tab visible when the row scrolls (320 px at large text).
    element?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
  };
  // HeroUI draws its focus ring from data-focus-visible; a tap leaves none
  // (compact-controls.css hides rings after touch anyway).
  const focus = (event) => {
    if (event.currentTarget.matches(":focus-visible")) event.currentTarget.dataset.focusVisible = "true";
  };
  const blur = (event) => { delete event.currentTarget.dataset.focusVisible; };

  return (
    <div className="article-list-tabs px-3">
      <div data-slot="tabs" className="tabs" data-orientation="horizontal">
        <div className="tabs__list-container" data-slot="tabs-list-container">
          <div
            ref={listRef}
            data-slot="tabs-list"
            className="tabs__list w-fit *:w-fit"
            role="tablist"
            aria-label="filter"
            aria-orientation="horizontal"
            data-orientation="horizontal"
            onKeyDown={keyDown}
          >
            {TABS.map((tab) => {
              const on = tab.id === selected;
              return (
                <div
                  key={tab.id}
                  data-slot="tabs-tab"
                  className="tabs__tab"
                  data-key={tab.id}
                  id={`${id}-tab-${tab.id}`}
                  role="tab"
                  tabIndex={on ? 0 : -1}
                  aria-selected={on ? "true" : "false"}
                  data-selected={on ? "true" : undefined}
                  onClick={() => select(tab.id)}
                  onFocus={focus}
                  onBlur={blur}
                >
                  {on && <div data-slot="tabs-indicator" className="tabs__indicator bg-accent" />}
                  <div className="flex items-center space-x-1.5">
                    {tab.icon}
                    <span className="article-tab-label"><span>{t(tab.key)}</span></span>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
