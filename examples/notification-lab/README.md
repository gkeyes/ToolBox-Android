# 通知实验室

ToolBox 内置的通知能力测试样本，不包含真实业务。它与其他内置范例一样通过生产安装器安装，
也可以用下方脚本独立打包为 `.tbx`。

所有通知均通过 ToolBox App 的 `notifications` 权限与公开 API 发出；小工具不直接申请 Android 通知权限，也不创建自己的原生通知通道。实时展示沿用宿主的固定通知身份并原位更新，行为组织参考 InstallerX，但未复制其 GPL-3.0 原生实现。

## 覆盖路径

- 普通通知：`notifications.post/update/cancel`。
- 实时展示：`notifications.live.start/update/end`。
- 后台动态更新：`background.start/setTimer/cancelTimer/stop`。
- 系统回执：显示 Android Live、HyperOS 超级岛、Focus 协议版本和系统权限报告。
- 行情、倒计时、行程三种动态内容预设，用于检查不同文本、进度、颜色和更新频率。

工具声明 `storage`、`notifications` 与 `background.runtime`。导入后需要在工具权限页开启通知和后台运行。

## 打包

```bash
examples/notification-lab/package.sh
```

产物为 `build/examples/notification-lab.tbx`。
