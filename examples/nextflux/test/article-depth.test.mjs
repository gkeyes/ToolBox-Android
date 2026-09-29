import assert from "node:assert/strict";
import test from "node:test";
import { articleDepthState } from "../src/hooks/articleDepth.js";

test("article depth keeps iOS-style parallax restrained and scale-free", () => {
  const closed = articleDepthState(0, 400);
  assert.equal(closed.progress, 0);
  assert.equal(closed.listX, -92);
  assert.equal(closed.dim, 0.025);
  assert.equal(closed.edgeAlpha, 0);

  const half = articleDepthState(200, 400);
  assert.equal(half.progress, 0.5);
  assert.ok(half.listX > -12 && half.listX < -11);
  assert.ok(half.dim > 0.003 && half.dim < 0.0032);
  assert.ok(half.edgeAlpha > 0.038 && half.edgeAlpha < 0.039);

  const revealed = articleDepthState(400, 400);
  assert.equal(revealed.progress, 1);
  assert.equal(revealed.listX, 0);
  assert.equal(revealed.dim, 0);
});

test("article depth clamps progress and never overshoots the background", () => {
  assert.equal(articleDepthState(-40, 400).listX, -92);
  assert.equal(articleDepthState(600, 400).listX, 0);
  assert.equal(articleDepthState(20, 0).progress, 0);
});
