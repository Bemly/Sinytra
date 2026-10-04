# VERDICTS — CTS 串行 verdict 台账

> 一条用例一次 `am instrument`（fresh process），批间双 force-stop + 静置。
> verdict 符号：OK / FAIL(类型) / FLAKY(几次几过)。
> 失败归因三选一：Gecko 语义差异 / 未实现 / 上游 bug（含 bug 号）。
> 基线：Chromium 同机 98.6%（`docs/CTS.md` §4），基线已 fail 的 4 条不计回归。

## WebSettingsTest（51，2026-10-04 起）

| 用例 | verdict | 备注 |
|---|---|---|
| testUserAgentString_default | OK | 串行 |
| testUserAgentStringTest | OK | 串行 |
| testAccessUserAgentString | OK | 串行 |
| testAccessAllowFileAccess | FAIL·deterministic→0020 | 复跑：datadir 半过（标题到），asset 半标题停留上一页 → 0020 |
| testAccessCacheMode_defaultValue | OK | 串行 |
| testAccessCacheMode_cacheElseNetwork | FAIL·load#2-stall | 两次咨询全 HIT，第二次永不完成；系统性形状（Type-A vs localhost 串行待区分） |
| testAccessCacheMode_noCache | FAIL·Type-A | tab 0.5s 出生健康，ALLOW 后静默；非 spawn 慢 |
| testAccessCacheMode_cacheOnly | FAIL·待定位 | 同上，待复跑+归因 |
| testAccessCursiveFontFamily | OK | 串行 |
| testAccessFantasyFontFamily | OK | 串行 |
| testAccessFixedFontFamily | OK | 串行 |
| testAccessSansSerifFontFamily | OK | 串行 |
| testAccessSerifFontFamily | OK | 串行 |
| testAccessStandardFontFamily | OK | 串行 |
| testAccessDefaultFontSize | OK | 串行 |
| testAccessDefaultFixedFontSize | OK | 串行 |
| testAccessDefaultTextEncodingName | OK | 串行 |
| testAccessJavaScriptCanOpenWindowsAutomatically | FAIL·poll-timeout:520 | 未实现：无 onCreateWindow 桥（已知缺口） |
| testAccessJavaScriptEnabled | FAIL·gate-timeout | 待复跑 |
| testAccessLayoutAlgorithm | OK | 串行 |
| testAccessMinimumFontSize | OK | 串行 |
| testAccessMinimumLogicalFontSize | OK | 串行 |
| testAccessPluginsEnabled | OK | 串行 |
| testOffscreenPreRaster | OK | 串行 |
| testAccessPluginsPath | OK | 串行 |
| testAccessTextSize | OK | 串行 |
| testAccessUseDoubleTree | OK | 串行 |
| testAccessUseWideViewPort | OK | 串行 |
| testSetNeedInitialFocus | OK | 串行 |
| testSetRenderPriority | OK | 串行 |
| testAccessSupportMultipleWindows | OK | 串行 |
| testAccessSupportZoom | OK | 串行 |
| testAccessBuiltInZoomControls | OK | 串行 |
| testAppCacheDisabled | OK | 串行 |
| testAppCacheEnabled | FAIL·gate-timeout | 待复跑 |
| testDatabaseDisabled | FAIL·gate-timeout | 待复跑 |
| testDisabledActionModeMenuItems | OK | 串行 |
| testLoadsImagesAutomatically_default | OK | 串行 |
| testLoadsImagesAutomatically_httpImagesLoaded | OK | 串行 |
| testLoadsImagesAutomatically_dataUriImagesLoaded | OK | 串行 |
| testLoadsImagesAutomatically_blockLoadingImages | OK | 串行 |
| testLoadsImagesAutomatically_loadImagesWithoutReload | OK | 串行 |
| testBlockNetworkImage | OK | 串行 |
| testBlockNetworkLoads | OK | 串行（此前已闭，保持绿） |
| testLocalImageLoads | FAIL·deterministic→0020 | asset 页本身不可加载（门挂两次同形） |
| testIframesWhenAccessFromFileURLsEnabled | FAIL·null-url | 确定性缺口：`file:///android_asset/` 无映射，getUrl null → 0020 |
| testIframesWhenAccessFromFileURLsDisabled | OK | 串行（拦截侧正确） |
| testXHRWhenAccessFromFileURLsEnabled | FAIL·empty-title | 确定性缺口：asset 内 XHR 取不到文件 → 0020 |
| testXHRWhenAccessFromFileURLsDisabled | OK | 串行（拦截侧正确） |
| testAllowMixedMode | FAIL·infra | `Path not set: /secure.html` 测试 infra 侧，非产品 |
| testEnableSafeBrowsing | OK | 串行 |

> 首轮小结（2026-10-04）：39 OK / 12 FAIL。其中确定性产品缺口 3
>（全归 0020 file-asset）、已知未实现 1（onCreateWindow）、测试 infra 1
>（MixedMode server path）、待复跑区分 7（全为 gate-timeout：
> AllowFileAccess/cacheElseNetwork/noCache/cacheOnly/JavaScriptEnabled/
> AppCacheEnabled/DatabaseDisabled）。
>
> 复跑轮（卫生后）：AllowFileAccess → deterministic→0020（datadir 半过，
> asset 半标题停留）；cacheElseNetwork → load#2-stall（两次咨询全 HIT，
> 第二次永不完成）；noCache → Type-A（tab 0.5s 健康出生，ALLOW 后静默）。
> 随后设备进坏窗口（服务启动超时 + 12 僵尸 tab，对照 dataUri 同步挂），
> cacheOnly/JavaScriptEnabled/AppCacheEnabled/DatabaseDisabled 四条复跑作废，
> 待清场重验（此后每个 verdict 自带绿对照）。
