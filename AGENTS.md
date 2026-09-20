# AGENTS.md — Sinytra

> Sinytra 是一个第三方 Android System WebView 实现：App 层**完全不改**
> `android.webkit.WebView` 调用代码，底层从 Chromium WebView 换成 Firefox/Gecko。
>
> 标准调用链：
>
> ```text
> android.webkit.WebView
>   → WebViewFactoryProvider（AOSP hidden/System API）
>     → Sinytra Gecko 胶水层
>       → GeckoView（GeckoSession / GeckoRuntime）
>         → Gecko（含 SpiderMonkey）
> ```

---

## 1. 技术选型（定稿，不要推翻）

| 组件 | 用不用 | 原因 |
|---|---|---|
| Gecko / Firefox 源码树 | ✅ | 真正的浏览器内核，唯一能替代 Chromium 的 engine |
| **GeckoView（核心）** | ✅ | Gecko 官方 Android embedding API；已解决 Surface/IME/触摸/无障碍/生命周期/多进程/权限/弹窗/下载/媒体/WebRTC 等平台层问题 |
| mozjs（standalone SpiderMonkey） | ❌ | 只有 JS/Wasm/GC/JIT，没有 HTML/DOM/CSS/layout/网络/导航/合成器，做不成 WebView |
| Android Components | ❌ | 那是浏览器产品层抽象（tabs/state/toolbar），会与 `android.webkit` 语义打架，多一层状态同步 |
| Fenix | ❌ | Firefox Android 浏览器前端，与本项目无关 |
| AOSP `WebViewProvider` API | ✅（核心） | 本项目真正要实现的 System WebView 胶水接口 |

Mozilla 官方分层本来就是：

```text
Gecko → GeckoView → Android Components → Fenix / Focus
```

Sinytra 只取下半截，做：

```text
AOSP WebView API →（Sinytra glue）→ GeckoView → Gecko
```

GeckoView 三大核心类（不要发明自己的同等物）：

- `GeckoRuntime`：进程级单例，代表一个运行中的 Gecko 实例，与 App 同寿命。
- `GeckoSession`：单个页面实例（可理解为一个 tab / 一个 WebView），导航/权限/进度/内容都挂在这里。
- `GeckoView`：Android `View`（`FrameLayout`），负责把 `GeckoSession` 画出来并接输入事件。只有 attach 到 `GeckoView` 的 session 才是 active 的。

---

## 2. 总体架构

```text
              Android App（零改动）
                      │
                      │ 标准 android.webkit.WebView API
                      ▼
            android.webkit.WebView（framework 代理类）
                      │
                      │ AOSP hidden/System API
                      ▼
            WebViewFactoryProvider
                      │
            ┌─────────┴──────────┐
            ▼                    ▼
 GeckoWebViewProvider    Gecko 单例族（Cookie/WebStorage/…）
            ▼
      GeckoSessionBridge
            ▼
       GeckoSession
            │
       GeckoRuntime（进程单例）
            │
         GeckoView（View/Surface）
            ▼
           Gecko
     ┌──────┼────────┐
    DOM   Necko   WebRender
     │
 SpiderMonkey（随 Gecko 一起进来，不单独依赖 mozjs）
```

GeckoView 的 Delegate 就是天然的翻译层：

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
| 历史持久化 | `HistoryDelegate`（GeckoView 自己不存历史，由 embedder 存） |
| 自动填充 | `Autocomplete.StorageDelegate` + Autofill 虚拟节点树 |

GeckoView 关键运行时事实（写 bridge 代码前必须知道）：

1. 页面加载回调序列固定为：`onLoadRequest → onPageStart → onLocationChange →`
   `onProgressChange → onSecurityChange → onSessionStateChange →`
   `onCanGoBack/onCanGoForward → onPageStop`，`onPageStart/onPageStop` 成对有序，
   中间可穿插多次 `onLoadRequest/onLocationChange`（重定向）。
   Chromium WebView 的回调顺序与此**不完全一致**，顺序适配是本项目核心难点之一。
2. Delegate 回调基本发生在 Android UI 线程；GeckoView 内部桥接了 Gecko 主线程与
   Android UI 线程。耗时工作不要在 delegate 回调里做。
3. `GeckoResult` 是类 Promise 的异步原语，会记住创建线程并在该线程执行回调；
   与 Gecko 侧 `MozPromise` 互转。所有异步 bridge 统一用它，不手写 latch/callback 缝合。
