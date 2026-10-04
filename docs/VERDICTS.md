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
| PacProcessorTest#testDefaultNetworkIsNull | TIMEOUT·待重跑 | 20:33:56 起零输出，至 20:37:57 下一条才接续 = 240s toybox timeout 击中，无 instrumentation 结果，不记 OK/FAIL |
| testDefaultNetworkIsNull（重跑） | OK | batch7 串行绿（首轮 TIMEOUT 系 flake/坏窗口，非产品信号，关闭） |

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
> 否决项）。batch5-7 健康：批间 MemAvailable 5.2G→5.2G→5.1G，均远超 2G 门。
> Pac 首轮 TIMEOUT 已由 batch7 重跑关闭（绿）；新 TIMEOUT 空洞各归各节。

## STATUS 已有结论补记（2026-10-02/03 轮，串行单测，本轮未重跑）

> 出处 `STATUS.md` §1v/§1x。只转 verdict，不转归因细节（归因见 STATUS）。

| 用例 | verdict | 备注 |
|---|---|---|
| CookieManagerTest.testGetInstance | OK | STATUS §1v 12/14 輪 |
| CookieManagerTest.testFlush | OK | 同上 |
| CookieManagerTest.testAcceptCookie | OK | 同上 |
| CookieManagerTest.testSetCookie | OK | 同上 |
| CookieManagerTest.testSetCookieNullCallback | OK | 同上 |
| CookieManagerTest.testSetCookieCallback | OK | 同上 |
| CookieManagerTest.testRemoveCookies | OK | 同上（含 0016 空清谎报修复后） |
| CookieManagerTest.testRemoveCookiesNullCallback | OK | 同上 |
| CookieManagerTest.testRemoveCookiesCallback | OK | 同上 |
| CookieManagerTest.testSameSiteNoneRequiresSecure | OK | 同上 |
| CookieManagerTest.testSchemefulSameSite | OK | 同上 |
| CookieManagerTest.testb3167208 | OK | 同上 |
| CookieManagerTest.testSameSiteLaxByDefault | FAIL·已知缺口 | 本台账 WebViewSslTest 后「opens」节已记（batch4 串行复现） |
| CookieManagerTest.testThirdPartyCookie | OK | 同上节已记（batch4 串行绿） |
| CookieTest.testDomain | OK | STATUS §1v，0014 同 host Domain 存 domain cookie 后 5/5 |
| CookieTest.testEmptyValue | OK | 同上 |
| CookieTest.testInvalidDomain | OK | 同上 |
| CookieTest.testPath | OK | 同上 |
| CookieTest.testSubDomain | OK | 同上 |
| PostMessageTest.testSimpleMessageToMainFrame | OK | STATUS §1v，transport 重写后 8/9 |
| PostMessageTest.testClose | OK | 同上 |
| PostMessageTest.testWildcardOriginMatchesAnything | OK | 同上 |
| PostMessageTest.testEmptyStringOriginMatchesAnything | OK | 同上 |
| PostMessageTest.testMultipleMessagesToMainFrame | OK | 同上 |
| PostMessageTest.testMessageChannel | OK | 同上（转移修复后翻绿） |
| PostMessageTest.testWebMessageHandler | OK | 同上 |
| PostMessageTest.testWebMessageDefaultHandler | OK | 同上 |
| PostMessageTest.testReceiveMessagePort | FAIL·待 patch | page→Java 端口转移（`message.getPorts()` null → 回调 NPE）；需 Gecko MessagePort IPDL 级 plumbing（P2 patch 候选） |
| HttpAuthHandlerTest.testCancel | OK | STATUS §1v 单过 |
| DateSorterTest（5 全） | OK | STATUS §1x 横扫：testConstants/testConstructor/testGetBoundary/testGetIndex/testGetLabel |
| MimeTypeMapTest（6 全） | OK | STATUS §1x 横扫 |
| URLUtilTest（16 全） | OK | STATUS §1x 横扫 |
| TestProcessClientTest（4 全） | OK | STATUS §1x 横扫 |
| WebBackForwardListTest.testGetCurrentItem | OK | STATUS §1x 单跑过（mProgress=15 卡死随 UA/title 多米诺同愈） |
| WebViewStartupTest.testGetCurrentWebViewPackageOnUiThread | OK | STATUS §1x 单跑 5/5（含后台创建 MainThread 有界 hop 修复） |
| WebViewStartupTest.testGetCurrentWebViewPackageOnBackgroundThread | OK | 同上 |
| WebViewStartupTest.testGetWebViewLooperOnUiThread | OK | 同上（单跑；全类跑仍卡第 4 位 fresh-process `Loading provider` 后零输出，未入我方代码，见 STATUS §1x） |
| WebViewStartupTest.testGetWebViewLooperCreatedOnUiThreadFromInstrThread | OK | 同上 |
| WebViewStartupTest.testGetWebViewLooperCreatedOnBackgroundThreadFromInstThread | OK | 同上 |

