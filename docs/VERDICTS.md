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
| testAccessAllowFileAccess | OK | 0020 后复跑绿（datadir+asset 双半） |
| testAccessCacheMode_defaultValue | OK | 串行 |
| testAccessCacheMode_cacheElseNetwork | FLAKY·load#2-stall | 两次咨询全 HIT，第二次永不完成；同双机制，待健康窗口重验 |
| testAccessCacheMode_noCache | FLAKY·双机制 | load#1 完整、load#2 咨询后零回调。已证伪 spawn 慢；tab35 死于 fork/specialize（服务超时）证实机制 M1；全员健康出生仍挂证实机制 M2（1673953 空窗）。warmUp 对照无效已回退 |
| testAccessCacheMode_cacheOnly | FLAKY·同上 | 三连挂同签名；归同双机制，待健康窗口重验 |
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
| testAccessJavaScriptEnabled | OK | 复跑绿（首轮门挂系坏窗口/Type-A flake） |
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
| testAppCacheEnabled | OK | 复跑绿（首轮门挂系坏窗口/Type-A flake） |
| testDatabaseDisabled | OK | 复跑绿（首轮门挂系坏窗口/Type-A flake） |
| testDisabledActionModeMenuItems | OK | 串行 |
| testLoadsImagesAutomatically_default | OK | 串行 |
| testLoadsImagesAutomatically_httpImagesLoaded | OK | 串行 |
| testLoadsImagesAutomatically_dataUriImagesLoaded | OK | 串行 |
| testLoadsImagesAutomatically_blockLoadingImages | OK | 串行 |
| testLoadsImagesAutomatically_loadImagesWithoutReload | OK | 串行 |
| testBlockNetworkImage | OK | 串行 |
| testBlockNetworkLoads | OK | 串行（此前已闭，保持绿） |
| testLocalImageLoads | OK | 0020 后复跑绿（串行） |
| testIframesWhenAccessFromFileURLsEnabled | OK | 0020 后复跑绿（strict 镜像阳性验证；曾因 spawn-stall 窗口连挂，见下） |
| testIframesWhenAccessFromFileURLsDisabled | OK | 0020 后复跑绿 |
| testXHRWhenAccessFromFileURLsEnabled | FAIL·honest-gap | app 私有区子资源：子进程 EACCES，file 通道无咨询点可替；需 file 拦截管线（后续项），不当 flake 追 |
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
> 第二次永不完成）；noCache → load#1 完整 + load#2 咨询后零回调。
> 随后设备进坏窗口（服务启动超时 + 12 僵尸 tab，对照 dataUri 同步挂），
> cacheOnly/JavaScriptEnabled/AppCacheEnabled/DatabaseDisabled 四条复跑作废。
> 清场后对照绿，四条重验三绿（JSEnabled/AppCache/DBDisabled），cacheOnly
> 仍挂。noCache diag 轮钉死 load#2 形态 + tab35 死于 fork/specialize
>（M1 spawn-stall 实锤）；warmUp A/B 对照无效（预热生效但 load#2 照挂，
> 机制在通知空窗侧 M2）→ 回退。cache 三项记 FLAKY·双机制，
> 待健康窗口补绿（重试制 verdict）。
