# Browser filtering

Browser menu → 广告过滤 provides a global switch, an exact-host site exception,
page element picking, per-site/all-site custom rule management, and a small
built-in resource-host blocklist. Filtering only runs in BrowserActivity's
separate browser process; TBX runtimes and their network APIs are unchanged.

## Pick and recover

Choose 选择广告位, tap the page, adjust 缩小/扩大, then 预览 and 保存.
The native controls sit below the WebView and can collapse. Back exits picking.
Advanced editing accepts standard CSS selectors. Picker rules default to the
current exact hostname (not all subdomains). Matching elements inserted later
are hidden by the saved stylesheet. Cancel removes preview CSS; saved rules
remain. Saved/deleted rules offer undo. 清除此网站的点选规则 preserves manual rules.

Rules can be added manually as either a CSS selector or a blocked request
hostname. Network host rules match that host and its subdomains, only on the
specified site. Main-frame navigations are never blocked. Refresh after network
rule changes to load previously blocked resources. Site exceptions and the
global switch disable both custom rules and the built-in baseline.

## Boundaries

- This is not an EasyList/uBlock parser. No subscriptions, regex filters,
  procedural selectors, scriptlets, or arbitrary script execution are supported.
- Element hiding does not stop downloads. The baseline only contains four
  dedicated Google advertising hosts; it does not promise comprehensive blocking.
- Only the top document is inspected. An iframe can be selected as a container;
  its cross-origin internal elements and closed shadow roots cannot be picked.
- WebView request interception does not cover every service-worker/WebSocket
  request. Video-stream-integrated ads are not removed.
- Dynamic IDs/layout changes may invalidate selectors. Positional fallback is
  labelled; all matches are previewed. Rules can be disabled or edited.
- Standard limits: 500 rules, 1024 characters per selector, up to 200 current
  matches per cosmetic selector. Root elements cannot be hidden. No polling or
  DOM observer runs during normal browsing; native polling runs only while picking.
- Rule settings survive clearing browser website data and app upgrades, but
  are not yet included in tool backup/export. No browsing data leaves the device.

## Verification

`node --test scripts/tests/browser-picker.test.cjs` exercises picker state and
selector construction using a DOM test double, not a rendering engine.
`./gradlew :app:testDebugUnitTest --tests 'io.toolbox.host.browser.*'` covers
host boundaries, exemptions, switches, input validation and navigation state.
Android CI runs these with the existing host suite and compiles an optimized
release. On the default branch, it separately builds a release with the original
signing identity. Browser-related changes in a PR or push also trigger the full
Android emulator suite; manual runs can enable it explicitly. Emulator checks
do not establish real-device WebView or visual-layout validation.

Before broad use, check a real webpage's banner, fixed footer and iframe:
select, preview, cancel, save, reload, disable, re-enable and undo; then verify
normal scrolling, links, Back, full-screen video and a TBX page still behave.
