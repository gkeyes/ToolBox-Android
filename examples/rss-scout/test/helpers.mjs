import fs from 'node:fs';
import vm from 'node:vm';
import { JSDOM } from 'jsdom';

const root = new URL('../', import.meta.url);

export function source(name) {
  return fs.readFileSync(new URL(name, root), 'utf8');
}

// Loads the tool's classic scripts into an isolated context that has the browser globals they use.
export function loadScripts(names, extra = {}) {
  const { window } = new JSDOM('');
  const ctx = { URL, URLSearchParams, DOMException, TextDecoder, AbortController, console, DOMParser: window.DOMParser, ...extra };
  ctx.globalThis = ctx;
  ctx.window = ctx;
  vm.createContext(ctx);
  for (const name of names) vm.runInContext(source(name), ctx, { filename: name });
  return ctx;
}

// Plain-object copy so values from the script context compare with deepStrictEqual.
export function plain(value) {
  return JSON.parse(JSON.stringify(value));
}
