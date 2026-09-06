# AI 填写格式

格式由 `web/ai-contract.mjs` 生成，不让模型自行设计字段。每次请求都发送相应的 JSON 填写模板、字段类型、长度与数量上限；名称匹配还发送本次可用 ID。

## 最新摘要 / 历史变化

```json
{"summary":"","sections":[{"title":"","text":""}]}
```

只填摘要和章节文字，最多 12 章，不增加其他字段。字符串中的引号、换行和反斜杠按程序生成的转义范例填写。

完整历史由程序分组，不再把全部历史挤进一次请求。每组沿用上面模板，但 `sections` 固定 **1 项**、摘要最多 100 字符、标题最多 120 字符、正文最多 1800 字符；约束同时写入提示、函数参数 schema 和解析校验。所有批次完成后直接拼接章节和摘要，不另让 AI 重新设计格式或合并内容。发送前给出组数和调用费用，失败仅在用户同意后重试未完成组，不丢失此前已完成组，也不保存不完整结果。

每组数据的 `columns` 对应 `points` 中的日期、类型、原始结果和原始参考范围，项目名、标本和原单位在 `series` 中保留。相同日期的记录不去重，缺失单位不补写；长序列分段时带前一段末点作为明确标注的衔接上下文。格式压缩不是删减历史数据。

## 报告图片识别

```json
{"date":"","type":"","items":[{"name":"","value":"","unit":"","normal":""}]}
```

`type` 只能选 `blood`、`urine`、`blood_bio`、`urine_bio`。结果始终是字符串，例如 `"5.3%"`；日期、单位和参考范围缺失就留空，不凭截图时间或常识补写。

## 识别后的名称匹配

```json
{"matches":[{"sourceId":"i0","targetId":""},{"sourceId":"i1","targetId":""}]}
```

这是示例。实际 `sourceId` 逐项由程序预填；AI 只从该项目所在分组的已有候选 ID 中选择 `targetId`。无法确定就留空，保留识别原名。没有结果、日期或参考范围字段，不可能通过此功能更新它们。

## 名称整理 / 类型整理

```json
{"suggestions":[{"sourceId":"m0","targetId":"","reason":""}]}
```

```json
{"suggestions":[{"recordId":"","type":"","evidenceItemIds":[],"reason":""}]}
```

只填写有充分依据的建议。没有确定建议时，返回 `{"suggestions":[]}`，不返回空白模板行。类型整理的证据必须来自该记录的 1–3 个实际项目。

## MiniMax 的实际提交方式

使用官方 `POST https://api.minimax.cn/v1/chat/completions` 和 `tools[].function.parameters`。每次只声明一个 `submit_health_<操作>` 格式函数；它只是结构化草稿容器，不执行命令，也不自动保存。使用 `stream:true`、`reasoning_split:true`；M3 使用 `thinking:{"type":"adaptive"}`，即开启思考。思考仅临时显示。工具调用正常结束为 `finish_reason:"tool_calls"`，此时只解析声明函数的 `arguments`，附带正文不参与结果解析。文档也允许模型不调用函数、以 `stop` 正常结束；仅在没有工具调用且正文是完整 JSON 并严格符合相同填写模板时接受，不从解释文字中截取或猜测修补 JSON。

MiniMax 续片允许空 `id/type/name` 表示沿用前片；不可变成另一个调用。JSON 语法错误、重复键、模板外字段、未知 ID、循环/重复写入仍拒绝应用，不使用猜测性修补。建议按条校验，独立有效条目保留供人工勾选；无法应用的原建议也可展开查看，真正无建议、截断和超时分别展示。

本地不使用医学名称白名单，也不要求 AI 理由逐字重复原始项目名称。程序只核验格式与实际记录对应，并保留用户要求的血样、尿样边界；方法和数量/比例差异仅显示核对提示，不删掉 AI 的建议。AI 理由与实际引用的项目并列显示，内容判断由用户核对；通过格式检查不等于医学内容正确。

Gemini 保留现有 JSON 输出模式并使用同一填写模板，不切换服务、不借用 MiniMax 密钥。本次 MiniMax 接口验证不能视为 Gemini 真实调用验证。

依据：[MiniMax OpenAI 兼容接口](https://platform.minimaxi.com/docs/api-reference/text-openai-api)、[M3 工具参数说明](https://platform.minimaxi.com/docs/guides/text-m3-function-call)。未加入文档未确认的 `response_format`、`tool_choice` 或 `strict` 参数；本地校验仍是必要边界，不声称模型输出保证正确。
