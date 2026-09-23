# 标题关键词过滤（1.0.12 / build 17）

本次将已交付 build 15–17 的过滤功能合入普通源码构建；不再依赖修改压缩后的 JavaScript。工具 ID、权限、最低宿主版本与依赖锁文件不变。正常构建使用既有 `npm run package` 和 TBX Actions。

## 使用
单个订阅的列表顶部和文章正文工具栏都有“标题过滤”漏斗。正文入口按文章实际的 feedId 定位，在全部文章、分类或收藏中也不会修改其他来源。输入普通关键词并以 `|` 分隔；正则特殊字符自动转义。清空并保存仅移除本入口管理的关键词。

## 原规则与保存边界
每次打开、每次保存前均读取单个订阅的服务器规则。原屏蔽、保留和其他条目过滤规则只读展示，不需要重新录入。输入框只回显 `EntryTitle=(?i)(?P<nextflux_title_keywords>...)` 管理行；手写正则不自动转换。保存请求只包含 `block_filter_entry_rules` 一个字段，其他规则行保留。管理行被其他客户端改动、响应不确定或账号/文章来源改变时，不显示虚假的保存成功。

标题专用字段需要 Miniflux 2.2.10+；缺少字段则不写入，不降级为范围更宽的旧屏蔽规则。服务器全局规则继续由服务器处理，本窗口不读取或改动。过滤作用于后续抓取，不批量删除已同步条目。读取与 PUT 之间仍可能有并发修改；Miniflux 的此接口没有提供原子条件写，本实现不承诺跨客户端原子 CAS。

保存提示通过 ReactDOM portal 放到页面根节点，底部定位同时考虑安全区与 VisualViewport，避免顶部模糊工具栏截获 fixed 定位。

## 检查
- `npm test` 包含 59 个新增的关键词、接口、原规则保留和文章来源回归用例，直接导入本目录源码。
- 既有浏览器检查增加 `test/browser/title-filter.spec.js`，通过 Vite 挂载真实组件并使用隔离账号/原生网络夹具，不依赖编译后的哈希文件名。
- Browser plugin not available in Actions；使用项目既有 Playwright。截图与测试结果由 Actions 保留，不作为源码提交。
- 没有增加 Android 模拟器测试，也不访问真实 Miniflux 账号；源构建与离线测试不等于真机、软键盘或真实服务器验收。

官方规则与接口：
- https://miniflux.app/docs/rules.html#entry-filtering-rules
- https://miniflux.app/docs/api.html
