# API_MAPPING — Sinytra

> GeckoView delegate / controller / settings 现成能力对照表。
> **写任何新 bridge / controller / manager 之前，先查本表 + GeckoView javadoc，
> 确认真的没有现成 API 才写。**

## 1. GeckoView 接入方式（直接复用官方产物）

- Prototype 直接用 Maven，不自己编译 Gecko：
  `https://maven.mozilla.org/maven2/`，
  `implementation "org.mozilla.geckoview:geckoview-${channel}:${version}"`，
  channel 取 `stable / beta / nightly` 之一并 pin 死版本号（禁用 `+`）。
- 默认用 `geckoview`（Lite）包；只有明确需要 Glean 等附加库时才换
  `geckoview-omni`（Omni 把附加库打进 `libxul.so`，体积更大）。
  ExoPlayer 场景才看 `geckoview-exoplayer2-*`，WebView 项目默认不碰。
- `compileOptions { sourceCompatibility JavaVersion.VERSION_17 /
  targetCompatibility JavaVersion.VERSION_17 }`（GeckoView 要求的底线）。
- Runtime 生命周期照抄官方 quick-start 模式：宿主进程级 `static GeckoRuntime` 单例，
  `GeckoRuntime.create(context[, settings])` 只调一次，
  `session.open(sRuntime)` + `view.setSession(session)` + `session.loadUri(url)`。
- 预热：`GeckoRuntime.warmUp()`（提前启动 child processes）**仅 GeckoView ≥ v150 存在**；
  `GECKOVIEW_VERSION` 未 pin 死前不许当固定 API 用，最终以 pin 后对应 Javadoc 为准。
  不要自己写 child process 拉起逻辑。

## 2. `GeckoRuntime` 级控制器（全部直接拿来用）

| WebView 侧需求 | 复用的 GeckoView 现成 API | 说明 |
|---|---|---|
| 存储清理（WebStorage / Cookie / 缓存清数） | `getStorageController()` → `StorageController.clearData / clearDataFromHost / clearDataFromBaseDomain / clearDataForSessionContext` + `ClearFlags` | open session 会重新累积数据，清全量前先 `close()` 所有 session；按 `contextId` 隔离多 profile，不要自建清理通道 |
| 权限存取 | `StorageController.getAllPermissions / getPermissions(uri[, contextId, privateMode]) / setPermission` | 权限按 principal 存，GeckoView 故意不暴露裸 URL 设权限接口；拿 `onLocationChange` 回传的 `ContentPermission` 对象去设值，不要自建 permission store |
| 自动填充存储 | `setAutocompleteStorageDelegate / getAutocompleteStorageDelegate` (`Autocomplete.StorageDelegate`) + session 侧 `getAutofillSession()` | 复用虚拟节点树，不要自己扫 DOM 建树 |
| 内容拦截 / SafeBrowsing 映射 | `getContentBlockingController()` + `ContentBlocking.Delegate` | 追踪保护/分类器 bypass 对应 `LOAD_FLAGS_BYPASS_CLASSIFIER`，不要自写拦截器 |
| 通知 | `setWebNotificationDelegate` (`WebNotificationDelegate`) | Web Notification 直接委托出去，不要自建通知通道 |
| Push | `getWebPushController()` (`WebPushController`) | 同上 |
| ServiceWorker | `setServiceWorkerDelegate / getServiceWorkerDelegate` (`GeckoRuntime.ServiceWorkerDelegate`) | 对应 `ServiceWorkerController`，不要另起 SW 管理器 |
| 屏幕方向 | `getOrientationController()` | 复用，不要自己监听 sensor |
| 崩溃 / RenderProcess | `ContentDelegate.onCrash` + `GeckoRuntime.ACTION_CRASHED / EXTRA_*` + `crashHandler(...)` 设置 | 对应 `WebViewRenderProcessClient`，不要自建 crash 管道；`appendAppNotesToCrashReport` 打附加信息即可 |
| Activity 拉起 / intent 结果 | `setActivityDelegate` (`GeckoRuntime.ActivityDelegate`) | 文件选择、外部 App 跳转走它，不要自己写 `startActivityForResult` 分发 |

