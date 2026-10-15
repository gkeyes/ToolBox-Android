# 更新说明

改动会打进 APK 的 PR（判断规则见 [CONTRIBUTING](../CONTRIBUTING.md#版本号)）在这个目录里**新增一个**文件，不改 `app/build.gradle.kts` 的版本号，也不改 `CHANGELOG.md`。文件名随意，只要不与别人重名（建议用分支名），扩展名为 `.md`，直接放在 `changes/` 下：

```markdown
---
bump: patch
---
修正……（一句话说明这次改动，简体中文）
```

- `bump` 只能是 `patch`（修复 bug）、`minor`（新功能或明显的界面变化）或 `major`（大的重新设计）。
- 正文是写给用户看的更新说明，不写“版本 x.y.z（n）：”前缀；多行会合成一条。正文不能为空。
- 一个 PR 有几处独立的改动，可以加几个文件；版本号按其中最大的一级提高一次。

`main` 上的 CI 构建 APK 前，收集测试版 `dev` 上次发布以来新增的这些文件，按最大的 `bump` 提高 `versionName`（低位归 0），`versionCode` 加 1，并把正文写进构建记录和 `dev` 的发布说明（`scripts/ci/app_version.py`）。已发布的文件留在这里即可，不需要删除。本说明文件本身不是更新说明。
