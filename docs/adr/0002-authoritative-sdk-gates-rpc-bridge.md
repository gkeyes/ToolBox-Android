# ADR 0002：项目内 API v1 合同是 RPC 的单一来源

- 状态：Accepted（取代“等待缺失外部 d.ts”的阻断决定）
- 日期：2026-08-30

## 背景

历史资料引用的 `sdk/toolbox-api.d.ts` 不在交付包中，导致无桥运行器和所有示例原生能力长期
不可用。继续等待不可恢复的外部 hash 会把“工具真实可用”永久阻塞，也会促使 Kotlin、JS、
schema 和帮助文档各自手写一套 API。

安全约束并不要求该 API 必须来自外部文件；它要求消息桥逐次验证 origin、frame、nonce、
manifest、grant、Android 权限、手势、速率和配额。

## 决策

1. 在 `:tool-api` 维护机器可读的 ToolBox API v1 合同，作为 capability、方法、参数上限、
   manifest 映射和错误码的唯一来源。
2. Gradle 任务从该合同生成或严格校验 Kotlin descriptors、JS shim method table、
   `sdk/toolbox-api.d.ts` 和 manifest capability 枚举；生成物必须纳入仓库，CI 失败于漂移。
3. 不存在真实 production handler 的 capability 不能出现在可授予权限 UI，也不能被 shim
   伪造为成功。
4. bridge 仍唯一使用 `WebViewCompat.addWebMessageListener`，继续遵守全部 AGENTS 安全
   不变量；本 ADR 不允许 `addJavascriptInterface`、`file://`、localhost 或弱化检查。
5. Developer Help 与四个范例直接从 API v1 合同同步，避免文档与运行时分叉。

## 后果

- 阶段 2 可以实现 `ready`、toast、sha256、storage、secure storage、device basic、haptics
  和 clipboard write 的真实垂直切片。
- 旧的外部 SHA-256 不是继续开发或发布的门；它只保留为历史资料，不进入 build 逻辑。
- API 变更必须先修改 canonical 合同、生成物和最小 dispatcher 测试，再修改示例和帮助。

## 验证

CI 运行生成/差异检查；参数化 dispatcher 测试确认每个方法同时通过 capability handler、
manifest、grant、系统状态、手势与配额。exact-origin instrumentation 确认 iframe、错误 origin、
非 main-frame 和旧 nonce 无法调用任一已生成方法。
