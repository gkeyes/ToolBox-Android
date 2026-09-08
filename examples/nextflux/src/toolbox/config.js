// Predeclared providers skip the new-domain prompt. Other HTTPS providers use
// ToolBox 0.3.12 per-tool authorizeDomain/listDomains consent; the native proxy
// remains authoritative for every request and redirect.
export const AI_ALLOWED_ORIGINS = ["https://api.openai.com"];
export const EXTRA_NETWORK_HOSTS = ["fonts.googleapis.com", "fonts.gstatic.com", "cdn.jsdelivr.net", "itunes.apple.com"];
