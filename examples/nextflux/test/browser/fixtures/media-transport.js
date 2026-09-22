const requests = [];

export function createMediaTransport() {
  return {
    load(source, { signal }) {
      return new Promise((resolve, reject) => {
        const request = { source, signal, resolve, reject, settled: false };
        requests.push(request);
        const abort = () => {
          request.settled = true;
          reject(Object.assign(new Error("Fixture media cancelled"), { code: "CANCELLED" }));
        };
        if (signal.aborted) abort();
        else signal.addEventListener("abort", abort, { once: true });
      });
    },
  };
}

export function pendingRequests(source) {
  return requests.filter((request) => !request.settled && (!source || request.source === source)).length;
}

export function requestCount(source) {
  return requests.filter((request) => request.source === source).length;
}

export function settleImage(source, { width = 640, height = 320, failure = null } = {}) {
  const request = requests.find((item) => item.source === source && !item.settled);
  if (!request) throw new Error(`No pending fixture image request: ${source}`);
  request.settled = true;
  if (failure === "network") request.reject(new Error("图片下载失败，请重试。"));
  else if (failure === "decode") request.resolve(new Blob(["invalid image bytes"], { type: "image/png" }));
  else {
    // A real browser decoder sets naturalWidth/naturalHeight and fires onLoad.
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><rect width="100%" height="100%" fill="#568779"/></svg>`;
    request.resolve(new Blob([svg], { type: "image/svg+xml" }));
  }
}
