import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const SRC = new URL("../src/", import.meta.url).pathname;
// HeroUI React components whose style file name is not their kebab-case name,
// or that bring no style file of their own.
const STYLE = {
  TextField: ["textfield"], TextArea: ["textarea"], ListBoxItem: ["list-box-item"],
  FieldGroup: ["fieldset"], Dropdown: ["dropdown", "menu", "menu-item", "popover"],
  Select: ["select", "list-box", "popover"], Form: [], cn: [],
};
const kebab = (name) => name.replace(/([a-z])([A-Z])/g, "$1-$2").toLowerCase();

function sources(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? sources(path) : /\.(jsx?|mjs)$/.test(name) ? [path] : [];
  });
}

// design/heroui.css loads only some HeroUI component styles: a component used
// without its stylesheet renders unstyled.
test("every HeroUI component in use has its stylesheet imported", () => {
  const css = readFileSync(join(SRC, "design/heroui.css"), "utf8");
  const imported = new Set([...css.matchAll(/@heroui\/styles\/components\/([a-z-]+)\.css/g)].map((match) => match[1]));
  const used = new Set();
  for (const file of sources(SRC)) {
    for (const match of readFileSync(file, "utf8").matchAll(/import\s*\{([^}]+)\}\s*from\s*"@heroui\/react"/g)) {
      for (const name of match[1].split(",")) if (name.trim()) used.add(name.trim().split(/\s+as\s+/)[0]);
    }
  }
  const missing = [...used].flatMap((name) => (STYLE[name] ?? [kebab(name)]).filter((style) => !imported.has(style)).map((style) => `${name} → ${style}.css`));
  assert.deepEqual(missing, []);
});
