import { atom } from "nanostores";

// No account, database, article query or network-login graph is mounted here.
export const imageGalleryActive = atom(false);
export const isModalOpen = atom(false);

// The dependency scanner can reach this alias before the per-importer hook.
// Share the same test atom as the toolbar instead of making a second entry store.
export { activeArticle, filteredArticles } from "./controls-state.js";

// The sidebar's board icon imports its store, but this reading fixture has no
// account or server writes. The production board tests exercise the real action.
export const markArticlesAsRead = async () => 0;
