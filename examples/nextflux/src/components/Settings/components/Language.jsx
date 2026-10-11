import { useTranslation } from "react-i18next";
import { ItemWrapper, MenuRow } from "@/components/ui/settingItem.jsx";
import { Globe } from "lucide-react";
import { Dropdown, Label } from "@heroui/react";
import SettingIcon from "@/components/ui/SettingIcon";

const languages = [
  { id: "zh-CN", name: "简体中文" },
  { id: "en-US", name: "English" },
  { id: "tr-TR", name: "Türkçe" },
  { id: "fr-FR", name: "Français" },
];

export default function Language() {
  const { i18n, t } = useTranslation();
  const current = languages.find((lang) => lang.id === i18n.language) || languages[1];

  return (
    <ItemWrapper title={t("settings.general.language")}>
      <MenuRow
        label={t("settings.general.interfaceLanguage")}
        icon={
          <SettingIcon variant="blue">
            <Globe />
          </SettingIcon>
        }
        ariaLabel="language"
        value={current.name}
        selectedKeys={new Set([current.id])}
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
      </MenuRow>
    </ItemWrapper>
  );
}
