# BOOTSTRAP — Sinytra

> P-1 可行性验证、System WebView 切换路线（root + AnyWebView）、GeckoView 三层依赖、
> workspace 布局。碰 provider 入口/manifest/切换流程、接 GeckoView 前先读本文件。

## 1. P-1 — Bootstrap 可行性验证（P0 之前必须先过，spike 性质，最高优先级）

> 背景：System WebView 的 provider Java/native code 是被动态加载进**宿主 App 进程**的，
> 而 GeckoView 的多进程实现依赖 manifest 里声明的一系列 Android `Service`
> （每个 Gecko process 对应一个 service）。**不能**默认“AAR 塞进 provider APK +
> `GeckoRuntime.create()` 就和普通 App 一样 work”。

验收清单（全部实测回答，写不出来就停，不许带着假设进 P0）：

```text
- provider APK 的 classloader 能否稳定加载 GeckoView Java classes
- libxul.so 能否从 provider package 正确加载（走哪个 ClassLoader / library path）
- GeckoRuntime.create() 用哪个 Context（provider APK context vs host App context）
- Gecko child processes（content / socket / GPU 等）的 Service bind 是否成功
- child process 的 package / UID / SELinux identity 是谁
- GeckoView AndroidManifest.xml 里的 components 在 WebView provider 场景下是否可解析/可实例化
```

结论只有两种：① 可行 → 锁定 bootstrap 约束（见下方实测结论 + §2 切换路线）；
② 不可行/需改 Gecko → 按 `ARCHITECTURE.md` §6.4 走 `firefox-patches/` 专门处理
process bootstrap（这是 P2 之外的前置 patch，优先级高于一切 bridge）。

### P-1 实测结论（2026-09-21，真机 MOONDROP MD-PH-001 / Android 14 / API 34）

- ✅ classloader：Gecko 三大类经 PathClassLoader 从 provider base.apk 加载正常。
- ✅ `libxul.so`：随 AAR `jni/arm64-v8a` 进 APK，`GeckoLoader` 从
  `base.apk!/lib/arm64-v8a` 加载成功（`Loaded libs in ~30ms`，`GeckoThread RUNNING`）。
- ✅ Context：用 provider APK 自身 context `create()` 成功（192ms，UI 线程）。
- ✅ Service 可解析性：gpu/tab0/socket/rdd/media/crashhelper 全部 `PackageManager`
  可解析（AAR manifest 合并进 APK，89 个 `<service>`，`query-services` 除外——
  该命令在此 ROM 上对所有包都返回空，不是我们的特例）。
- ⚠️ child process bind：**首次启动必成，后续启动偶发被拒**（统一文案
  `Unable to launch app ... : process is bad`——注意这句≠已命中
  `mBadProcesses`，`ActiveServices` 只要 `startProcessLocked()` 返回 null
  就打这句）。双路模型：AOSP bad-process（background bind + bad 名单→null；
  Gecko child 永远后台 bind，`BIND_AUTO_CREATE+BIND_IMPORTANT`）与 MTK
  DuraSpeed vendor veto 都能触发。最强证据：卸载重装换新 UID 后首启仍
  `duraspeed block` 失败——bad 名单按 `processName+uid` 查询，旧 UID 残留
  命中不了新 UID，至少这次是 vendor veto。触发器是 MTK DuraSpeed
  （`AiuiAmsExt: duraspeed block ... bringUpServiceLocked`）：拦截每次
  service 拉起，偶发卡死/杀掉正在启动的 child（crashhelper 首当其冲，
  每次重装/重启后第一次必现），可能再把 child 推入 crash→bad 循环（两套
  机制放大）。禁掉 `com.mediatek.duraspeed` 包无用（hook 在 `system_server` 内）。
  `force-stop` 不清 bad 名单（只 `resetProcessCrashTime`，不清
  `mBadProcesses`；且主进程显式启动清的也是主进程名≠`:gpu/:tabN`）。
  **重启不是 GeckoView 的要求**：只是清 `system_server`/AMS 内存态所以暂时
  恢复；P0 不以重启为正常条件，优先 DuraSpeed whitelist/后台无限制。
- ✅ P0Render（`GeckoView + session + loadUri`）：child 起 Gramm 后完整渲染
  example.com（含进度 15→55→100 + `onPageStop success=true`），截图验证通过。
  → **P-1 结论①：bootstrap 可行**，约束：child bind 失败时页面停在 `about:blank`
  并重试 tabN（`tab27 → tab0 → tab8...` 轮询），属 Gecko 侧正常重试语义，
  不是 glue bug；目标机上需关掉 vendor 电源管理（本机 MTK DuraSpeed，见下 +
  `STATUS.md` §3）——切换后所有 App 的 Gecko child 都受它影响。
