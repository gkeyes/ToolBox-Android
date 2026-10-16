import { filter } from "@/stores/articlesStore";
import { CircleDot, Star, Text } from "lucide-react";
import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";
import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";

const TABS = [
  { id: "starred", key: "articleList.starred", icon: <Star className="size-3 fill-current" /> },
  { id: "unread", key: "articleList.unread", icon: <CircleDot className="size-3 p-px fill-current" /> },
  { id: "all", key: "articleList.all", icon: <Text strokeWidth={4} className="size-3" /> },
];

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
export default function ArticleListFooter() {
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
      slide.current = { dx: tab.offsetLeft - to.offsetLeft, dy: tab.offsetTop - to.offsetTop, width: tab.offsetWidth, height: tab.offsetHeight };
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
    if (!move || !pill) return undefined;
    // Start where the old pill was, then let tabs.css transition translate,
    // width and height to the new tab.
    pill.style.translate = `${move.dx}px ${move.dy}px`;
    pill.style.width = `${move.width}px`;
    pill.style.height = `${move.height}px`;
    for (const animation of pill.getAnimations()) animation.cancel();
    getComputedStyle(pill).translate;
    const frame = requestAnimationFrame(() => {
      pill.style.translate = "";
      pill.style.width = "";
      pill.style.height = "";
    });
    return () => cancelAnimationFrame(frame);
  }, [selected]);

  const keyDown = (event) => {
    const index = TABS.findIndex((tab) => tab.id === selected);
    const next = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: TABS.length - 1 }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    const tab = TABS[(next + TABS.length) % TABS.length];
    select(tab.id);
    listRef.current?.querySelector(`[data-key="${tab.id}"]`)?.focus();
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
