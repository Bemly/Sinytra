# STATUS — Sinytra

> 实现进展与待办（给新会话的交接页）。技术细节见 `ARCHITECTURE.md` /
> `API_MAPPING.md` / `BOOTSTRAP.md`，阶段定义见 `ROADMAP.md`。
> 更新时间：2026-09-21 深夜。设备：MOONDROP MD-PH-001 / Android 14 / API 34。

## 1. 当前位置

- **P-1：通过**（结论①：bootstrap 可行）。`GeckoRuntime.create`（~190ms）→
  libxul 加载（~30ms，`GeckoThread RUNNING`）→ child service bind → P0Render
  完整渲染 example.com（含进度 15→55→100 + `onPageStop success=true`），截图验证通过。
  约束见 `BOOTSTRAP.md` §1（vendor launch 故障见 §3）。
- **P0 glue：8/8 通过（连续两轮 P0 GLUE PASS，2026-09-21 22:37/22:39）**。
  `P0GlueActivity` harness（真机反射注入 `WebView + PrivateAccess`，绕开
  framework 切换）：`PASS provider construct / createWebView type /
  page1 finished / page1 progress-url / page2 finished / canGoBack /
  goBack finished / backForwardList size=2 / settings roundtrip`，
  `title1=Example Domain`。copy=0 根因已定性（见 §2a）：Gecko 有 history
  只是没及时 flush，`flushSessionState()` 后 `size=2 index=0`。
- DuraSpeed 总开关已关（见 §3），child bind 连续多轮 `0 failed binds`、
  `duraspeed block` 计数 0。P0 收尾完成：探针退到 `src/debug`（release
  dexdump 0 引用，debug 702 引用，harness 照跑），`cdeb498`。
  工作区提交到 `cdeb498`。

## 1a. P1 进展（harness 全绿，已合入）

- 历史导航：`canGoBackOrForward/gotoHistoryIndex/purgeHistory`，`saveState`
  返回快照；`clearHistory` 后 list 2→1（purge 保留当前页，Chromium 同语义）。
- 系统能力（2026-09-22 00:43）：单例族全非空；`httpAuth store` round-trip；
  `find total=2`；`loadError code=-2`。
- 关键发现记死：`WebStorage/GeolocationPermissions` 包可见构造器不可跨包
  继承——factory 返回 framework `getInstance()`，Gecko 侧走内部通道；
  `JsResult/JsPromptResult/HttpAuthHandler` 同理（反射建 token，
  Gecko 决策从 app 返回值同步驱动）；harness 里 storage 单例是系统
  Chromium 的（framework 切换后才轮到我方）。

## 1b. P2 进展（harness 28 PASS，2026-09-22 01:25）

- **P2-5 StateBridge 落地**：`saveState` 写 Parcelable SessionState + flat
  url/title/index 三件套；`restoreState` 优先 parcel 恢复（history+scroll+
  zoom+form 全量），无 parcel 则导航到 flat index URL。harness
  `PASS saveState / restoreState`。
- **P2-1/2 evaluateJavascript + addJavascriptInterface**：AAR javap 确认
  GV153 无 eval 原语——`JsEvaluator` 无 transport 时 honest-null 回调
  （不断言、不抛，harness `jsEval null=true`）；`JavascriptBridge` 只做
  `@JavascriptInterface` 反射登记/注销 bookkeeping（harness
  `PASS jsInterface`），真 transport 等 firefox-patch。
- **P2-3 WebMessage**：`MessageBridge` bookkeeping 落地（ports/pending/
  origin），harness `bridgePorts=2`；但 `WebMessagePort` 是**抽象类**，
  反射 `newInstance` 真机报 `InstantiationException`——framework-typed
  ports 必须等 P2 patch 的具体子类，`createWebMessageChannel` 暂返 null
  + loud 日志（`fwNull=true`）。
- **P2-4 shouldInterceptRequest**：`InterceptBridge` 经
  `onSubframeLoadRequest` 做 allow/deny（LoadRequest 只有 uri 级字段，
  无 method/headers/body 替换能力）；app 返回非空 response 即记 DENY
  （P2 patch 才能替 body）。`P2NavigationDelegate` 把主/子帧分流，主帧走
  旧 NavigationBridge，子帧走 InterceptBridge。
- **P2-6 回调顺序**：主/子帧分流 + DENY 日志即顺序 hardware；典型流不断言，
  以 harness 锁行为为准（ARCHITECTURE §5）。
