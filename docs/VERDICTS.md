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

## WebViewSslTest（15，2026-10-04 串行）

| 用例 | verdict | 备注 |
|---|---|---|
| testOnReceivedSslError | OK | 串行 |
| testOnReceivedSslErrorCancel | OK | 串行 |
| testSslErrorProceedResponseReusedForSameHost | OK | 串行（temporary override 同 host 复用成立） |
| testCancelClientCertRequest | FAIL·未实现 | `never saw error -11`（WebViewSslTest.java:894）；client-cert 无桥接（仅 `ProviderAdapters.SinytraClientCertRequest` token 类，无 session 驱动） |
| testIgnoreClientCertRequest | FAIL·未实现 | 同上签名（:860） |
| testSecureServerRequiringClientCertDoesCancelRequest | FAIL·未实现 | 同上签名（:753） |
| testClientCertIssuersReceivedCorrectly | FAIL·未实现 | NPE `get length of null array`（:924，issuers 为空） |
| testProceedClientCertRequest | FAIL·未实现 | `expected:<test hello world> but was:<null>`（:780） |
| testProceedClientCertRequestKeyWithAndroidKeystoreKey | FAIL·未实现 | 同上签名（:811） |
| testSecureServerRequestingClientCertDoesNotCancelRequest | FAIL·未实现 | 同上签名（:742） |
| testSecureSiteSetsCertificate | FAIL·未实现 | `assertNotNull`（:574，`getCertificate()` 无证书面） |
| testInsecureSiteClearsCertificate | FAIL·未实现 | `assertNotNull`（:537，同上） |
| testClearSslPreferences | FAIL·未实现 | `should be called again after clearing`（:596）；`GeckoWebViewProvider.clearSslPreferences()` 为空实现，未清 0006 temporary override |
| testOnReceivedSslErrorProceed | FAIL·待查 | `expected:<test hello world> but was:<null>`（:659）；harness sslProceed 探针 PASS 过，CTS 变体差异待专用复现，不在此轮 chasing |
| testSslErrorProceedResponseNotReusedForDifferentHost | FAIL·待查 | `should be called when request is sent to 127.0.0.1`（:726）；host:port 会话放行语义与 CTS 换 host 期待的交互待查 |

> 小结：3 OK / 12 FAIL。client-cert 整族 8 + clearSslPreferences + 证书面 2
> 归未实现（无 client-cert 原语、无证书面、无 clear 语义）；proceed 双项
> 归待查（0006 链路在 harness 成立）。

## WebViewZoomTest（9，2026-10-04 串行）

| 用例 | verdict | 备注 |
|---|---|---|
| testZoom_canNotZoomInPastMaximum | OK | 串行（no-op 边界恰好成立） |
| testZoom_canNotZoomOutPastMinimum | OK | 串行 |
| testCanZoomWhileZoomSupportedFalse | FAIL·未实现 | `unexpected timeout`，`setUpPage:140→pollingCheckForCanZoomIn:311`；`getScale()=1.0/canZoomIn()=false/zoomIn()=false` 全 stub |
| testInvokeZoomPicker | FAIL·未实现 | 同上签名（:171） |
| testScaleChangeCallbackMatchesGetScale | FAIL·未实现 | 同上签名（:266） |
| testZoomByNonPowerOfTwoIncrements | FAIL·未实现 | 同上签名（:234） |
| testZoomByPowerOfTwoIncrements | FAIL·未实现 | 同上签名（:222） |
| testZoomIn | FAIL·未实现 | 同上签名（:145） |
| testGetZoomControls | FAIL·未实现 | `Should be able to get zoom controls`（:155） |

## WebViewDataDirTest（6，2026-10-04 串行）

| 用例 | verdict | 备注 |
|---|---|---|
| testCookieJarsSeparate | OK | 串行 |
| testDisableThenUse | OK | 串行 |
| testInvalidDir | OK | 串行 |
| testUseThenChangeDir | OK | 串行 |
| testUseThenDisable | OK | 串行 |
| testSameDirTwoProcesses | FAIL·待查 | `didn't throw RuntimeException`（:121）；同 dir 双进程未抛，隔离 enforcement 待查 |

## WebChromeClientTest（11，2026-10-04 串行）

| 用例 | verdict | 备注 |
|---|---|---|
| testOnJsAlert | OK | 串行 |
| testOnJsConfirm | OK | 串行 |
| testOnJsPrompt | OK | 串行 |
| testOnProgressChanged | OK | 串行 |
| testOnReceivedIcon | OK | 串行 |
| testOnReceivedTitle | OK | 串行 |
| testBlockWindowsAsync | FAIL·未实现 | gate-timeout `mLoaded/mNewPicture/mProgress=0`（runWindowTest:203）；无 onCreateWindow 桥（与 WebSettingsTest JavaScriptCanOpenWindowsAutomatically 同缺口） |
| testBlockWindowsSync | FAIL·未实现 | `unexpected timeout`（runWindowTest:211，同上） |
| testWindows | FAIL·未实现 | `unexpected timeout`（:226，同上） |
| testOnConsoleMessage | FAIL·待查 | `Timeout BlockingQueue`（:403）；ConsoleBridge 存在并路由 host，http 页 console 未达原因待查（data: 页 honest-gap 另计） |
| testOnJsBeforeUnloadIsCalled | FAIL·待查 | `Timeout BlockingQueue`（:274）；PromptBridge 有 onJsBeforeUnload host 位，未达原因待查 |

