# Third-party notices

ToolBox itself is released under the MIT License; its notice is bundled at `assets/licenses/ToolBox-LICENSE.txt` in the APK. The host also includes the following third-party components, which keep their own licenses:

## HyperNotification focus-api 1.4

- Project: https://github.com/xzakota/HyperNotification
- Maven coordinate: `com.xzakota.hyper.notification:focus-api:1.4`
- License: Apache License 2.0
- Use in ToolBox: construct HyperOS Focus V3 notification data. ToolBox does not include permission or whitelist bypass code.

The component is used under the terms of the Apache License, Version 2.0:
https://www.apache.org/licenses/LICENSE-2.0

## AndroidSVG 1.4

- Project: https://github.com/BigBadaboom/androidsvg/tree/Release_1.4
- Documentation: https://bigbadaboom.github.io/androidsvg/
- Maven coordinate: `com.caverock:androidsvg-aar:1.4`
- Copyright 2013 Paul LeBeau, Cave Rock Software Ltd.
- License: Apache License 2.0
- Use in ToolBox: rasterize validated static package icons off the main thread. Internal entities and external resolvers are disabled; no WebView is used for icon rendering.
- License text: https://www.apache.org/licenses/LICENSE-2.0
- The upstream license text is bundled at `assets/licenses/androidsvg-LICENSE.txt` in the APK.

## AndroidLiquidGlass catalog components

- Project: https://github.com/Kyant0/AndroidLiquidGlass (catalog app @ 65ab177)
- Copyright Kyant
- License: Apache License 2.0
- Use in ToolBox: vendored source in `core-ui/src/main/java/io/toolbox/core/ui/liquid/` (glass buttons, toggle, slider and bottom tabs). Package renamed; some theme values exposed as parameters, as noted in each file header.
- License text: https://www.apache.org/licenses/LICENSE-2.0
- The Apache License 2.0 text is bundled at `assets/licenses/androidsvg-LICENSE.txt` in the APK.