- **P2-7 RenderProcess**：`RenderProcessBridge` 落地——`onRenderProcessGone`
  在 **WebViewClient** 上（不是 RenderProcessClient，javap 确认），crash
  时先调 app 的 `onRenderProcessGone(didCrash=true)`，再按 executor 调
  `onRenderProcessUnresponsive`；`getWebViewRenderProcess` 返回稳定 token
  （`terminate()=false`）。harness `PASS renderProcess`。
- **拆分**：`GeckoWebViewProvider` 1081→690 行（fan-out→`ClientFanOut` 520
  行，adapters→`ProviderAdapters`，tokens→`FrameworkTokens`）。

## 1c. P2-8 androidx glue 落地（harness 31 PASS，2026-09-22 02:30）

- **入口**：自研 `org.chromium.support_lib_glue.SupportLibReflectionUtil`
 （同包同类同方法名，Chromium 脏捷径禁令不适用——这是正式 boundary glue，
  不是 bootstrap trampoline），`createWebViewProviderFactory()` 返回
  `CompatWebViewFactory` handler。harness 反射验证
  `PASS glue entry=CompatWebViewFactory`。
- **诚实 feature 集（18 个）**：只宣称有 Gecko 端到端实现的；
  `isFeatureSupported` 走 framework 判定链（framework→glue
  `getSupportedFeatures`），未宣称的（如 JS_INJECTION、WEB_MESSAGE_LISTENER、
  PROXY_OVERRIDE）诚实 false。harness 逐个打印 + 断言无泄漏。
- **已接 boundary**：factory（createWebView/converter/statics/features/
  SW/tracing）、provider（visualState/message/client/renderer/profile）、
  statics（multiprocess=true 其余 honest）、converter（settings/request/
  error/port/cookie/storage）、SW/tracing（含 settings）、renderer 双向、
  profile（default 单 profile）、visualState（page-stop 触发）。
  未宣称的 builder/proxy/dropData/profileStore 抛 honest 错误。
- **关键修复记死**：`Proxy.newProxyInstance` 返回的是 boundary 接口实现，
  **不能强转为 InvocationHandler**（真机 `ClassCastException: $Proxy5`）——
  所有 `create()` 改为直接返回实现 `InvocationHandler` 的具名 Stub 类；
  Chromium 正解是 `BoundaryInterfaceReflectionUtil.
  createInvocationHandlerFor(adapter)`，等价。
- **依赖**：`compileOnly webkit:1.12.1`（boundary 接口编译用）+
  `debugImplementation`（harness 在进程内驱动 glue，debug APK 需自带
  boundary 类型；release 不带，由 app 提供；dexdump 验证 release
  probe 0 引用）。
- `insertVisualStateCallback` 落地：pending map + page-stop 触发，
  harness `PASS visualState`（requestId=42 回调）。

## 2. 下一步（按顺序，一次做一件）

1. **P2 patch 队列**（都要 `firefox-patches/` 独立 patch + 独立测试）：
   `evaluateJavascript` 真 transport、JS interface 注入、`WebMessagePort`
   具体子类 + transport、response-body 替换拦截。Java 侧 bookkeeping 已
   就绪，patch 一到只绑 transport。
2. **CTS**：WebView 相关用例全量，逐项记 Gecko 差异/未实现/上游 bug。

## 2a. copy=0 诊断矩阵（先 flush，后 hidden-View A/B）

- GV153 实测（AAR 反编译）：`GeckoSession.flushSessionState()` 存在，无需
  attach `GeckoView` 即可调；`HistoryDelegate` 三方法全是 interface default
  （缺省 `onVisited/getVisited` 返回 null 即默认处理，`onHistoryStateChange`
  缺省空实现）——visited 应答与 `StateUpdated` 是独立分支。
- `GeckoView:StateUpdated → mStateCache.updateSessionState`：有
  `HistoryDelegate` 走 History handler，无则 Progress handler 接手更新 cache；
  `SessionState` 本身即 `HistoryList`（`size()`/`getCurrentIndex()` 直读）。
- 所以 `size=0 index=-1` 只有四种可能：① Gecko 没发带 historychange 的
  StateUpdated；② 发了但 bridge 没存住；③ 存住了但 copy 读的是旧快照；
  ④ headless 没及时 flush。P0Glue 已加三路打印（HistoryList / SessionState /
  snapshot）+ `flushSessionState()`，一次运行即可定性：

```text
HistoryList=2, SessionState=2, copy=0 → Sinytra bridge/cache bug
HistoryList=0, SessionState=2         → HistoryDelegate wiring 问题
HistoryList=0, SessionState=0         → Gecko 没 flush，再测 hidden GeckoView
挂 hidden GeckoViewHost 后变 2       → 才能证明 headless/active 是根因
```

