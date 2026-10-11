import { Monitor, MoonStar, Paintbrush, Sun } from "lucide-react";
import { useStore } from "@nanostores/react";
import { ItemWrapper, MenuRow, SettingRow, Segmented } from "@/components/ui/settingItem";
import { Dropdown, Label } from "@heroui/react";
import { setTheme, themeState, themes } from "@/stores/themeStore";
import { useTranslation } from "react-i18next";
import SettingIcon from "@/components/ui/SettingIcon";

function Swatch({ color }) {
  return (
    <span
      aria-hidden="true"
      className="inline-block size-4 shrink-0 rounded-full"
      style={{ backgroundColor: color, boxShadow: "inset 0 0 0 1px var(--ds-hairline)" }}
    />
  );
}

export default function Theme() {
  const { t } = useTranslation();
  const { themeMode, lightTheme, darkTheme } = useStore(themeState);

  const mode = [
    { value: "system", ariaLabel: t("settings.appearance.system"), icon: <Monitor /> },
    { value: "light", ariaLabel: t("settings.appearance.light"), icon: <Sun /> },
    { value: "dark", ariaLabel: t("settings.appearance.dark"), icon: <MoonStar /> },
  ];
  const lightItem = themes.light.find((item) => item.id === lightTheme);
  const darkItem = themes.dark.find((item) => item.id === darkTheme);

  return (
    <ItemWrapper title={t("settings.appearance.theme")}>
      <SettingRow
        icon={
          <SettingIcon variant="blue">
            <Paintbrush />
          </SettingIcon>
        }
        label={t("settings.appearance.mode")}
        trailing={
          <Segmented
            ariaLabel="theme"
            selectedKey={themeMode}
            onSelectionChange={(value) => setTheme(value)}
            options={mode}
          />
        }
      />
      <MenuRow
        label={t("settings.appearance.lightTheme")}
        icon={
          <SettingIcon variant="amber">
            <Sun />
          </SettingIcon>
        }
        ariaLabel="theme"
        value={<span className="inline-flex items-center gap-2">
          {lightItem && <Swatch color={lightItem.color} />}
          {t(`settings.appearance.themes.${lightItem?.id}`)}
        </span>}
        selectedKeys={new Set([lightTheme])}
        onSelectionChange={(values) => {
          themeState.set({
            ...themeState.get(),
            lightTheme: values.currentKey,
          });
          themeMode !== "dark" && setTheme(themeMode, values.currentKey);
        }}
      >
        {themes.light.map((item) => (
          <Dropdown.Item
            id={item.id}
            key={item.id}
            textValue={t(`settings.appearance.themes.${item.id}`)}
          >
            <Swatch color={item.color} />
            <Label>{t(`settings.appearance.themes.${item.id}`)}</Label>
            <Dropdown.ItemIndicator />
          </Dropdown.Item>
        ))}
      </MenuRow>
      <MenuRow
        label={t("settings.appearance.darkTheme")}
        icon={
          <SettingIcon variant="purple">
            <MoonStar />
          </SettingIcon>
        }
        ariaLabel="theme"
        disallowEmptySelection
        value={<span className="inline-flex items-center gap-2">
          {darkItem && <Swatch color={darkItem.color} />}
          {t(`settings.appearance.themes.${darkItem?.id}`)}
        </span>}
        selectedKeys={new Set([darkTheme])}
        onSelectionChange={(values) => {
          themeState.set({
            ...themeState.get(),
            darkTheme: values.currentKey,
          });
          themeMode !== "light" && setTheme(themeMode, values.currentKey);
        }}
      >
        {themes.dark.map((item) => (
          <Dropdown.Item
            id={item.id}
            key={item.id}
            textValue={t(`settings.appearance.themes.${item.id}`)}
          >
            <Swatch color={item.color} />
            <Label>{t(`settings.appearance.themes.${item.id}`)}</Label>
            <Dropdown.ItemIndicator />
          </Dropdown.Item>
        ))}
      </MenuRow>
    </ItemWrapper>
  );
}
