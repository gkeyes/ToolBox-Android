import { useTranslation } from "react-i18next";
import { ItemWrapper } from "@/components/ui/settingItem.jsx";
import { ChevronsUpDown, Globe } from "lucide-react";
import { Button, Dropdown, Label } from "@heroui/react";
import SettingIcon from "@/components/ui/SettingIcon";

const languages = [
  { id: "zh-CN", name: "简体中文" },
  { id: "en-US", name: "English" },
  { id: "tr-TR", name: "Türkçe" },
  { id: "fr-FR", name: "Français" },
];

export default function Language() {
  const { i18n, t } = useTranslation();

  return (
    <ItemWrapper title={t("settings.general.language")}>
      <div className="flex justify-between items-center gap-2 bg-default/60 dark:bg-default/30 px-2.5 py-2">
        <div className="flex items-center gap-2">
          <SettingIcon variant="blue">
            <Globe />
          </SettingIcon>
          <div className="text-sm text-foreground line-clamp-1">
            {t("settings.general.interfaceLanguage")}
          </div>
        </div>
        <Dropdown>
          <Button variant="tertiary" size="sm" className="text-muted h-8">
            {languages.find((lang) => lang.id === i18n.language)?.name ||
              languages[1].name}
            <ChevronsUpDown className="size-4 shrink-0 text-muted opacity-60" />
          </Button>
          <Dropdown.Popover>
            <Dropdown.Menu
              aria-label="language"
              selectedKeys={
                new Set([
                  languages.find((lang) => lang.id === i18n.language)?.id ||
                    languages[1].id,
                ])
              }
              selectionMode="single"
              onSelectionChange={(keys) => {
                i18n.changeLanguage(keys.currentKey);
              }}
            >
              {languages.map((lang) => (
                <Dropdown.Item id={lang.id} key={lang.id} textValue={lang.name}>
                  <Dropdown.ItemIndicator />
                  <Label>{lang.name}</Label>
                </Dropdown.Item>
              ))}
            </Dropdown.Menu>
          </Dropdown.Popover>
        </Dropdown>
      </div>
    </ItemWrapper>
  );
}
