# ToolBox · Liquid Glass 重设计基线

来源：`gkeyes/ToolBox-Android` 的 `DESIGN.md`（Liquid Glass 浅色模式）。本原型保留其产品边界，只重构视觉层级、交互反馈与信息组织。

```css
:root {
  --bg: oklch(96.257% 0.00665 286.27);
  --surface: oklch(100% 0 89.88);
  --fg: oklch(22.728% 0.00382 286.09);
  --muted: oklch(48.721% 0.0079 286.10);
  --border: oklch(93.546% 0.0067 286.27);
  --accent: oklch(51.961% 0.17756 257.61);
}
```

- **Display：** `-apple-system`, `BlinkMacSystemFont`, `SF Pro Display`, `system-ui`, sans-serif
- **Body：** `-apple-system`, `BlinkMacSystemFont`, `SF Pro Text`, `system-ui`, sans-serif
- **Mono：** `ui-monospace`, `SF Mono`, `Menlo`, monospace

视觉规则：

1. 一级页面只保留一个大标题与一个明确入口；信息区使用成组底板，而非逐行卡片。
2. 内容层保持清晰白色，导航与局部悬浮操作才使用半透明玻璃材质。
3. ToolBox 蓝只表达主要动作、选择与焦点；运行与危险状态使用语义色，不用于装饰。
4. 行高和点击区优先满足可访问性，所有可操作行不低于 48dp。
5. 动效克制为 160–180ms 的位移/按压反馈，不对整页做缩放或透明度过渡。
