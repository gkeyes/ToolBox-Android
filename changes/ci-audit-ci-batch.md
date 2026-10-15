---
bump: patch
---
CI 与发布流程（独立审计 CI-1～CI-7、PKG-8）：推送到 `main` 时，发布到测试版 `dev` 之前再查一次版本号，改了会发布的内容却没提高版本号（例如两个 PR 先后合并成同一个版本号）时不再悄悄跳过，而是不发布并让任务失败；TBX 发布核对每个成功的打包任务都交出了 `.tbx`，下载失败不再被忽略；正式版发布不再原样沿用旧版的 `.tbx`，而是按该次构建所在提交的清单版本从 `dev`（或指定的旧版 Release）取每个小工具的包并校验，缺任何一个就失败，且新标签必须高于当前最新正式版；NextFlux、SocialCoach 改了 `package.json`／`package-lock.json` 时跑全部测试；TBX CI 也跑 `scripts/tests` 的脚本测试；`core-data` 的 JVM 测试把 Room schema 目录登记为输入，内置示例打包任务把 `scripts/package-tool.py` 登记为输入，并新增测试：内置示例目录里有打包清单之外的文件时失败。
