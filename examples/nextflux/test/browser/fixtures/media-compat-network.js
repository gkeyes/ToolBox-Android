// Only the ToolBox native API is substituted. Media validation, stream reads,
// candidate selection, Blob leases and the browser decoder remain production.
const PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAECAYAAACzzX7wAAAAEklEQVR4nGNwq131Hx9moL0CANchTYGRtgwQAAAAAElFTkSuQmCC";
const png = Uint8Array.from(atob(PNG_BASE64), (character) => character.charCodeAt(0));
const encoder = new TextEncoder();
const cancelled = () => Object.assign(new Error("Fixture stream cancelled"), { code: "CANCELLED" });
const requests = [];
const streams = new Map();
const objectUrls = new Set();
const createdUrls = [];
const revokedUrls = [];
let nextId = 0;

function close(request, state) {
  const wasOpening = request.state === "opening";
  request.state = state;
  streams.delete(request.streamId);
  request.signal?.removeEventListener("abort", request.abort);
  if (wasOpening) request.reject(cancelled());
  for (const waiter of request.waiters.splice(0)) waiter.reject(cancelled());
}

function chunk(request) {
  const midpoint = Math.max(1, Math.floor(request.body.length / 2));
  const first = request.cursor === 0;
  const data = first ? request.body.slice(0, midpoint) : request.body.slice(midpoint);
  request.cursor += 1;
  if (!first) close(request, "completed");
  return { data, done: !first };
}

const network = {
  openStream(payload, { signal } = {}) {
    return new Promise((resolve, reject) => {
      const request = {
        source: payload.url, headers: payload.headers, method: payload.method,
        streamId: `fixture-image-${++nextId}`, state: "opening", signal,
        resolve, reject, reads: 0, cancels: 0, cursor: 0, waiters: [],
        aborted: false, hold: false, body: png,
      };
      request.abort = () => {
        request.aborted = true;
        // Before headers, the native API owns abort handling. Once a stream ID
        // is returned, the real transport must call cancelStream itself.
        if (request.state === "opening") close(request, "aborted");
      };
      requests.push(request);
      streams.set(request.streamId, request);
      if (signal?.aborted) request.abort();
      else signal?.addEventListener("abort", request.abort, { once: true });
    });
  },
  readStream(streamId) {
    const request = streams.get(streamId);
    if (!request) return Promise.reject(cancelled());
    request.reads += 1;
    if (request.hold) return new Promise((resolve, reject) => request.waiters.push({ resolve, reject }));
    return Promise.resolve(chunk(request));
  },
  cancelStream(streamId) {
    const request = requests.find((item) => item.streamId === streamId);
    if (request) {
      request.cancels += 1;
      if (streams.has(streamId)) close(request, "cancelled");
    }
    return Promise.resolve();
  },
};

export function installNativeMediaFixture() {
  window.__nextfluxNativeMediaFixture = true;
  window.ToolBox = { network };
  const create = URL.createObjectURL.bind(URL);
  const revoke = URL.revokeObjectURL.bind(URL);
  URL.createObjectURL = (blob) => {
    const url = create(blob);
    objectUrls.add(url);
    createdUrls.push(url);
    return url;
  };
  URL.revokeObjectURL = (url) => {
    objectUrls.delete(url);
    revokedUrls.push(url);
    revoke(url);
  };
}

export function pendingRequests(source) {
  return requests.filter((request) => request.state === "opening" && (!source || request.source === source)).length;
}

export function requestCount(source) {
  return requests.filter((request) => request.source === source).length;
}

export function respond(source, { status = 200, type = "image/png", body = "png", hold = false } = {}) {
  const request = requests.find((item) => item.source === source && item.state === "opening");
  if (!request) throw new Error(`No pending fixture native request: ${source}`);
  request.body = body === "png" ? png
    : encoder.encode(body === "html" ? "<!doctype html><html><body>Origin returned HTML</body></html>" : "invalid image bytes");
  request.hold = hold;
  request.state = "streaming";
  request.resolve({ streamId: request.streamId, status, headers: { "Content-Type": type } });
}

export function resumeReads(source) {
  const request = [...streams.values()].find((item) => item.source === source);
  if (!request) throw new Error(`No fixture native stream: ${source}`);
  request.hold = false;
  for (const waiter of request.waiters.splice(0)) waiter.resolve(chunk(request));
}

export function snapshot() {
  return {
    activeStreams: streams.size,
    waitingReads: [...streams.values()].reduce((count, request) => count + request.waiters.length, 0),
    activeObjectUrls: [...objectUrls],
    createdUrls: [...createdUrls],
    revokedUrls: [...revokedUrls],
    requests: requests.map(({ source, headers, method, state, reads, cancels, aborted }) => ({ source, headers, method, state, reads, cancels, aborted })),
  };
}
