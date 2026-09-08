# ToolBox Android

ToolBox 是 Android 13+ 的轻量 `.tbx` 小工具宿主。它导入包含 HTML/CSS/JavaScript 的
本地 ZIP 包，在唯一 HTTPS origin 的硬化 WebView 中运行，并按工具提供真实的权限开关和
可独立于 Compose 页面生命周期的通用网页运行环境。

当前重构的目标是让用户只经历：**导入、使用、授权、后台任务、删除**。选择 `.tbx` 后，宿主在
后台完成结构、完整性和签名检查；首次安装和更高版本直接进入工具列表，同版本覆盖或降级只增加
一次明确确认。失败或取消不留下待安装状态。不会出现审核会话、风险评分、签名/发布者标签、审计
日志或恢复审核页面。

## 开发基线

当前候选为 `0.6.5 (21)`，由 GitHub 使用同一签名密钥构建 release APK，可覆盖已交付版本，
并增加可即时切换的 Miuix / Liquid Glass 双主题。新安装默认 Liquid Glass 且跟随系统明暗；升级用户
保留原 Miuix 外观与颜色选择。版本升级会保留工具、权限和设置，
不需要卸载。
0.6.0 同时加入可取消的原生网络增量读取接口；依赖该接口的小工具必须声明最低宿主为 0.6.0。
0.6.1 将可配置网络超时上限扩展至 60 分钟；超过 10 分钟的声明要求最低宿主 0.6.1，默认仍为 30 秒。
0.6.2 移除运行页标题和刷新栏，仅保留左上角半透明玻璃返回按钮；内容填满系统安全区域。
0.6.3 移除运行页悬浮返回按钮，使用系统返回手势或按键退出工具；增加按工具授权的自定义 HTTPS 域名，
并提供独立 NextFlux 阅读器，包含 OpenAI 兼容 AI 摘要。
0.6.4 将联网控制统一为每工具的网络权限开关，移除域名二次授权和 DNS/IP 地址范围拦截，兼容代理 Fake-IP；最近使用改为纯图标行，可见数量随可用宽度自动适配。
0.6.5 修复存储声明未生效的固定 2 MiB 上限，普通数据按键分片保存，避免大缓存反复整体重写；超过 50 MiB 的存储声明需最低宿主 0.6.5，最大为 512 MiB。NextFlux 1.0.3 恢复上游每页 1000 篇与完整同步范围，取消缓存淘汰。
本次不改变 Room `version = 1` 的表结构，不新增数据库迁移。

设置只保留外观、后台保障、工具权限和 Developer Help。外观页统一管理界面风格、明暗、系统取色与
降低透明度；主题切换不重建 Activity、导航或正在运行的 WebView。工具详情提供打开、权限、后台任务和
删除；权限是每工具的虚拟 grant，仍必须通过 manifest、宿主 Android 权限、用户手势、配额
与 origin 校验才会生效。

## 功能切片

1. **导入、目录和权限**：SAF 导入、内部包检查、原子安装/更新、真实删除、Miuix 权限开关。
2. **安全运行时与基础 API**：exact HTTPS origin、CSP、消息桥、`ready`、toast、SHA-256、
   storage、secure storage、device basic、haptics、clipboard write；包内同源 Web Worker 可承载
   高负载前台计算，ServiceWorker 与远程 Worker 仍禁用。
3. **持续运行环境**：应用级管理器拥有 WebView、permit、bridge、timer 和位置监听；运行页只
   挂载显示层。工具主动 `background.start()` 后可在离开页面时继续工作，并在进程/重启恢复后
   接收事件；一个 `specialUse` 前台服务承载，每个会话独立通知卡，分别更新、打开和停止。
   隐藏遵循手机默认机制，超级岛实际展示和排序由系统决定。
4. **ToolBox 通用能力**：由网络权限控制的 HTTPS 请求、会话绑定的实时通知与 HyperOS 增强、前后台位置
   watch、精确闹钟，以及剪贴板、分享、SAF、快捷方式和相机。0.2 WorkManager 任务 API 冻结兼容。

仓位计算器、快速笔记、后台任务演示和通知实验室四个范例继续内置。行情哨兵作为独立 `.tbx`
交付，不加入 APK assets；宿主候选仅交付 APK、SHA256 清单和同提交测试回执，不重新发布独立小工具。

全部 10 个小工具的版本、源码及可导入包见 [小工具目录](examples/README.md)。安装包统一收录在
`examples/packages/`，包括 NextFlux、健康档案、GitHub 构建守望、2048 和稳力。

## 本地运行

需要 JDK 21 与 Android SDK 37。按当前阶段只运行最小相关验证；宿主 CI 检查入口为
`scripts/qa/run-host-gate.sh`。典型本地构建为：

```bash
./gradlew --no-daemon verifySecurityInvariants assembleDebug testDebugUnitTest
```

需要真机测试时，可在 Android 13+ 设备上安装：

```bash
./gradlew :app:installCandidate
```

`release` 关闭调试，启用 R8 代码精简/优化与资源裁剪；未使用的代码和资源被移除，四个范例与
离线帮助保留。`candidate` 使用相同优化；没有配置固定签名时会使用本机 debug key，因此不能
用它覆盖 GitHub 同签名版本。不要用 debug 构建评价页面帧性能。

GitHub Actions 在安全不变量、API 合同、静态编译、最小单元与优化构建通过后上传
`toolbox-v0.6.5-release.apk`、`SHA256SUMS.txt` 和构建回执。构建会核对 APK 不可调试、固定签名、
版本、后台任务类名与内置资源；R8 映射独立归档以便排查崩溃，不放进安装包。
系统权限、SAF、相机、通知、持续运行、后台位置、精确闹钟和 HyperOS 增强通知由用户在小米真机
上验证；自动交付流程不启动模拟器，也不把未执行的设备测试写成通过。

按用户要求，自动截图测试及其插件、PNG 基线和 CI 门禁已删除，今后不运行该测试。
`app/src/debug` 仅保留 Android Studio 手动 Compose 预览，不比较图片、不影响交付；
回执明确记录 `HOST_SCREENSHOT_VALIDATION=REMOVED_BY_USER_REQUEST`，不是视觉验收通过。

## 工程结构

```text
app/                              宿主 Compose 页面、路由、系统结果协调
core-ui/                          ToolBox/Miuix 适配层与主题
core-data/                        Room、DataStore、目录、grant、KV、任务与结果
tool-package/                     `.tbx` 检查、签名/完整性、原子安装与卸载
tool-runtime/                     exact-origin AssetLoader 与硬化 WebView
tool-api/                         API v1 合同、bridge、handler 与后台协调
docs/ToolBox_Android_技术方案.md   当前产品与安全架构基线
examples/                         四个内置范例及独立工具的源码与打包脚本
```

## 安全边界

安全不变量以 [`AGENTS.md`](AGENTS.md) 为准：不使用 `addJavascriptInterface`、`file://` 或
localhost；不申请广泛存储、应用列表、无障碍、短信、联系人或 root 权限；所有 WebView 调用
验证 origin/frame/nonce/声明/grant/系统权限/手势/限额；网络经 ToolBox 原生 HTTPS 代理，
由每工具网络权限控制，不限制目的域名或解析地址，并支持 HTTPS 重定向。保留 TLS 证书校验、
跨来源跳转清除凭据、超时、响应上限及撤权取消。
