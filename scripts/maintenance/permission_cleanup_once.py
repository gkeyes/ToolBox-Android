from pathlib import Path
import subprocess

ROOT = Path.cwd()
def replace(path, old, new, expected=1):
    target = ROOT / path
    text = target.read_text(encoding='utf-8')
    if text.count(old) != expected:
        raise RuntimeError(f'{path}: expected {expected} copies of {old[:60]}')
    target.write_text(text.replace(old, new), encoding='utf-8')

# Match the existing native two-stream resource pool, not the number of loaded
# audio/video elements. Finished streams return the slot while Blob leases live.
replace('examples/nextflux/src/toolbox/mediaTransport.js', '  maxConcurrent = 3,', '  maxConcurrent = 2,')
replace('examples/nextflux/test/media.test.mjs', 'assert.equal(pending.length, 3);', 'assert.equal(pending.length, 2);', expected=2)
replace('examples/nextflux/src/components/ArticleView/components/Iframe.jsx', '可按需加载，单个文件上限 4 MiB。', '可按需加载，关闭后释放媒体缓存。')
replace('examples/nextflux/README.md', '- 每次普通网络响应最多 **4 MiB**，超出上限的图片、音视频或字体无法通过本工具完整下载播放。音视频以代理下载后播放的范围为准，长视频、直播、任意第三方 iframe 页面不等同于外部浏览器播放。部分站点的媒体授权、防盗链或跳转要求也可能使资源不可用。', '- 常规 JSON 同步和字体请求仍受普通响应预算约束。图片与音视频使用原生分块传输，不再单独限制图片 2 MiB、音视频 4 MiB 或已加载音视频两个；它们共用 **64 MiB 已保留压缩载荷预算**，闲置图片优先回收，关闭媒体后释放占用。最多两条并发媒体下载与宿主流槽位一致，其余排队；这不是已加载媒体数量上限，也不是总堆内存或无限文件大小保证。音视频仍是完整取得 Blob 后播放，不是渐进播放/磁盘流式播放；长视频、直播、任意第三方 iframe 页面不等同于外部浏览器播放。部分站点的媒体授权、防盗链或跳转要求也可能使资源不可用。')
tech = 'docs/ToolBox_Android_技术方案.md'
replace(tech, '以下规则与 `AGENTS.md` 一致，任何产品简化不得改变它们：', '以下保留的隔离与数据完整性规则仍然有效；调用配额与重复确认的调整以本文运行时授权章节及实现为准：')
replace(tech, '真实触摸，每工具每分钟最多 10 次。', '真实触摸，不再设置每工具每分钟调用配额。')
replace(tech, '网络读取最多同时三个。', '媒体下载最多同时两条，与宿主流槽位一致；已加载媒体数量不受此并发值限制。')
replace('sdk/help/manual.md', 'readStream 有单独的每分钟 1000 次上限，openStream、cancelStream 及其他接口仍使用原速率上限；页面宜合并小块更新，不要高频空读。', 'readStream、openStream、cancelStream 及其他接口不再使用 ToolBox 每分钟调用配额；并发流槽位、在途请求/字节预算及单消息上限仍然有效。页面宜合并小块更新，不要高频空读。')
replace('tool-api/src/main/kotlin/io/toolbox/tool/api/ToolBoxApiV1.kt', 'enum class GestureRequirement { NONE, RECENT, CONFIRMED_ONE_SHOT }', 'enum class GestureRequirement { NONE, RECENT }')

# Do not change compiler flags or disable existing tests. All commands run in
# this isolated GitHub Actions checkout, never on the user's computer.
subprocess.run(['node', 'scripts/check-developer-help.mjs'], check=True)
subprocess.run(['npm', 'ci', '--no-audit', '--no-fund'], cwd=ROOT / 'examples/nextflux', check=True)
subprocess.run(['npm', 'test'], cwd=ROOT / 'examples/nextflux', check=True)
subprocess.run(['git', 'grep', '-n', '-E', 'DefaultRuntimeAuthorizationPolicy\\(|Clipboard.*confirm|clipboard.*confirm|剪贴板.*确认|每分钟.{0,12}(10|20|30|120|1000)|AGENTS.md|DESIGN.md', '--', 'sdk', 'docs', 'tool-runtime', 'tool-api', 'app', 'examples/nextflux', ':!examples/nextflux/package-lock.json'], check=False)
print('NextFlux existing unit suite and developer-help checks passed in Actions; Android/native and browser integration CI are separate.')
