import { useTranslation } from "react-i18next";
import { NotFancyLogo } from "@/components/Settings/components/NotFancyLogo.jsx";
import { ArrowUpRight, Heart, Rss } from "lucide-react";
import manifest from "../../../manifest.json";
import { SERVER_URL } from "@/toolbox/network.js";
import { ItemWrapper, SettingRow } from "@/components/ui/settingItem.jsx";
import SettingIcon from "@/components/ui/SettingIcon";

const SERVER_HOST = new URL(SERVER_URL).host;

const credits = [
  { name: "Reeder", href: "https://reederapp.com" },
  { name: "HeroUI", href: "https://www.heroui.com" },
  { name: "TailwindCSS", href: "https://tailwindcss.com" },
  { name: "React-photo-view", href: "https://react-photo-view.vercel.app" },
  { name: "Shiki", href: "https://shiki.matsu.io" },
  { name: "Virtuoso", href: "https://virtuoso.dev" },
];

const external = <ArrowUpRight className="nf-set-row-chevron" aria-hidden="true" />;

export default function About() {
  const { t } = useTranslation();

  return (
    <>
      <div className="nf-about-hero">
        <span className="nf-about-mark" aria-hidden="true">
          <Rss />
        </span>
        <div className="nf-about-name">NextFlux</div>
        <p className="nf-about-meta">ToolBox 适配版 {manifest.version} · {SERVER_HOST}</p>
        <NotFancyLogo since={2025} />
      </div>

      <ItemWrapper>
        <SettingRow
          as="a"
          pressable
          href="https://github.com/electh"
          target="_blank"
          rel="noopener noreferrer"
          icon={
            <SettingIcon variant="red">
              <Heart className="fill-current" />
            </SettingIcon>
          }
          label="Made with love by electh"
          trailing={external}
        />
      </ItemWrapper>

      <ItemWrapper title={t("about.acknowledgments")}>
        {credits.map((credit) => (
          <SettingRow
            key={credit.href}
            as="a"
            pressable
            href={credit.href}
            target="_blank"
            rel="noopener noreferrer"
            label={credit.name}
            trailing={external}
          />
        ))}
      </ItemWrapper>
    </>
  );
}