4. 默认用 `SurfaceView` 渲染（`TextureView` 可变形但更慢更占内存）。
   后台/不可见时 surface 被回收，session 会被冻结；不用 `GeckoView` 的场景要手调
   `GeckoSession.setActive` / `setPriorityHint`。
5. Gecko 自带 CA 证书库，默认不用系统 CA；需要系统第三方根证书时开
   `GeckoRuntimeSettings.enterpriseRootsEnabled(true)`。

---

## 3. 仓库结构

```text
sinytra/
├── AGENTS.md
├── README.md
├── patches/                        # 针对 firefox tree 的 patch set（见 §7）
│   ├── geckoview-evaluate-js.patch
│   ├── geckoview-js-interface.patch
│   ├── geckoview-webmessage.patch
│   ├── geckoview-webview-cookies.patch
│   └── geckoview-system-provider.patch
├── framework/                      # 对 AOSP frameworks/base 的最小改动（见 §6）
│   └── ...
├── gecko-webview/
│   ├── provider/                   # AOSP Provider 接口实现
│   │   ├── GeckoWebViewFactoryProvider.java
│   │   ├── GeckoWebViewProvider.java
│   │   └── GeckoWebViewStatics.java
│   ├── session/                    # GeckoSession 桥接
│   │   ├── GeckoSessionBridge.java
│   │   ├── NavigationBridge.java
│   │   ├── ProgressBridge.java
│   │   ├── ChromeClientBridge.java
│   │   └── PermissionBridge.java
│   ├── view/                       # View / 渲染宿主
│   │   ├── GeckoViewHost.java
│   │   ├── GeckoViewDelegate.java
│   │   └── GeckoScrollDelegate.java
│   ├── settings/
│   │   └── GeckoWebSettings.java
│   ├── storage/
│   │   ├── GeckoCookieManager.java
│   │   ├── GeckoWebStorage.java
│   │   ├── GeckoWebViewDatabase.java
│   │   └── GeckoGeolocationPermissions.java
│   └── compat/                     # 语义最难的兼容层
│       ├── JavascriptBridge.java   # addJavascriptInterface / evaluateJavascript
│       ├── WebMessageBridge.java   # WebMessagePort / postWebMessage
│       ├── RequestInterceptBridge.java
│       └── StateBridge.java        # saveState / restoreState / BackForwardList
└── tests/
    ├── unit/
    ├── integration/
    └── cts/
```

`WebViewFactoryProvider` 要求提供的全局单例，一个都不能少：
`Statics`、`CookieManager`、`GeolocationPermissions`、`ServiceWorkerController`、
`WebIconDatabase`、`WebStorage`、`WebViewDatabase`、`TracingController`、
`WebViewClassLoader`（`TokenBindingService` 已废弃，返回 null 即可），
外加 `createWebView(WebView, WebView.PrivateAccess)`。

分层依赖方向（单向，不许反向依赖）：

```text
provider → session → view
   ↓          ↓
settings    storage
   ↓          ↓
        compat
```

`compat/` 只允许依赖 `session/` 的公开桥接接口，不许直调 GeckoView 内部 API。

---

## 4. 分阶段路线

### P0 — 跑起来（约 20% API，首个里程碑）

目标：`new WebView(context); w.loadUrl("https://example.com")` 能渲染，
App 感知不到底下是 Gecko。

- `GeckoRuntime` 进程单例启动、`GeckoSession` 生命周期、`GeckoView` 挂载。
- `loadUrl / reload / stopLoading / goBack / goForward / canGoBack / canGoForward /
  getUrl / getTitle / getProgress`。
- `WebViewClient`：`onPageStarted / onPageFinished / shouldOverrideUrlLoading`。
- `WebChromeClient`：`onProgressChanged / onReceivedTitle`、基础 JS dialog。
- 基础 `WebSettings`：JavaScript 开关、UA、zoom、viewport、media autoplay。

验收：示例 App 打开 `https://example.com`，前进后退、标题、进度条行为正常。

### P1 — 补齐系统能力

CookieManager、history（`HistoryDelegate` 落盘由我们做）、权限、文件选择、
下载、SSL 回调、HTTP Auth、WebStorage、geolocation、页内查找、打印。

### P2 — 啃硬骨头（见 §5）

`evaluateJavascript`、`addJavascriptInterface`、`WebMessagePort`、
`shouldInterceptRequest`、`loadDataWithBaseURL`、`saveState/restoreState`、
`WebBackForwardList`、`ServiceWorkerController`、`WebViewRenderProcess`、
`androidx.webkit` 兼容、CTS 全量通过。

