import { createRoot } from "react-dom/client";
import { Button } from "@heroui/react";
import { installTouchPressCompat } from "@/toolbox/touch-press.js";
import { showLinkActions } from "@/toolbox/actions.js";
import "../../../src/index.css";

// Production compatibility layer over real HeroUI (React Aria) buttons.
installTouchPressCompat();
window.pressCounts = { more: 0, close: 0, disabled: 0 };
const count = (name) => () => { window.pressCounts[name] += 1; };
createRoot(document.getElementById("root")).render(
  <main style={{ display: "grid", gap: 24, padding: 24 }}>
    <Button onPress={count("more")}>更多</Button>
    <Button aria-label="关闭" onPress={count("close")}>×</Button>
    <Button isDisabled onPress={count("disabled")}>不可用</Button>
    <button type="button" onClick={() => showLinkActions("https://example.com/a")}>链接</button>
  </main>,
);
