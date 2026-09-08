import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import { getPreference, setPreference } from "../toolbox/preferences.js";
import zhCN from "./locales/zh-CN";
import enUS from "./locales/en-US";
import trTR from "./locales/tr-TR";
import frFR from "./locales/fr-FR";
i18n
  .use(initReactI18next)
  .init({
    resources: {
      "zh-CN": {
        translation: zhCN,
      },
      "en-US": {
        translation: enUS,
      },
      "tr-TR": {
        translation: trTR,
      },
      "fr-FR": {
        translation: frFR,
      },
    },
    fallbackLng: "en-US",
    lng: getPreference("language") || globalThis.navigator?.language || "zh-CN",
    interpolation: {
      escapeValue: false,
    },
  });

i18n.on("languageChanged", (language) => setPreference("language", language));

export default i18n;
