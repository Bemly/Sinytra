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

## 3. 仓库结构（本 Git 只存差异，不塞大源码树）

```text
sinytra/                            # = gecko-system-webview，本仓库
├── AGENTS.md
├── README.md
├── provider/                        # 独立 Java 17 Gradle Provider 工程（日常开发几乎只碰这里）
│   ├── build.gradle                 # 日常走 GeckoView Maven AAR（见 §7）
│   ├── AndroidManifest.xml          # 正式路线声明 provider 入口 metadata（见 §6）
│   ├── Android.bp                   # 进 AOSP 阶段才用（见 §12）
│   └── src/main/java/org/mozilla/geckowebview/
│       ├── provider/                # AOSP Provider 接口实现
│       │   ├── GeckoWebViewFactoryProvider.java
│       │   ├── GeckoWebViewProvider.java
│       │   └── GeckoWebViewStatics.java
│       ├── session/                 # GeckoSession 桥接
│       │   ├── GeckoSessionBridge.java
│       │   ├── NavigationBridge.java
│       │   ├── ProgressBridge.java
│       │   ├── ChromeClientBridge.java
│       │   └── PermissionBridge.java
│       ├── view/                    # View / 渲染宿主
│       │   ├── GeckoViewHost.java
│       │   ├── GeckoViewDelegate.java
│       │   └── GeckoScrollDelegate.java
│       ├── settings/
│       │   └── GeckoWebSettings.java
│       ├── storage/
│       │   ├── GeckoCookieManager.java
│       │   ├── GeckoWebStorage.java
│       │   ├── GeckoWebViewDatabase.java
│       │   └── GeckoGeolocationPermissions.java
│       └── compat/                  # 语义最难的兼容层
│           ├── JavascriptBridge.java   # addJavascriptInterface / evaluateJavascript
│           ├── WebMessageBridge.java   # WebMessagePort / postWebMessage
│           ├── RequestInterceptBridge.java
│           └── StateBridge.java        # saveState / restoreState / BackForwardList
├── aosp-patches/                    # 相对 AOSP 的 patch stack（见 §6、§12）
│   ├── 0001-allow-gecko-webview-provider.patch
│   └── 0002-skip-chromium-relro-for-gecko.patch
├── firefox-patches/                 # 相对 firefox 的 patch stack（见 §7，前期可为空）
│   └── ...
├── manifests/                       # repo manifest 片段（把本仓库接进 AOSP，见 §12）
│   └── gecko-webview.xml
├── tools/
│   ├── apply-aosp-patches.sh
│   └── apply-firefox-patches.sh
└── tests/
    ├── unit/
    ├── integration/
    └── cts/
```

> 旧命名映射：`patches/` = 现在的 `firefox-patches/`；
> `framework/` = 现在的 `aosp-patches/`；`gecko-webview/` = 现在的
> `provider/src/main/java/org/mozilla/geckowebview/`。看到旧名一律按新名理解。

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
   GeckoView 不是按 WebView 语义设计的，大概率要加 internal API（走 `firefox-patches/`）。
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
`WebViewFactory` 会反射调 `create(WebViewDelegate)` 静态工厂拿 provider；
`WebViewLibraryLoader` 仍有 `CHROMIUM_WEBVIEW_NATIVE_RELRO_32/64`、
RELRO/shared_relro、`WebViewZygote` 等 Chromium 专属假设；
provider 包名由 `config_webview_packages.xml` 决定（默认 `com.android.webview`）；
provider 加载前 framework 会读 provider APK 的 `com.android.webview.WebViewLibrary`
metadata 并预加载其 native 库。

### 6.1 测试 ROM：userdebug + config overlay（先做这个，不改 framework 也能验）

- 测试 ROM 用 `userdebug / eng` build——emulator 配置下 WebView provider
  签名检查可被忽略，正好适合开发第三方 provider。
- 在设备 overlay 或 `frameworks/base/core/res/res/xml/config_webview_packages.xml`
  中加入：