## WebViewClientTest（26，2026-10-04 batch5 串行：4 OK / 15 FAIL / 7 N/A）

| 用例 | verdict | 备注 |
|---|---|---|
| testOnReceivedHttpAuthRequest | OK | 串行 |
| testOnRenderProcessGoneDefault | OK | 串行（默认行为面） |
| testShouldOverrideKeyEvent | OK | 串行 |
| testShouldOverrideUrlLoadingDefault | OK | 串行 |
| testOnSafeBrowsingBillingCode | N/A | runner `OK (0 tests)`：dex 有名但无可运行用例（helper/注解剥离），不计分母 |
| testOnSafeBrowsingCode | N/A | 同上 |
| testOnSafeBrowsingHitBackToSafety | N/A | 同上 |
| testOnSafeBrowsingHitProceed | N/A | 同上 |
| testOnSafeBrowsingMalwareCode | N/A | 同上 |
| testOnSafeBrowsingPhishingCode | N/A | 同上 |
| testOnSafeBrowsingUnwantedSoftwareCode | N/A | 同上 |
| testDoUpdateVisitedHistory | FAIL·gate-timeout | `unexpected timeout`（:421），待健康窗口重验 |
| testLoadPage | FAIL·gate-timeout | `unexpected timeout`（:291），待健康窗口重验 |
| testOnFormResubmission | FAIL·gate-timeout | `unexpected timeout`（:402），待健康窗口重验 |
| testOnRenderProcessGone | FAIL·gate-timeout | `unexpected timeout`（:623）；关联 terminate()=false honest token，待查 |
| testShouldOverrideUrlLoading | FAIL·gate-timeout | `mLoaded/mNewPicture/mProgress=0`（:170），待健康窗口重验 |
| testOnPageCommitVisibleCalled | FAIL·未实现 | future TimeoutException（:793）；仓内无 onPageCommitVisible |
| testOnScaleChanged | FAIL·未实现 | `unexpected timeout`（:479）；zoom 全 stub |
| testOnUnhandledKeyEvent | FAIL·未实现 | `unexpected timeout`（:461）；仓内无 key-event 桥 |
| testShouldOverrideUrlLoadingOnCreateWindow | FAIL·未实现 | `unexpected timeout`（:222）；无 onCreateWindow 桥（同 ChromeClient 窗口族） |
| testOnReceivedError | FAIL·映射待查 | `expected:<-10> but was:<-1>`（:342）；ErrorBridge 错误码映射 |
| testOnReceivedErrorForSubresource | FAIL·待查 | assertNotNull（:361，回调未达） |
| testOnReceivedHttpError | FAIL·待查 | assertNotNull（:375，回调未达） |
| testOnReceivedLoginRequest | FAIL·待查 | assertTrue（:318） |
| testShouldInterceptRequestParams | FAIL·待查 | AssertionError（:542）；0001-0003 链路在 harness 成立，CTS 变体待专用复现 |
| testShouldInterceptRequestResponse | FAIL·替身状态行缺口 | `expected:<404[Not Found]> but was:<404[]>`（:594）；reason-phrase 未进合成响应，0001-0003 patch 侧 |

## WebViewTest（77，2026-10-04 batch6 串行：27 OK / 48 FAIL / 1 CRASH / 1 TIMEOUT）

OK（27，全串行）：testAccessPluginList / testAndroidAssetAnchor / testAndroidAssetQueryParam（0020 asset 映射端到端成立）/ testConstructor / testCreatingWebViewCreatesCookieSyncManager / testDebugDump / testDestroy / testFindAddress / testGetOriginalUrl / testGetWebChromeClient / testLoadData / testLoadDataWithBaseUrl_javascriptCanAccessOrigin / testLoadDataWithBaseUrl_resolvesRelativeToBaseUrl / testLoadDataWithBaseUrl_unencodedContentHttpBaseUrl / testLoadUrl / testPlatformNotifications / testPostUrlWithNonNetworkUrl / testPrinting / testScrollBarOverlay / testSetLayoutParams / testSetMapTrackballToArrowKeys / testSetSafeBrowsingAllowlistWithMalformedList / testSetWebChromeClient / testStartSafeBrowsingWithNullCallbackDoesntCrash / testStopLoading / testVisualStateCallbackCalled / testWebViewClassLoaderReturnsNonNull。

