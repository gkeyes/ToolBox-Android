# SocialCoach · ToolBox TBX

想说的话，说出来。保留原项目 React 界面、46 个场景、34 项技能、42 条理论、30 个案例，以及场景安排、角色模拟、提示、证据式复盘、反思和跨场次模式任务。移植基线见 `UPSTREAM.json`。

## 使用

在 ToolBox 0.8.0 或更新版本导入 `socialcoach-v1.0.0.tbx`，开启存储、安全存储和网络权限。完成本地个人练习设置后，在「设置 → 模型设置」填写 HTTPS API 地址、API Key、对话模型和复盘模型；两者可以相同。通过「测试并保存」验证连接，再选择场景开始练习。读取模型列表失败时仍可手动输入模型名称。

支持 OpenAI Chat Completions 兼容接口及 Anthropic Messages；Gemini 可使用其 OpenAI 兼容端点。兼容服务的 Base URL 填到 `/v1` 等基路径，不要包含 `/chat/completions`。推理模型可选择 `max_completion_tokens`。首版不提供共享 API、不内置密钥、不部署服务器。

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
bash package.sh ../../build/socialcoach-v1.0.0.tbx
```

浏览器集成测试使用明确标记的模拟 ToolBox/模型响应，仅验证协议与界面交互，不代表真实模型效果或 Android 真机验证。未使用任何用户密钥进行测试。

Apache-2.0；原版权、许可证和语料来源见 `LICENSE`、`NOTICE` 与原数据 `source` 字段。