```xml
<webviewprovider
    description="Gecko WebView"
    packageName="org.mozilla.geckowebview"
    availableByDefault="true" />
```

- 之后“设置 → 开发者选项 → WebView 实现”里就能切到 `Gecko WebView`。
  这比每改一点就烧完整 system image 舒服太多；先走通这条切换链路，再碰 §6.2。

### 6.2 Framework patch：PoC trampoline（短期）→ metadata 自声明（正式）

**PoC 路线（允许进分支、不进主分支）**：Gecko APK 里直接提供 10～20 行
compatibility trampoline，AOSP 以为自己在加载 Chromium，实际拿到 Gecko：

```java
package com.android.webview.chromium;

public final class WebViewChromiumFactoryProviderForB {
    public static WebViewFactoryProvider create(WebViewDelegate delegate) {
        return new GeckoWebViewFactoryProvider(delegate);
    }
}
```

（`...ForT` 同理。）这样 PoC 期**零 framework 改动**即可验证。
但这是脏捷径，P0 验收后必须切正式路线，trampoline 不许合入主分支
（见 §8.3“无魔法”）。

**正式路线（唯一长期方案）**：provider 在 `AndroidManifest.xml` 自声明入口：

```xml
<meta-data
    android:name="android.webkit.WebViewFactoryClass"
    android:value="org.mozilla.geckowebview.GeckoWebViewFactoryProvider" />
```

AOSP 侧改成从 provider APK 的 metadata 读 factory 类名，而不是硬编码返回
`com.android.webview.chromium.*`（同时把 `CHROMIUM_WEBVIEW_FACTORY_METHOD`
之类命名泛化）。效果：

```text
              WebViewFactory
                   │
            provider metadata
              ┌────┴────┐
              ▼         ▼
          Chromium    Gecko
              │         │
            Blink     Gecko
```

### 6.3 `WebViewLibraryLoader` 按 engine 分流

`engine="chromium"` 走原 RELRO 路径；`engine="gecko"` 时 provider 自行
bootstrap（`GeckoRuntime/GeckoThread/libxul` + Gecko child processes），
**不许**让 Gecko 假装 Chromium RELRO loader。

### 6.4 顺序

先 §6.1（overlay 切换）→ PoC trampoline 跑 P0 → 再做 §6.2 正式解耦 +
§6.3 分流。以目标 ROM 分支的 `frameworks/base/core/java/android/webkit/`
为准改；`aosp-patches/` 里每个 patch 只做一件事（命名见 §3）。

---

## 7. GeckoView 依赖策略

### 7.1 三层开发模型（日常 / 本地 Gecko / 发布）

```text
① 绝大部分时间（日常开发，几乎只碰这里）

provider/（独立 Java 17 Gradle 工程）
        ↓ implementation "org.mozilla.geckoview:geckoview-nightly:<pin死版本>"
Mozilla Maven AAR

不用编 Firefox。改一次 Java adapter：
./gradlew assembleDebug && adb install -r GeckoWebView.apk
就能试。简单如 reload()/stopLoading()/goBack() 全是这种纯 Java 转发。
```

```text
② GeckoView public API 不够时（才启用本地 Gecko）

~/src/
├── sinytra/     ← 本仓库（Provider + patch stack，只存差异）
└── firefox/     ← Mozilla Firefox checkout（sibling，不进本 Git）

provider/build.gradle 加：
ext.topsrcdir = "/path/to/firefox"
ext.topobjdir = "/path/to/objdir"
apply from: "${topsrcdir}/substitute-local-geckoview.gradle"

Mozilla 官方支持的 dependency substitution：
Provider → 本地修改后的 GeckoView → 本地 Firefox/Gecko，
GeckoWebViewProvider.java 一行依赖代码都不用改。
触发条件见 §10.4（evaluateJavascript / addJavascriptInterface /
WebMessage / request intercept / Surface-compositor / native 生命周期）。
```

```text
③ 发布 / CI / 可重现

Firefox @ 固定 commit（后期再做成 git submodule pin）
        + firefox-patches/（逐个 rebase，冲突不解不升级）
        + provider/ 固定 source
```

