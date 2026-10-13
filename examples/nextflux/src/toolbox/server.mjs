// The single Miniflux server this tool is bound to. Every module that needs
// the origin (API client, media/proxy resolution, login) imports it from here.
export const SERVER_URL = "https://miniflux.xiaochen.win";
export const SERVER_HOST = new URL(SERVER_URL).host;
// Base for resolving the server's own relative /proxy/ and /media/v1/ paths.
export const SERVER_BASE = `${SERVER_URL}/`;