- 调试机注意事项（本机 `user release-keys`，非 userdebug/eng）：
  保持亮屏解锁测（Doze + 锁屏会冻住 App 心跳，截图全黑属正常）；
  复现 bind 问题先看 `am_proc_start ... :<process>` 是否出现，再看
  `Unable to launch app ... : process is bad` + `duraspeed block` 是否成对出现。

## 2. System WebView 切换路线（唯一路线：root + AnyWebView，不刷机）

> 2026-09-26 用户拍板：唯一目标是在**已 root + LSPosed + AnyWebView** 的现有
> 手机上，经「开发者选项 → WebView 实现」把 Sinytra 切成 system WebView。
> 旧 §2.1–§2.4（测试 ROM overlay、`aosp-patches/` metadata 自声明、
> `WebViewLibraryLoader` engine 分流、自建 ROM/CTS）**全部作废**，不再规划。
> 路线在 `poc/dev-option-switch` 分支真机跑通过（2026-09-22，153 线），
> 主线化是当前第一优先级（`STATUS.md` §2）。

### 2.1 framework 侧事实（Android 14，不改 framework 的前提）

- `WebViewUpdateService` 判 provider 是否 Valid（`dumpsys webviewupdate`
  的 `WebView packages` 列表）：`targetSdkVersion ≥ 33`、manifest 必须有
  `com.android.webview.WebViewLibrary` metadata（缺则
  `VALIDITY_NO_LIBRARY_FLAG`）、user build 还要求 `versionCode ≥
  Minimum WebView version code`（按 stock 候选包的 branch 字段算，本机
  647807131；过低报 `Version code too low`）、非系统包签名要匹配
  `config_webview_packages.xml` 且包要在候选名单里——**候选名单 + 签名这一关靠
  AnyWebView 放行**（具体 hook 点未逐一反编译核实，以 dumpsys 结果为准）。
- `WebViewFactory` 硬编码 `Class.forName("com.android.webview.chromium.
  WebViewChromiumFactoryProviderForT")` + `create(WebViewDelegate)`，不读任何
  provider 声明的 factory 类名——`android.webkit.WebViewFactoryClass` 之类
  metadata 在 stock framework 上是死配置，不声明。
- framework 会按 `WebViewLibrary` 对 provider 做 RELRO 预加载
  （`WebViewLibraryLoader`）：对 `libxul.so` 失败属预期、无害——Gecko 自己经
  `GeckoLoader` 从 `base.apk!/lib/arm64-v8a` 加载（P-1 已证）。
  `WebViewZygote.preloadInZygote` NoSuchMethod 同属 benign（Chromium 私有静态
  方法，缺失即跳过）。

### 2.2 AnyWebView（LSPosed 模块）

- 包 `com.thinkdifferent.anywebview`，**必须 v1.3**（`de.robv` 旧入口）；
  v1.4.x 要 libxposed API 101，本机 LSPosed v1.11.0 不支持，hook 静默失败。
- LSPosed 作用域勾「系统框架」（scope=system），改作用域后重启生效。
- 作用：把已安装且声明了 `WebViewLibrary` 的包追加进 provider 候选名单并
  放过签名检查——它**不**改 factory 类名、**不**放宽 targetSdk/versionCode，
  这些仍由 provider 自己满足。
- 可逆、不写 `/system`：开发者选项切回任一 stock 包即回滚。

### 2.3 provider 侧必须满足的约束（主线化清单）

| 约束 | 做法 | 来源 |
|---|---|---|
| Valid：library flag | `<meta-data android:name="com.android.webview.WebViewLibrary" android:value="libxul.so"/>`（真实 .so，不做 dummy） | PoC `ce86279` |
| Valid：versionCode | ≥ `Minimum WebView version code`；PoC 用 647900000（branch 6479 > stock 6478/8037/8066）；正式编码方案主线化时定 | PoC `ce86279` |
| Valid：targetSdk | ≥ 33（现 34） | DEVICE §2 |
| 包名 | **`moe.bemly.geckowebview`**（2026-09-26 定稿；debug 为 `.debug` 后缀）。自有包名，不顶 `com.android.webview`；Java 包/namespace 仍是 `org.mozilla.geckowebview`（只换安装身份） | 用户拍板 |
| 入口 | `com.android.webview.chromium.WebViewChromiumFactoryProviderForT.create(WebViewDelegate)` trampoline：用 `Proxy` 实现**真实** `WebViewFactoryProvider` 接口（stub 把 `createWebView` 参数 erase 成 Object，直接 implements 会 `AbstractMethodError`），只做转发 | PoC `00d3559` |
| `PrivateAccess` | `createWebView` 时保留 `PrivateAccess` 并经 `super_setLayoutParams` 先写 MATCH_PARENT（否则 Activity measure NPE） | PoC `00d3559` |
| WebStorage/Geolocation | framework 这两类 ctor 包私有、只能返回 `getInstance()`；在 Proxy 内会 `getInstance()↔getProvider()` 自循环 → trampoline 里用重入标记直读已构造实例断环 | PoC `00d3559` |

