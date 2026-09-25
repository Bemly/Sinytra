# ROADMAP — Sinytra

> 分阶段路线、P2 硬点清单、测试验收。开新阶段 / 认领硬点前读本文件。
> **顺序不许跳**：P-1 未通过不开 P0；P0 未验收通过，不开 P1；
> 语义难题全部归到 P2，不在 P0 期打补丁。

## 1. P-1 — Bootstrap 可行性验证（最高优先级，spike 性质）

见 `BOOTSTRAP.md` §1。结论只有“可行（锁定约束）”或“先修 process bootstrap patch”两种，
不许带着假设进 P0。

## 2. P0 — 跑起来（约 20% API，P-1 通过后的首个里程碑）

目标：`new WebView(context); w.loadUrl("https://example.com")` 能渲染，
App 感知不到底下是 Gecko。

- `GeckoRuntime` 宿主进程单例启动、`GeckoSession` 生命周期、`GeckoView` 挂载。
- `loadUrl / reload / stopLoading / goBack / goForward / canGoBack / canGoForward /
  getUrl / getTitle / getProgress`。
- `WebViewClient`：`onPageStarted / onPageFinished / shouldOverrideUrlLoading`。
- `WebChromeClient`：`onProgressChanged / onReceivedTitle`、基础 JS dialog。
- 基础 `WebSettings`：JavaScript 开关、UA、zoom、viewport、media autoplay。

验收：示例 App 打开 `https://example.com`，前进后退、标题、进度条行为正常。

## 3. P1 — 补齐系统能力

CookieManager、权限、文件选择、下载、SSL 回调、HTTP Auth、WebStorage、
geolocation、页内查找、打印。
（history 转译见 `ARCHITECTURE.md` §4：`SessionState/HistoryList ↔ WebBackForwardList`，
`HistoryDelegate` 只做 visited 记录 + history 变更通知。）

> **验收记录（2026-09-25）**：P1 完成。设备 harness 37 PASS
> （含 P1 新探针 cookiePolicy/print/permissionPrompt/geolocationPrompt）
> + JVM 53 锁。已落地：SSL onReceivedSslError、permission 决策回流、
> PrintBridge fd 写入；cookie/HTTP Auth/WebStorage/geolocation/查找均
> 有实装+探针。已登记遗留：download 的 onExternalResponse 在 opt 构建
> 未分派（0006 排查候选）；SSL proceed 语义（同查）；fileChooser e2e
> 归 CTS/手测（无手势无法自动化）。
>
> **补充（2026-09-25 晚，P2 第一批）**：download 判决反转——分派链在
> opt 构建正常（原探针把 setDownloadListener 调在 harness 中绑定系统
> Chromium 的 framework WebView 上），确定性 attachment 探针恢复，
> harness 38 PASS + P0 GLUE PASS；CookieManager 经 firefox-patches/0007
> 真实化（逐 cookie get/set/removeSessionCookies/hasCookies + 策略映射
> cookieBehavior）。0006 剩余候选：SSL proceed（cert-override 原语）、
> contentDisposition 透传。
>
> **补充（2026-09-25 深夜，P2 第二批）**：0006 SSL proceed 定稿
> （`SslErrorHandler.proceed()` 接真：delegate 通道带出失败证书 →
> nsICertOverrideService temporary override → 重载；设备 sslProceed
> 探针）；0008 contentDisposition 透传（纯 provider 侧）。harness
> **39 PASS** + P0 GLUE PASS、JVM 66 锁。P2 剩余：framework 面
> WebMessagePort（已于第三批闭案，无需 AOSP patch）、SW+拦截并存语义（储备）、
> a11y、切换主线化 + CTS（见 §4 第 10 项）。

## 4. P2 — 啃硬骨头（逐项建任务跟踪）

1. **`evaluateJavascript`**：WebView 要求任意 JS 在当前页面执行并异步回传 JSON 结果；
   GeckoView 不是按 WebView 语义设计的，大概率要加 internal API（走 `firefox-patches/`）。
   > **已落地（2026-09-23）**：预判被推翻——不需要 patch，内置 WebExtension
   > `sinytra-js` + `SessionController.setMessageDelegate` 即可（STATUS §1d）。
