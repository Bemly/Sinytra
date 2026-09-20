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

## 4. P2 — 啃硬骨头（逐项建任务跟踪）

1. **`evaluateJavascript`**：WebView 要求任意 JS 在当前页面执行并异步回传 JSON 结果；
   GeckoView 不是按 WebView 语义设计的，大概率要加 internal API（走 `firefox-patches/`）。
2. **`addJavascriptInterface`**：Chromium 那套 Java 反射 + `@JavascriptInterface` +
   线程/返回值/GC/对象生命周期的语义，GeckoView 没有天然对应实现，需自研
   `JavascriptBridge` + 可能的 GeckoView patch。
3. **`WebMessagePort / postWebMessage`**：消息通道生命周期与线程语义需逐项对齐。
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

## 5. 测试与验收

- `tests/unit`：JUnit，覆盖每个 bridge 的映射逻辑与边界值。
- `tests/integration`：真机/模拟器，覆盖 P0 验收场景与 P1 系统能力。
- `tests/cts`：Android CTS WebView 相关用例全量通过是 P2 出货门槛；
  不通过的用例逐项记录原因（Gecko 语义差异 / 未实现 / 上游 bug）。
- 回归红线：P0 验收脚本在每次提交后可重跑，通过率 100% 才允许合入 P1/P2 改动。