- 开发早期**不要**用 git submodule 绑 firefox，也**不要**把 Provider 写进
  Firefox 源码树。Firefox 负责 Gecko+GeckoView，本仓库负责
  `android.webkit → GeckoView`，只有确实缺 primitive 才往下打 patch。
  这样 Firefox 156→157→158 时绝大部分 glue 不用跟着 rebase。
- Nightly 版本一律 pin 精确号，不用 `+`。

### 7.2 正式阶段 pin 策略

- pin 死 `mozilla-firefox/firefox` 某个 commit，
  只取 `mobile/android/geckoview`、`widget/android`、Gecko 引擎目录
 （`dom/layout/gfx/netwerk/js/src` 等），SpiderMonkey 随 Gecko 进来，
  **不**单独引 mozjs。
- 凡 GeckoView public API 覆盖不到、但 WebView 语义必需的能力，
  一律写成 `firefox-patches/` 下的独立 patch，每个 patch 只做一件事，
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

- 语言：见 §10（定稿）。胶水主体一律 Java 17；`provider/src/...`（按 §3 分包）
  只用 Java，不许进 Kotlin；Kotlin 只允许出现在 `tests/` 样例与工具脚本（占比 0～5%）；
  **一个模块内只用一种语言**。
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

## 10. 语言策略（定稿，不要推翻）

> **胶水主体一律 Java 17；只有需要修改 Gecko/GeckoView 内部能力时，才下沉到
> C++ / Gecko JS。Kotlin 不做核心 provider 语言，Rust 基本不碰。**

理由：两边要对接的 API 本来就是 Java——AOSP 侧
`WebViewFactoryProvider / WebViewProvider / ViewDelegate / ScrollDelegate /
WebSettings / CookieManager / WebStorage / …` 全是 Java 接口
（`WebView` 经 `createWebView()` 拿 provider）；
GeckoView 对 embedder 暴露的最外层
`GeckoRuntime / GeckoSession / GeckoView / GeckoResult / *Delegate` 同样是 Java，
官方示例即 Java，且当前 GeckoView 要求 Java 17 compatibility。
于是主路径是干净的 Java→Java：

```text
android.webkit.WebView
        │
        ▼
Java GeckoWebViewProvider
        │
        ├── GeckoSession / GeckoRuntime / GeckoView
        └── GeckoSession.*Delegate
                 │
                 ▼
          GeckoView internal（Mozilla 已有的 JNI/C++/JS）
                 │
                 ▼
               Gecko
```

### 10.1 分语言表（按 §3 目录）

| 位置 | 语言 | 说明 |
|---|---|---|
| `provider/src/...`（`provider/ session/ view/ settings/ storage/ compat/` 分包） | Java 17 | 全部 glue（含所有 bridge）；不许进 Kotlin |
| `aosp-patches/`（`frameworks/base/.../webkit/*` patch） | Java | 与 AOSP 侧注解/签名对齐 |
| `firefox-patches/` → `mobile/android/geckoview/` | Java | 给 GeckoView 加 internal API 时用 |
| `firefox-patches/` → `mobile/android/modules/` 等 | Gecko JavaScript | Module/Actor 层扩展（如 JS 执行、消息通道） |
| `firefox-patches/` → `widget/android/` | C++ | 仅 Surface/compositor/生命周期等不得不下沉时用 |
| `tests/` 样例与工具脚本 | 允许 Kotlin | 占比 0～5%，不进主胶水 |
| Rust | ~0% | Stylo/WebRender/URL 等是 Gecko backend 实现细节，不直调 |

预期自研代码占比：`Java 75～85% / Gecko JS 5～15% / C++ 5～10% /
Kotlin 0～5% / Rust ~0%`。**PoC 第一版甚至可以 95%+ Java**：
先跑通 `loadUrl→GeckoSession.load()`、Client→Delegate、View 生命周期挂载，
遇到 public API 表达不了的 WebView 语义，再逐项加 patch。

