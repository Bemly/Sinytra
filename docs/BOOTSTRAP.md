# BOOTSTRAP — Sinytra

> P-1 可行性验证、AOSP 最小解耦、GeckoView 三层依赖、workspace 布局。
> 动 framework / 接 GeckoView / 拉 AOSP 前先读本文件。

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

结论只有两种：① 可行 → 锁定 bootstrap 约束进 §3（`WebViewLibraryLoader` 分流）；
② 不可行/需改 Gecko → 按 `ARCHITECTURE.md` §6.4 走 `firefox-patches/` 专门处理
process bootstrap（这是 P2 之外的前置 patch，优先级高于一切 bridge）。

## 2. AOSP Framework 改动（只做最小解耦）

现状（Android 14 实测，`AOSP_BASE = android14-release`）：`WebViewFactory` 直接硬编码
`CHROMIUM_WEBVIEW_FACTORY =
"com.android.webview.chromium.WebViewChromiumFactoryProviderForT"`，
没有 master 那套 `Flags.useBEntryPoint()` + `ForB/ForT` 分流；
`WebViewFactory` 会反射调 `create(WebViewDelegate)` 静态工厂拿 provider；
`WebViewLibraryLoader` 仍有 `CHROMIUM_WEBVIEW_NATIVE_RELRO_32/64`、
RELRO/shared_relro、`WebViewZygote` 等 Chromium 专属假设；
provider 包名由 `config_webview_packages.xml` 决定（默认 `com.android.webview`）；
provider 加载前 framework 会读 provider APK 的 `com.android.webview.WebViewLibrary`
metadata（`verifyPackageInfo` 里缺失直接判 provider 无效）并预加载其 native 库
（随后必走 `WebViewLibraryLoader.loadNativeLibrary()`）。

### 2.1 测试 ROM：userdebug + config overlay（先做这个）

- 测试 ROM 用 `userdebug / eng` build。
- **不能假设所有 userdebug/eng 产品都自动跳过 provider 签名检查**：AOSP
  emulator/goldfish 等具体 product 的 overlay 才配置允许测试 provider；
  实际行为以目标 product 的 `config_webview_packages.xml` / overlay 为准。
- 在设备 overlay 或 `frameworks/base/core/res/res/xml/config_webview_packages.xml`
  中加入：

```xml
<webviewprovider
    description="Gecko WebView"
    packageName="org.mozilla.geckowebview"
    availableByDefault="true" />
```

- 之后“设置 → 开发者选项 → WebView 实现”里就能切到 `Gecko WebView`。
  先走通这条切换链路，再碰 §2.2。

### 2.2 Framework patch：PoC trampoline（短期）→ metadata 自声明（正式）

> PoC 诚实声明：只放 `WebViewChromiumFactoryProviderForT` trampoline
> 做不到“零 framework 改动”——Android 14 在加载该类之前就要求 provider APK
> 有 `com.android.webview.WebViewLibrary` metadata（无则判无效），之后还必走
> `WebViewLibraryLoader.loadNativeLibrary()`。所以 PoC 只有两条路：
> ① 做 dummy native library 先满足 RELRO/WebViewZygote 旧假设（脏活，P0 后全删）；
> ② **推荐：第一版 AOSP patch 就先把 native loader/bootstrap 对 Gecko 分流掉**（见 §2.3），
> trampoline 只解决类名硬编码。不要花时间伪装 Chromium bootstrap 再全部删掉。

**PoC 路线（允许进分支、不进主分支）**：Gecko APK 里直接提供 10～20 行
compatibility trampoline，AOSP 以为自己在加载 Chromium，实际拿到 Gecko：

```java
package com.android.webview.chromium;

public final class WebViewChromiumFactoryProviderForT {
    public static WebViewFactoryProvider create(WebViewDelegate delegate) {
        return new GeckoWebViewFactoryProvider(delegate);
    }
}
```

这样 PoC 期至多省掉 §2.3 之外的 framework 改动即可验证。
但这是脏捷径，P0 验收后必须切正式路线，trampoline 不许合入主分支。

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

### 2.3 `WebViewLibraryLoader` 按 engine 分流

`engine="chromium"` 走原 RELRO 路径；`engine="gecko"` 时 provider 自行
bootstrap（`GeckoRuntime/GeckoThread/libxul` + Gecko child processes，
约束由 P-1 锁定），**不许**让 Gecko 假装 Chromium RELRO loader。

### 2.4 顺序

先 §2.1（overlay 切换）→ PoC trampoline 跑 P0 → 再做 §2.2 正式解耦 +
§2.3 分流。以目标 ROM 分支的 `frameworks/base/core/java/android/webkit/`
为准改；`aosp-patches/` 里每个 patch 只做一件事。

## 3. GeckoView 三层开发模型（日常 / 本地 Gecko / 发布）

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
触发条件见 ARCHITECTURE.md §6.4。
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

## 4. 本地 workspace 布局（AOSP / Firefox 不进本 Git）

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
├── firefox/                 ← Mozilla Firefox checkout（sibling，见 §3②）
└── aosp/                    ← repo 管理的 AOSP checkout
    ├── .repo/
    ├── frameworks/base/
    ├── packages/apps/GeckoWebView/   ← 稳定后把本仓库接进来（见下）
    └── ...
```

### 4.1 本仓库接进 AOSP（稳定后）

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

### 4.2 AOSP 拉取规模（三阶段）

1. **只写 adapter**（P0 前期）：不拉 AOSP。`WebViewProvider /
   WebViewFactoryProvider` 是 hidden API，普通 SDK 没有——针对目标系统完整
   `framework.jar` 做 `compileOnly`，或引用对应版本 AOSP 接口源码/stub。
2. **第一次替换 System WebView**：拉完整 AOSP checkout。改
   `frameworks/base/core/java/android/webkit/` + overlay/config
   （见 §2.1），在真机/模拟器验证“开发者选项 → WebView 实现 → Gecko WebView”。
3. **完整 ROM / CTS**：正常 AOSP build 环境出
   `system.img / product.img / system_ext.img...` 并跑 CTS。

升级路线：`AOSP 官方源码 + aosp-patches/ + Firefox 固定 commit + firefox-patches/`，
Android 大版本 / Firefox 大版本升级时只 rebase patch stack，
绝大部分 Java glue 不用动。
