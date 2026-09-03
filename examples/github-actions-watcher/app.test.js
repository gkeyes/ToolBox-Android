"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const model = require("./github-model.js");
const source = fs.readFileSync(path.join(__dirname, "app.js"), "utf8");

test("repository button preserves native quota, timeout and permission errors", async () => {
  for (const code of ["QUOTA_EXCEEDED", "NETWORK_TIMEOUT", "NETWORK_UNAVAILABLE", "NETWORK_BLOCKED", "PERMISSION_DENIED"]) {
    const nodes = new Map();
    const node = id => {
      if (!nodes.has(id)) nodes.set(id, {
        value: "", textContent: "", hidden: false, disabled: false, dataset: {},
        handlers: new Map(),
        addEventListener(type, callback) { this.handlers.set(type, callback); },
      });
      return nodes.get(id);
    };
    const requests = [];
    const api = {
      ready: async () => ({ hostVersion: "0.3.5", apiVersion: "1.0" }),
      storage: { get: async () => null, secure: { get: async () => null } },
      network: { request: async request => {
        requests.push(request);
        throw Object.assign(new Error("specific native failure"), { code });
      } },
    };
    vm.runInNewContext(source, {
      window: { ToolBox: api, GitHubWatcherModel: model, addEventListener() {} },
      document: {
        getElementById: node,
        querySelectorAll: () => [],
        querySelector: () => ({ value: "branch" }),
        addEventListener() {},
      },
      URL, Intl,
      setTimeout: () => 1, clearTimeout() {},
      setInterval: () => 1, clearInterval() {},
    });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(node("host-caption").textContent, "ToolBox 0.3.5 · API 1.0");
    node("repo-input").value = "https://github.com/gkeyes/ToolBox-Android";

    await node("load-repository").handlers.get("click")();

    assert.equal(requests.length, 1);
    assert.equal(node("toast").textContent, `${code}: specific native failure`);
    assert.equal(requests[0].maxResponseBytes, 4_194_304);
    assert.equal(node("loading-overlay").hidden, true);
    assert.equal(node("load-repository").disabled, false);
  }
});
