# AGENTS.md — Sinytra

> Sinytra 是一个第三方 Android System WebView 实现：App 层**完全不改**
> `android.webkit.WebView` 调用代码，底层从 Chromium WebView 换成 Firefox/Gecko。
>
> ```text
> android.webkit.WebView
>   → WebViewFactoryProvider（AOSP hidden/System API）
>     → Sinytra Gecko 胶水层
>       → GeckoView（GeckoSession / GeckoRuntime）
>         → Gecko（含 SpiderMonkey）
> ```

> **唯一部署目标（定稿，2026-09-26 用户拍板）**：已 root + LSPosed + AnyWebView
> 的**现有**手机上，经「开发者选项 → WebView 实现」（等价
> `cmd webviewupdate set-webview-implementation`）把 Sinytra 切成 system
> WebView 跑起来。**不刷机**：不自建/改 ROM、不改 framework、不做
> `aosp-patches/`、不做 overlay/预装/系统签名、不走 ROM 阶段 CTS。
> 细节见 `docs/BOOTSTRAP.md` §2。

> **Implementation source of truth（定稿，Agent 查代码一律以此为准）：**
>
> ```text
> TARGET_ANDROID_API = 34
> AOSP_BASE = android14-release（只作接口语义/类名/加载流程的参考源；不编 AOSP、
>   不 pin commit——运行期事实以调试机实际 framework 为准，存疑时从设备拉
>   /system/framework/framework.jar 反编译核对）
> GECKOVIEW_VERSION = geckoview-nightly:158.0.20260924093433（provider/build.gradle 已 pin）
> FIREFOX_COMMIT = 34ed69f161676c3ac7ca5f201fade6d081e5bcd2
>   （mozilla-central 158.0a1 最后一刻，2026-09-25 定稿；158.0 正式 tag
>    未发布，出来后再评估切 release tag——version.txt=158.0a1 已核，
>    对齐上面的 nightly AAR。sibling checkout：<U+F8FF 卷>/Projects/firefox，
>    分支 sinytra-pin-158——与仓库同卷、非系统盘，磁盘以该卷为准；卷名
>    字面路径经 shell 传参编码不稳定，用 ~/sinytra-vol 符号链接访问，
>    详见 firefox-patches/README.md；153 线冻结在 sinytra-pin 分支；
>    本地替换默认关闭，-PsinytraLocalGecko 启用）
> ```
>
> - AOSP 接口语义、类名、加载流程一律以 `AOSP_BASE`（Android 14）为准；
>   master/main 只做“未来版本参考”，**不许**按 master 写 Android 14 的代码。
> - 关键差异示例：Android 14 的 `WebViewFactory` 直接硬编码
>   `CHROMIUM_WEBVIEW_FACTORY =
>   "com.android.webview.chromium.WebViewChromiumFactoryProviderForT"`，
>   没有 master 那套 `Flags.useBEntryPoint()` + `ForB/ForT` 分流，也不读
>   provider 的任何“factory 类名” metadata——不改 framework 的前提下，这个
>   类名就是 Sinytra 唯一的入口（见 §5 trampoline 条）。

## Docs（细节住这里，本文件只留硬规则）

| 文件 | 内容 |
|---|---|
| `docs/ARCHITECTURE.md` | 技术选型、三核心类、结构图、分层依赖、历史语义、运行时事实、语言策略 |
| `docs/API_MAPPING.md` | GeckoView delegate / controller / settings 对照表（写 bridge 前先查） |
| `docs/BOOTSTRAP.md` | P-1 验收、AnyWebView 切换路线（入口/validity 约束）、三层依赖模型、workspace 布局 |
| `docs/ROADMAP.md` | P-1 → P0 → P1 → P2、P2 硬点清单、测试验收 |
| `docs/STATUS.md` | **新会话先读我**：实现进展、下一步、已知阻塞 |
| `docs/DEVICE.md` | 调试机档案（root/LSPosed/AnyWebView）、目标 API、adb 与切换流程 |
| `docs/CTS.md` | CTS 跑法（切换后设备上 `am instrument` 直跑） |
| `docs/SOURCES.md` | 上游引用（AOSP 一律 android14-release） |

读文档顺序：先本文件 → `STATUS.md` → `ARCHITECTURE.md` → 动手前读 `API_MAPPING.md` +
`BOOTSTRAP.md` 对应章节。

## 1. 仓库结构（本 Git 只存差异）

