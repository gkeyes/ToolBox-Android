# SocialCoach · ToolBox TBX

想说的话，说出来。保留原项目 React 界面、46 个场景、34 项技能、42 条理论、30 个案例，以及场景安排、角色模拟、提示、证据式复盘、反思和跨场次模式任务。移植基线见 `UPSTREAM.json`。

## 使用

在 ToolBox 0.8.0 或更新版本导入 `socialcoach-v1.0.3.tbx`，开启存储、安全存储和网络权限。完成本地个人练习设置后，在「设置 → 模型设置」填写 HTTPS API 地址、API Key、对话模型和复盘模型；两者可以相同。通过「测试并保存」验证连接，再选择场景开始练习。读取模型列表失败时仍可手动输入模型名称。

支持 OpenAI Chat Completions 兼容接口及 Anthropic Messages；Gemini 可使用其 OpenAI 兼容端点。兼容服务的 Base URL 填到 `/v1` 等基路径，不要包含 `/chat/completions`。推理模型可选择 `max_completion_tokens`。首版不提供共享 API、不内置密钥、不部署服务器。

## MiniMax M3 适配（1.0.1）

在「模型设置」选择「MiniMax M3 · 国内」或「MiniMax M3 · 国际」，填入对应平台的密钥，再「测试并保存」。两个模型均填 `MiniMax-M3`；OpenAI 兼容基地址分别是 `https://api.minimaxi.com/v1` 与 `https://api.minimax.io/v1`。国内官方 `api.minimax.cn` 也会识别。已有官方 M3 配置自动适配，不必重新填写。区域预设改变域名时清空当前密钥，避免把其他服务的密钥发往新地址。

- 提示、角色对话及原本 `thinking:false` 的短任务：发送 `thinking:{type:"disabled"}`；复盘：显式使用 `adaptive`，保留推理需求。
- OpenAI 兼容请求发送顶层 `reasoning_split:true`，M3 使用 `max_completion_tokens`。短任务保持原输出上限；复盘为推理和答案预留至少 16384 tokens 的总上限，不是每次实际用量，也不会自动重试增加费用。
- 同时兼容 MiniMax Anthropic 协议：SDK 基地址 `/anthropic` 映射到 `/anthropic/v1/messages`，只读取 text 块；不拼接 thinking 块。该协议与 OpenAI 兼容接口的默认思考开关不同，因此按任务显式设置。
- 返回解析在界面、角色元数据和 JSON 复盘解析之前完成：忽略单独的推理字段，并增量过滤正文中的 `<think>` / `<thinking>` 保留标记。标签跨流式片段、中文 UTF-8 分块和嵌套标记均有回归测试；只返回思考、标签未结束、输出截断或 MiniMax HTTP 200 业务错误时明确报错，不保存为有效提示或报告。
- 未验证的第三方网关不会收到 MiniMax 专属请求参数，但仍使用最终答案过滤。网关需要支持相应原生参数才能关闭模型思考；不会擅自更改现有地址或密钥。本工具没有 function/tool-call 链路，不把该过滤器作为通用工具调用历史序列化器。
- 保持 TBX ID、存储键和权限不变。更新前建议备份，直接导入更新，无需卸载。已保存的旧提示不自动改写；重新请求提示或开启一次新练习查看效果。

官方依据（核对日期 2026-09-24）：[OpenAI 兼容接口](https://platform.minimax.io/docs/api-reference/text-openai-api)、[Anthropic 兼容接口](https://platform.minimax.io/docs/api-reference/text-anthropic-api)、[国内接口文档](https://platform.minimaxi.com/docs/api-reference/text-openai-api)。`reasoning_split` 仅分离内容，不等于关闭思考；M2.x 与 M3 的开关能力不能混用。

## 手机布局修复（1.0.2）

针对 320–430px 宽手机及输入法弹出后的短可视高度做了专门修复：对话页始终把消息区和发送操作限制在视觉视口内，长 URL/连续编号可在气泡内断行；手机步骤条改为“当前阶段 + n/3”；成长记录优先显示场景标题，评分说明不再挤占标题；复盘目录使用页内滚动而不改写 SPA 路由；模型设置在窄屏将主要操作纵向排列。共享按钮使用最小高度而非固定高度，放大文字时可随内容增长。

这些修复不改变场景、Prompt、训练任务、模型配置或本地数据结构。

## 今日推荐卡片排版修复（1.0.3）

修正手机首页「今日推荐」卡片的封面排版：原先封面只有 144px 高，但标签层按 160px 以上的封面留白设计，导致「今日推荐 / 场景」标签与人物角色行几乎贴在一起；标签还从稿纸竖线左侧起排，与正文基线不一致。现在手机封面调整到 176px，并将标签左缘与人物、引语统一到稿纸正文列，保留至少 10px 的垂直呼吸空间。桌面布局和其他场景封面不受影响。

## 本地与联网边界

场景、知识内容、历史及草稿可以离线查看；AI 场景生成、训练、复盘和反思需要连接你配置的模型服务。相关对话文本与训练上下文会发送给该服务。记录使用 ToolBox 存储；密钥使用 ToolBox 安全存储，导出不包含密钥。普通浏览器预览时密钥仅保存在内存中。

移除了上游 `/api/*`、匿名统计、反馈上报、Service Worker 与语音采集。语音输入和朗读暂不提供，使用手机输入法语音转文字仍由输入法处理。界面沿用上游暖纸色风格，中文默认、中英双语和深浅色设置保留。模型评分仅为练习参考，不是临床、招聘或绩效评估。

数据导出与恢复需要相应文件权限；恢复前会验证结构并要求确认替换。建议先导出当前记录。上游主状态仍保留最近 200 条练习的持久化策略。

## 源码

- `src/app`, `src/components`：移植后的原版 React 界面。
- `src/data`：原场景、技能、理论、案例与可追溯来源。
- `src/lib/tasks`, `src/lib/prompts.ts`：原训练核心。
- `platform/`：ToolBox 网络、存储、导航、备份、模型设置和启动入口。
- `test/`：传输、备份、浏览器交互回归。
- `bootstrap.py`：一次性固定版本导入记录；常规构建不运行、不下载上游。

## 构建与验证

正式构建和测试通过 GitHub Actions `SocialCoach TBX` 执行；该工作流使用提交中的 `package-lock.json` 执行 `npm ci`。产物通过仓库 `scripts/package-tool.py` 打包并生成完整性哈希。标准 TBX CI 中也登记了 `socialcoach` 目标。

```sh
npm ci --ignore-scripts
npm test
npm run build
npm run test:browser
bash package.sh ../../build/socialcoach-v1.0.3.tbx
```

浏览器集成测试使用明确标记的模拟 ToolBox/模型响应，仅验证协议与界面交互，不代表真实模型效果或 Android 真机验证。未使用任何用户密钥进行测试。

Apache-2.0；原版权、许可证和语料来源见 `LICENSE`、`NOTICE` 与原数据 `source` 字段。