### 10.2 为什么不用 Kotlin 做核心

- Kotlin 调用 GeckoView 写普通 App 完全没问题，但 Sinytra 是贴着
  **Framework ABI 兼容层**：`implements WebViewProvider` 用 Java 最直接，
  没有 `Companion / DefaultImpls / Intrinsics / synthetic methods / metadata /
  nullable ABI / Kotlin runtime 依赖` 这些对 system provider 零收益的东西。
- provider 最终走 `Class.forName(providerClassName)` + 确定签名的静态工厂
  （如 `create(WebViewDelegate)`）加载，系统边界代码用 Java 最省事。
- 一旦混入 Kotlin，hidden API 反射、系统类加载、崩溃栈可读性都会变差。

### 10.3 为什么 C++ 不当主胶水

不要做成 `android.webkit → JNI → 巨大 C++ adapter → Gecko`。
那等于绕开 GeckoView 已解决的 View/IME/无障碍/Surface/生命周期/
多进程/权限/JNI/session，自己重造一套 embedding，工作量直接爆炸。
Mozilla 自己的分层就是外层 Java API + Java frontend、中间 JS modules/actors、
靠平台侧 `widget/android` C++、Java↔native 走 Mozilla JNI binding——
Sinytra 沿用它，只在必要处加接口，例如：

```text
Java GeckoSession.evaluateJavascript()
        ↓ EventDispatcher / JNI
Gecko JS Actor
        ↓ content process
SpiderMonkey
```

### 10.4 下沉到 patch 的触发条件（只限这几类）

`evaluateJavascript` 特殊行为、`addJavascriptInterface`、
特殊 WebMessage bridge、底层 request interception、
Surface/compositor 特殊行为、Gecko native 生命周期。
除此之外一律在 Java glue 层解决，不许以“性能”或“方便”为由下沉；
每个下沉项走 §7 的独立 patch + 独立测试。

---

## 11. 依赖复用（不要重复造轮子，硬性）

> **原则：凡 GeckoView / `androidx.webkit` / Android framework 已有能力，
> 一律直接复用，不自研、不包装一层同名类、不另起状态机。
> 写任何新 bridge / controller / manager 之前，必须先查
> GeckoView javadoc + 本节复用表，确认真的没有现成 API 才写。**

### 11.1 GeckoView 接入方式（直接复用官方产物）

- Prototype 直接用 Maven，不自己编译 Gecko：
  `https://maven.mozilla.org/maven2/`，
  `implementation "org.mozilla.geckoview:geckoview-${channel}:${version}"`，
  channel 取 `stable / beta / nightly` 之一并 pin 死版本号。
- 默认用 `geckoview`（Lite）包；只有明确需要 Glean 等附加库时才换
  `geckoview-omni`（Omni 把附加库打进 `libxul.so`，体积更大）。
  ExoPlayer 场景才看 `geckoview-exoplayer2-*`，WebView 项目默认不碰。
- `compileOptions { sourceCompatibility JavaVersion.VERSION_17 /
  targetCompatibility JavaVersion.VERSION_17 }`（GeckoView 要求的底线，
  与 §10 一致）。
- Runtime 生命周期照抄官方 quick-start 模式：进程级 `static GeckoRuntime` 单例，
  `GeckoRuntime.create(context[, settings])` 只调一次，
  `session.open(sRuntime)` + `view.setSession(session)` + `session.loadUri(url)`；
  预热用现成的 `warmUp()`，不要自己写 child process 拉起逻辑。

### 11.2 `GeckoRuntime` 级控制器（全部直接拿来用）

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

### 11.3 `GeckoSession` 现成能力（禁止自研同功能类）

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
  WebView 不需要就**不接**，不许以“以后可能用”引入（见 §11.5 例外表）。

### 11.4 Delegate → WebViewClient/WebChromeClient（对照表，缺一不可先查表）

session 级能设的 delegate 必须先从这张表找位置，找不到才新增 bridge 文件：