```text
sinytra/
├── AGENTS.md
├── docs/                            # 见上表
├── provider/                        # 独立 Java 17 Gradle Provider 工程（日常开发几乎只碰这里）
│   ├── build.gradle                 # 日常走 GeckoView Maven AAR；-PsinytraLocalGecko 换本地树
│   └── src/
│       ├── main/AndroidManifest.xml # validity 所需 metadata（WebViewLibrary）
│       ├── main/java/org/mozilla/geckowebview/
│       │   └── provider/ session/ view/ settings/ storage/ compat/ runtime/
│       ├── main/java/com/android/webview/chromium/  # framework 硬编码入口 trampoline（§5）
│       ├── main/java/android/webkit/                # 同包子类（SslErrorHandler/WebMessagePort 等）
│       ├── main/java/org/chromium/support_lib_glue/ # androidx.webkit boundary 入口
│       ├── main/assets/             # sinytra-js 内置 transport + geckoview-config.yaml
│       └── debug/                   # 设备 harness / 探针（release 零引用）
├── framework-stubs/                 # compileOnly 的 android14 hidden API stubs
├── firefox-patches/                 # 相对 firefox 的 patch stack
└── tests/unit/                      # JVM 单测（设备集成测试 = provider/src/debug harness）
```

- Firefox 源码树永远是 sibling checkout：不做 submodule、不复制进仓库、
  不把 provider 写进 Firefox 树、不拼不完整 Gecko fork。**不拉 AOSP 树**。
- `WebViewFactoryProvider` 全局单例一个不能少：`Statics`、`CookieManager`、
  `GeolocationPermissions`、`ServiceWorkerController`、`WebIconDatabase`、
  `WebStorage`、`WebViewDatabase`、`TracingController`、`WebViewClassLoader`
  （`TokenBindingService` 已废弃，返回 null），外加
  `createWebView(WebView, WebView.PrivateAccess)`。

## 2. 分层与关键语义（违反即打回）

- 依赖单向：`provider（orchestration）├─→ session / view / settings+storage /
  compat`；`runtime/`（进程级 `GeckoRuntimeHolder`）是最底层，任何包可依赖，
  它不依赖任何自有包；除 provider 与入口类外，**谁都不许依赖 `provider/`**。**`session` 不依赖 `view`**（bridge 只做翻译、不碰 View，才能做无 View
  后台 session + `setActive/setPriorityHint` 手动管理）；`compat/` 只许依赖
  `session/` 公开接口 + `storage`，不许直调 `view`/GeckoView 内部。
- `GeckoRuntime`：**每个宿主 App 进程最多一个活动实例**——不是全系统、也不是
  provider APK 跨进程共享。provider code 是被加载进每个用 WebView 的宿主进程的。
- `GeckoView` 不是 `GeckoRuntime` 的下一层：它是单个 session 的显示/输入宿主
  （`session.open(runtime)` + `view.setSession(session)`）。
- 历史：Gecko 自己维护 session 内 history（`SessionState` 本身即 `HistoryList`）；
  `StateBridge` 只做 `SessionState/HistoryList ↔ WebBackForwardList/Bundle` 转译，
  **不许**自建历史栈；`HistoryDelegate` 只做 visited 记录 + 变更通知。
- 回调序列只是**典型流**（顶层网络导航），不是全局不变量；不许把典型流当断言硬编码，
  以 pin 住的 GeckoView 版本的集成测试为准。
- GeckoView API 以 pin 住的 `GECKOVIEW_VERSION` 对应 Javadoc 为准
  （如 `warmUp()` 仅 ≥ v150 存在）；未 pin 前不许当固定 API 用。

## 3. 语言（定稿）

- 胶水主体一律 **Java 17**；`provider/src/...` 只用 Java，不许进 Kotlin；
  Kotlin 只允许 `tests/` 样例与工具脚本（0～5%）；**一个模块内只用一种语言**。
- C++ / Gecko JS 只在 GeckoView public API 确实缺 primitive 时下沉
  （`evaluateJavascript` 特殊行为、`addJavascriptInterface`、特殊 WebMessage、
  底层 request intercept、Surface/compositor、native 生命周期），每个下沉项走
  `firefox-patches/` 独立 patch + 独立测试；Rust 基本不碰。

## 4. 复用优先（硬性）

凡 GeckoView / `androidx.webkit` / framework 已有能力，一律直接复用，不自研、
不包一层同名类、不另起状态机。写 bridge 前先查 `API_MAPPING.md` + GeckoView javadoc。
只有这几类允许自研：GeckoView 明确不存数据的（visited 落盘、表单/密码、图标缓存）、
Chromium 语义 Gecko 无对应的（JS interface 反射、WebMessage 通道、`Bundle` 转译）、
不改 framework 前提下的接入适配（硬编码类名 trampoline、framework 同包子类）
——且必须在注释写明“为什么现成 API 不够 + 上游 bug”。

## 5. 禁止事项（硬性）

- 不许按 AOSP master 写 Android 14 代码。
- 不许改 framework / 刷机 / 依赖 ROM 侧改动（overlay、预装、系统签名、
  `aosp-patches/`）——provider 的 validity 由 AnyWebView 放行，切换走开发者选项。
