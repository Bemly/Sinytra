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

> **Implementation source of truth（定稿，Agent 查代码一律以此为准）：**
>
> ```text
> TARGET_ANDROID_API = 34
> AOSP_BASE = android14-release（固定 commit：<待填，provider 开工前 pin 死>）
> GECKOVIEW_VERSION = <待填，精确版本号，开工前 pin 死，禁用 +>
> FIREFOX_COMMIT = <启用本地 Gecko / 发布时才填，此前留空>
> ```
>
> - AOSP 接口语义、类名、加载流程一律以 `AOSP_BASE`（Android 14）为准；
>   master/main 只做“未来版本参考”，**不许**按 master 写 Android 14 的代码。
> - 关键差异示例：Android 14 的 `WebViewFactory` 直接硬编码
>   `CHROMIUM_WEBVIEW_FACTORY =
>   "com.android.webview.chromium.WebViewChromiumFactoryProviderForT"`，
>   没有 master 那套 `Flags.useBEntryPoint()` + `ForB/ForT` 分流。

## Docs（细节住这里，本文件只留硬规则）

| 文件 | 内容 |
|---|---|
| `docs/ARCHITECTURE.md` | 技术选型、三核心类、结构图、分层依赖、历史语义、运行时事实、语言策略 |
| `docs/API_MAPPING.md` | GeckoView delegate / controller / settings 对照表（写 bridge 前先查） |
| `docs/BOOTSTRAP.md` | P-1 验收、AOSP 最小解耦、三层依赖模型、workspace 布局 |
| `docs/ROADMAP.md` | P-1 → P0 → P1 → P2、P2 硬点清单、测试验收 |
| `docs/DEVICE.md` | 调试机档案、目标 API、adb 固定流程 |
| `docs/SOURCES.md` | 上游引用（AOSP 一律 android14-release） |

读文档顺序：先本文件 → `ARCHITECTURE.md` → 动手前读 `API_MAPPING.md` +
`BOOTSTRAP.md` 对应章节。

## 1. 仓库结构（本 Git 只存差异）

```text
sinytra/
├── AGENTS.md
├── docs/                            # ARCHITECTURE / API_MAPPING / BOOTSTRAP / ROADMAP / DEVICE / SOURCES
├── provider/                        # 独立 Java 17 Gradle Provider 工程（日常开发几乎只碰这里）
│   ├── build.gradle                 # 日常走 GeckoView Maven AAR
│   ├── AndroidManifest.xml          # 正式路线声明 provider 入口 metadata
│   ├── Android.bp                   # 进 AOSP 阶段才用
│   └── src/main/java/org/mozilla/geckowebview/
│       ├── provider/ session/ view/ settings/ storage/ compat/
├── aosp-patches/                    # 相对 AOSP 的 patch stack，每个 patch 只做一件事
├── firefox-patches/                 # 相对 firefox 的 patch stack（前期可为空）
├── manifests/  tools/  tests(unit/integration/cts)/
```

- AOSP 与 Firefox 源码树永远是 sibling checkout：不做 submodule、不复制进仓库、
  不把 provider 写进 Firefox 树、不拼不完整 Gecko fork。
- `WebViewFactoryProvider` 全局单例一个不能少：`Statics`、`CookieManager`、
  `GeolocationPermissions`、`ServiceWorkerController`、`WebIconDatabase`、
  `WebStorage`、`WebViewDatabase`、`TracingController`、`WebViewClassLoader`
  （`TokenBindingService` 已废弃，返回 null），外加
  `createWebView(WebView, WebView.PrivateAccess)`。

## 2. 分层与关键语义（违反即打回）

- 依赖单向：`provider（orchestration）├─→ session / view / settings+storage /
  compat`。**`session` 不依赖 `view`**（bridge 只做翻译、不碰 View，才能做无 View
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
AOSP 解耦（类名可配 + engine 分流）——且必须在注释写明“为什么现成 API 不够 + 上游 bug”。

## 5. 禁止事项（硬性）

- 不许按 AOSP master 写 Android 14 代码。
- 不许把 Chromium bootstrap 伪装（dummy RELRO 等）或 `com.android.webview.chromium.*`
  trampoline 合入主分支（PoC 只许进分支，P0 后切 metadata 自声明正式路线）。
- 不许假设所有 userdebug/eng 都跳过 provider 签名检查——以目标 product 的
  overlay/config 为准；本机 `user release-keys` 设备按系统 provider 要求签名/预装。
- 不许只抽几个 Firefox 目录拼 fork：pin 就 pin **完整**源码树。
- 不许为“以后可能用”接 WebExtension / Translations / 页面抽取等不需要的能力。

## 6. 阶段门槛（硬性，顺序不许跳）

```text
P-1（bootstrap spike，最高优先级）→ P0（~20% API 跑起来）→ P1（系统能力）→ P2（硬骨头 + CTS）
```

- **P-1 不通过不开 P0**：provider 进宿主进程后的 classloader、`libxul.so`、
  `create()` 用哪个 Context、child process Service bind、package/UID/SELinux、
  manifest components 可解析性——全部实测回答，写不出就停。失败则先修 process
  bootstrap patch（优先级高于一切 bridge）。
- P0 未验收不开 P1；语义难题全归 P2。`androidx.webkit` 兼容 = 自研 support-library
  boundary glue（P2 独立大项），不是实现 framework API 自动获得。
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
  异步统一 `GeckoResult` 链式，不手写 latch；超时/取消显式处理；delegate 异常不许上抛
  崩 App，按 WebView 语义降级 + 日志（tag `Sinytra/<模块>`，发布关 verbose）。
- 安全：`addJavascriptInterface` 反射面最小化；自定义 scheme/拦截器默认拒文件访问，
  按 allowlist 放行。
- 测试：每 bridge 至少一单测锁调用顺序/线程/返回值，顺序类行为用集成测试固化；
  `tests/unit|integration|cts`；P0 验收脚本提交后可重跑，100% 才合入 P1/P2。

## 8. 目标与调试（细则见 `docs/DEVICE.md`）

- `compileSdk / targetSdk = 34`；provider `targetSdkVersion ≥ 33`；
  `minSdk` 不低于 pin 住的 GeckoView 版本要求。
- adb 一律 `adb -s V885Q49L8TAMFEEE`；root 不用 `adb root`，用 `su -c`（Magisk）；
  读状态先 `dumpsys webviewupdate` 再看 logcat，不要猜。