- 顺序：先 `flushSessionState()` 看 SessionState 是否变 2（信息量最大的一步）；
  只有 flush 后仍 0，才上 hidden `GeckoViewHost` 做 A/B。
- **实测结论（2026-09-21 22:37/22:39，两轮一致）**：goBack 的 `onPageFinished`
  时刻三路全空（`live=null state=null snapshot=size=0`），`flushSessionState()`
  后 Gecko 立刻补发 `onSessionStateChange size=2` + `onHistoryStateChange size=2`
 （`urls=[example.com/, example.org/]`），post-flush 三路全 `size=2 index=0`。
  定性：**HistoryList=0 + SessionState=0 → flush 后全 2 = Gecko 有 history
  只是 headless 没及时 flush**；HistoryDelegate wiring 正常（flush 后立刻收到），
  不是 bridge/cache bug。hidden-View A/B 不必做。生产修复：在
  `onPageStop(success)` 后自动 flush（见 §2 下一步 1）。
3. **P1 开工**：按 `ROADMAP.md` §3 逐项认领（CookieManager 落地 P2 patch 前先保持
   honest-default；权限/文件选择/下载/SSL/HTTP Auth/WebStorage/geolocation/
   查找/打印）。

## 3. 已知阻塞：vendor launch 故障（测试环境问题，非 glue bug）

- 现象：偶发 `loadUrl` 卡 `about:blank` 30s 超时；logcat 配对出现
  `AiuiAmsExt: duraspeed block ... bringUpServiceLocked` +
  `ActivityManager: Unable to launch app ... : process is bad`；Gecko 侧
  `ServiceAllocator: 0 successful binds` + `BindException: Cannot connect`。
- **文案修正**：`ActiveServices` 只要 `startProcessLocked()` 返回 null 就统一
  打印 `process is bad`——这句日志≠已命中 `mBadProcesses`，只是“AMS 没能给
  Service 起进程”（DuraSpeed veto 同样走这句）。模型是双路：AOSP
  bad-process（background bind + bad 名单→null；显式启动才
  `resetProcessCrashTime` + `clearBadProcess`，两者是不同操作）与 MTK
  DuraSpeed vendor veto 都能让 `startProcessLocked` 返回 null。
- 最强证据（排除纯 bad 名单解释）：卸载重装换新 UID 后首启仍
  `duraspeed block` 失败——`mBadProcesses` 按 `processName+uid` 查询，
  旧 UID 残留命中不了新 UID，至少这次失败的根因是 vendor veto。
  重装/重启后首启 100% 复现 crashhelper 被拦，指向 DuraSpeed。
- 之前误判：①“重启是必需步骤”——错。重启只是清 `system_server`/AMS
  内存态（`mBadProcesses` + vendor 调度状态），暂时恢复，不代表 GeckoView
  要求重启。P0 不以重启为正常条件，目标是 DuraSpeed 排除后连续跑十几轮
  不 reboot。②“force-stop 能清 bad”——错。`forceStopPackage` 只调
  `resetProcessCrashTime`（清计数器），不清 `mBadProcesses`；且
  `package` 主进程显式启动清的也是主进程名，不等于清 `:gpu/:tabN`。
  禁 `com.mediatek.duraspeed` 包无用（hook 在 `system_server` 内）；
  Doze 白名单/`deviceidle disable` 无用（管心跳不管 bring-up）。
- Fennec 同机同症状，证实非我方回归；其“强停+清缓存+点图标恢复”不能倒推
  标准行为（前后台判定窗口/vendor whitelist/manifest 配置都可能不同）。
- 下次失败先分支再动手：`dumpsys activity processes` 看有无 `:gpu/:tabN`
  进 `Bad processes` + logcat 看 `DuraSpeed|AiuiAmsExt|Unable to launch`：
  有 block 无 bad→vendor 问题；有 bad→AOSP 问题；都有→vendor 先杀 child
  再被推进 bad、两套机制放大。P0 期间优先把测试包加 DuraSpeed whitelist/
  后台无限制（DuraSpeed 本职就是限后台 service），而不是反复重装猜状态。
- **已解决（2026-09-21 深夜，不重启，无重装）**：root 起 DuraSpeed 界面
  （`su -c 'am start -n com.mediatek.duraspeed/.DuraSpeedMainActivity'`，
  shell 直接起会 `SecurityException: not exported`）→截图确认
  “前台优先模式”总开关开着→`input tap` 关总开关（uiautomator 复核
  `checked=false`）。另把测试包写进 `app_list.db`（`status 0→1`，root
  替换 + `force-stop com.mediatek.duraspeed` 生效，DB 语义待定，总开关是
  主因）。效果：whitelist 前 `duraspeed block` 28 条 + 全 `0 successful
  binds`；关总开关后两轮 `block` 计数 0、`successful binds` 全过、
  P0Render 完整渲染（progress 15→55→100）、P0Glue 连续两轮 PASS。
  **“必须重启”彻底证伪**：同一开机会话内从全失败到全 PASS。
  注意：这是测试环境手段，量产 ROM 仍需 vendor 电源管理白名单（BOOTSTRAP）。
