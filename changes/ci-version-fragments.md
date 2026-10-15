---
bump: patch
---
版本号改由 CI 在发布时计算：PR 不再改 `app/build.gradle.kts` 的版本号和 `CHANGELOG.md`，而是在 `changes/` 里新增一条更新说明，同时打开的 PR 不再互相冲突；测试版的构建记录和发布说明列出本次的更新内容。安装包功能不变。