## 3. `GeckoSession` 现成能力（禁止自研同功能类）

- 导航：`load(Loader) / loadUri / reload([flags]) / stop / goBack / goForward /
  gotoHistoryIndex / purgeHistory` + `Loader` 的 `LOAD_FLAGS_*`
 （`BYPASS_CACHE / BYPASS_PROXY / BYPASS_CLASSIFIER / REPLACE_HISTORY /
  ALLOW_POPUPS / EXTERNAL / FORCE_ALLOW_DATA_URI / BYPASS_LOAD_URI_DELEGATE`）
  和 `HEADER_FILTER_CORS_SAFELISTED / HEADER_FILTER_UNRESTRICTED_UNSAFE`。
  `loadDataWithBaseURL` 优先用 `Loader.uri/data` 表达，表达不了才进 P2 patch。
- 状态：`SessionState`（`onSessionStateChange` 产出）+ `restoreState(state)` +
  `flushSessionState()`（`setActive(false)` 也会 flush）。`StateBridge` 只做
  WebView `Bundle` ↔ `SessionState` 转译，不自建历史序列化格式。
- 页内查找：`getFinder()` → `SessionFinder`（`FINDER_FIND_* / FINDER_DISPLAY_*` +
  `FinderResult`）。`findAll/findNext/clearMatches` 直接包它。
- 打印 / PDF：`printPageContent() / didPrintPageContent()` +
  `setPrintDelegate/getPrintDelegate` + `saveAsPdf() / getPdfFileSaver() / isPdfJs()`。
  Android 侧只接 `PrintManager`，排版渲染不要自己做。
- 下载：`ContentDelegate.onExternalResponse(WebResponse)` 接 Android `DownloadManager`；
  上传/文件选择：`PromptDelegate.onFilePrompt` 接系统 file picker。不要自写下载栈。
- 输入法 / 无障碍：`getTextInput()` (`SessionTextInput` + `TextInputDelegate`)、
  `getAccessibility()`。IME、selection（`SelectionActionDelegate`）、滚动
  （`ScrollDelegate` / `CompositorScrollDelegate` + `PanZoomController` /
  `OverscrollEdgeEffect`）全部复用。
- 多媒体：`setMediaDelegate` (`MediaDelegate`) + `setMediaSessionDelegate`
  (`MediaSession.Delegate`) 对应 `WebChromeClient` 媒体回调，不要自建播放器桥。
- 扩展 / 翻译 / 页面抽取：`getWebExtensionController`（runtime 级
  `WebExtensionController` + session 级 `SessionController`）、
  `TranslationsController`、`PageExtractionController`、`SessionPdfFileSaver` —
  WebView 不需要就**不接**，不许以“以后可能用”引入（见 §6 例外）。

## 4. Delegate → WebViewClient/WebChromeClient（对照表，缺一不可先查表）

session 级能设的 delegate 必须先从这张表找位置，找不到才新增 bridge 文件：

`Navigation / Progress / Content / Permission / Prompt / Media /
Scroll / CompositorScroll / History / SelectionAction / TextInput /
Print / MediaSession / Autocomplete / ContentBlocking / Experiment /
TranslationsSession`。

其中高频映射：

