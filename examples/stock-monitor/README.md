# 行情哨兵

面向 ToolBox 0.3.2 的独立股票监控小工具，不会加入宿主内置三个范例。

## 功能

- 默认使用腾讯 HTTPS 行情读取 A 股实时或最近价格；代码支持沪、深、北交所六位代码。
- 可切换 Twelve Data 监控全球股票，API Token 只保存到 `storage.secure`，请求时通过 `Authorization` Header 发送。
- 每只股票可设置高于或低于价格提醒；只在首次越过阈值时通知，价格回到阈值内后重新待命。
- 可启动 ToolBox 0.3 持续运行环境，以 1、5、15 或 30 分钟间隔刷新；进程恢复后重新登记计时器。
- 后台持续通知显示启用股票数量，并汇总每只股票的名称、价格和涨跌；通知原位请求 Android 实时更新与 HyperOS 超级岛增强。
- 价格越过阈值时仍发送独立普通提醒，不创建重复的短时实时卡片。

## 使用

1. 在 ToolBox 中导入 `build/examples/stock-monitor.tbx`。
2. 在工具详情开启网络、通知和后台运行权限。
3. 打开工具，编辑默认的 `600550` 或添加其他股票，设置阈值。
4. 点“后台监控”后可以离开运行页；宿主会显示持续运行通知。

Twelve Data 的 Token 可在其官网申请。中国沪深交易所行情在 Twelve Data 通常属于较高套餐，A 股默认建议使用腾讯行情。

## 打包

```bash
examples/stock-monitor/package.sh
```

产物写入 `build/examples/stock-monitor.tbx`，包内只包含 `manifest.json`、HTML、CSS、`live-summary.js`、`app.js`、SVG 图标和生成的 `integrity.json`。
