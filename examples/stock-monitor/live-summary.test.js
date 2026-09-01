"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { buildLiveRequest } = require("./live-summary.js");

test("live notification represents every enabled stock without repeating the primary name", () => {
  const request = buildLiveRequest({
    sessionId: "session-a",
    loading: false,
    now: () => 3_000,
    items: [
      { enabled: true, name: "保变电气", symbol: "600550", price: 11.03, changePct: 0.27, fetchedAt: 1_000, provider: "tencent" },
      { enabled: true, name: "邯郸钢铁", symbol: "600001", price: 5.29, changePct: 0, fetchedAt: 2_000, provider: "tencent" },
    ],
    formatPrice: (price) => price.toFixed(2),
    formatPercent: (value) => `${value > 0 ? "+" : ""}${value.toFixed(2)}%`,
    quoteTimeLabel: (item) => `更新 ${item.fetchedAt}`,
    sourceLabel: () => "腾讯行情",
  });

  assert.equal(request.title, "行情哨兵 · 2 只");
  assert.equal(request.primaryText, "保变电气 11.03 +0.27%");
  assert.match(request.secondaryText, /邯郸钢铁 5\.29 0\.00%/);
  assert.equal((request.body.match(/保变电气/g) || []).length, 1);
  assert.equal((request.body.match(/邯郸钢铁/g) || []).length, 1);
});
