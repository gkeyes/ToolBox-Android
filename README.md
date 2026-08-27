# ToolBox Android

ToolBox 是 Android 13+ 的原生小工具宿主。本仓库当前只完成 `CODEX_PROMPT.md` 的阶段 1：工程骨架、Miuix 适配层、类型安全导航和静态宿主界面。包检查、安装、WebView 与 JS API 尚未实现。

## 阶段 1 内容

- Gradle 模块：`:app`、`:core-ui`、`:core-data`、`:tool-package`、`:tool-runtime`、`:tool-api`
- `core-ui` 封装 Miuix 主题、顶栏、导航、卡片、按钮、搜索、风险与设置组件
- Navigation 3 类型安全路由与首页、工具管理、导入审核、权限中心、设置、运行外壳
- 紧凑宽度底部导航和中宽度导航栏布局
- Compose Preview、单元测试、instrumentation 导航用例和截图 golden
- CI 中的安全不变量扫描、Debug 构建、测试、Lint 与截图验证
- 测试准入与逐项理由/方法/预期结果见 `TESTING.md`

阶段 1 中所有安装和运行入口均为禁用或静态占位，不会假装执行后续阶段的安全敏感行为。

## 本地运行

要求 JDK 21 与 Android SDK 37。构建及验证：

```bash
./gradlew --no-daemon \
  verifySecurityInvariants \
  assembleDebug \
  :app:assembleDebugAndroidTest \
  testDebugUnitTest \
  lintDebug \
  validateDebugScreenshotTest
```

连接 Android 13+ 设备或模拟器后安装：

```bash
./gradlew :app:installDebug
```

连接设备后执行宿主导航 instrumentation 测试：

```bash
./gradlew :app:connectedDebugAndroidTest
```

更新截图基准只应在人工检查渲染差异后执行：

```bash
./gradlew updateDebugScreenshotTest
```

## 目录

```text
app/                              宿主入口、路由、六个静态页面与测试
core-ui/                          ToolBox/Miuix 适配层和主题
core-data/                        阶段 1 空骨架
tool-package/                     阶段 1 空骨架
tool-runtime/                     阶段 1 空骨架
tool-api/                         阶段 1 空骨架
docs/ToolBox_Android_技术方案.md   产品、架构、安全与验收方案
design/                           明亮模式设计板与页面参考图
schema/manifest.schema.json       `.tbx` manifest JSON Schema
examples/position-calculator.tbx  后续阶段验收夹具
```

## 已知输入缺口

资料包的 `SHA256SUMS.txt` 和任务书都引用 `sdk/toolbox-api.d.ts`，但原始 ZIP 与当前目录均不包含该文件。它不阻塞静态阶段 1，但在阶段 3 实现 JS API 前必须恢复并校验原声明，不能根据文档自行臆造接口。

## 安全边界

安全不变量见 `AGENTS.md`。当前阶段不包含 WebView 或导入执行逻辑；后续实现仍必须保持唯一 exact HTTPS origin、来源校验消息桥、原子安装、网络代理与危险权限禁用等约束。辅助进程只用于崩溃和内存隔离，不构成独立 UID 沙箱。
