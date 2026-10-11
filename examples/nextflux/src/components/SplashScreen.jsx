import { useEffect } from "react";

// The splash shares the page colour, so it can leave as soon as the first real
// frame is painted: two animation frames, then a short opacity-only fade.
export default function SplashScreen() {
  useEffect(() => {
    const splashScreen = document.getElementById("splash-screen");
    if (!splashScreen) return;
    let removeTimer;
    let second;
    const first = requestAnimationFrame(() => {
      second = requestAnimationFrame(() => {
        splashScreen.classList.add("fade-out");
        removeTimer = setTimeout(() => splashScreen.remove(), 240);
      });
    });
    return () => {
      cancelAnimationFrame(first);
      if (second) cancelAnimationFrame(second);
      clearTimeout(removeTimer);
      splashScreen.remove();
    };
  }, []);

  return null;
}