**顺序不许跳**：P0 未验收通过，不开 P1；语义难题全部归到 P2，不在 P0 期打补丁。

---

## 5. 已知硬点（P2 清单，逐项建任务跟踪）

1. **`evaluateJavascript`**：WebView 要求任意 JS 在当前页面执行并异步回传 JSON 结果；
   GeckoView 不是按 WebView 语义设计的，大概率要加 internal API（走 `patches/`）。
2. **`addJavascriptInterface`**：Chromium 那套 Java 反射 + `@JavascriptInterface` +
   线程/返回值/GC/对象生命周期的语义，GeckoView 没有天然对应实现，需自研
   `JavascriptBridge` + 可能的 GeckoView patch。
3. **`WebMessagePort / postWebMessage`**：消息通道生命周期与线程语义需逐项对齐。
4. **`shouldInterceptRequest`**：拦截时机、线程、返回值语义差异大，单独建 bridge。
5. **`saveState / restoreState / WebBackForwardList`**：Gecko 的
   `onSessionStateChange` 序列化与 WebView 的状态模型不同，需 `StateBridge` 转译。
6. **回调顺序与重定向**：部分 App 依赖 Chromium 回调的调用次数/顺序/线程；
   用 §2 的固定序列为基准写顺序适配测试锁死行为。
7. **Cookie/Storage/ServiceWorker/RenderProcess/`androidx.webkit`**：逐项对齐，
   每项独立 patch + 独立测试，不许混在一个提交里。

---

## 6. AOSP Framework 改动（只做最小解耦）

现状（已核实）：`WebViewFactoryProvider.getWebViewFactoryClassName()` 按
`Flags.useBEntryPoint()` 硬编码返回
`com.android.webview.chromium.WebViewChromiumFactoryProviderForT/ForB`；
`WebViewLibraryLoader` 仍有 `CHROMIUM_WEBVIEW_NATIVE_RELRO_32/64`、
RELRO/shared_relro、`WebViewZygote` 等 Chromium 专属假设；
provider 包名由 `config_webview_packages.xml` 决定（默认 `com.android.webview`）；
provider 加载前 framework 会读 provider APK 的 `com.android.webview.WebViewLibrary`
metadata 并预加载其 native 库。

路线（**不**伪造 `com.android.webview.chromium.*` 类名做 PoC 捷径，长期维护会烂掉）：

1. 把 provider 类名做成系统属性可配（示例）：
   `ro.webview.provider_class`，默认保持 Chromium 实现，Gecko 设备覆盖为
   `org.mozilla.geckowebview.GeckoWebViewFactoryProvider`；并把
   `CHROMIUM_WEBVIEW_FACTORY_METHOD` 之类命名泛化。
2. `WebViewLibraryLoader` 按 engine 分流：`engine="chromium"` 走原 RELRO 路径；
   `engine="gecko"` 时 provider 自行 bootstrap（`GeckoRuntime/GeckoThread/libxul`
   + Gecko child processes），**不许**让 Gecko 假装 Chromium RELRO loader。
3. 以目标 ROM 分支的 `frameworks/base/core/java/android/webkit/` 为准改，
   先做这一步解耦，再写 Gecko glue。

---

## 7. GeckoView 依赖策略

- **Prototype 阶段**：允许 Maven 依赖
  `org.mozilla.geckoview:geckoview(-nightly):...` 快速验证 P0。
- **正式阶段**：pin 死 `mozilla-firefox/firefox` 某个 commit，
  只取 `mobile/android/geckoview`、`widget/android`、Gecko 引擎目录
 （`dom/layout/gfx/netwerk/js/src` 等），SpiderMonkey 随 Gecko 进来，
  **不**单独引 mozjs。
- 凡 GeckoView public API 覆盖不到、但 WebView 语义必需的能力，
  一律写成 `patches/` 下的独立 patch（命名见 §3），每个 patch 只做一件事，
  附带说明：解决哪个 WebView API、为什么 public API 不够用、上游有无对应 bug。
- 升级 Firefox commit 时逐个 rebase patch，冲突不解决不许升级。

---

## 8. 工程铁律（所有 Agent / 贡献者必须遵守）

### 8.1 单文件 ≤ 1000 行（硬性）

