// The browser runs the production dist and its real module Worker. Only the
// native ToolBox boundary is replaced; every article read, write, migration,
// query, sanitizer, image lease and React component remains production code.
export async function installFixture(page, options = {}) {
  const blocked = [];
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await page.route("**/*", (route) => {
    const url = route.request().url();
    if (url.startsWith("http://127.0.0.1:4173/") || /^(blob:|data:)/.test(url)) return route.continue();
    blocked.push(url);
    return route.abort("blockedbyclient");
  });
  await page.addInitScript(({ reduceMotion = true, markAsReadOnScroll = false, aiEnabled = false, hasIntegrations = false } = {}) => {
    const server = "https://miniflux.xiaochen.win";
    const aiServer = "https://fixture-ai.example";
    const clone = (value) => structuredClone(value);
    const state = {
      workerUrls: [], calls: [], results: [], held: [], holds: [],
      storageCalls: [], network: [], unexpectedNetwork: [], revoked: [],
      sanitizations: {}, createdBlobs: [], streams: [], heldNetwork: [],
    };
    window.__nextfluxTest = state;
    const canvas = document.createElement("canvas");
    canvas.width = 640;
    canvas.height = 360;
    const context = canvas.getContext("2d");
    context.fillStyle = "#2873a6";
    context.fillRect(0, 0, 640, 360);
    const png = canvas.toDataURL("image/png").split(",")[1];
    const timestamp = new Date().toISOString();
    const body = (id, original = false) => {
      const marker = `FIXTURE_${original ? "ORIGINAL" : "BODY"}_${id}`;
      const count = id >= 94 ? 60 : 2;
      const image = id >= 94 ? `<img src="${server}/proxy/fixture/image${id}" alt="Fixture image ${id}">` : "";
      return `<p>${marker}</p>${image}${Array.from({ length: count }, (_, index) =>
        `<p>Paragraph ${index + 1}: A complete synthetic article for reading, scrolling, image layout and cache validation. All words are retained.</p>`).join("")}<p>END_${marker}</p>`;
    };
    const articles = Array.from({ length: 96 }, (_, index) => {
      const id = index + 1;
      return {
        id, feedId: 1, title: `Article ${id}`, author: "Fixture author",
        url: `https://example.org/articles/${id}`, content: body(id),
        status: "unread", starred: id % 4 === 0 ? 1 : 0,
        published_at: new Date(Date.UTC(2026, 0, 1, 0, id)).toISOString(),
        created_at: new Date(Date.UTC(2026, 0, 1, 0, id)).toISOString(),
        changed_at: timestamp, reading_time: 2, enclosures: [],
      };
    });
    const feeds = [{ id: 1, title: "Fixture Feed", url: "https://example.org/feed.xml", site_url: "https://example.org", categoryId: 1, hide_globally: false }];
    const categories = [{ id: 1, title: "Fixture category" }];
    let memory = new Map();
    const secure = new Map();
    const legacyTables = { articles, feeds, categories, feedIcons: [], meta: { lastSyncTime: timestamp } };
    const manifest = { version: 2, generation: 1, tables: {} };
    for (const [name, value] of Object.entries(legacyTables)) {
      const serialized = JSON.stringify(value);
      const keys = [];
      for (let index = 0; index < serialized.length; index += 48 * 1024) {
        const key = `nextflux.cache.v1.${name}.1.${keys.length}`;
        memory.set(key, serialized.slice(index, index + 48 * 1024));
        keys.push(key);
      }
      manifest.tables[name] = { codec: "json", keys };
    }
    memory.set("nextflux.cache.v1.manifest", manifest);
    memory.set("nextflux.preferences.v1", {
      language: "en-US",
      settings: JSON.stringify({
        syncInterval: "0", showUnreadByDefault: true, reduceMotion,
        markAsReadOnScroll, cardImageSize: "none", showFavicon: false,
        fontFamily: "system-ui", textPreviewLines: 2,
        ...(aiEnabled ? { aiBaseUrl: `${aiServer}/v1`, aiModel: "fixture-model", aiPrompt: "Summarize this synthetic test article." } : {}),
      }),
    });
    secure.set("nextflux.auth", {
      serverUrl: server, userId: 1, username: "fixture-user",
      authType: "token", token: "fixture-token-not-a-real-credential",
    });
    if (aiEnabled) secure.set("nextflux.ai-key.v1", "fixture-ai-key-not-a-real-credential");
    state.storageRoot = () => clone(memory.get("nextflux.cache.v3.root") ?? null);
    const storage = {
      async get(key) { state.storageCalls.push({ method: "get", keys: [key] }); return clone(memory.get(key) ?? null); },
      async getMany(keys) {
        if (keys.length > 256) throw new Error("Fixture: storage batch exceeds 256 keys");
        state.storageCalls.push({ method: "getMany", keys: [...keys] });
        return keys.map((key) => clone(memory.get(key) ?? null));
      },
      async set(key, value) { memory.set(key, clone(value)); },
      async remove(key) { memory.delete(key); },
      async keys(prefix = "") { return [...memory.keys()].filter((key) => key.startsWith(prefix)); },
      async apply({ set = [], remove = [] } = {}) {
        if (set.length + remove.length > 256) throw new Error("Fixture: storage batch exceeds 256 keys");
        state.storageCalls.push({ method: "apply", keys: [...set.map((entry) => entry.key), ...remove] });
        const next = new Map(memory);
        for (const { key, value } of set) next.set(key, clone(value));
        for (const key of remove) next.delete(key);
        memory = next;
      },
      secure: {
        async get(key) { return clone(secure.get(key) ?? null); },
        async set(key, value) { secure.set(key, clone(value)); },
        async remove(key) { secure.delete(key); },
      },
    };
    const response = (value, status = 200) => ({ status, bodyEncoding: "text", headers: { "content-type": "application/json" }, body: JSON.stringify(value) });
    // Pause native responses, never React state, so pending buttons are reached
    // through the same event handlers and async operations used in production.
    const networkHolds = [];
    state.holdNetworkNext = (path) => networkHolds.push(path);
    state.releaseNetwork = () => state.heldNetwork.splice(0).forEach((entry) => entry.resolve());
    const streams = new Map();
    const enqueueStream = (text, done = false) => {
      const stream = [...streams.values()].find((item) => !item.cancelled);
      if (!stream) throw new Error("Fixture: no open AI stream");
      const chunk = { data: new TextEncoder().encode(text), done };
      if (stream.waiter) {
        const resolve = stream.waiter;
        stream.waiter = null;
        resolve(chunk);
      } else stream.queue.push(chunk);
    };
    state.emitAIChunk = (content) => enqueueStream(`data: ${JSON.stringify({ choices: [{ delta: { content } }] })}\n\n`);
    state.finishAIStream = () => enqueueStream("data: [DONE]\n\n", true);
    window.ToolBox = {
      async ready() { return { hostVersion: "0.6.6", apiVersion: "1.0" }; },
      storage,
      network: {
        async request(payload) {
          const url = new URL(payload.url);
          const record = { path: url.pathname, method: payload.method || "GET" };
          state.network.push(record);
          if (url.origin !== server) {
            state.unexpectedNetwork.push(record);
            throw { code: "NETWORK_BLOCKED" };
          }
          const holdIndex = networkHolds.indexOf(url.pathname);
          if (holdIndex >= 0) {
            networkHolds.splice(holdIndex, 1);
            await new Promise((resolve) => state.heldNetwork.push({ path: url.pathname, resolve }));
          }
          if (url.pathname.startsWith("/proxy/fixture/")) return { status: 200, headers: { "content-type": "image/png" }, bodyEncoding: "base64", body: png };
          if (url.pathname === "/v1/me") return response({ id: 1, username: "fixture-user" });
          if (url.pathname === "/v1/integrations/status") return response({ has_integrations: hasIntegrations });
          if (/^\/v1\/feeds\/\d+\/icon$/.test(url.pathname)) return response({ id: 1, mime_type: "image/png", data: `image/png;base64,${png}` });
          if (url.pathname === "/v1/entries" && record.method === "PUT") {
            const change = typeof payload.body === "string" ? JSON.parse(payload.body) : payload.body;
            record.ids = [...change.entry_ids];
            record.status = change.status;
            for (const article of articles) if (change.entry_ids.includes(article.id)) article.status = change.status;
            return response(null, 204);
          }
          const bookmark = /^\/v1\/entries\/(\d+)\/bookmark$/.exec(url.pathname);
          if (bookmark) {
            const article = articles.find((item) => item.id === Number(bookmark[1]));
            article.starred = article.starred ? 0 : 1;
            return response(null, 204);
          }
          const original = /^\/v1\/entries\/(\d+)\/fetch-content$/.exec(url.pathname);
          if (original) return response({ content: body(Number(original[1]), true) });
          if (hasIntegrations && /^\/v1\/entries\/\d+\/save$/.test(url.pathname) && record.method === "POST") return response(null, 204);
          state.unexpectedNetwork.push(record);
          throw { code: "NETWORK_UNAVAILABLE" };
        },
        async openStream(payload) {
          const url = new URL(payload.url);
          const record = { method: "openStream", path: url.pathname, origin: url.origin, requestMethod: payload.method };
          if (!aiEnabled || url.origin !== aiServer || url.pathname !== "/v1/chat/completions" || payload.method !== "POST") {
            state.unexpectedNetwork.push(record);
            throw { code: "NETWORK_BLOCKED" };
          }
          const body = JSON.parse(payload.body);
          if (!body.stream || body.model !== "fixture-model") throw new Error("Fixture: expected production streaming request");
          const streamId = `fixture-stream-${streams.size + 1}`;
          streams.set(streamId, { queue: [], waiter: null, cancelled: false });
          state.streams.push({ ...record, streamId });
          return { streamId, status: 200, headers: { "content-type": "text/event-stream" } };
        },
        async readStream(streamId) {
          const stream = streams.get(streamId);
          if (!stream || stream.cancelled) throw { code: "CANCELLED" };
          state.streams.push({ method: "readStream", streamId });
          if (stream.queue.length) return stream.queue.shift();
          if (stream.waiter) throw new Error("Fixture: overlapping native stream reads");
          return new Promise((resolve) => { stream.waiter = resolve; });
        },
        async cancelStream(streamId) {
          const stream = streams.get(streamId);
          state.streams.push({ method: "cancelStream", streamId });
          if (!stream) return;
          stream.cancelled = true;
          stream.waiter?.({ data: new Uint8Array(), done: true });
          stream.waiter = null;
          stream.queue = [];
        },
      },
      ui: { async toast() {} },
      haptics: { async perform() {} },
    };

    const NativeWorker = window.Worker;
    state.holdNext = (method, match = {}) => state.holds.push({ method, match });
    state.releaseHeld = () => { const held = state.held.splice(0); held.forEach((entry) => entry.deliver()); };
    window.Worker = class ObservedWorker extends NativeWorker {
      constructor(url, options) {
        super(url, options);
        state.workerUrls.push(new URL(url, location.href).href);
        this.requests = new Map();
        this.listeners = new Map();
        super.addEventListener("message", ({ data }) => {
          if (data?.type !== "cache:result") return;
          const request = this.requests.get(data.id);
          if (!request) return;
          state.results.push({
            ...request, ok: data.ok, ids: data.value?.items?.map((item) => item.id),
            total: data.value?.total, hasMore: data.value?.hasMore,
            queryId: data.value?.queryId,
          });
        });
      }
      postMessage(data, ...transfer) {
        if (data?.type === "cache:request") {
          const args = ["readArticle", "readMetadata", "readQueryPage", "closeQuery"].includes(data.method) ? [...data.args] : [];
          const request = { id: data.id, method: data.method, args };
          this.requests.set(data.id, request);
          state.calls.push(request);
        }
        return super.postMessage(data, ...transfer);
      }
      addEventListener(type, listener, options) {
        if (type !== "message") return super.addEventListener(type, listener, options);
        const wrapped = (event) => {
          const request = this.requests.get(event.data?.id);
          const index = event.data?.type === "cache:result" ? state.holds.findIndex((hold) =>
            hold.method === request?.method &&
            (hold.match.articleId === undefined || Number(request.args[0]) === hold.match.articleId) &&
            (hold.match.page === undefined || request.args[1] === hold.match.page)) : -1;
          const deliver = () => typeof listener === "function" ? listener.call(this, event) : listener.handleEvent(event);
          if (index >= 0) {
            state.holds.splice(index, 1);
            state.held.push({ method: request.method, args: request.args, deliver });
          } else deliver();
        };
        this.listeners.set(listener, wrapped);
        return super.addEventListener(type, wrapped, options);
      }
      removeEventListener(type, listener, options) {
        const wrapped = type === "message" ? this.listeners.get(listener) : null;
        if (wrapped) this.listeners.delete(listener);
        return super.removeEventListener(type, wrapped || listener, options);
      }
    };

    const createBlob = URL.createObjectURL.bind(URL);
    const revokeBlob = URL.revokeObjectURL.bind(URL);
    URL.createObjectURL = (blob) => {
      const url = createBlob(blob);
      state.createdBlobs.push({ url, size: blob.size });
      return url;
    };
    URL.revokeObjectURL = (url) => { state.revoked.push(url); revokeBlob(url); };
    const createElement = Document.prototype.createElement;
    const html = Object.getOwnPropertyDescriptor(Element.prototype, "innerHTML");
    Document.prototype.createElement = function (name, ...args) {
      const element = createElement.call(this, name, ...args);
      if (String(name).toLowerCase() === "template") Object.defineProperty(element, "innerHTML", {
        get() { return html.get.call(this); },
        set(value) {
          const match = /FIXTURE_(?:BODY|ORIGINAL)_(\d+)/.exec(String(value));
          if (match) state.sanitizations[match[1]] = (state.sanitizations[match[1]] || 0) + 1;
          html.set.call(this, value);
        },
      });
      return element;
    };
  }, options);
  return { blocked, pageErrors };
}

export async function openFixture(page) {
  await page.goto("/");
  await page.locator('[data-article-id="96"]').waitFor();
  await page.locator("#splash-screen").waitFor({ state: "detached" });
}

export async function scrollList(page, distanceFromBottom = 0) {
  await page.locator(".v-list").evaluate((element, distance) => {
    element.scrollTop = Math.max(0, element.scrollHeight - element.clientHeight - distance);
  }, distanceFromBottom);
}

export async function settleFrames(page) {
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}