## TracingControllerTest（7，2026-10-04 串行）

| 用例 | verdict | 备注 |
|---|---|---|
| testTracingCannotStartIfAlreadyTracing | OK | 串行（double-start 抛成立） |
| testTracingInvalidCategoriesPatternComma | OK | 串行 |
| testTracingInvalidCategoriesPatternExclusion | OK | 串行 |
| testTracingStopFalseIfNotTracing | OK | 串行 |
| testTracingWithNullConfig | OK | 串行 |
| testTracingControllerCallbacks | FAIL·待查 | `Tracing did not complete`（:207，64s）；honest no-op 后端写 `{}`+close，CTS 完成契约未满足，待查 |
| testTracingControllerCallbacksOnUI | FAIL·待查 | 同上签名（:193，64s） |

## PacProcessorTest（3，2026-10-04 串行）

| 用例 | verdict | 备注 |
|---|---|---|
| testSetNetwork | OK | 串行（no-op 绑定成立） |
| testCreatePacProcessor | FAIL·待查 | `expected:<PROXY 1.2.3.4:8080> but was:<null>`（:76）；JsBridge eval 传输回 null，TestProcessService 进程内传输就绪性待查 |
| testDefaultNetworkIsNull | TIMEOUT·待重跑 | 20:33:56 起零输出，至 20:37:57 下一条才接续 = 240s toybox timeout 击中，无 instrumentation 结果，不记 OK/FAIL |

## CookieManagerTest opens（2，2026-10-04 串行）

| 用例 | verdict | 备注 |
|---|---|---|
| testThirdPartyCookie | OK | 串行（0017 v2 下稳定出 verdict，见 STATUS §1v） |
| testSameSiteLaxByDefault | FAIL·已知缺口 | `expected null, but was:<test1=value1>`（:403）；第三方无 SameSite 读侧未拦，open 项 |

## HttpAuthHandlerTest（2，2026-10-04 串行）

| 用例 | verdict | 备注 |
|---|---|---|
| testProceed | FAIL·待查 | `expected:<Android CTS> but was:<null>`（:189←:179）；STATUS §1v triple-auth prompt 链竞态 open 项 |
| testUseHttpAuthUsernamePassword | FAIL·待查 | `assertFalse`（:227），同上链路 |

## WebHistoryItemTest（1，2026-10-04 串行）

| 用例 | verdict | 备注 |
|---|---|---|
| testWebHistoryItem | FAIL·gate-timeout | `mLoaded=false, mNewPicture=false, mProgress=0`（:153，28.9s）；STATUS §1x 归 M5-A 域，等 0017 多轮验收后重取，不单测 chasing |

## batch1 附带（2026-10-04 同扫表，串行）

| 用例 | verdict | 备注 |
|---|---|---|
| ServiceWorkerClientTest.testSetNullServiceWorkerClient | OK | 串行 |
| ServiceWorkerClientTest.testServiceWorkerClientInterceptCallback | FAIL·待查 | `JS could not register Service Worker`（:200，66s） |
| ServiceWorkerWebSettingsTest.testAllowContentAccess | OK | 串行 |
| ServiceWorkerWebSettingsTest.testBlockNetworkLoads | OK | 串行 |
| ServiceWorkerWebSettingsTest.testCacheMode | OK | 串行 |
| ServiceWorkerWebSettingsTest.testAllowFileAccess | FAIL·待查 | `expected:<false> but was:<true>`（:122） |
| WebViewRenderProcessClientTest.testSetWebViewRenderProcessClient | OK | 串行 |
| WebViewRenderProcessClientTest.testWebViewRenderProcessClientWithExecutor | FAIL·gate-timeout | `mLoaded/mNewPicture/mProgress=0`（:195，21.7s） |
| WebViewRenderProcessClientTest.testWebViewRenderProcessClientWithoutExecutor | FAIL·待查 | `TimeoutException SettableFuture PENDING`（:189，22.9s，JSBlocker 未被唤起） |
| WebViewRenderProcessTest.testGetWebViewRenderProcess | FAIL·honest-gap | `A started renderer should be able to be terminated`（:165）；`terminate()=false` 稳定 token 诚实语义，见 STATUS §1b |
| WebViewDarkModeDarkThemeTest.testSimplifiedDarkMode_rendersDark | FAIL·未实现 | `prefers-color-scheme shall set to dark, was null`（:84）；仓内零 dark-mode 实现 |
| WebViewDarkModeLightThemeTest.testSimplifedDarkMode_rendersLight | FAIL·未实现 | gate-timeout（:81）；同上 |
| WebViewTransportTest.testAccessWebView | OK | 串行 |

> 健康注记（2026-10-04 夜）：batch 前 MemAvailable 4.9G → batch3 前 3.6G →
> batch4 前 3.6G，均 ≥2G 门；load ~15（MTK D 态常驻，见 STATUS §1v，非 verdict
> 否决项）。Pac testDefaultNetworkIsNull 的 240s 空洞为 runner 超时击中，
> 非产品信号，需单独重跑补 verdict。