2. **`addJavascriptInterface`**：Chromium 那套 Java 反射 + `@JavascriptInterface` +
   线程/返回值/GC/对象生命周期的语义，GeckoView 没有天然对应实现，需自研
   `JavascriptBridge` + 可能的 GeckoView patch。
3. **`WebMessagePort / postWebMessage`**：消息通道生命周期与线程语义需逐项对齐。
   > **2026-09-25 闭案**：framework 面落地——`android.webkit.SinytraWebMessagePort`
   > 同包子类（框架 ctor 实为 public @SystemApi，旧"package-private"记录是
   > android.jar 桩误读），无需 AOSP patch；boundary 面早已全功能
   > （LiveMessagePort）。真机 fwPort 探针 via=page + 端口身份断言；
   > 端口转移（getPorts）仍为诚实缺口（transport 无原语）。
4. **`shouldInterceptRequest`**：拦截时机、线程、返回值语义差异大，单独建 bridge。
5. **`saveState / restoreState / WebBackForwardList`**：Gecko 的
   `onSessionStateChange` 序列化与 WebView 的状态模型不同，需 `StateBridge` 转译。
6. **回调顺序与重定向**：部分 App 依赖 Chromium 回调的调用次数/顺序/线程；
   用 `ARCHITECTURE.md` §5 的典型序列为基准写顺序适配测试锁死行为
   （不许把典型流当全局断言）。
7. **Cookie/Storage/ServiceWorker/RenderProcess**：逐项对齐，
   每项独立 patch + 独立测试，不许混在一个提交里。
8. **`androidx.webkit` support-library boundary glue（P2 独立大项）**：
   `androidx.webkit` 新功能经 provider APK 的
   `org.chromium.support_lib_glue.SupportLibReflectionUtil →
   WebViewProviderFactoryBoundaryInterface` 反射找 glue；
   Sinytra 最终要自己提供一套 support-library boundary glue，
   逐个想宣称支持的 `WebViewFeature` 对齐实现 + `isFeatureSupported` 诚实返回。
   光实现 framework `android.webkit.*` 不会自动获得 AndroidX 兼容。
9. **视觉面（GeckoViewHost 挂载 + Surface 生命周期）**：
   > **2026-09-25 闭案**：GeckoViewHost 作为 WebView 子视图挂进视图树
   > （AbsoluteLayout.LayoutParams；子视图 View 生命周期接管 Surface），
   > onPause/onResume → session.setActive；screencap 像素证据 +
   > visualSurface 探针（harness 41 PASS）。a11y v1 遍历链路（子视图 →
   > SessionAccessibility）随挂载免费连通（uiautomator 证据）；v2 深度
   > 对齐等 TalkBack/CTS 反馈。

10. **System WebView 切换主线化（root + AnyWebView，唯一部署路线）**：
   把 `poc/dev-option-switch` 的入口 trampoline + validity 约束搬进 master，
   以真实 `new WebView()` 路径重验 P0–P2（`BOOTSTRAP.md` §2.3/§2.4）。
   **这是 CTS 对照轮的前置条件。**

> 其余硬点状态：2（JS interface）同 1 走 `sinytra-js` 已落地；4
> （shouldInterceptRequest）经 firefox-patches 0001–0003 完整（body 替身/
> 请求面保真/子帧子资源/流式无上限）；5（StateBridge）、6（顺序由 harness 锁）、
> 7（Cookie 0007 / RenderProcess）、8（androidx glue 18 项）已落地——细节见
> `STATUS.md` §1b–§1p。

## 5. 测试与验收

- `tests/unit`：JUnit（JVM），覆盖每个 bridge 的映射逻辑与边界值。
- 设备集成：`provider/src/debug` harness（P0GlueActivity 编排 + P1/P2 探针），
  反射注入口径做 bridge 级回归；切换后的真实路径探针做最终验收。
- CTS：切换到 Sinytra 后在调试机上 `am instrument` 直跑 `CtsWebkitTestCases`
  （`CTS.md`），以 Chromium 基线 98.6% 为参照，是 P2 出货门槛；不通过的用例
  逐项记录原因（Gecko 语义差异 / 未实现 / 上游 bug）。
- 回归红线：P0 验收脚本在每次提交后可重跑，通过率 100% 才允许合入 P1/P2 改动。