| 用例 | verdict | 备注 |
|---|---|---|
| testPostUrlWithNetworkUrl | FAIL·process-crash（真 bug，优先修） | `UnsupportedOperationException: postUrl: P1/P2`（provider `todo():694` 经 `postUrl:769` 上抛，杀 CTS 进程）；违反 AGENTS §7 delegate 异常不上抛纪律。同族：`saveWebArchive`×2（:813-816）、`createPrintDocumentAdapter`（:915）同为裸 throw |
| testUseRemovedJavascriptInterface | TIMEOUT·待重跑 | 21:04:33 起零输出至 21:08:34 = 240s 超时击中，无结果，不记 FAIL |
| testCanInjectHeaders | FAIL·基线同挂 | `Referer not found`（:453）；Chromium 基线同 fail（CTS.md §4），不计回归 |
| testSetNetworkAvailable | FAIL·基线同挂 | `unexpected timeout`（:2208）；基线同 fail，不计回归 |
| testAddJavascriptInterfaceNullObject | FAIL·语义差异 | `IAE: injectedObject`（provider:1019 上抛）；null-object 容忍语义待按 AOSP 对齐（抛 vs 忽略） |
| testSaveAndRestoreState | FAIL·空快照缺口 | `Should return null when nothing to save, was GeckoBackForwardList`（:2030）；StateBridge 空表仍返快照 |
| testSetPictureListener | FAIL·行为差异（需决策） | `expected null, was Picture`（:941）；b9ed3c1 合成 onNewPicture + 空 Picture 与 CTS 期待冲突，设计决策点 |
| testGetHitTestResult | FAIL·未实现 | NPE `getType() on null`（:1908）；`getHitTestResult()` return null stub（:921） |
| testGetVisibleTitleHeight | FAIL·未实现 | future timeout（:463）；`getVisibleTitleHeight()` return 0 stub |
| testFlingScroll | FAIL·未实现 | `unexpected timeout`（:1695）；`flingScroll(){}` stub（:1036） |
| testRequestFocusNodeHref | FAIL·未实现 | `unexpected timeout`（:1753）；`{}` stub（:922） |
| testRequestImageRef | FAIL·未实现 | future timeout（:1820）；`{}` stub（:923） |
| testPauseResumeTimers | FAIL·未实现 | assertTrue（:2284）；`pauseTimers/resumeTimers(){}` stub（:929-930） |
| testSetInitialScale | FAIL·未实现 | `unexpected timeout`（:1961）；`setInitialScale(){}` stub（:919） |
| testGetSafeBrowsingPrivacyPolicyUrl | FAIL·未实现 | `not well-formed URL`（:2841）；无 SB 后端 |
| testSetSafeBrowsingAllowlistWithValidList | FAIL·未实现 | `should be successful`（:2493）；honest false |
| testStartSafeBrowsingInvokesCallback | FAIL·未实现 | assertTrue（:2666）；无 SB 后端 |
| testStartSafeBrowsingUseApplicationContext | FAIL·未实现 | assertTrue（:2634）；同上 |
| testCreatingWebViewWithDeviceEncrpytionFails | FAIL·未实现（minor） | `should have thrown`（:240）；device-protected storage 未校验 |
| testCreatingWebViewWithMultipleEncryptionContext | FAIL·未实现（minor） | （:264）；同上 |
| testSetCustomTextClassifier | FAIL·未实现 | `expected same NO_OP`（:2591）；仓内无 TextClassifier |
| testSaveWebArchive | FAIL·未实现 | gate `mLoaded=false`（:1283）表象下是 `todo()` throw；crash 未发生系门先挂 |
| testAddJavascriptInterface | FAIL·gate-timeout | `mLoaded=false`（:624），待健康窗口重验 |
| testClearHistory | FAIL·gate-timeout | future timeout（:2002），待健康窗口重验 |
| testFindAll | FAIL·gate-timeout | `mLoaded=false`（:1374），待健康窗口重验 |
| testGoBackAndForward | FAIL·gate-timeout | future timeout（:537），待健康窗口重验 |
| testEvaluateJavascript | FAIL·transport 待查 | `expected:<2> but was:<null>`（:2328）；首跳 honest-null 同族（MiniWV 观察），待重验区分 |
| testAddJavascriptInterfaceExceptions | FAIL·transport 待查 | `expected:<"pass"> but was:<null>`（:762） |
| testJavascriptInterfaceCustomPropertiesClearedOnReload | FAIL·transport 待查 | `expected:<42> but was:<null>`（:776） |
| testJavascriptInterfaceForClientPopup | FAIL·待查 | future timeout（:833）；popup 桥无 |
| testRemoveJavascriptInterface | FAIL·待查 | `expected:<[object]> but was:<[undefined]>`（:679） |
| testAccessHttpAuthUsernamePassword | FAIL·待查 | assertNotNull（:1004）；harness httpAuth store round-trip 成立，CTS 变体待专用复现 |
| testWebViewDatabaseAccessHttpAuthUsernamePassword | FAIL·待查 | assertNotNull（:1071）；同上 |
| testAppCanInjectHeadersViaImmutableMap | FAIL·待查 | `expected:<1> but was:<0>`（:428）；header 注入面 |
| testAppInjectedXRequestedWithHeaderIsNotOverwritten | FAIL·待查 | `expected:<1> but was:<0>`（:404）；同上 |
| testGetWebViewClient | FAIL·待查 | assertNotNull（:2535） |
| testFindNext | FAIL·待查 | future timeout（:1404） |
| testDocumentHasImages | FAIL·待查 | future timeout（:1485） |
| testGetContentHeight | FAIL·待查 | `unexpected timeout`（:1585） |
| testPageScroll | FAIL·待查 | `unexpected timeout`（:1538）；`pageUp/Down=false` stub 关联 |
| testRequestChildRectangleOnScreen | FAIL·待查 | `unexpected timeout`（:2109） |
| testCapturePicture | FAIL·待查 | `unexpected timeout`（:882）；空 Picture 合成后内容面仍不到 |
| testPrintingPagesCount | FAIL·待查 | future timeout（:2421） |
| testSetDownloadListener | FAIL·待查 | `expected:<100> but was:<-1>`（:2168）；listener 已接（:667），进度语义 |
| testLoadDataWithBaseUrl_dataBaseUrlIgnoresHistoryUrl | FAIL·history-url 待查 | `Current URL (https://www.example.com/) should be data URI`（:1190） |
| testLoadDataWithBaseUrl_historyUrl | FAIL·HSTS 升级所致 | `expected http: was https:`（:1146）；baseUrl 进 necko 被 HSTS 升级（PostMessage 单测同根，已有 canonicalKey；history 面未覆盖） |
| testLoadDataWithBaseUrl_nullHistoryUrlShowsAsAboutBlank | FAIL·history-url 待查 | `expected about:blank was https://www.baseurl.com/`（:1156） |
| testLoadDataWithBaseUrl_nullSafe | FAIL·history-url 待查 | `expected about:blank was data:text/html;base64,`（:1230） |
| testLoadDataWithBaseUrl_urlEncodedContentDataBaseUrl | FAIL·编码残留 | `expected Hello World[!] was [%21]`（:1224）；§1x 双重编码修复的例外分支 |
| testLoadUrlDoesNotStripParamsWhenLoadingContentUrls | FAIL·待查 | NPE `uriString`（:382） |

