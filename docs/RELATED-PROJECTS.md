# RELATED-PROJECTS — wszgrcy/capacitor-geckoview 生态比对

> 与 Sinytra 同题（GeckoView 替代 WebView）的独立第三方实现。其 firefox fork
> 的请求拦截线与我们 firefox-patches/0001-0003 是**同一问题域的两套实现**，
> 本文档做逐项对照 + 可借鉴项清单，供快速适配。
>
> 取证快照：2026-09-25。对方四仓库均 0 star / 0 fork（个人实验性质，无可见
> 测试纪律），本文所有判断以拉下来的 diff/源码为准，不预设其正确性。
> **我方 0001-0003 是真机 harness 32 PASS 验证过的权威实现，对方只做参考。**

## 1. 四仓库分工

```text
wszgrcy/firefox                    # Firefox 完整 fork，build 分支：拦截器 + 构建裁剪 + 发布基建
  → capacitor-geckoview            # Capacitor Android 运行时（com.getcapacitor.*）GeckoView 化
    → capacitor-geckoview-plugins  # 官方 ionic-team/capacitor-plugins fork，适配插件
      → capacitor-geckoview-starter# 模板工程（local/production 双轨 + 迁移文档）
```

| 仓库 | 实质 | 与 Sinytra 的关系 |
|---|---|---|
| `firefox`（build 分支） | 基于 mozilla main **156.0a1**（~2026-08-29 fork），49 commits / 29 文件 / 73KB diff：① 请求拦截器（§2.1）② 构建裁剪 7 件（§2.2）③ Maven Central 发布基建 | **核心比对对象**：①与 0001-0003 同域；②是打印/a11y 的"反向地图"（§2.2） |
| `capacitor-geckoview` | 把 Capacitor Android runtime 整体搬成 GeckoView 版（60+ Java 文件），含 `LocalAssetRequestInterceptor`、WebExtension 桥、demo 冒烟测试 | 集成层参考：本地资产伺服语义、嵌入式 prefs 卫生（§3） |
| `capacitor-geckoview-plugins` | 官方插件 fork，跟随上游 main 的常规变更为主；实质改动：`browser` 插件从 Chrome Custom Tabs **重写为 GeckoView 应用内浏览器**（保留 BROWSER_LOADED/FINISHED 事件契约）；`text-zoom`/`app` 有改动未逐行核 | 低——Capacitor 生态特有 |
| `capacitor-geckoview-starter` | 模板工程。Gradle 9 / compileSdk 37；`geckoviewMode=local/production` 双轨（local 走 `local-maven-repo/`，production 走 `io.github.wszgrcy:geckoview-custom-arm64-v8a:156.0.24`） | `geckoviewMode` 双轨与我们的 `-PsinytraLocalGecko` 同构，互相印证 |

## 2. firefox fork（build 分支）逐项分析

基线：`browser/config/version.txt = 156.0a1`（main 与 build 同值，fork 自 mozilla
main，比我们 pin 的 FIREFOX_153_0_RELEASE 高约 3 个版本，**上下文漂移是适配
成本的主要来源**）。

### 2.1 请求拦截线（vs 我们的 0001-0003）

同目标：`shouldInterceptRequest` 语义——每条 http/https 请求咨询嵌入端，
app 返回 WebResponse 则由 Gecko 合成响应，**URI 身份保持不变**（同样落在
`InterceptedHttpChannel` 机制上）。

**其架构**（新文件 `mobile/android/components/geckoview/GeckoViewRequest
Interceptor{Controller,Observer}.*` + Java `GeckoViewRequestInterceptor`）：

1. XPCOM 组件 `GeckoViewRequestInterceptorObserver` 在 `app-startup` 注册，
   监听 **`http-on-modify-request`**（parent 进程，MAIN_PROCESS_ONLY）；
2. 对每条 http/https channel：同步 JNI 调 Java 进程级静态拦截器
   `GeckoViewRequestInterceptor.intercept(uri, method, headers["k: v"...])`
   （`@WrapForJNI`，`GeckoSession.setRequestInterceptor` 全局唯一实例）；
3. Java 返回 `WebResponse`（含 body `InputStream`）→ C++ 构造 per-response
   的 `nsINetworkInterceptController`，**包装 channel 的
   notificationCallbacks**（Requestor 委托原 callbacks，仅劫持
   nsINetworkInterceptController 查询）→ necko 稍后在
   `HttpBaseChannel::ShouldIntercept()`（OnBeforeConnect）发现它 →
   转 `InterceptedHttpChannel` 合成；
