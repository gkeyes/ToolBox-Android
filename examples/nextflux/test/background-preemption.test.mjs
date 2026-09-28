import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const sourceUrl = new URL("../src/stores/syncStore.js", import.meta.url);

test("opening or merely interacting with NextFlux does not preempt an active background sync", async () => {
  const source = await readFile(sourceUrl, "utf8");
  assert.doesNotMatch(source, /addEventListener\(["']visibilitychange["'][\s\S]{0,160}requestForegroundPriority/);
  assert.doesNotMatch(source, /addEventListener\(["']pointerdown["'][\s\S]{0,160}requestForegroundPriority/);
  assert.doesNotMatch(source, /addEventListener\(["']keydown["'][\s\S]{0,160}requestForegroundPriority/);
});

test("explicit foreground work can still ask a background sync to yield", async () => {
  const source = await readFile(sourceUrl, "utf8");
  assert.match(source, /export function requestForegroundPriority\(\)/);
  assert.match(source, /runAccountScopeOperation[\s\S]*?requestForegroundPriority\(\)/);
  assert.match(source, /mode === "foreground" && currentSyncMode === "background"[\s\S]*?requestForegroundPriority\(\)/);
});