### 2.4 切换后验收

- `dumpsys webviewupdate`：Sinytra 在 `Valid package` 里、Current/Preferred 均为我方。
- 真实路径探针（`FrameworkEntryActivity`，已迁入 `src/debug`，验收实录
  `STATUS.md` §1s）：`new WebView(context)` →
  framework `WebViewFactory` → trampoline → Sinytra，`onPageFinished` 到达。
  这取代“反射注入 harness”成为 P0–P2 的最终验收口径；反射 harness 继续做
  bridge 级回归。
- 切换后单例族全部是我方实现（反射 harness 里 storage 单例是系统 Chromium
  的，这是两种口径的核心差异）。
- 第三方 App 冒烟 + CTS（`CTS.md`，切换后 `am instrument` 直跑）。
- 注意：切换是**全局**的，所有 App（含系统 App）立即改用 Sinytra；跑前先确认
  回滚命令可用（`DEVICE.md` §5）。

## 3. GeckoView 两层开发模型（Maven AAR / 本地 Gecko）

> 现状（2026-09-25 起）：master 引用 firefox-patches 0001–0007 注入的
> GeckoView 类型，**一切构建/测试都必须走 ②**（`-PsinytraLocalGecko=true`）；
> ① 只在 stock AAR 能编过的分支上成立。要恢复“默认绿”需发布自建 AAR 到可达
> Maven/mavenLocal——显式决策点，暂不做。

```text
① stock AAR（patch 前的历史形态）

provider/（独立 Java 17 Gradle 工程）
        ↓ implementation "org.mozilla.geckoview:geckoview-nightly:<pin死版本>"
Mozilla Maven AAR
```

```text
② 本地 Gecko（现行形态）

<U+F8FF 卷>/Projects/          （经 ~/sinytra-vol 符号链接访问）
├── Sinytra/     ← 本仓库（Provider + patch stack，只存差异）
├── firefox/     ← Mozilla Firefox checkout（sibling，不进本 Git；分支 sinytra-pin-158）
└── mozbuild/    ← MOZBUILD_STATE_PATH

provider/build.gradle 在 -PsinytraLocalGecko 下 apply
${topsrcdir}/substitute-local-geckoview.gradle（topobjdir = firefox/objdir-158）：
Provider → 本地 patch 后的 GeckoView → 本地 Gecko，provider 源码不用改。
触发条件见 ARCHITECTURE.md §6.4；命令见 firefox-patches/README.md。
```

可重现 = `FIREFOX_COMMIT` + `firefox-patches/` 按编号 `git apply`
（2026-09-26 实测：0001→0007 按编号应用到 34ed69f16167 的树与
`sinytra-pin-158` HEAD 完全一致）+ provider 固定 source。

- **不要**用 git submodule 绑 firefox，也**不要**把 Provider 写进
  Firefox 源码树。Firefox 负责 Gecko+GeckoView，本仓库负责
  `android.webkit → GeckoView`，只有确实缺 primitive 才往下打 patch。
- Nightly 版本一律 pin 精确号，不用 `+`。

### 正式阶段 pin 策略

- pin 死**完整** `mozilla-firefox/firefox` 源码树到某个 commit——Firefox 构建依赖
  整树的 build system / toolkit / IPC / modules，**禁止**只抽几个目录拼成不完整
  Gecko fork。本项目直接修改/关注的范围限制在 `mobile/android/geckoview`、
  `mobile/android/modules`（JS Actor 等）、`widget/android`、Gecko 引擎目录
  （`dom/layout/gfx/netwerk/js/src` 等）；SpiderMonkey 随 Gecko 进来，
  **不**单独引 mozjs。
- 凡 GeckoView public API 覆盖不到、但 WebView 语义必需的能力，
  一律写成 `firefox-patches/` 下的独立 patch，每个 patch 只做一件事，
  附带说明：解决哪个 WebView API、为什么 public API 不够用、上游有无对应 bug。
- 升级 Firefox commit 时逐个 rebase patch，冲突不解决不许升级。

## 4. 本地 workspace 布局（Firefox 不进本 Git；不拉 AOSP）

> **本 Git 只存差异**：`provider/` + `framework-stubs/` + `firefox-patches/` +
> `tests/` + 文档。Firefox 源码树是 sibling checkout，不做 submodule、不复制进
> 仓库、不把 provider 写进 Firefox 树。**不拉 AOSP 树**：hidden API 编译靠
> `framework-stubs/`（android14 接口 stub，compileOnly），语义核对看
> `SOURCES.md` 的 android14-release 源码链接或设备 `framework.jar`。

实际布局见 §3 ②（全部在 U+F8FF 外置卷上，内置盘空间不足；`~/src` 布局不适用）。