4. body 流：`java::GeckoViewInputStream::Create(response->Body())` 直接包
   app 的 InputStream（`@WrapForJNI` 静态工厂，加在既有
   `GeckoViewInputStream.java` 上），C++ 侧 `GeckoViewInterceptInputStream`
   继承包装——**无注册表、无 bundle、无 base64**；
5. 合成前 `loadInfo->SynthesizeServiceWorkerTainting(LoadTainting::Basic)`
   ——绕开顶层导航无 loading principal 的 ORB/跨源检查。

**ServiceWorker 联动**（改上游 `dom/serviceworkers/` 4 文件， Capacitor
PWA 场景驱动）：为「app 拦截器伺服导航页 + SW 管 subresource 缓存」设计：

- 新增 `ServiceWorkerManager::ForceControlClient()`：导航被 app 拦截时
  强制把 active SW 标记为该页 controller（LoadInfo + StartControllingClient），
  使后续子资源仍走 SW fetch handler；
- 受控子资源 **SW-first**：SW 不 respondWith 时 Gecko ResetInterception →
  重建 REDIRECT_INTERNAL channel → 重触 http-on-modify-request →
  观察者借该 flag 识别 fallback、清 flag 后落回 app 拦截器；
- SW 脚本自身通道清 `LOAD_BYPASS_SERVICE_WORKER`（否则合成响应不生效）。

**逐维对照**：

| 维度 | Sinytra 0001-0003（153，已验证） | wszgrcy build 分支（156.0a1） |
|---|---|---|
| 查询机制 | docshell 挂 `nsINetworkInterceptController`（包装 SW controller），EventDispatcher 查 per-session `ResponseDelegate` | 全局 `http-on-modify-request` 观察者 + 进程级静态拦截器 |
| 过滤/惰性 | filter 前缀下发（`setInterceptFilters`），未注册完全惰性 | 无过滤：每条 channel 两遍 header visit + 一次 JNI；Java null 即放行 |
| 线程 | app 回调经 dispatcher 走 UI 线程，necko **异步等待** | `interceptRequest` 在 http-on-modify-request 线程**同步执行**（含主线程 → ANR 风险；其 `@AnyThread` 注释自认） |
| 请求信息 | v2：uri/method/headers（0002），敏感头收窄待决策 | uri/method/headers（"name: value" 拍平，同 0002 形态）；**无请求 body** |
| body 回传 | `GeckoViewResponseStreams` 注册表 + `@WrapForJNI take(id)` → `nsIAndroidContentInputStream`（0003） | `WebResponse.body` InputStream 经 `GeckoViewInputStream.create()` 直包 JNI（比我们的注册表少一跳，因其 JNI 直调能传对象引用；我们走 bundle 通道才需要 id） |
| URL 身份 | InterceptedHttpChannel 内部重定向保持 | 同 |
| ORB/principal | 未显式处理（153 端到端已通，可能 153 尚不需要） | `SynthesizeServiceWorkerTainting(Basic)` 显式处理 |
| SW 联动 | 独立并存；0002 让位规则按 filter 前缀 | 深度耦合：导航 ForceControl + 子资源 SW-first + REDIRECT_INTERNAL fallback |
| JS 层 | 触及 `GeckoViewNavigation.sys.mjs`（filter 下发/查询路由） | **零 JS**，纯 C++/Java |
| 回归 | 真机 harness 32 探针 + JVM 49 锁 | 无可见测试（仅 demo smoke） |

**可借鉴项**（按去向）：

1. **`SynthesizeServiceWorkerTainting(LoadTainting::Basic)`** —— 升级
   156+ 时 0001 线的 ORB 加固候选。153 真机已通不必急，升级窗口按需引入
   （去向：记入 0003 §4 候选清单）。
2. **SW 并存语义**（ForceControlClient + SW-first + fallback 识别）——
   Chromium WebView 下「SW + shouldInterceptRequest 并存」是真实存在的
   app 形态；对方给了一组可用的优先级语义（导航=拦截器、受控子资源=SW
   先、fallback=拦截器）。P2 语义题的现成参考实现，不急着抄，先记语义。
3. **Range 已被证明 app 侧闭环** —— 其 `LocalAssetRequestInterceptor`
   对 Range 请求直接合成 206 + `Content-Range`（app 侧读 `Range` 头即可，
   0002 已把请求头透传给 app）。0003 设计文档 §4 的 "Range" 候选可降级
   为「无 Gecko 改动需求」。