`Navigation / Progress / Content / Permission / Prompt / Media /
Scroll / CompositorScroll / History / SelectionAction / TextInput /
Print / MediaSession / Autocomplete / ContentBlocking / Experiment /
TranslationsSession`。

其中高频映射：`Navigation.onLoadRequest`→`shouldOverrideUrlLoading`、
`Progress.onPageStart/onPageStop`→`onPageStarted/onPageFinished`、
`Progress.onProgressChange`→`onProgressChanged`、
`Content.onTitleChange`→`onReceivedTitle`、
`Content.onFocusRequest`→焦点、`Content.onCloseRequest`→`onCloseWindow`、
`Prompt` 全家桶→JS dialog + HTTP auth + file chooser + select/autocomplete、
`HistoryDelegate`（GeckoView 不存历史，embedder 必须实现存取）
→`WebBackForwardList` + `gotoHistoryIndex/getCurrentIndex`。

### 11.5 `*Settings` 复用（WebSettings 只做翻译）

- `GeckoSessionSettings.Builder`：`userAgentMode (MOBILE/DESKTOP/VR)`、
  `viewportMode`、allowJavascript、contextId（多 profile 隔离，对应
  `clearDataForSessionContext`）、字体/缩放相关。
- `GeckoRuntimeSettings.Builder`：`enterpriseRootsEnabled(true)`（吃系统第三方 CA）、
  contentBlocking、crashHandler、telemetry 开关（`notifyTelemetryPrefChanged`）。
- `GeckoWebSettings` 不存独立状态，只做
  `android.webkit.WebSettings` ↔ 上面两个 Builder 的双向翻译；
  UA 读 `GeckoSession.getDefaultUserAgent()` 做基线，不要手写 UA 串。

### 11.6 `androidx.webkit` / Android framework 侧（兼容层不另起炉灶）

- Sinytra 是 provider，`androidx.webkit`（`WebViewCompat / WebSettingsCompat /
  WebViewClientCompat / WebChromeClientCompat / WebViewAssetLoader /
  ProcessGlobalConfig / TracingController / StartupFeature / WebViewFeature`）
  最终调的是 `android.webkit`。我们的义务是让 `WebViewFeature.isFeatureSupported`
  诚实返回 + 行为对齐，而不是重实现一套 `*Compat`。
- 本地资源加载：有 `WebViewAssetLoader`（`https://appassets.androidplatform.net`
  风格的 path handler）就复用它，不要自写 `shouldInterceptRequest` 静态资源分支；
  `shouldInterceptRequest` 只留真正需要改 Gecko 网络栈语义的场景（P2）。
- 进程配置：`ProcessGlobalConfig.apply` 一次性、WebView 加载前调用——provider 侧
  不要二次封装启动配置，只保证 Gecko bootstrap 在 WebView 加载前可被触发一次。

### 11.7 测试与构建（复用现成 harness）

- 单元测试：JUnit（GeckoView 官方有专门的 junit test framework：
  testing envelope + delegate 调用追踪/回放/等待语义），bridge 的顺序/线程/
  返回值断言优先用这套 harness + Mockito，不手写 latch 轮询。
- UI/集成：Espresso + 真机/模拟器跑 P0 验收脚本；CTS（WebView 相关用例）是 P2
  出货门槛，不通过逐项记录（Gecko 语义差异 / 未实现 / 上游 bug）。
- 构建：Gradle + Android Lint；第三方依赖（Maven GeckoView、AndroidX、JUnit、
  Mockito）全部 pin 死版本，和 Firefox commit pin 策略（§7）同等对待，
  升级逐个 rebase。

### 11.8 例外（允许自研的极少数）

只有这几类允许写新代码：GeckoView 明确不存数据的
（history 落盘、`WebViewDatabase` 表单/密码、`WebIconDatabase` 图标缓存）、
Chromium 语义 Gecko 侧无对应（`addJavascriptInterface` 反射语义、
`WebMessagePort` 通道语义、`saveState/Bundle` 转译）、
AOSP framework 解耦（§6 的 provider 类名可配 + engine 分流）。
例外项也必须先在代码注释写明“为什么现成 API 不够用 + 上游有无对应 bug”，
否则按重复造轮子打回。

