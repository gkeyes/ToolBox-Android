import http from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const directory = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(directory, "../web");
const mime = { ".html": "text/html; charset=utf-8", ".mjs": "text/javascript; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml", ".png": "image/png", ".json": "application/json; charset=utf-8", ".txt": "text/plain; charset=utf-8" };
const server = http.createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, "http://127.0.0.1").pathname);
    const target = pathname === "/__preview/bridge.js" ? path.join(directory, "bridge.js") : path.resolve(root, `.${pathname === "/" ? "/index.html" : pathname}`);
    if (pathname !== "/__preview/bridge.js" && !target.startsWith(`${root}/`)) { response.writeHead(403).end(); return; }
    let body = await readFile(target);
    if (target.endsWith("/index.html")) body = Buffer.from(body.toString().replace('<script type="module" src="app.mjs">', '<script src="/__preview/bridge.js"></script>\n  <script type="module" src="app.mjs">'));
    response.writeHead(200, { "Content-Type": mime[path.extname(target)] || "application/octet-stream", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer" });
    response.end(body);
  } catch { response.writeHead(404).end("Not found"); }
});
server.listen(0, "127.0.0.1", () => console.log(`Preview: http://127.0.0.1:${server.address().port}`));
