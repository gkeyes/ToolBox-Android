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

// 移动端设置界面 - 共享手势 Sheet
function MobileSettings() {
  const isOpen = useStore(settingsModalOpen);
  const [activeTab, setActiveTab] = useState(null);
  // The content page keeps its last tab while sliding out, so "back" never shows a blank page.
  const [shownTab, setShownTab] = useState(null);
  const [collapsed, setCollapsed] = useState({ menu: false, content: false });
  const [openCount, setOpenCount] = useState(0);
  const [wasOpen, setWasOpen] = useState(isOpen);
  const { t } = useTranslation();
  const titleFor = useItemTitle();

  // Reset while rendering the opening frame (not in an effect), so the sheet
  // never paints the previous session's page first. Closing keeps the page in
  // place for the exit animation.
  if (wasOpen !== isOpen) {
    setWasOpen(isOpen);
    if (isOpen) {
      setActiveTab(null);
      setShownTab(null);
      setCollapsed({ menu: false, content: false });
      setOpenCount((count) => count + 1);
    }
  }

  const handleSelectMenu = (id) => {
    setActiveTab(id);
    setShownTab(id);
    setCollapsed((current) => ({ ...current, content: false }));
  };
  const handleBack = () => setActiveTab(null);
  const handleClose = (value) => settingsModalOpen.set(value);
  const currentPage = activeTab === null ? "menu" : "content";
  const currentTitle = activeTab === null ? t("common.settings") : titleFor(activeTab);
  const isCollapsed = collapsed[currentPage];

  return (
    <MobileSheet
      open={isOpen}
      onOpenChange={handleClose}
      title={<span className="nf-collapse-title" data-collapsed={isCollapsed}>{currentTitle}</span>}
      closeLabel={t("common.close")}
      dialogClassName="h-[85vh]"
      bodyClassName="overflow-hidden"
      headerStart={activeTab !== null ? (
        <Button
          isIconOnly
          size="sm"
          variant="tertiary"
          onPress={handleBack}
          className="size-10 min-w-10"
          aria-label={t("common.back", { defaultValue: "Back" })}
        >
          <ArrowLeft className="size-5" />
        </Button>
      ) : null}
    >
      <div key={openCount} className="t-settings-slide h-full" data-active={currentPage}>
        <div className="nf-collapse-hairline" data-collapsed={isCollapsed} />
        <div className="t-page" data-page-id="menu" inert={currentPage !== "menu" || undefined}>
          <MenuList
            onSelect={handleSelectMenu}
            onCollapsedChange={(value) => setCollapsed((current) => ({ ...current, menu: value }))}
          />
        </div>
        <div className="t-page" data-page-id="content" inert={currentPage !== "content" || undefined}>
          {shownTab && (
            <ContentArea
              key={shownTab}
              activeTab={shownTab}
              onCollapsedChange={(value) => setCollapsed((current) => ({ ...current, content: value }))}
            />
          )}
        </div>
      </div>
    </MobileSheet>
  );
}

// 桌面端设置界面 - 使用 Modal
function DesktopSettings() {
  const isOpen = useStore(settingsModalOpen);
  const [activeTab, setActiveTab] = useState("general");
  const [wasOpen, setWasOpen] = useState(isOpen);
  const { t } = useTranslation();

  if (wasOpen !== isOpen) {
    setWasOpen(isOpen);
    if (isOpen) setActiveTab("general");
  }

  const handleClose = (value) => settingsModalOpen.set(value);

  return (
    <Modal>
      <Button className="hidden" />
      <Modal.Backdrop
        className="nextflux-modal-backdrop"
        isOpen={isOpen}
        onOpenChange={handleClose}
        variant="transparent"
      >
        <Modal.Container>
          <Modal.Dialog className="nextflux-modal-surface w-[760px] max-w-[90vw] h-[600px] p-0 relative">
            <Modal.CloseTrigger className="nextflux-close-button" aria-label={t("common.close")} />
            <div className="flex h-full">
              {/* 左侧导航栏 */}
              <aside className="nf-set-rail">
                <h2 className="nf-set-rail-title">{t("common.settings")}</h2>
                <nav className="nf-set-nav flex-1 overflow-y-auto">
                  {menuItems.map((item) => (
                    <NavRow
                      key={item.id}
                      item={item}
                      active={activeTab === item.id}
                      onSelect={setActiveTab}
                    />
                  ))}
                </nav>
              </aside>

              {/* 右侧内容区域 */}
              <div className="nf-set-pane">
                <ContentArea key={activeTab} activeTab={activeTab} showTitle={true} />
              </div>
            </div>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </Modal>
  );
}

// 主组件 - 根据设备类型选择渲染方式
export default function SettingsModal() {
  const { isMedium } = useIsMobile();

  if (isMedium) {
    return <MobileSettings />;
  }

  return <DesktopSettings />;
}
