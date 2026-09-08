import dayjs from "dayjs";
import "dayjs/locale/zh-cn";
import "dayjs/locale/en";
import "dayjs/locale/tr";
import "dayjs/locale/fr";
// 这里可以按需导入更多语言包
import relativeTime from "dayjs/plugin/relativeTime";
import calendar from "dayjs/plugin/calendar";
import i18n from "@/i18n";

dayjs.extend(relativeTime);
dayjs.extend(calendar);

// dayjs 语言代码映射表
const DAYJS_LOCALE_MAP = {
  "zh-CN": "zh-cn",
  "en-US": "en",
  "tr-TR": "tr",
  "fr-FR": "fr",
  // 可以添加更多语言映射
  // 'ja-JP': 'ja',
  // 'ko-KR': 'ko',
  // 'fr-FR': 'fr',
  // ...
};

// 日期格式映射表
const DATE_FORMAT_MAP = {
  "zh-CN": {
    calendar: {
      sameDay: "[今天] HH:mm",
      lastDay: "[昨天] HH:mm",
      sameElse: "YYYY-MM-DD HH:mm",
    },
    fullDate: "YYYY年MM月DD日 HH:mm · dddd",
  },
  "en-US": {
    calendar: {
      sameDay: "[Today at] HH:mm",
      lastDay: "[Yesterday at] HH:mm",
      sameElse: "YYYY-MM-DD HH:mm",
    },
    fullDate: "dddd, MMMM D, YYYY HH:mm",
  },
  "tr-TR": {
    calendar: {
      sameDay: "[Bugün] HH:mm",
      lastDay: "[Dün] HH:mm",
      sameElse: "YYYY-MM-DD HH:mm",
    },
    fullDate: "dddd, MMMM D, YYYY HH:mm",
  },
  "fr-FR": {
    calendar: {
      sameDay: "[Aujourd'hui à] HH:mm",
      lastDay: "[Hier à] HH:mm",
      sameElse: "YYYY-MM-DD HH:mm",
    },
    fullDate: "dddd D MMMM YYYY HH:mm",
  },
};

// 监听语言变化
i18n.on("languageChanged", (lng) => {
  const dayjsLocale = DAYJS_LOCALE_MAP[lng] || "en";
  dayjs.locale(dayjsLocale);
});

// 初始化语言
const currentLng = i18n.language;
const dayjsLocale = DAYJS_LOCALE_MAP[currentLng] || "en";
dayjs.locale(dayjsLocale);

export const formatLastSync = (date) => {
  if (!date) return i18n.t("common.neverSync");
  const format =
    DATE_FORMAT_MAP[i18n.language]?.calendar ||
    DATE_FORMAT_MAP["en-US"].calendar;
  return dayjs(date).calendar(null, format);
};

export const formatPublishDate = (date) => {
  return dayjs(date).fromNow(true);
};

export const generateReadableDate = (dateString) => {
  const format =
    DATE_FORMAT_MAP[i18n.language]?.fullDate ||
    DATE_FORMAT_MAP["en-US"].fullDate;
  return dayjs(dateString).format(format);
};

export const formatDate = (dateString) =>
  dayjs(dateString).format("YYYY-MM-DD");
