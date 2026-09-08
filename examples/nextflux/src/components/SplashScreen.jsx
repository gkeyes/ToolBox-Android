import { useEffect } from "react";

export default function SplashScreen() {
  useEffect(() => {
    const splashScreen = document.getElementById("splash-screen");
    if (!splashScreen) return;

    // 添加淡出动画类
    setTimeout(() => {
      splashScreen.classList.add("fade-out");
    }, 800);

    // 动画结束后移除元素
    setTimeout(() => {
      splashScreen.remove();
    }, 1200);
  }, []);

  return null;
}
