# Third-party notices

## ToolBox-Android

https://github.com/gkeyes/ToolBox-Android

Reference commit: 02aec25cdcac18c56410e531a0f518ffcffacf6e

The native file adapter follows `examples/zhuzhang/runtime.js`, the theme extends the system-font/purple-action/light/dark conventions from `examples/quick-notes/style.css`, and packaging uses the repository's official `scripts/package-tool.py`.

License: MIT. Full license retained at `licenses/ToolBox-MIT.txt`.

## screenStitch

https://github.com/jaflo/screenStitch/

By jaflo at github: https://github.com/jaflo/screenStitch/
Licensed under the MIT license (http://opensource.org/licenses/MIT)
If you make any changes or use this in a project, I would appreciate
if you tell me. Please leave this notice intact.

The changed-region differencing approach was ported and substantially extended for tolerant JPEG input, fixed divider detection, preserved page margins, scale handling, profile search, spatial verification, dark themes and low-confidence review. The original exact-row equality stitcher is not shipped or executed.

License: MIT. Full license retained at `licenses/screenStitch-MIT.txt`.

## screenshot-stitcher (research reference)

https://github.com/ayumiya90/screenshot-stitcher

Its client-side image loading / Canvas composition and sorting flow was reviewed. It does not automatically deduplicate scrolling screenshots, so its app was not bundled as the stitching engine. Its MIT notice is retained at `licenses/screenshot-stitcher-MIT.txt` for provenance.

All runtime code and assets are bundled locally; no third-party network calls or SDK copies are required.
