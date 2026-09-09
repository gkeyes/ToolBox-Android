# ToolBox JS API 声明来源记录

状态：`REPOSITORY_CANONICAL_CONTRACT`

当前声明以仓库中的 [API v1 合同](../tool-api/src/main/resources/toolbox-api-v1.json) 为维护来源，
与 [技术方案第 6.1 节](../docs/ToolBox_Android_技术方案.md#61-单一协议来源) 一致。它是当前宿主的
TypeScript 接口声明，不宣称恢复了早期资料包中缺失的原始 SDK。

合同同步包括：Kotlin capability/method descriptors、`sdk/toolbox-api.d.ts`、manifest schema、
包检查器和运行时方法表。合同新增 `browser` / `browser.open` 及可选错误字段 `retryAfterMs`；
API 兼容级别仍为 `1.0`，原有错误码与必需的 `code`、`message` 保持不变。

每次修改合同后，对 JSON 文件的原始 UTF-8 字节计算 SHA-256，将结果同步到
`ToolBoxApiV1.CANONICAL_SHA256` 和 `ToolBoxContractSha256`。该值是合同文件摘要，
不是 TypeScript 文件自身的摘要。再同步 [完整手册](help/manual.md) 内嵌的声明。
`:tool-api:verifyToolBoxApiContract` 检查合同、声明、schema 和包检查器的一致性；
`node scripts/check-developer-help.mjs` 检查手册内嵌内容并执行既有模拟桥范例。
这些命令是维护入口，列出命令不代表本次已运行或通过。

历史记录：2026-08-27 曾未找到任务书要求的原始 `sdk/toolbox-api.d.ts`。当时资料包提供的
校验锚点如下，仅用于追溯原始资料，不作为当前仓库合同的阻断条件：

```text
SHA-256  7792a14e810d77d2e8c1368fc4cb38e2b4d304d8b4d701bfc082e2ef6dfb4421
Path     sdk/toolbox-api.d.ts
```

今后如取得原始交付文件，应另行记录来源、获取日期、不可变标识和文件摘要，保留它与当前
仓库声明的区别；不能用历史哈希声称当前声明已获原始交付方验证。