- 调试纪律：保持亮屏解锁（Doze+锁屏冻心跳、截图全黑属正常）；
  先看 `am_proc_start ... :<child>` 是否出现，再看 denials；
  跑 harness 前先跑 `P0RenderActivity` 确认 child 起得来（金丝雀）。

## 5. PoC 开发者选项切换（分支 poc/dev-option-switch，2026-09-22 06:21）

- **Valid+切换达成**：manifest 补 `WebViewLibrary=libxul.so` + versionCode
  647900000（branch 6479>stock 6478/8037/8066），经 AnyWebView v1.3（scope=system）
  进 Valid 列表；`set-webview-implementation firefox.bemly.moe.debug` Success，
  Current/Preferred 均为我方（applicationId 已改 `firefox.bemly.moe`）。
- **Cromite 结论**：`com.android.webview` 包名 + `libwebviewchromium.so` +
  真 Factory 类天然过关；Sinytra 走保持包名+hook 路线（可逆，不写/system），
  不学顶包名。AnyWebView v1.4.x 要 libxposed API 101（机上 LSPosed v1.11.0 不
  支持，hook 静默失败），必须用 v1.3（de.robv 入口）。
- **FrameworkEntryActivity PASS**：`new WebView()` + `onPageFinished` 走真
  `WebViewFactory` 路径。关键修复：trampoline 必须用 Proxy 实现真实接口
  （exact descriptor；直接 impl 因 stub erase 到 Object 报 AbstractMethodError）；
  PrivateAccess 保留 + `super_setLayoutParams` 解决 Activity measure NPE；
  WebViewZygote `preloadInZygote` NoSuchMethod  benign（Chromium 私有静态方法，
  无则跳过，不影响后续 load）。
- **已知缺口（P2 aosp-patch）**：WebStorage/GeolocationPermissions 构造器
  package-private 不可继承 + `getInstance()` 经 Proxy 自循环——切换后设备上
  harness 内已无 Chromium fallback，单例检查对 storage/geo 诚实容忍（null +
  P2-pending 标记），其余 5 单例照常断言。P0Glue **P0 GLUE PASS**（31 项全过，
  含 features 18 + visualState）。
- **回滚**：开发者选项切回任一官方包即可；分支不合入 main（trampoline 禁令）。

## 4. 已实现文件速览

```text
provider/src/main/java/org/mozilla/geckowebview/
├── provider/
│   ├── GeckoWebViewFactoryProvider.java  # WebViewFactoryProvider 实现 + Statics
│   ├── GeckoWebViewProvider.java         # WebViewProvider 实现（P0 导航 live，其余 loud-todo）
│   ├── GeckoRuntimeHolder.java           # 宿主进程级 GeckoRuntime 单例（UI 线程 create）
│   ├── GeckoBackForwardList.java         # SessionState/HistoryList → WebBackForwardList 转译
│   ├── CompatWebSettings.java            # android.webkit.WebSettings facade
│   ├── P0GlueActivity.java               # P0 E2E harness（反射注入 WebView+PrivateAccess）
│   ├── P0RenderActivity.java             # P0 渲染探针（GeckoView + loadUri，直显）
│   ├── BootstrapProbe.java / BootstrapProbeActivity.java  # P-1 六项探针
├── session/  (GeckoSessionBridge + NavigationBridge + ProgressBridge)
├── settings/ (GeckoWebSettings 纯状态 + toSessionSettings)
├── storage/  (GeckoCookieManager honest-default + GeckoWebStorage→StorageController)
├── view/     (GeckoViewHost：bind/release，不进 session 依赖)
framework-stubs/  # compileOnly 的 android14 hidden API stubs（WebViewFactoryProvider/
                  # WebViewProvider/WebViewDelegate/WebViewFactory/PacProcessor/
                  # TokenBindingService；createWebView 参数 erase 到 Object，见其 README 注释）
```

构建：Java 17 + Gradle wrapper 9.4.1 + AGP 9.2.0，`compileSdk=36`（GeckoView 153
构建链要求）/`targetSdk=34`（对齐真机），GV 153 stable 已 pin（`155+` 要
compileSdk 37.1，本地 SDK 快照未暴露，暂不升）。详见 `DEVICE.md` §4。
