import { cn, Button, Modal } from "@heroui/react";
import MobileSheet from "@/components/ui/MobileSheet.jsx";
import { useEffect, useRef, useState } from "react";
import { settingsModalOpen } from "@/stores/modalStore.js";
import { useStore } from "@nanostores/react";
import General from "@/components/Settings/General.jsx";
import Appearance from "@/components/Settings/Appearance.jsx";
import Readability from "@/components/Settings/Readability.jsx";
import AI from "@/components/Settings/AI.jsx";
import Speech from "@/components/Settings/Speech.jsx";
import About from "@/components/Settings/About.jsx";
import Shortcuts from "@/components/Settings/Shortcuts.jsx";
import SettingIcon from "@/components/ui/SettingIcon.jsx";
import { useTranslation } from "react-i18next";
import { useIsMobile } from "@/hooks/use-mobile.jsx";
import { claimStagger } from "@/design/motion.js";
import "@/components/Settings/settings.css";
import {
  Paintbrush,
  Keyboard,
  Info,
  ArrowLeft,
  X,
  ChevronRight,
} from "lucide-react";
import {
  SettingsIcon as Cog,
  FullTextIcon as FileText,
  AISummaryIcon as Sparkles,
  SpeechIcon as Volume2,
} from "@/components/ui/icons.jsx";

const menuItems = [
  { id: "general", icon: Cog, tone: "default", translationKey: "settings.general.title" },
  {
    id: "appearance",
    icon: Paintbrush,
    tone: "blue",
    translationKey: "settings.appearance.title",
  },
  {
    id: "readability",
    icon: FileText,
    tone: "green",
    translationKey: "settings.readability.title",
  },
  { id: "ai", icon: Sparkles, tone: "purple", translationKey: "settings.ai.title" },
  { id: "speech", icon: Volume2, tone: "pink", translationKey: "settings.speech.title" },
  {
    id: "shortcuts",
    icon: Keyboard,
    tone: "cyan",
    translationKey: "sidebar.shortcuts.title",
  },
  { id: "about", icon: Info, tone: "amber", translationKey: "about.title" },
];
// Grouped cards on the phone menu (no dividers; cards separate the groups).
const menuGroups = [["general", "appearance", "readability"], ["ai", "speech"], ["shortcuts", "about"]];

const pages = {
  general: General,
  appearance: Appearance,
  readability: Readability,
  ai: AI,
  speech: Speech,
  shortcuts: Shortcuts,
  about: About,
};

function useItemTitle() {
  const { t } = useTranslation();
  return (id) => {
    const item = menuItems.find((entry) => entry.id === id);
    return item?.label || t(item?.translationKey || "");
  };
}

/**
 * Collapsing large title: the 30px title scrolls with the content; once it is
 * under the bar the 17px title and the hairline fade in. State starts at
 * "expanded" on every mount, which matches scrollTop 0, so the first frame is right.
 */
function useCollapsingTitle(onChange) {
  const scrollerRef = useRef(null);
  const titleRef = useRef(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  const [collapsed, setCollapsed] = useState(false);
  useEffect(() => {
    const scroller = scrollerRef.current;
    if (!scroller) return undefined;
    let frame = 0;
    let last = false;
    const measure = () => {
      frame = 0;
      const limit = Math.max(8, (titleRef.current?.offsetHeight || 44) - 12);
      const next = scroller.scrollTop > limit;
      if (next === last) return;
      last = next;
      setCollapsed(next);
      onChangeRef.current?.(next);
    };
    const onScroll = () => {
      if (!frame) frame = requestAnimationFrame(measure);
    };
    scroller.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      scroller.removeEventListener("scroll", onScroll);
      if (frame) cancelAnimationFrame(frame);
    };
  }, []);
  return { scrollerRef, titleRef, collapsed };
}

function NavRow({ item, active, onSelect, trailing }) {
  const { t } = useTranslation();
  const Icon = item.icon;
  return (
    <button
      type="button"
      onClick={() => onSelect(item.id)}
      data-active={active || undefined}
      aria-current={active ? "page" : undefined}
      className="nf-set-row is-pressable"
    >
      <SettingIcon variant={item.tone}>
        <Icon />
      </SettingIcon>
      <span className="nf-set-row-text">
        <span className="nf-set-row-title">{item.label || t(item.translationKey)}</span>
      </span>
      {trailing}
    </button>
  );
}

// 移动设备菜单列表内容
function MenuList({ onSelect, onCollapsedChange }) {
  const { t } = useTranslation();
  const { scrollerRef, titleRef } = useCollapsingTitle(onCollapsedChange);
  const [stagger] = useState(() => claimStagger("settings:menu"));

  return (
    <div ref={scrollerRef} className="nf-set-scroller">
      <div ref={titleRef} className="nf-set-large-title" aria-hidden="true">
        {t("common.settings")}
      </div>
      <nav className={cn("nf-set-stack", stagger && "ds-stagger")}>
        {menuGroups.map((group) => (
          <div key={group[0]} className="ds-group-card">
            {group.map((id) => (
              <NavRow
                key={id}
                item={menuItems.find((entry) => entry.id === id)}
                onSelect={onSelect}
                trailing={<ChevronRight className="nf-set-row-chevron" aria-hidden="true" />}
              />
            ))}
          </div>
        ))}
      </nav>
    </div>
  );
}