4. **嵌入式 prefs 卫生** —— 其 demo `geckoview-config.yaml`：
   `network.connectivity-service.enabled=false`、
   `network.captive-portal-service.enabled=false`、
   `services.settings.server='http://127.0.0.1:1'`。provider 初始化默认值
   的候选（P1，GeckoRuntime settings 范围）。

**明确不借**：

- 同步 JNI 查询（主线程阻塞 + 与 Chromium「回调在后台线程」语义相悖）；
- 无过滤的全局观察者（每请求成本 + 未注册也白跑）；
- ~~往上游 5 个文件打日志~~ **按用户决策修正（2026-09-25）**：调试构建里
  多打日志是对的（不打没法排查），他们的真问题不是"打了"而是**不分构建、
  无开关**——正解：debug 构建保留/加密插桩（tag `Sinytra/*`），release
  构建编译期剔除（C++ `#ifdef DEBUG` / Java `BuildConfig.DEBUG` /
  provider `src/debug` sourceSet，三套机制都在）。落入 patch stack 纪律，
  见 firefox-patches/README.md「日志纪律」；
- 进程级静态 `setRequestInterceptor`——WebView 多实例语义下 per-session
  delegate + provider 扇出才是正解。

### 2.2 构建裁剪补丁（build-patch/slim.patch + split/ 7 件）

体积向裁剪（其 auto-arm-slim CI 用）：`02-remove-pdfjs`（package-manifest +
toolkit/components/moz.build）、`04-remove-ml`、`05-remove-translations`
（toolkit/components/moz.build 各删一行）、`03-disable-hls`
（MOZ_ANDROID_HLS_SUPPORT default=False，甩掉 ExoPlayer）、
`01-disable-webrtc-videocapturetest`（--disable-webrtc 时 androidTest 编译
修复）、`android-a11y-disable`、`disable-printing`。

对 Sinytra 的价值（**方向相反的地图**）：

- `disable-printing`：把 `nsPrintSettingsServiceAndroid` /
  `nsDeviceContextSpecAndroid`（contract `@mozilla.org/gfx/printsettings-service;1`，
  `@mozilla.org/gfx/devicecontextspec;1`，widget/android/components.conf +
  moz.build）改成 `NS_PRINTING` 条件编译——P1 打印认领时的入口地图；
  他关掉是因为不需要，我们需要能工作。
- `android-a11y-disable`：`--disable-accessibility` 构建下 GV Java 仍会调
  SessionAccessibility natives，其补丁给 `nsWindow.{cpp,h}` 加 `#ifdef
  ACCESSIBILITY` + stub Natives——顺带完整列出 a11y JNI 面
  （Attach/Transfer/GetNodeInfo/Pivot/ExploreByTouch/NavigateText/
  SetSelection/剪贴板…），P2 a11y 测试清单可参照。
- pdfjs/ml/translations/HLS 裁剪与 AGENTS §5「不为不需要的能力接线」同向，
  provider APK 体积优化（远期）可直接复用其 split 补丁。

### 2.3 发布基建（略）

其 `geckoview/build.gradle` 改动：groupId `io.github.wszgrcy`、
`GECKOVIEW_VERSION` 环境变量覆盖版本号、GPG 签名发 Maven Central、
ABI 后缀 artifact（`geckoview-custom-arm64-v8a` / `-x86_64`）。starter 的
`geckoviewMode=local/production` 与我们 `-PsinytraLocalGecko` 同构。无需跟进
（我们本地 publish 已闭环），`GECKOVIEW_VERSION` 覆盖技巧留档。

## 3. capacitor-geckoview 集成层（要点）

- **`LocalAssetRequestInterceptor`**（其 Firefox patch 的第一消费者）：
  `https://localhost` 伺服 `assets/public`，完整复刻 Capacitor
  `WebViewLocalServer`：`_capacitor_file_`/`_capacitor_content_` 真文件/
  content:// 映射、html5mode SPA fallback（无扩展名路径→index.html）、
  MIME sniffing（BufferedInputStream mark/reset）、favicon 空 png、HTML
  经 `JSInjector` 流式注入 bridge JS、Range→206。**验证了拦截线上可以
  承载完整本地伺服语义**——对应我们 `androidx.webkit` 的
  WebViewAssetLoader 边界 glue（P2-8 未宣称项）的可行路径。
- **WebExtension 桥**：`WebviewExtension.java` / `WebExtensionPortProxy.java`
  + assets（`content.js`/`background.js`/`manifest.json`）——与我们
  `sinytra-js` 内置扩展（§1d）**同架构的独立收敛**，二次印证 WebExtension
  messaging 是 JS transport 的正确路线（本次仅核文件名与结构，未逐行对比协议）。
