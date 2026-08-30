# ADR 0005：首帧后维护与 Profile 策略保持分离

- 状态：Accepted
- 日期：2026-08-29
- 关联：ADR 0003、ADR 0004

## 背景

V2 要求宿主首帧不等待孤儿 WebView Profile 扫描。启动维护时机与工具运行隔离策略是两个不同
决策：把清理延后可以直接缩短首屏关键
路径，而更换隔离模式会改变浏览状态能力、卸载证明和 provider 迁移边界。

## 方案比较

### Dedicated profile

- 每个工具拥有独立 Profile，Cookie、DOM storage、Cache、ServiceWorker 等浏览状态不会与其他
  工具共享。
- 卸载必须在同工具生命周期租约内删除整个 Profile；已加载、provider 不支持或删除失败时保持
  证据并 fail closed。
- 孤儿清理可能访问 WebView provider，因此不属于首页首帧的必要工作。

### Origin-only stateless mode

- 唯一 exact HTTPS Origin 只能隔离同源数据，不能证明默认 Profile 中所有浏览状态都能按工具
  完整删除。
- 只有采用 ADR 0004 的无状态约束，禁用 Cookie、DOM storage、IndexedDB、Cache、ServiceWorker
  与相关持久 API，才能在不依赖整 Profile 删除的情况下安全卸载。
- 它减少 provider 能力要求，但会移除网页持久存储能力；这不是单纯的性能开关，也不放宽
  任何安全边界。

## 决策

继续采用 ADR 0004 的 capability-driven 规则：provider 同时支持 multi-profile 与
browsing-data 删除时使用 dedicated profile，否则仅在完整无状态前置能力成立时使用 origin-only
stateless。两种模式都保留 exact Origin、CSP、导航与网络阻断、文件/content access 禁用、运行
租约和可验证卸载。

孤儿 Profile/模式记录清理改为收到真实 Compose 宿主首帧信号后执行。清理失败不替换已经发布的
宿主 Ready 状态；遗留 marker/记录继续由运行许可和卸载流程按 ADR 0003/0004 显式 fail closed。

## 后果

- 宿主首帧关键路径不再等待 catalog 首次读取和 provider 清理。
- 延后不等于忽略：清理证据仍保留，相关工具操作仍会给出类型化错误。
- 是否固定选择某一隔离模式必须基于真机启动/内存数据和存储能力取舍，不能以产品模式名
  猜测或降低边界。

## 验证

- 启动 instrumentation 测试在 `Ready` 后、首帧信号前确认维护调用次数为零；发出信号后确认只
  启动一次，普通维护失败不会替换同一个 `Ready`。
- Perfetto 使用 `coreData.create` 与 `catalog.firstEmission` 标出首屏关键路径，并确认
  `runtimeProfile.cleanup` 位于宿主首帧之后，不与首屏目录争抢维护工作。
- WebView 开关内存与 provider 行为仍由现有真实 WebView instrumentation 测试和 Phase 4 真机
  meminfo 流程验证。
