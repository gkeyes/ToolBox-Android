# 安全存储开关与 NextFlux 启动

本文说明权限中心关闭安全存储时的确认流程，以及 NextFlux 的启动阶段、启动错误提示和设置保存方式。安全存储确认框自 ToolBox 0.8.30 起提供，适用于所有声明 `storage.secure` 的工具。版本历史见 [CHANGELOG](../CHANGELOG.md)。

## 安全存储确认

### 行为

在工具权限页关闭“安全存储”时，先显示“关闭并清除安全存储？”确认框，并由用户明确确认。取消不修改授权；确认后才进入撤权、清除密文和密钥的流程。开启安全存储以及其他能力的开关不经过这个确认框。这是宿主共用的权限界面，与某个 TBX 的声明内容无关。

### 主要文件

| 文件 | 职责 |
| --- | --- |
| `app/src/main/kotlin/io/toolbox/host/permissions/PermissionCenterScreen.kt` | `PermissionCenterContent` 拦截 `storage.secure` 的关闭操作并显示确认框；确认后才调用 `onSetEnabled("storage.secure", false)` |
| `app/src/main/kotlin/io/toolbox/host/permissions/PermissionCenterViewModel.kt` | 授权写入、忙碌状态和清理失败后的重试入口 |
| `app/src/main/kotlin/io/toolbox/host/permissions/PermissionMutationRunner.kt` | 按工具排队执行授权变更；撤权时先拒绝新调用，再等待进行中的工作并清理安全数据 |
| `core-ui/src/main/java/io/toolbox/core/ui/component/ToolBoxModalDialog.kt` | 确认框的容器，经 `ToolBoxActionSheet` 在独立的全窗口 Dialog 中显示 |

### 不变量

- 确认框必须实际渲染出来。它在独立的 Dialog 窗口中显示，不依赖页面 Scaffold 的弹窗容器。若改用依赖 Scaffold 弹窗容器的浮层，必须放在该 Scaffold 的内容树内；放在外面时确认状态变为 true 而界面不显示，授权保持开启。
- 关闭安全存储只有经过确认这一条路径，不提供绕过确认的方式；开关、授权写入与清除流程不因确认框而改变。
- 页面离开就绪状态（`loadState` 不是 `Ready`）时收起确认框。
- 清理失败时授权保持关闭，可在页面上重试清理；重试只在工具版本和授权都未变化时执行。

### 测试

- `app/src/androidTest/kotlin/io/toolbox/host/permissions/PermissionCleanupBehaviorTest.kt`：在各外观下点按安全存储行，断言先显示确认框，且确认前没有任何授权变更。
- `app/src/test/kotlin/io/toolbox/host/permissions/PermissionMutationRunnerTest.kt`、`PermissionCenterViewModelTest.kt`：撤权与清理顺序、清理失败后的重试、与重新授权和包替换的竞争。

## NextFlux 启动

### 启动阶段

入口 `examples/nextflux/src/main.jsx` 依次执行：连接 ToolBox、恢复设置、恢复登录信息、恢复阅读缓存（存在有效账号时）、打开阅读界面。

入口没有静态 JS chunk 依赖：认证、缓存、界面和关闭工作都按实际阶段动态加载。关闭处理（flush handler）先等待偏好初始化，再动态导入同步与后台模块；启动未完成就退出时也使用同一个初始化 Promise。入口不得静态导入同步或后台模块：它们会经 `syncStore → settingsStore` 和 `syncStore → api/miniflux → authStore → db/storage` 提前加载 store，并让入口与 articlesStore 形成静态分块环。

### 启动错误提示

错误页显示失败阶段，以及允许列出的原生错误码或标准错误类型；不在列表中的错误显示为 `UNKNOWN`。

- 只有 `PERMISSION_DENIED` 才提示检查工具授权（恢复登录信息阶段提示开启安全存储）。
- 系统权限（`SYSTEM_PERMISSION_DENIED`）、能力不匹配（`NOT_DECLARED`，或非缓存阶段的 `UNSUPPORTED`）、存储额度（`QUOTA_EXCEEDED` / `QuotaExceededError`）和阅读缓存错误各有单独的说明。
- 其余失败保留现有数据，并给出重试入口。
- 认证恢复保留原生错误码和 cause；页面不显示原生错误正文、堆栈、账号或密钥。

### 设置保存

实现位于 `examples/nextflux/src/toolbox/preferences.js`。

- 设置是平坦的 JSON 值。恢复设置时密钥字段放在 JSON 尾部，store 挂载时按当前默认字段顺序重新编码，因此字符串可能只有字段顺序变化。持久化引擎在字符串变化时比较字段和值；仅顺序变化时只更新内存中的编码，不发起原生写入。
- 实际值变化进入串行写入队列：先保存有变化的安全密钥（AI 密钥、语音密钥），再保存不含密钥的普通设置；flush 等待实际写入结果。
- 滑块等连续调整通过 `deferPreferenceWrites` 在 400 ms 后合并为一次写入；其他变化和宿主 flush 立即写入。页面隐藏时也会写出尚未保存的变化。

### 测试

- `examples/nextflux/test/browser/startup.spec.js`：生产启动恢复已保存的设置和安全密钥且不产生写入；安全存储被拒绝、认证恢复内部错误时的提示。
- `examples/nextflux/test/browser/packaged-startup.spec.js`：打包后的生产入口通过分块启动到登录页，以及从 v3 缓存恢复已登录账号。
- 这些浏览器测试由 `tbx.yml` 在 NextFlux 受影响时运行。
