# TBX 主题继承

## 用户操作

更新宿主，并通过原有导入流程更新 NextFlux 1.0.8。NextFlux 外观设置选择“跟随系统”；在 ToolBox 内，这表示跟随宿主的实际明暗模式。工具显式选择浅色、深色或独立配色时，仍以工具自己的选择为准。无需清除工具数据、退出账号或重新安装宿主。

## 实现边界

宿主 Compose 的颜色变化不会自动改变 WebView 的 Android 主题。运行时现在使用独立的 RuntimeWebViewThemeContext，按宿主实际主题设置 Android 明暗主题和 Configuration.uiMode；RuntimeSessionManager 监听现有 DataStore 设置，并通过 ToolBoxApplication 接收系统配置变化，将更新分发给已存在的前台及后台 WebView。

六种宿主模式 SYSTEM、LIGHT、DARK、MONET_SYSTEM、MONET_LIGHT、MONET_DARK 映射到一致的网页明暗偏好。网页通过标准 prefers-color-scheme CSS 查询和 matchMedia 的 change 事件感知变化。不新增 ToolBox JS API，不注入覆盖工具配色的 CSS，也不重载网页来切换主题；运行中的页面、阅读位置和内存状态不因此被主动丢弃。

NextFlux 的 systemDark 状态改为响应式 atom，themeState 由工具自身选择和该 atom 派生。首次启动、系统变化、工具内切回跟随系统及监听器清理均沿用同一状态路径。其他支持 prefers-color-scheme 的 TBX 也能使用宿主修复；写死浅色的工具仍需要自身适配，不能声称宿主为所有 HTML 自动生成深色设计。

这里同步的是明暗偏好，不把 Android 动态取色调色板强行应用到第三方 HTML。NextFlux 自己的主题色仍保留。

## 验证

- RuntimeThemeModeTest：六种模式与系统明暗组合。
- RuntimeThemeInheritanceTest：真实 WebView 首次启动、已有页面切换、事件分发及页面实例/状态保持。
- NextFlux test/theme-inheritance.test.mjs：响应式状态、显式配色优先、幂等初始化和清理。
- NextFlux test/browser/theme-inheritance.spec.mjs：真实 Chromium 页面行为，不使用截图比较。

手动检查：在 NextFlux 选择跟随系统，分别切换宿主深色/浅色/跟随系统，再切换 Android 深色模式；打开一篇文章后重复操作，检查配色变化、阅读位置和登录状态。模拟器/CI 不等于 HyperOS 真机验证。
