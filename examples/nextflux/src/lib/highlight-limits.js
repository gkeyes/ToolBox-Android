// Syntax color is optional; reading/copying is never truncated. The SAX reader
// still mounts every line of larger examples in frames. This bounds both Shiki
// work and the resulting optional React/DOM update, including token-dense code.
export const MAX_HIGHLIGHT_CODE_CHARACTERS = 16 * 1024;
export const MAX_HIGHLIGHT_HTML_CHARACTERS = 64 * 1024;