- 任何源码文件（含注释空行，总行数）**不得超过 1000 行**。
- 接近 900 行时就必须拆分：按职责拆类/拆包，拆分方向见 §3 的目录职责。
- 检查命令：`wc -l <file>`；超限的提交会被直接打回。
- 文档（`.md`）不受此限，但也要保持精简。

### 8.2 每次修改必提交（硬性）

- 每个逻辑修改完成后**立即** `git add` + `git commit`，不攒大提交，不隔夜提交。
- 一个提交只做一件事：一个 bridge / 一个单例 / 一个 patch / 一组测试。
- 提交信息用 Conventional Commits，前缀按目录：
  `feat(provider): …`、`fix(session): …`、`feat(storage): …`、
  `fix(compat): …`、`chore(framework): …`、`docs: …`、`test: …`。
- 提交前必须 `git diff --stat` 自检：确认改动文件都在预期内、无调试残留、无超 1000 行文件。

### 8.3 编程范式

- 语言：与 AOSP `android.webkit` 对接层用 Java（与 framework 侧注解/签名对齐）；
  其余模块可用 Kotlin，但**一个模块内只用一种语言**。
- Java 风格遵循 AOSP 规范：`@NonNull/@Nullable` 全覆盖 public API，
  参数校验在入口做，内部信任已校验值。
- 一个类只做一件事：bridge 类只做“翻译”，不缓存业务状态；
  需要存状态的（如历史、cookie）下沉到 `storage/`。
- 线程纪律：GeckoView 调用与 delegate 回调默认在 UI 线程；
  阻塞 IO（落盘、网络取数）一律切后台线程，结果贴回 UI 线程交付。
- 异步统一用 `GeckoResult` 链式，不手写 `CountDownLatch` 缝合异步回调；
  超时与取消路径必须显式处理。
- 错误处理：delegate 回调异常不许向上传播崩掉 App，
  按 WebView 语义降级（调 `onReceivedError` / 返回安全默认值）并打日志。
- 安全：`addJavascriptInterface` 的反射暴露面默认最小化；
  自定义 scheme / 拦截器默认拒绝文件访问，按 allowlist 放行。
- 日志：统一 tag 前缀 `Sinytra/<模块>`，`DEBUG` 详细、`INFO` 关键状态机跳转，
  发布构建默认关闭 verbose。
- 无魔法：禁止用反射伪造 Chromium 类名/方法签名做兼容（PoC 也不许进主分支）；
  跨进程/跨线程假设必须写成注释 + 测试。
- 测试：每个 bridge 至少一个单测锁死“调用顺序/线程/返回值”；
  回调顺序类行为用集成测试固化（见 §5.6）。

### 8.4 Agent 工作流

1. 动手前先读相关现有文件，理解分层位置；不读代码不许提方案。
2. 小步快跑：一次只改一个文件（或一个紧密相关的文件组），改完即测即提交。
3. 跑 `lint + unit test` 通过后再提交；失败就地修，不带病提交。
4. 新文件落盘位置必须符合 §3；拿不准先问，不乱建目录。

---

## 9. 测试与验收

- `tests/unit`：JUnit，覆盖每个 bridge 的映射逻辑与边界值。
- `tests/integration`：真机/模拟器，覆盖 P0 验收场景与 P1 系统能力。
- `tests/cts`：Android CTS WebView 相关用例全量通过是 P2 出货门槛；
  不通过的用例逐项记录原因（Gecko 语义差异 / 未实现 / 上游 bug）。
- 回归红线：P0 验收脚本在每次提交后可重跑，通过率 100% 才允许合入 P1/P2 改动。

---

## Sources

- [GeckoView Architecture — Firefox Source Docs](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/contributor/geckoview-architecture.html)
- [WebViewFactoryProvider.java — platform/frameworks/base](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/webkit/WebViewFactoryProvider.java)
- [WebViewProvider.java — platform/frameworks/base](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/webkit/WebViewProvider.java)
- [WebViewLibraryLoader.java — platform/frameworks/base](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/webkit/WebViewLibraryLoader.java?pli=1)
- [config_webview_packages.xml — platform/frameworks/base](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/res/res/xml/config_webview_packages.xml)
- [WebView providers — chromium/android_webview/docs](https://github.com/chromium/chromium/blob/main/android_webview/docs/webview-providers.md)
- [AOSP system integration — WebView for AOSP system integrators](https://chromium.googlesource.com/chromium/src/+/main/android_webview/docs/aosp-system-integration.md)
