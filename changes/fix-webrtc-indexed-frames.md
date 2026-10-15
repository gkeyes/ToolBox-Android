---
bump: patch
---
小工具无法再通过 window[0]、frames[0] 或框架名称取得刚插入的 iframe 来绕过 WebRTC 封锁：任何插入页面元素的操作返回前，都会先锁住新框架（独立审计 SEC-X1）。