---

## 12. 本地 workspace 布局（AOSP / Firefox 不进本 Git）

> **本 Git 只存差异**：`provider/` + `aosp-patches/` + `firefox-patches/` +
> `manifests/` + `tools/` + `tests/` + 文档。AOSP 与 Firefox 源码树永远是
> sibling checkout，不做 submodule、不复制进仓库、不把 provider 写进 Firefox 树。

推荐磁盘布局：

```text
~/src/
├── sinytra/                 ← 本仓库
│   ├── provider/
│   ├── aosp-patches/
│   ├── firefox-patches/
│   ├── manifests/
│   └── tests/
├── firefox/                 ← Mozilla Firefox checkout（sibling，见 §7.1②）
└── aosp/                    ← repo 管理的 AOSP checkout
    ├── .repo/
    ├── frameworks/base/
    ├── packages/apps/GeckoWebView/   ← 稳定后把本仓库接进来（见下）
    └── ...
```

### 12.1 本仓库接进 AOSP（稳定后）

用 repo manifest 把本仓库作为独立 project 落到 AOSP 树里，
仍是自己的 Git，不 fork 整个 AOSP：

```xml
<project name="Bemly/gecko-system-webview"
         path="packages/apps/GeckoWebView"
         revision="main"
         remote="github" />
```

片段放在本仓库 `manifests/gecko-webview.xml`。效果：`repo sync` 后
AOSP 与本项目自动落到正确位置；早期只读 `frameworks/base` 代码时可单独
`git clone platform/frameworks/base`，不拉整套 AOSP。

### 12.2 AOSP 拉取规模（三阶段）

1. **只写 adapter**（P0 前期）：不拉 AOSP。`WebViewProvider /
   WebViewFactoryProvider` 是 hidden API，普通 SDK 没有——针对目标系统完整
   `framework.jar` 做 `compileOnly`，或引用对应版本 AOSP 接口源码/stub。
2. **第一次替换 System WebView**：拉完整 AOSP checkout。改
   `frameworks/base/core/java/android/webkit/` + overlay/config
   （见 §6.1），在真机/模拟器验证“开发者选项 → WebView 实现 → Gecko WebView”。
3. **完整 ROM / CTS**：正常 AOSP build 环境出
   `system.img / product.img / system_ext.img...` 并跑 CTS（见 §9）。

升级路线与 `firefox-ios12` 同构：
`AOSP 官方源码 + aosp-patches/ + Firefox 固定 commit + firefox-patches/`，
Android 大版本 / Firefox 大版本升级时只 rebase patch stack，
绝大部分 Java glue 不用动。

---

## 13. 调试设备与目标 API（实测，已验证）

### 13.1 设备档案

| 项 | 值 |
|---|---|
| 设备 | MOONDROP MD-PH-001 |
| adb serial | `V885Q49L8TAMFEEE`（所有 adb 命令一律加 `-s V885Q49L8TAMFEEE`） |
| Android 版本 | 14（`ro.build.version.release=14`，codename REL） |
| API 等级 | 34（`ro.build.version.sdk=34`） |
| build 类型 | `user`，`release-keys`，`ro.debuggable=0`（production build） |
| root 方式 | Magisk（`/system/bin/su -> ./magisk`）；`adb root` 不可用，`adb shell su -c id` 可拿到 `uid=0(root)` |

### 13.2 目标 API（定稿）

- `compileSdk / targetSdk = 34`（对齐这台调试机 Android 14）。
- Provider APK 的 `targetSdkVersion` 必须 ≥ 33（TIRAMISU）：当前 AOSP
  `WebViewFactoryProvider.isCompatibleImplementationPackage()` 在旧入口下要求
  `targetSdkVersion >= MINIMUM_SUPPORTED_TARGET_SDK(33)`，实测机上
  `dumpsys webviewupdate` 显示 `Minimum targetSdkVersion: 33`。
