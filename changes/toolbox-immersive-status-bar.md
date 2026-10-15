---
bump: minor
---
小工具可以在 manifest 里声明 ui.edgeToEdge，让页面铺到系统状态栏下面（沉浸状态栏），宿主会把状态栏高度通过 CSS 变量 --toolbox-safe-area-top 告诉页面；没有声明的小工具显示和以前完全一样。
