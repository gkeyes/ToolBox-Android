import { atom } from "nanostores";

// Fixture data only; no account, database, token, server or native bridge.
export const activeArticle = atom({ id: 42, title: "Fixture article", url: "https://example.invalid/article", status: "read", starred: 1 });
export const filteredArticles = atom([{ id: 41 }, activeArticle.get(), { id: 43 }]);
export const loadingOriginContent = atom(false);
export const hasIntegrations = atom(false);
export const settingsState = atom({ aiApiKey: "fixture", floatingSidebar: false });
export const settingsModalOpen = atom(false);
export const currentThemeMode = atom("light");
export const calls = [];
export const handleMarkStatus = (article) => calls.push(["status", article.id]);
export const handleMarkRead = (article) => calls.push(["read", article.id]);
export const handleToggleStar = (article) => calls.push(["star", article.id]);
export const handleToggleContent = (article) => calls.push(["content", article.id]);
export const shareText = async (text) => calls.push(["share", text]);
export const summarizeArticleStream = (_, { onChunk, onDone }) => { onChunk("Fixture summary."); onDone(); };
export default { saveToThirdParty: async (id) => calls.push(["save", id]) };