- 其 `android/capacitor` 模块还有 `MockCordovaGeckoviewImpl`、
  `CapacitorCordovaGeckoViewCookieManager` 等 Cordova 兼容层——Capacitor
  生态特有，不展开。

## 4. 文件级适配实测（git apply --check）

其功能 diff（11+6=17 文件，剔除其 CI/裁剪补丁）对 **sinytra-pin @
`62b7b46e280e`**（153.0 + 我方 0001-0003）实测：

| 结果 | 文件 |
|---|---|
| **通过（13）** | dom/serviceworkers/ 全部 5 个（Manager.cpp 偏移 4 行）；components/geckoview/ 新文件 4 个（Controller.{cpp,h}、Observer.{cpp,h}）；**components.conf（偏移 7 行，与我方 0001 条目共存）**；**GeckoSession.java（偏移 34/116，与我方 ResponseDelegate 共存）**；GeckoViewInputStream.java；GeckoViewRequestInterceptor.java（新） |
| **冲突（4）** | components/geckoview/moz.build（我方 0001 同列表加过条目）；widget/android/moz.build（我方 0003 加过 `GeckoViewResponseStreams`）；geckoview/build.gradle（156 上下文）；netwerk/.../InterceptedHttpChannel.cpp（156 上下文漂移） |

结论：**两套 stack 文件级重叠仅 4 处且全是"同列表追加"或版本漂移**，无逻辑
对抗；若真要并任一方改动，手工量在小时级。

## 5. 快速适配清单（若采纳借鉴项）

> 2026-09-25 状态同步：158 升级当日完成，①已落 **0004**，③已关闭
> （0003 文档修订），⑥已写入 README 日志纪律并随 **0005** 落地
> （运行时 pref 门 `sinytra.log.enabled`——AAR 双变体共用 libxul，
> 编译期门做不到，pref 门是正解）。②④⑤维持既定去向。

按 AGENTS §3 纪律（每项独立 patch + 独立测试），建议编号与动作：

1. **0004（候选）：ORB 加固** —— 升级 156+ 窗口引入
   `SynthesizeServiceWorkerTainting`；153 上先以 harness 回归确认不需要。
   测试：现有 interceptBody/interceptLargeBody 探针 + 顶层导航 NXDOMAIN 替身。
2. **0003 §4 修订**：Range 候选降级为「app 侧 206 即可（0002 头透传已覆盖），
   无 Gecko 改动」；POST body 透传仍为真候选（对方也未解决）。
3. **P1 候选**：provider 默认 prefs 三件（connectivity/captive-portal/
   services.settings，见 §2.1 借鉴 4）。
4. **P1 打印认领**：入口地图用其 `disable-printing` 补丁反查
   （widget/android 两个 contract + components.conf）。
5. **P2 语义储备**：SW+拦截并存优先级（§2.1 借鉴 2），届时再定是否移植
   ForceControlClient。
6. **APK 体积（远期）**：slim/split 裁剪补丁按需取用（先 rebase 到当时 pin）。
   不做 ROM（AGENTS 顶部唯一部署目标）。

## 6. 取证与复现

```bash
# 对方全部改动（三点比较 = merge-base 起算，恰好是作者改动）
curl -sL "https://github.com/wszgrcy/firefox/compare/main...build.diff" -o /tmp/wz.diff
# 基线版本
curl -s "https://raw.githubusercontent.com/wszgrcy/firefox/build/browser/config/version.txt"
# 小仓库
git clone --depth 1 https://github.com/wszgrcy/capacitor-geckoview
git clone --depth 1 https://github.com/wszgrcy/capacitor-geckoview-starter
# 插件 fork 相对上游
curl -sL "https://github.com/ionic-team/capacitor-plugins/compare/main...wszgrcy:capacitor-geckoview-plugins:main.diff"
# 兼容性实测（本文 §4 数据）
cd /Volumes//Projects/firefox && git apply --check --verbose /tmp/wz-cut.diff
```

本次取证中间产物在 `/tmp/sinytra-wszgrcy/`（易失，重启即清）。全量 clone
非必需（diff 已覆盖全部改动）；如需 clone 放 `/Volumes/（项目卷）/Projects/`
即可——该卷与仓库同卷、空间充足，**不要**放系统盘（`/`）。注意卷名含
U+F8FF 特殊字符：shell 字面路径经 Bash 传递时编码不稳定（实测踩过），
用 `~/sinytra-vol` 符号链接或 python 探测访问。