// 内容区域：每个分页独立挂载（key），滚动位置与标题收合状态都从顶部开始
function ContentArea({ activeTab, showTitle = false, onCollapsedChange }) {
  const titleFor = useItemTitle();
  const { scrollerRef, titleRef, collapsed } = useCollapsingTitle(onCollapsedChange);
  const [stagger] = useState(() => claimStagger(`settings:${activeTab}`));
  const Page = pages[activeTab] || General;
  const title = titleFor(activeTab);

  return (
    <div className="flex flex-col h-full min-h-0">
      {showTitle && (
        <div className="nextflux-close-header nf-set-pane-header">
          <h3 className="min-w-0">
            <span className="nf-collapse-title" data-collapsed={collapsed}>{title}</span>
          </h3>
        </div>
      )}
      <div className="relative flex-1 min-h-0">
        {showTitle && <div className="nf-collapse-hairline" data-collapsed={collapsed} />}
        <div ref={scrollerRef} className="settings-content nf-set-scroller">
          <div ref={titleRef} className="nf-set-large-title" aria-hidden="true">
            {title}
          </div>
          <div className={cn("nf-set-stack", stagger && "ds-stagger")}>
            <Page />
          </div>
        </div>
      </div>
    </div>
  );
}

// Keep the form at the same React position while the menu changes from a
// page into a rail. Uncommitted text, selection and scroll belong to the form.
export default function SettingsModal() {
  const isOpen = useStore(settingsModalOpen);
  const { isMedium: compact } = useIsMobile();
  const { t } = useTranslation();
  const titleFor = useItemTitle();
  const [activeTab, setActiveTab] = useState(compact ? null : "general");
  const [shownTab, setShownTab] = useState(activeTab);
  const [collapsed, setCollapsed] = useState({ menu: false, content: false });
  const [wasOpen, setWasOpen] = useState(isOpen);
  // Hold the presentation for an open session so changing input hardware
  // cannot move an active field between two different overlay portals.
  const chooseSheet = () => compact || window.matchMedia("(pointer: coarse)").matches;
  const [sheet, setSheet] = useState(chooseSheet);

  if (wasOpen !== isOpen) {
    setWasOpen(isOpen);
    if (isOpen) {
      setActiveTab(compact ? null : "general");
      setShownTab(compact ? null : "general");
      setCollapsed({ menu: false, content: false });
      setSheet(chooseSheet());
    }
  }

  if (isOpen && !compact && activeTab === null) {
    setActiveTab(shownTab ?? "general");
    setShownTab(shownTab ?? "general");
  }

  const selectTab = (id) => {
    setActiveTab(id);
    setShownTab(id);
    setCollapsed((current) => ({ ...current, content: false }));
  };
  const currentPage = activeTab === null ? "menu" : "content";
  const shown = shownTab ?? "general";
  const currentTitle = compact && activeTab ? titleFor(activeTab) : t("common.settings");
  const back = compact && activeTab && (
    <Button isIconOnly size="sm" variant="tertiary" onPress={() => setActiveTab(null)}
      className="size-11 min-w-11" aria-label={t("common.back")}>
      <ArrowLeft className="size-5" />
    </Button>
  );

  const layout = (
    <div className={compact ? "t-settings-slide h-full" : "flex h-full min-h-0"} data-active={currentPage}>
      {compact && <div className="nf-collapse-hairline" data-collapsed={collapsed[currentPage]} />}
      <div key="menu" className={compact ? "t-page" : "nf-set-rail"} data-page-id="menu" inert={compact && currentPage !== "menu" || undefined}>
        {compact ? <MenuList onSelect={selectTab} onCollapsedChange={(value) => setCollapsed((current) => ({ ...current, menu: value }))} /> : (
          <>
            {!sheet && <h2 className="nf-set-rail-title">{t("common.settings")}</h2>}
            <nav className="nf-set-nav flex-1 overflow-y-auto">
              {menuItems.map((item) => <NavRow key={item.id} item={item} active={shown === item.id} onSelect={selectTab} />)}
            </nav>
          </>
        )}
      </div>
      <div key="content" className={compact ? "t-page" : "nf-set-pane"} data-page-id="content" inert={compact && currentPage !== "content" || undefined}>
        {(!compact || shownTab) && <ContentArea key={shown} activeTab={shown} showTitle={!compact && !sheet}
          onCollapsedChange={(value) => setCollapsed((current) => ({ ...current, content: value }))} />}
      </div>
    </div>
  );

  if (sheet) {
    return (
      <MobileSheet
        open={isOpen}
        onOpenChange={(open) => settingsModalOpen.set(open)}
        title={compact ? <span className="nf-collapse-title" data-collapsed={collapsed[currentPage]}>{currentTitle}</span> : currentTitle}
        closeLabel={t("common.close")}
        dialogClassName="nf-settings-sheet h-[85dvh] max-w-[760px]"
        bodyClassName="overflow-hidden"
        headerStart={back}
        headerEnd={(
          <Button isIconOnly size="sm" variant="tertiary" onPress={() => settingsModalOpen.set(false)}
            className="size-11 min-w-11" aria-label={t("common.close")}>
            <X className="size-5" />
          </Button>
        )}
      >{layout}</MobileSheet>
    );
  }

  return (
    <Modal>
      <Button className="hidden" />
      <Modal.Backdrop className="nextflux-modal-backdrop" isOpen={isOpen} onOpenChange={(open) => settingsModalOpen.set(open)} variant="transparent">
        <Modal.Container>
          <Modal.Dialog className="nextflux-modal-surface w-[760px] max-w-[90vw] h-[600px] p-0 relative" aria-label={t("common.settings")}>
            <Modal.CloseTrigger className="nextflux-close-button" aria-label={t("common.close")} />
            {compact && <div className="nextflux-close-header flex items-center px-4">{back}<h2>{currentTitle}</h2></div>}
            {layout}
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </Modal>
  );
}
