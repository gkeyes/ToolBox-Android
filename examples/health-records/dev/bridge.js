(() => {
  const database = new Promise((resolve, reject) => {
    const request = indexedDB.open("health-records-development-preview", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("kv");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  async function access(mode, action) {
    const db = await database;
    return new Promise((resolve, reject) => {
      const transaction = db.transaction("kv", mode), request = action(transaction.objectStore("kv"));
      transaction.oncomplete = () => resolve(request.result ?? null);
      transaction.onerror = () => reject({ code: "INTERNAL_ERROR" });
      transaction.onabort = () => reject({ code: "INTERNAL_ERROR" });
    });
  }
  function storage(prefix) {
    return {
      get: (key) => access("readonly", (s) => s.get(prefix + key)),
      set: (key, value) => access("readwrite", (s) => s.put(value, prefix + key)),
      remove: (key) => access("readwrite", (s) => s.delete(prefix + key)),
      keys: async () => (await access("readonly", (s) => s.getAllKeys())).filter((key) => key.startsWith(prefix)).map((key) => key.slice(prefix.length)),
    };
  }
  const normal = storage("normal:"), tokens = new Map();
  window.ToolBox = {
    ready: async () => { await database; return { apiVersion: "1.0", hostVersion: "0.3.3-preview", toolId: "io.toolbox.healthrecords", generation: "development-only" }; },
    storage: { ...normal, secure: storage("preview-key:") },
    network: { request: async () => { throw { code: "UNSUPPORTED" }; } },
    files: {
      open: (mimeTypes = []) => new Promise((resolve) => {
        const input = document.createElement("input"); input.type = "file"; input.accept = mimeTypes.join(","); input.hidden = true;
        input.addEventListener("cancel", () => { input.remove(); resolve(null); }, { once: true });
        input.addEventListener("change", () => {
          const file = input.files[0]; input.remove(); if (!file) { resolve(null); return; }
          const token = crypto.randomUUID(); tokens.set(token, file);
          resolve({ token, name: file.name, mimeType: file.type || "application/octet-stream", size: file.size });
        }, { once: true });
        document.body.append(input); input.click();
      }),
      read: async (token) => {
        const file = tokens.get(token); if (!file) throw { code: "NOT_FOUND" }; tokens.delete(token);
        return new Uint8Array(await file.arrayBuffer());
      },
      save: async (name, mimeType, content) => {
        const blob = new Blob([content], { type: mimeType }), url = URL.createObjectURL(blob), link = document.createElement("a");
        link.href = url; link.download = name; document.body.append(link); link.click(); link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 10000);
        return { token: crypto.randomUUID(), name, mimeType, size: blob.size };
      },
    },
  };
  document.title = "健康档案 · 本地开发预览";
})();