| `android.webkit` 侧 | GeckoView 侧 |
|---|---|
| `loadUrl()` | `GeckoSession.load()` |
| `reload()` / `stopLoading()` | `GeckoSession.reload()` / `stop()` |
| `goBack()` / `goForward()` / `canGoBack()` | `GeckoSession.goBack()/goForward()` + `NavigationDelegate.onCanGoBack/onCanGoForward` |
| `getUrl()` / `getTitle()` | `NavigationDelegate.onLocationChange` / `ContentDelegate.onTitleChange` |
| `getProgress()` | `ProgressDelegate.onProgressChange`（0–100） |
| `WebViewClient.onPageStarted/Finished` | `ProgressDelegate.onPageStart/onPageStop`（保证成对、有序） |
| `shouldOverrideUrlLoading` | `NavigationDelegate.onLoadRequest`（allow/deny） |
| `WebChromeClient` 进度/标题/弹窗 | `ProgressDelegate` + `ContentDelegate` + `PromptDelegate` |
| 权限/定位/媒体 | `PermissionDelegate`（Content 权限 + Android 权限两层含义，注意区分） |
| 页面错误页 | `NavigationDelegate.onLoadError`（可返回本地错误页 URL） |
| 滚动 | `ScrollDelegate` |
| 历史转译 | `SessionState`（本身即 `HistoryList`）+ `HistoryDelegate.onHistoryStateChange` → `WebBackForwardList` |
| visited 全局历史 | `HistoryDelegate.onVisited / getVisited / hasVisitedHostSince`（provider 自建 visited store 落盘） |
| 自动填充 | `Autocomplete.StorageDelegate` + Autofill 虚拟节点树 |
| `Prompt` 全家桶 | JS dialog + HTTP auth + file chooser + select/autocomplete |
| 焦点/窗口 | `Content.onFocusRequest`→焦点、`Content.onCloseRequest`→`onCloseWindow` |

## 5. `*Settings` 复用（WebSettings 只做翻译）

- `GeckoSessionSettings.Builder`：`userAgentMode (MOBILE/DESKTOP/VR)`、
  `viewportMode`、allowJavascript、contextId（多 profile 隔离，对应
  `clearDataForSessionContext`）、字体/缩放相关。
- `GeckoRuntimeSettings.Builder`：`enterpriseRootsEnabled(true)`（吃系统第三方 CA）、
  contentBlocking、crashHandler、telemetry 开关（`notifyTelemetryPrefChanged`）。
- `GeckoWebSettings` 不存独立状态，只做
  `android.webkit.WebSettings` ↔ 上面两个 Builder 的双向翻译；
  UA 读 `GeckoSession.getDefaultUserAgent()` 做基线，不要手写 UA 串。

## 6. `androidx.webkit` / Android framework 侧（兼容层不另起炉灶）

- Sinytra 是 provider，`androidx.webkit` 基础能力（`WebViewCompat / WebSettingsCompat /
  WebViewClientCompat / WebChromeClientCompat / ProcessGlobalConfig / TracingController /
  StartupFeature / WebViewFeature`）最终调的是 `android.webkit`。这部分我们的义务是让
  `WebViewFeature.isFeatureSupported` 诚实返回 + 行为对齐，而不是重实现一套 `*Compat`。
- 但 AndroidX 新功能**不是**实现 framework `android.webkit.*` 就自动获得的：
  `androidx.webkit` 经 provider APK 的 classloader 反射找 support-library glue——
  `org.chromium.support_lib_glue.SupportLibReflectionUtil →
  createWebViewProviderFactory() → WebViewProviderFactoryBoundaryInterface`
  （见 `WebViewGlueCommunicator`；找不到 glue 类时直接按“无特性可用”降级）。
  所以 Sinytra 最终要自己提供一套 support-library boundary glue（P2 独立大项，
  见 `ROADMAP.md`）：逐个想宣称支持的 `WebViewFeature` 对齐实现，不支持的诚实返回 false。
- 本地资源加载：`WebViewAssetLoader`（`https://appassets.androidplatform.net`
  风格的 path handler）能复用的就复用，不要自写 `shouldInterceptRequest` 静态资源分支；
  `shouldInterceptRequest` 只留真正需要改 Gecko 网络栈语义的场景（P2）。
- 进程配置：`ProcessGlobalConfig.apply` 一次性、WebView 加载前调用——provider 侧
  不要二次封装启动配置，只保证 Gecko bootstrap 在 WebView 加载前可被触发一次。

## 7. 例外（允许自研的极少数）

只有这几类允许写新代码：GeckoView 明确不存数据的
（visited 落盘、`WebViewDatabase` 表单/密码、`WebIconDatabase` 图标缓存）、
Chromium 语义 Gecko 侧无对应（`addJavascriptInterface` 反射语义、
`WebMessagePort` 通道语义、`saveState/Bundle` 转译）、
AOSP framework 解耦（provider 类名可配 + engine 分流）。
例外项也必须先在代码注释写明“为什么现成 API 不够用 + 上游有无对应 bug”，
否则按重复造轮子打回。
