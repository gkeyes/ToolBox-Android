import { useId } from "react";
import { useStore } from "@nanostores/react";
import { settingsState, sidebarIconStyle } from "@/stores/settingsStore.js";
import { board } from "@/toolbox/board/boardStore.js";
import { BoardSidebarIcon } from "@/components/Board/BoardPage.jsx";
import { AllIcon, StarredIcon, UnreadIcon } from "@/components/ui/icons.jsx";
import "@/design/sidebar-icons.css";

const Svg = ({ children }) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {children}
  </svg>
);

const STAR = "M12 3.8l2.4 4.9 5.4.8-3.9 3.8.9 5.4L12 16.1l-4.8 2.6.9-5.4-3.9-3.8 5.4-.8z";

function SunriseBoard() {
  const id = `nf-sb-a${useId().replace(/:/g, "")}`;
  return <Svg>
    <defs><clipPath id={id}><rect x="0" y="0" width="24" height="15.9" /></clipPath></defs>
    <g className="rays"><path d="M12 6v2M6.4 9.4l1.4 1.4M17.6 9.4l-1.4 1.4M3.6 13.6h1.8M18.6 13.6h1.8" /></g>
    <g clipPath={`url(#${id})`}><path className="sun" d="M7.4 16a4.6 4.6 0 0 1 9.2 0z" fill="#fff" stroke="none" /></g>
    <path d="M3.5 16h17" /><path d="M8 19.6h8" opacity=".75" />
  </Svg>;
}

function GlassBoard() {
  const id = `nf-sb-c${useId().replace(/:/g, "")}`;
  return <Svg>
    <defs><linearGradient id={id} x1="3" y1="4" x2="21" y2="21" gradientUnits="userSpaceOnUse"><stop offset="0" stopColor="#7C5CFF" /><stop offset="1" stopColor="#F0508C" /></linearGradient></defs>
    <path d="M8.5 4.2h9a2.3 2.3 0 0 1 2.3 2.3v9" stroke={`url(#${id})`} opacity=".55" />
    <rect x="4.2" y="7.6" width="12.4" height="12.4" rx="2.8" stroke={`url(#${id})`} fill={`url(#${id})`} fillOpacity=".12" />
    <path className="spk" d="M10.4 9.9c.4 2.3 1.2 3.1 3.5 3.5-2.3.4-3.1 1.2-3.5 3.5-.4-2.3-1.2-3.1-3.5-3.5 2.3-.4 3.1-1.2 3.5-3.5z" fill={`url(#${id})`} stroke="none" />
  </Svg>;
}

const GLYPHS = {
  a: {
    unread: () => <Svg><path d="M5 8h8M5 12.5h14M5 17h10" /><circle cx="18.2" cy="7.2" r="3" fill="#fff" stroke="none" /></Svg>,
    starred: () => <Svg><path d={STAR} fill="#fff" strokeWidth="1.6" /></Svg>,
    all: () => <Svg><path d="M12 4.2l8 4-8 4-8-4z" fill="#fff" /><path d="M4 12.2l8 4 8-4" /><path d="M4 16.2l8 4 8-4" opacity=".8" /></Svg>,
    board: SunriseBoard,
  },
  b: {
    unread: () => <Svg><rect x="5.5" y="4" width="13" height="16" rx="2.6" fill="#fff" fillOpacity=".22" /><path d="M8.8 9h6.4M8.8 12.4h6.4M8.8 15.8h3.8" /><circle cx="18" cy="5.2" r="2.7" fill="#FFD66B" stroke="none" /></Svg>,
    starred: () => <Svg><path d={STAR} fill="#fff" fillOpacity=".3" strokeWidth="1.8" /></Svg>,
    all: () => <Svg><path d="M4.5 13.5l2-6.4A2 2 0 0 1 8.4 5.7h7.2a2 2 0 0 1 1.9 1.4l2 6.4v4.3a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2z" fill="#fff" fillOpacity=".22" /><path d="M4.5 13.5h4.2l1.4 2h3.8l1.4-2h4.2" /></Svg>,
    board: () => <Svg>
      <path d="M4 8.2a1.8 1.8 0 0 1 1.8-1.8h8.4A1.8 1.8 0 0 1 16 8.2v10.4a1.8 1.8 0 0 1-1.8 1.8H6.2A2.2 2.2 0 0 1 4 18.2z" fill="#fff" fillOpacity=".22" />
      <path d="M16 11.5h2.2a1.3 1.3 0 0 1 1.3 1.3v5.4a2.2 2.2 0 0 1-2.2 2.2H6.2" /><path d="M7 10h6M7 13.4h6M7 16.6h3.6" />
      <path className="spk" d="M19 1.9c.35 2.05 1.25 2.95 3.3 3.3-2.05.35-2.95 1.25-3.3 3.3-.35-2.05-1.25-2.95-3.3-3.3 2.05-.35 2.95-1.25 3.3-3.3z" fill="#FFE08A" stroke="none" />
    </Svg>,
  },
  c: {
    unread: () => <Svg><circle cx="6" cy="7" r="2.3" fill="currentColor" stroke="none" /><circle cx="6" cy="12.3" r="1.4" strokeWidth="1.6" opacity=".55" /><circle cx="6" cy="17.6" r="1.4" strokeWidth="1.6" opacity=".55" /><path d="M10.5 7h9" /><path d="M10.5 12.3h9M10.5 17.6h6" opacity=".55" /></Svg>,
    starred: () => <Svg><path d={STAR} fill="currentColor" fillOpacity=".22" /></Svg>,
    all: () => <Svg><rect x="4.5" y="4.5" width="6.3" height="6.3" rx="1.8" fill="currentColor" fillOpacity=".22" /><rect x="13.2" y="4.5" width="6.3" height="6.3" rx="1.8" /><rect x="4.5" y="13.2" width="6.3" height="6.3" rx="1.8" /><rect x="13.2" y="13.2" width="6.3" height="6.3" rx="1.8" fill="currentColor" fillOpacity=".22" /></Svg>,
    board: GlassBoard,
  },
};

const CLASSIC = { unread: UnreadIcon, starred: StarredIcon, all: AllIcon };

// The tile in front of 未读 / 收藏 / 全部 / 今日看板. `kind` is the filter name
// or "board"; the board tile also shows when a board is new or being written.
export default function SidebarBadge({ kind }) {
  const style = sidebarIconStyle(useStore(settingsState).sidebarIconStyle);
  const state = useStore(board);
  if (style === "classic") {
    const Classic = CLASSIC[kind];
    return <span className="ds-icon-badge sidebar-row-icon">{Classic ? <Classic /> : <BoardSidebarIcon />}</span>;
  }
  const working = kind === "board" && (state.status === "loading" || state.updating);
  const fresh = kind === "board" && !working && state.unseen;
  const Glyph = GLYPHS[style][kind] || GLYPHS[style].all;
  return (
    <span
      className={`ds-icon-badge sidebar-row-icon nf-sb nf-sb-${style}${working ? " is-gen" : ""}${fresh ? " is-new" : ""}`}
      data-k={kind}
      data-state={working ? "working" : fresh ? "new" : undefined}
    >
      <span className="nf-sb-fx" aria-hidden="true" />
      <Glyph />
    </span>
  );
}