- `minSdk` 按 GeckoView 要求定（GeckoView 底线 Java 17，见 §10/§11.1），
  不许低于 GeckoView nightly pin 版本的 minSdk。

### 13.3 机上现有 WebView（2026-09-20 实测）

- 当前在用：`com.google.android.webview` 154.0.8037.22（targetSdk 36）。
- 另装：`com.google.android.webview.canary` 155.0.8055.0（targetSdk 37）、
  `com.android.webview` 126.0.6478.246（targetSdk 34）。
- 切 provider 前后一律用 `dumpsys webviewupdate` 确认生效。

### 13.4 调试方法（这台机固定流程）

```bash
adb -s V885Q49L8TAMFEEE devices -l          # 确认在线
adb -s V885Q49L8TAMFEEE shell echo shell_ok # shell 通路
adb -s V885Q49L8TAMFEEE shell \
  "getprop ro.build.version.release; getprop ro.build.version.sdk"

# 需要 root 时：不用 adb root，用 Magisk su
adb -s V885Q49L8TAMFEEE shell "su -c id"
adb -s V885Q49L8TAMFEEE shell "su -c 'dumpsys webviewupdate'"

# 日常装 Provider 调 P0（见 §7.1①）
./gradlew assembleDebug && adb -s V885Q49L8TAMFEEE install -r GeckoWebView.apk
```

- 本机是 `user` build（非 `userdebug/eng`），没有 emulator 那种“忽略 provider
  签名检查”的便利（见 §6.1）；要进“开发者选项 → WebView 实现”切换，
  provider APK 需按系统 provider 要求签名/预装，不许靠改 framework 签名检查绕过。
- 需要读 framework 侧状态一律先 `dumpsys webviewupdate`，再看 logcat
 （tag 前缀 `Sinytra/<模块>`，见 §8.3），不要猜。

---

## Sources

- [GeckoView Architecture — Firefox Source Docs](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/contributor/geckoview-architecture.html)
- [WebViewFactoryProvider.java — platform/frameworks/base](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/webkit/WebViewFactoryProvider.java)
- [WebViewProvider.java — platform/frameworks/base](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/webkit/WebViewProvider.java)
- [WebView.java — platform/frameworks/base](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/webkit/WebView.java)
- [WebViewFactory.java — platform/frameworks/base](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/webkit/WebViewFactory.java)
- [WebViewLibraryLoader.java — platform/frameworks/base](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/webkit/WebViewLibraryLoader.java?pli=1)
- [config_webview_packages.xml — platform/frameworks/base](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/res/res/xml/config_webview_packages.xml)
- [WebView providers — chromium/android_webview/docs](https://github.com/chromium/chromium/blob/main/android_webview/docs/webview-providers.md)
- [AOSP system integration — WebView for AOSP system integrators](https://chromium.googlesource.com/chromium/src/+/main/android_webview/docs/aosp-system-integration.md)
- [Getting Started with GeckoView — Firefox Source Docs](https://mozilla.github.io/geckoview/consumer/docs/geckoview-quick-start)
- [GeckoSession API — GeckoView javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.html)
- [GeckoRuntime API — GeckoView javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntime.html)
- [StorageController API — GeckoView javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/StorageController.html)
- [SessionFinder API — GeckoView javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/SessionFinder.html)
- [GeckoView junit Test Framework — Firefox Source Docs](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/contributor/junit.html)
- [Substituting a local GeckoView — Firefox Source Docs](https://firefox-source-docs.mozilla.org/mobile/android/fenix/substituting-local-gv.html)
- [substitute-local-geckoview.gradle — searchfox](https://searchfox.org/firefox-main/source/substitute-local-geckoview.gradle)
- [Repo command reference — Android Open Source Project](https://source.android.com/docs/setup/reference/repo)
- [WebViewCompat — Android Developers](https://developer.android.com/reference/androidx/webkit/WebViewCompat)
- [WebViewAssetLoader — Android Developers](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
