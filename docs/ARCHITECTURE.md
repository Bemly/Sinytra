# ARCHITECTURE — Sinytra

> 目标：App 层完全不改 `android.webkit.WebView` 调用，底层从 Chromium 换成 Gecko。
> Source of truth 见 `AGENTS.md` 顶部：`TARGET_ANDROID_API = 34`，
> `AOSP_BASE = android14-release`。

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

## 2. GeckoView 三大核心类（不要发明自己的同等物）

- `GeckoRuntime`：**每个宿主 App 进程最多一个活动实例**——不是整个系统、
  也不是整个 provider APK 跨进程共享一个实例。代表一个运行中的 Gecko 实例，
  与宿主进程同寿命。（provider Java code 是被动态加载进每个使用 WebView 的
  宿主 App 进程的，见 `BOOTSTRAP.md` P-1。）
- `GeckoSession`：单个页面实例（可理解为一个 tab / 一个 WebView），导航/权限/进度/内容都挂在这里。
- `GeckoView`：Android `View`（`FrameLayout`），单个 session 的显示/输入宿主。
  典型用法是 attach 到 `GeckoView` 的 session 才 active；但 `GeckoSession.setActive()`
  本身是显式 API，无 `GeckoView` 的场景（如后台/不可见）要手调
  `setActive` / `setPriorityHint`（见下文第 4 条）。

## 3. 总体结构

> **GeckoView 不是 GeckoRuntime 的下一层。** `GeckoView` 是 session 的
> 显示/输入宿主；`GeckoSession` 经 `open(runtime)` 挂到 runtime，
> 经 `view.setSession(session)` 被显示出来。

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
                     GeckoRuntime（每个宿主 App 进程一个）
                          ↑
                          │ session.open(runtime)
                     GeckoSession
                       ↗      ↖
             SessionBridge    GeckoView（显示/输入宿主）
                                  ↑
                           GeckoViewHost
```

底层（随 Gecko 一起进来，不单独依赖 mozjs）：

```text
           Gecko
     ┌──────┼────────┐
    DOM   Necko   WebRender
     │
 SpiderMonkey
```

`WebViewFactoryProvider` 要求提供的全局单例，一个都不能少：
`Statics`、`CookieManager`、`GeolocationPermissions`、`ServiceWorkerController`、
`WebIconDatabase`、`WebStorage`、`WebViewDatabase`、`TracingController`、
`WebViewClassLoader`（`TokenBindingService` 已废弃，返回 null 即可），
外加 `createWebView(WebView, WebView.PrivateAccess)`。

分层依赖方向（单向，不许反向依赖）：

```text
provider（orchestration）
   ├─→ session（bridge：只做 android.webkit ↔ GeckoSession/Delegate 翻译，不碰 View）
   ├─→ view（GeckoView 宿主：own View/Surface/输入，调 session 接口）
   ├─→ settings / storage（无状态翻译 / 有状态下沉）
   └─→ compat（只依赖 session 公开桥接接口 + storage，不许直调 view/GeckoView 内部）
```

即：`session` 不依赖 `view`（bridge 与 UI host 解耦，才能做无 View 的后台 session
+ `setActive/setPriorityHint` 手动管理）；`compat/` 只允许依赖 `session/`
的公开桥接接口，不许直调 GeckoView 内部 API。

## 4. 历史语义

Gecko 自己维护 session 内 back/forward history，`SessionState` 本身即
`HistoryList`（Parcelable，含 history + current index）。
`HistoryDelegate` 不是“历史存储接口”，而是 visited 记录
（`onVisited / getVisited / hasVisitedHostSince`，provider 自己实现 visited store 落盘）
+ history 变更通知（`onHistoryStateChange`）。
`StateBridge` 只做 `SessionState / HistoryList ↔ WebBackForwardList / Bundle` 转译，
**不许**自己重新实现一套历史栈。

## 5. 关键运行时事实（写 bridge 代码前必须知道）

1. 普通顶层网络导航的**典型**事件流为：`onLoadRequest → onPageStart → onLocationChange →`
   `onProgressChange → onSecurityChange → onSessionStateChange →`
   `onCanGoBack/onCanGoForward → onPageStop`，中间重定向可穿插多次
   `onLoadRequest/onLocationChange`。
   这只是典型流，**不是全局不变量**：same-document 导航 / reload / error 等路径不同；
   上游也只保证 `onPageStart/onPageStop` 成对有序 + delegate 在 UI 线程等属性。
   WebView 回调适配一律以目标 GeckoView pin 版本的集成测试为准，不许把典型流当断言硬编码。
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

## 6. 语言策略（定稿，不要推翻）

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

### 6.1 分语言表（按仓库目录）

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

### 6.2 为什么不用 Kotlin 做核心

- Kotlin 调用 GeckoView 写普通 App 完全没问题，但 Sinytra 是贴着
  **Framework ABI 兼容层**：`implements WebViewProvider` 用 Java 最直接，
  没有 `Companion / DefaultImpls / Intrinsics / synthetic methods / metadata /
  nullable ABI / Kotlin runtime 依赖` 这些对 system provider 零收益的东西。
- provider 最终走 `Class.forName(providerClassName)` + 确定签名的静态工厂
  （如 `create(WebViewDelegate)`）加载，系统边界代码用 Java 最省事。
- 一旦混入 Kotlin，hidden API 反射、系统类加载、崩溃栈可读性都会变差。

### 6.3 为什么 C++ 不当主胶水

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

### 6.4 下沉到 patch 的触发条件（只限这几类）

`evaluateJavascript` 特殊行为、`addJavascriptInterface`、
特殊 WebMessage bridge、底层 request interception、
Surface/compositor 特殊行为、Gecko native 生命周期。
除此之外一律在 Java glue 层解决，不许以“性能”或“方便”为由下沉；
每个下沉项走 `firefox-patches/` 下的独立 patch + 独立测试（pin 与 rebase 规则见 `BOOTSTRAP.md` §3）。