- **trampoline 条（2026-09-26 反转旧禁令）**：`com.android.webview.chromium.
  WebViewChromiumFactoryProviderForT` trampoline 是不改 framework 时唯一入口，
  **允许且必须进主分支**；但它只许做“入口转发 + 描述符适配”（Proxy 实现真实
  接口、转发到 `GeckoWebViewFactoryProvider`），不许承载业务逻辑。
- 不许伪装 Chromium bootstrap：不做 dummy RELRO / 假 `.so` / 顶
  `com.android.webview` 包名。`WebViewLibrary` metadata 指向真实的
  `libxul.so`（只为过 validity 的存在性检查；framework 的 RELRO 预加载对它
  失败属预期且无害，Gecko 自己经 GeckoLoader 加载）。
- 不许只抽几个 Firefox 目录拼 fork：pin 就 pin **完整**源码树。
- 不许为“以后可能用”接 WebExtension / Translations / 页面抽取等不需要的能力
  （`sinytra-js` 内置扩展是 eval/JS interface/WebMessage 的必需 transport，已登记例外）。

## 6. 阶段门槛（硬性，顺序不许跳）

```text
P-1（bootstrap spike，最高优先级）→ P0（~20% API 跑起来）→ P1（系统能力）→ P2（硬骨头 + 切换后 CTS）
```

- **P-1 不通过不开 P0**：provider 进宿主进程后的 classloader、`libxul.so`、
  `create()` 用哪个 Context、child process Service bind、package/UID/SELinux、
  manifest components 可解析性——全部实测回答，写不出就停。失败则先修 process
  bootstrap patch（优先级高于一切 bridge）。
- P0 未验收不开 P1；语义难题全归 P2。`androidx.webkit` 兼容 = 自研 support-library
  boundary glue（P2 独立大项），不是实现 framework API 自动获得。
- 最终验收一律在**切换后的真实路径**上做（`new WebView()` 经 framework
  `WebViewFactory` 进入 Sinytra）；反射注入 harness 只是 bridge 级回归手段。
- 各阶段范围与验收见 `ROADMAP.md`。

## 7. 工程铁律

- 单文件 ≤ 1000 行（含注释空行）；近 900 行必须按 `docs/` 职责拆分；`wc -l` 自检。
- 每次逻辑修改立即 `git add + git commit`；一提交只做一件事；Conventional Commits
  前缀按目录（`feat(provider)` / `fix(session)` / `feat(storage)` / `fix(compat)` /
  `chore(framework)` / `docs:` / `test:`）；提交前 `git diff --stat` 自检。
- Agent 工作流：先读相关现有文件再提方案；一次只改一个文件（组）；`lint + unit test`
  通过再提交；新文件位置符合 §1。
- Java 风格：AOSP 规范，`@NonNull/@Nullable` 全覆盖 public API，入口校验参数；
  bridge 只翻译不缓存状态，状态下沉 `storage/`。
- 线程/异步：GeckoView 调用与 delegate 回调默认 UI 线程，阻塞 IO 切后台、贴回 UI 交付；
  异步统一 `GeckoResult` 链式，不手写 latch（framework 同步 API 必须阻塞等待的
  例外须在注释写明理由，并禁止在 UI 线程等待）；超时/取消显式处理；delegate 异常
  不许上抛崩 App，按 WebView 语义降级 + 日志（tag `Sinytra/<模块>`，发布关 verbose）。
- 安全：`addJavascriptInterface` 反射面最小化；自定义 scheme/拦截器默认拒文件访问，
  按 allowlist 放行。
- 测试：每 bridge 至少一单测锁调用顺序/线程/返回值，顺序类行为用设备 harness 固化；
  JVM 单测在 `tests/unit`，设备集成在 `provider/src/debug`，CTS 见 `docs/CTS.md`；
  P0 验收脚本提交后可重跑，100% 才合入 P1/P2。

## 8. 目标与调试（细则见 `docs/DEVICE.md`）

- `targetSdk = 34`（对齐真机）；`compileSdk` 跟随 pin 的 GeckoView 构建链
  要求（158 线 = 37.2）；provider `targetSdkVersion ≥ 33`；
  `minSdk` 不低于 pin 住的 GeckoView 版本要求。
- validity 硬约束（user build 实测）：manifest 带 `com.android.webview.WebViewLibrary`；
  `versionCode ≥ dumpsys webviewupdate` 的 `Minimum WebView version code`
  （本机 647807131）；不顶 `com.android.webview` 包名。
- adb 一律 `adb -s V885Q49L8TAMFEEE`；root 不用 `adb root`，用 `su -c`（Magisk）；
  读状态先 `dumpsys webviewupdate` 再看 logcat，不要猜。
- 切换前必备回滚：`cmd webviewupdate set-webview-implementation <任一 stock 包>`
  或开发者选项切回（见 `DEVICE.md` §5）。