## GeolocationTest（5，2026-10-04 batch7 串行：1 OK / 4 FAIL）

| 用例 | verdict | 备注 |
|---|---|---|
| testGeolocationPermissions | OK | 串行；修正 STATUS §1v「整类不可跑」——permission 面可跑（UiAutomation 空只杀 prompt 交互类） |
| testGeolocationRequestDeniedOnInsecureOrigin | FAIL·gate-timeout | `mLoaded=false`（load 门挂），待健康窗口重验 |
| testSimpleGeolocationRequestAcceptAlways | FAIL·基线同挂 | `JS didn't get position`；Chromium 基线同 fail（真机定位未开，CTS.md §4），不计回归 |
| testSimpleGeolocationRequestAcceptOnce | FAIL·基线同挂 | 同上 |
| testSimpleGeolocationRequestReject | FAIL·产品信号 | `JS got position`（应拒却给了）；insecure-origin 仍给位置，STATUS §2-step3 已记 |

> 全量覆盖（2026-10-04 夜）：294/294 全部有 verdict 或 N/A——WebSettings 51 +
> Ssl 15 + Zoom 9 + DataDir 6 + ChromeClient 11 + Tracing 7 + Pac 3 +
> CookieManager 14 + HttpAuth 3 + HistoryItem 1 + STATUS 补记 51 +
> batch1 附带 13 + WebViewClient 26 + WebViewTest 77 + Geo 5 = 292；
> 另 2 N/A（RenderProcessClient.OnExecutor helper、WebViewTest$2 inner 非独立用例）。
> 其中基线同挂不计回归：Geo×2 + CanInjectHeaders + SetNetworkAvailable。
> 待重跑：testUseRemovedJavascriptInterface（TIMEOUT 空洞）。
> 真 bug（证据确凿）：postUrl 裸 throw 杀进程（AGENTS §7 纪律违反，同族 saveWebArchive×2/createPrintDocumentAdapter）。
