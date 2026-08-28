# ADR 0002：权威 SDK 输入恢复前暂缓 RPC 桥

- 状态：Accepted
- 日期：2026-08-28

## 背景

阶段 3 的完整目标包括独立 Origin、硬化 WebView、RPC 消息桥，以及与
`sdk/toolbox-api.d.ts` 一致的 `ready/ui.toast/storage/haptics` API。当前资料包中
缺少该声明文件；`sdk/PROVENANCE.md` 给出的唯一可接受校验锚点是：

```text
SHA-256  7792a14e810d77d2e8c1368fc4cb38e2b4d304d8b4d701bfc082e2ef6dfb4421
Path     sdk/toolbox-api.d.ts
```

技术方案明确要求 API 声明作为桥协议来源，且禁止从技术方案或示例反向推导。
因此，在声明恢复并验证前实现桥协议会把未经授权的猜测变成公开接口，违反来源和
安全边界。

## 决策

阶段 3 允许的唯一调整是拆开“安全渲染器”和“原生 API 桥”两个交付门：

1. 立即实现不依赖 SDK 声明的硬化无桥渲染器：每工具唯一 exact HTTPS Origin、
   `WebViewAssetLoader`、CSP、安全响应头、导航限制、禁用文件/内容访问和
   renderer 崩溃恢复。
2. 暂缓 RPC、JS shim、`ready/ui.toast/storage/haptics` handler、声明生成物及
   依赖声明的范例工具原生能力。不得用占位实现、静默降级或伪造成功结果替代它们。
3. 工具需要原生能力时，宿主必须在可见界面明确报告“ToolBox 原生 API 不可用”，
   并给出可操作的原因或重试提示；离线 HTML/CSS/JS 渲染仍可继续。
4. 以上调整不改变任何 `AGENTS.md`、`CODEX_PROMPT.md` 或技术方案中的安全不变量，
   尤其不允许 `addJavascriptInterface`、`file://`、localhost 服务或放宽导航/来源校验。

## 安全后果

- 正面：没有未经证实的协议形状、能力名称或授权语义进入可发布 APK；无桥状态减少
  可被利用的入口，并保留独立 Origin、CSP 和 WebView 隔离边界。
- 代价：在 SDK 输入恢复前，依赖 ToolBox 原生 API 的工具不能宣称功能可用；阶段 3
  和完整端到端验收保持未完成状态。
- 风险控制：无桥渲染器仍须通过 exact Origin、主 frame、危险 scheme、远程资源和
  renderer 崩溃测试；任何“原生 API 不可用”状态不得绕过权限或网络策略。

## 拒绝的备选方案

- 根据技术方案、`USAGE.md` 或现有示例自行推导 `toolbox-api.d.ts`：拒绝，违反
  `sdk/PROVENANCE.md` 的来源约束，且可能造成协议不兼容和错误授权。
- 先实现一个兼容桥或空实现，再以后替换：拒绝，容易让工具误以为能力已执行，无法
  证明权限、审计、限流和返回值语义正确。
- 为了“完整运行”放宽 WebView 安全设置或使用 `file://`/localhost：拒绝，直接违反
  不可协商的安全不变量。

## 重新进入条件

只有以下条件全部满足，才能恢复 RPC/API 工作：

1. 从原始交付方或可验证不可变上游恢复 `sdk/toolbox-api.d.ts`；
2. 文件 SHA-256 与 `7792a14e810d77d2e8c1368fc4cb38e2b4d304d8b4d701bfc082e2ef6dfb4421`
   完全一致；
3. 在 `sdk/PROVENANCE.md` 记录来源位置、获取日期和不可变标识；
4. 基于该文件实现桥和 shim，补齐来源、frame、nonce、声明、授权、Android 权限、
   用户手势、限流和配额校验，并完成对应安全测试与 API 文档同步。

## 测试与验收影响

当前交付可验收的范围是硬化无桥渲染：验证唯一 Origin、CSP/响应头、危险导航阻断、
文件/内容访问关闭和 renderer 恢复；每项测试仍须在 `TESTING.md` 记录理由、方法和
预期结果。RPC、shim、ToolBox API 调用、权限联动和“导入 → 安装 → 运行 → API”
完整链路在权威声明恢复前必须标记为阻塞，不得以静态占位页或伪造返回值通过验收。
