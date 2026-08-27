# ToolBox JS API 声明来源记录

状态：`BLOCKED_AUTHORITATIVE_SDK_INPUT`

截至 2026-08-27，资料包、原始压缩包、本地相关目录及本仓库 Git 对象中均未找到任务书要求的 `sdk/toolbox-api.d.ts`。该声明不得依据技术方案或 `USAGE.md` 反向推导。

资料包给出的唯一校验锚点是：

```text
SHA-256  7792a14e810d77d2e8c1368fc4cb38e2b4d304d8b4d701bfc082e2ef6dfb4421
Path     sdk/toolbox-api.d.ts
```

恢复声明时必须同时满足：

1. 来源是原始交付方或可验证的不可变上游制品；
2. 文件 SHA-256 与上述值完全一致；
3. 来源位置、获取日期和不可变标识记录在本文件；
4. 校验通过前，不实现或发布 JS Bridge、声明生成物及依赖该声明的范例工具原生能力。

不依赖该声明的宿主界面、`.tbx` 检查和事务安装阶段可继续开发。
