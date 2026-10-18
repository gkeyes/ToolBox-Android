import { atom } from "nanostores";

// Account catalog and native-service state only; the notification row is real.
export const feeds = atom([
  { id: "12", title: "关注的订阅一" },
  { id: 34, title: "未关注的订阅" },
  { id: 56, title: "关注的订阅二" },
]);
export const watchedFeeds = atom([12, 56, 404]);
export const continuousSync = atom(true);
export const feedNotificationsAvailable = () => true;
