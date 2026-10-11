import { ItemWrapper, KeyboardItem } from "@/components/ui/settingItem.jsx";
import { useTranslation } from "react-i18next";

export default function Shortcuts() {
  const { t } = useTranslation();
  const shortcuts = {
    article: [
      { key: "J", desc: t("sidebar.shortcuts.next") },
      { key: "K", desc: t("sidebar.shortcuts.previous") },
      { key: "M", desc: t("sidebar.shortcuts.toggleRead") },
      { key: "S", desc: t("sidebar.shortcuts.toggleStar") },
      { key: "G", desc: t("sidebar.shortcuts.toggleReaderView") },
      { key: "ESC", desc: t("sidebar.shortcuts.close") },
      { key: "V", desc: t("sidebar.shortcuts.viewInBrowser") },
    ],
    global: [
      { key: "R", desc: t("sidebar.shortcuts.refresh") },
      { key: "F", desc: t("sidebar.shortcuts.search") },
    ],
    sidebar: [
      {
        key: "B",
        desc: t("sidebar.shortcuts.toggleSidebar"),
        kbdKey: ["ctrl"],
      },
      { key: "N", desc: t("sidebar.shortcuts.addFeed"), kbdKey: ["shift"] },
      { key: "P", desc: t("sidebar.shortcuts.prevItem") },
      { key: "N", desc: t("sidebar.shortcuts.nextItem") },
      { key: "X", desc: t("sidebar.shortcuts.toggleCategory") },
    ],
  };

  const groups = [
    ["global", t("sidebar.shortcuts.global")],
    ["sidebar", t("sidebar.shortcuts.sidebar")],
    ["article", t("sidebar.shortcuts.article")],
  ];

  return (
    <>
      {groups.map(([id, title]) => (
        <ItemWrapper key={id} title={title}>
          {shortcuts[id].map((shortcut) => (
            <KeyboardItem
              key={shortcut.desc}
              keyStr={shortcut.key}
              kbdKey={shortcut.kbdKey}
              desc={shortcut.desc}
            />
          ))}
        </ItemWrapper>
      ))}
    </>
  );
}
