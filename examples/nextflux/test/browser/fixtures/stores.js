import { atom } from "nanostores";

// No account, database, article query or network-login graph is mounted here.
export const imageGalleryActive = atom(false);
export const isModalOpen = atom(false);

// The dependency scanner can reach this alias before the per-importer hook.
// Share the same test atom as the toolbar instead of making a second entry store.
export { activeArticle } from "./controls-state.js";
