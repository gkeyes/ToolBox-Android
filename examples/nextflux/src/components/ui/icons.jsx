// NextFlux "ink" icon set — 24px grid, 1.75 stroke, round caps/joins.
// Signature: the RSS origin dot. Icons tied to unread state, feeds and
// generated content carry a small filled accent dot (.nf-dot). Same API as
// lucide-react (className, size via CSS, extra SVG props pass through).
// Source of truth for shapes: nextflux-concept/icons/icons.py.
import { forwardRef } from "react";

function make(name, body) {
  const Icon = forwardRef(function Icon({ className = "", strokeWidth = 1.75, ...props }, ref) {
    return (
      <svg
        ref={ref}
        xmlns="http://www.w3.org/2000/svg"
        width="24"
        height="24"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={strokeWidth}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className={`lucide nf-icon nf-icon-${name}${className ? ` ${className}` : ""}`}
        {...props}
      >
        {body}
      </svg>
    );
  });
  Icon.displayName = `${name}Icon`;
  return Icon;
}

export const UnreadIcon = make("unread", <><path d="M14 3.5H7.5a2.5 2.5 0 0 0-2.5 2.5v12a2.5 2.5 0 0 0 2.5 2.5h9a2.5 2.5 0 0 0 2.5-2.5V10"/><path d="M8.5 10.5h5M8.5 14h7M8.5 17.5h4"/><circle className="nf-dot" cx="18" cy="5.5" r="2.6"/></>);
export const StarredIcon = make("starred", <><path d="M12 3.6l2.45 5 5.5.8-3.98 3.88.94 5.48L12 16.17l-4.91 2.59.94-5.48L4.05 9.4l5.5-.8z"/></>);
export const AllIcon = make("all", <><path d="M4.5 6.5h15M4.5 12h15M4.5 17.5h9"/><circle className="nf-dot" cx="18.2" cy="17.5" r="2.1"/></>);
export const TodayIcon = make("today", <><rect x="4" y="5" width="16" height="15" rx="3"/><path d="M4 9.5h16M8.5 3.5v3M15.5 3.5v3"/><circle className="nf-dot" cx="12" cy="14.6" r="2.1"/></>);
export const FeedIcon = make("feed", <><path d="M5.5 11a7.5 7.5 0 0 1 7.5 7.5"/><path d="M5.5 5a13.5 13.5 0 0 1 13.5 13.5"/><circle className="nf-dot" cx="6.6" cy="17.4" r="2.3"/></>);
export const FolderIcon = make("folder", <><path d="M3.8 7.5a2.5 2.5 0 0 1 2.5-2.5h3.4l2 2.2h6a2.5 2.5 0 0 1 2.5 2.5v7.8a2.5 2.5 0 0 1-2.5 2.5H6.3a2.5 2.5 0 0 1-2.5-2.5z"/></>);
export const FolderOpenIcon = make("folderopen", <><path d="M3.8 16.8V7.5A2.5 2.5 0 0 1 6.3 5h3.4l2 2.2h6a2.5 2.5 0 0 1 2.5 2.5v.8"/><path d="M3.8 17.2l2.1-5.6a2 2 0 0 1 1.9-1.3h11.4a1.4 1.4 0 0 1 1.3 1.9l-1.9 5.2a2.5 2.5 0 0 1-2.4 1.6H6.3a2.5 2.5 0 0 1-2.5-1.8z"/></>);
export const SidebarIcon = make("sidebar", <><rect x="3.5" y="4.5" width="17" height="15" rx="3.5"/><path d="M9.5 4.5v15"/><path d="M6 8.5h1M6 11.5h1"/></>);
export const SearchIcon = make("search", <><circle cx="10.8" cy="10.8" r="6.3"/><path d="M15.5 15.5l4 4"/></>);
export const MoreIcon = make("more", <><circle cx="5.5" cy="12" r="1.3" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.3" fill="currentColor" stroke="none"/><circle className="nf-dot" cx="18.5" cy="12" r="1.6"/></>);
export const MoreVerticalIcon = make("morevertical", <><circle cx="12" cy="5.5" r="1.3" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.3" fill="currentColor" stroke="none"/><circle className="nf-dot" cx="12" cy="18.5" r="1.6"/></>);
export const SyncIcon = make("sync", <><path d="M19 12a7 7 0 0 1-12.2 4.7"/><path d="M5 12a7 7 0 0 1 12.2-4.7"/><path d="M17.6 3.8v3.9h-3.9"/><path d="M6.4 20.2v-3.9h3.9"/></>);
export const MarkAllReadIcon = make("markallread", <><path d="M3.5 12.5l3.6 3.6L14 9.2"/><path d="M11.8 15.4l.7.7L20 8.6"/><circle className="nf-dot" cx="19.6" cy="17" r="1.9"/></>);
export const AddIcon = make("add", <><path d="M12 5v14M5 12h14"/></>);
export const AddFeedIcon = make("addfeed", <><path d="M5 11a7.5 7.5 0 0 1 7.5 7.5"/><path d="M5 5a13.5 13.5 0 0 1 9.2 3.6"/><path d="M18 12.5v7M14.5 16h7"/><circle className="nf-dot" cx="6.1" cy="17.4" r="2.2"/></>);
export const SettingsIcon = make("settings", <><path d="M12 3.5l2 1.3 2.4-.2 1.1 2.1 2.1 1.1-.2 2.4 1.3 2-1.3 2 .2 2.4-2.1 1.1-1.1 2.1-2.4-.2-2 1.3-2-1.3-2.4.2-1.1-2.1-2.1-1.1.2-2.4-1.3-2 1.3-2-.2-2.4 2.1-1.1 1.1-2.1 2.4.2z"/><circle cx="12" cy="12" r="3"/></>);
export const BackIcon = make("back", <><path d="M14.5 5.5L8 12l6.5 6.5"/></>);
export const CloseIcon = make("close", <><path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/></>);
export const FullTextIcon = make("fulltext", <><path d="M14 3.5H7.5A2.5 2.5 0 0 0 5 6v12a2.5 2.5 0 0 0 2.5 2.5h9A2.5 2.5 0 0 0 19 18V8.5z"/><path d="M14 3.5v5h5"/><path d="M8.5 12.5h7M8.5 16h5"/></>);
export const StarIcon = make("star", <><path d="M12 3.6l2.45 5 5.5.8-3.98 3.88.94 5.48L12 16.17l-4.91 2.59.94-5.48L4.05 9.4l5.5-.8z"/></>);
export const AISummaryIcon = make("aisummary", <><path d="M11 4.5c.6 3.6 2.4 5.4 6 6-3.6.6-5.4 2.4-6 6-.6-3.6-2.4-5.4-6-6 3.6-.6 5.4-2.4 6-6z"/><circle className="nf-dot" cx="18.4" cy="5.6" r="1.9"/></>);
export const SpeechIcon = make("speech", <><path d="M4.5 10v4M8.5 7v10M12.5 4.5v15M16.5 8v8"/><circle className="nf-dot" cx="20" cy="12" r="1.9"/></>);
export const ShareIcon = make("share", <><path d="M12 14.5V3.8M8 7.6L12 3.6l4 4"/><path d="M7.5 10.5H7a2.5 2.5 0 0 0-2.5 2.5v5A2.5 2.5 0 0 0 7 20.5h10a2.5 2.5 0 0 0 2.5-2.5v-5a2.5 2.5 0 0 0-2.5-2.5h-.5"/></>);
export const ReadStateIcon = make("readstate", <><circle cx="12" cy="12" r="7.5"/><circle className="nf-dot" cx="12" cy="12" r="3.2"/></>);
export const TitleFilterIcon = make("titlefilter", <><path d="M4.5 5.5h15l-5.7 6.9v5.4l-3.6 1.9v-7.3z"/></>);
export const AdaptIcon = make("adapt", <><path d="M5 19l9.5-9.5"/><path d="M13 8l3 3"/><path d="M15.5 3.8v2.4M14.3 5h2.4M19.6 9.8v2M18.6 10.8h2"/><circle className="nf-dot" cx="8.2" cy="5.8" r="1.7"/></>);
export const SaveIcon = make("save", <><path d="M7 20.5V5.5A2 2 0 0 1 9 3.5h6a2 2 0 0 1 2 2v15l-5-3.4z"/></>);
export const PrevIcon = make("prev", <><path d="M18 15l-6-6-6 6"/></>);
export const NextIcon = make("next", <><path d="M6 9l6 6 6-6"/></>);
export const PlayIcon = make("play", <><path d="M8 5.5v13l10.5-6.5z"/></>);
export const PauseIcon = make("pause", <><path d="M8.5 5.5v13M15.5 5.5v13"/></>);
export const EmptyIcon = make("empty", <><path d="M4 13.5l2.6-7.1A2.5 2.5 0 0 1 9 4.8h6a2.5 2.5 0 0 1 2.4 1.6l2.6 7.1"/><path d="M4 13.5v4a2.5 2.5 0 0 0 2.5 2.5h11a2.5 2.5 0 0 0 2.5-2.5v-4h-4.5l-1.3 2.2h-4.4L8.5 13.5z"/></>);
export const ImageOffIcon = make("imageoff", <><rect x="4" y="5" width="16" height="14" rx="3"/><path d="M4 16l4.5-4.5 3.5 3.5M13.5 12.5l1.5-1.5 5 5"/><path d="M3.5 3.5l17 17"/></>);
export const WarningIcon = make("warning", <><path d="M10.3 4.7a2 2 0 0 1 3.4 0l7 12.2a2 2 0 0 1-1.7 3H5a2 2 0 0 1-1.7-3z"/><path d="M12 9.5v4"/><circle cx="12" cy="16.8" r="1" fill="currentColor" stroke="none"/></>);
export const CheckIcon = make("check", <><path d="M5 12.5l4.5 4.5L19 7.5"/></>);
export const ReadStateEmptyIcon = make("readstateempty", <><circle cx="12" cy="12" r="7.5"/></>);
