# STATUS — Sinytra

> 实现进展与待办（给新会话的交接页）。技术细节见 `ARCHITECTURE.md` /
> `API_MAPPING.md` / `BOOTSTRAP.md`，阶段定义见 `ROADMAP.md`。
> 更新时间：2026-09-23 02:50。设备：MOONDROP MD-PH-001 / Android 14 / API 34。

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

## 1d. P2 JS transport 落地（feat/p2-js-transport 分支，harness 29 PASS，2026-09-23 00:22）

- **路线修正（重要）**：`evaluateJavascript` / `addJavascriptInterface` /
  WebMessage 的 transport **不需要 firefox-patch**——GV153 的 public API
  `WebExtensionController.ensureBuiltIn` + `SessionController.setMessageDelegate`
  就够。实现是内置 WebExtension `sinytra-js`（`provider/src/main/assets/`，
  id `sinytra-js@bemly.moe`）：content script 单飞轮询 native message，
  Java 把请求入队 session mailbox、以 GeckoResult 应答 poll（长轮询语义），
  page-shim.js 以 MAIN-world `<script>` 执行 eval / 装 interface stub /
  收发端口消息。协议全文见 `content.js` 头注释；transport 设计见
  `JsBridge.java` 头注释（含 7 轮真机排除 Port push 的记录）。
  §1b/§2 里"真 transport 等 firefox-patch"的旧表述作废。
- **P2-1 eval 实测**：harness `jsEval ready=true value="Example Domain"`、
  `jsArith value=3`（JSON round-trip）；首个导航后 eval 竞争新 document
  轮询启动，重试 4 次兜底 honest-null。
- **P2-2 interface dispatch**：`register` 入队 → shim 装 `window[iface]`
  stub（`__sinytraStub` 标记，防覆盖诚实上报）→ 页面调用走
  `sinytra-event` iface-call → Java 反射 invoke。harness `PASS jsInterface`。
- **P2-3 WebMessage 双面（已提交 a760ac6）**：
  - framework 面：`createWebMessageChannel` 维持 honest null（框架 ctor
    package-private，javac 实测不可子类化；反射真机被 hidden-API 拦截；
    Chromium 的 `WebMessagePortImpl` 在 android.webkit 包内，provider APK
    不能撞包）。决策点记录在 `ProviderAdapters.WebMessagePortFactory`
    （AOSP patch 放开 ctor vs framework factory hook）。
  - boundary 面（androidx.webkit）：**全功能**。`CompatSmallBoundaries.
    LiveMessagePort` 把 postMessage/close/setWebMessageCallback 路由进
    MessageBridge → JsBridge transport。
- **glue factory 保真度修复（1b50409）**：`CompatWebViewFactory.
  createWebView` 原来会对已有 WebView 构造**第二个** provider（新开
  GeckoSession、transport 未绑，glue 功能悄悄哑火）。现
  `GeckoWebViewFactoryProvider` 持 WeakHashMap 注册表：createWebView 注册、
  `webViewProvider()` 查活实例（查不到 honest 抛错）、destroy() 注销。
- **boundary round-trip 真机验证（f2db384 探针）**：harness 新增
  `boundaryPort` 探针——setFactoryForTests 绑 harness factory →
  boundary createWebView 拿**活** provider → createChannel → 端口[1]
  注册 boundary onMessage → post `sinytra-boundary-echo` → shim 回显
  `sinytra-port-deliver` → 回调收到同数据。`via=page`（走完整页面传输，
  非本地回退）；close 后 bridgePorts 精确 -1（delta 断言，msgChannel
  探针的 2 个 bookkeeping 端口也在册）。
- 注意：`P0GlueActivity` 852 行，逼近 900 行拆分线，下次加探针前先拆。

## 1e. unit 测试骨架落地（0eb8fa5，21 锁全绿，2026-09-23）

- **位置与接线**：`tests/unit/java/`（AGENTS.md §1 布局）经
  `provider/build.gradle` 的 test sourceSet `srcDirs` 接入，跑法
  `./gradlew :provider:testDebugUnitTest`；`returnDefaultValues=true`
  （Log/WebMessage ctor 等偶触 android.* 用桩）、testImplementation 带
  **真 org.json**（mockable jar 把 JSONObject 桩死，路由类逻辑测不了）。
- **首批锁**：MessageBridge 9（通道配对/close 语义/pendingOrigin/无
  transport 本地回退扇出/postToMainFrame 首回调投递——页面 round-trip
  仍由设备 boundaryPort 探针锁）；JavascriptBridge 7（只暴露
  @JavascriptInterface、arity 重载键 + 简名别名、invoke 解析与
  NoSuchMethodException、无 transport 簿记）；SupportedFeatures 5
  （恰 18 项、harness 锁定的在册、未实现项零泄漏、防御性拷贝）。
- **lintDebug 门首次全绿（4c369b6）**，并挖出真 bug：API 28/29 框架类
  （ServiceWorkerController/TracingController/WebViewRenderProcess）在
  factory/bridge 里 eager 实例化——API 26/27 宿主一加载类就
  NoClassDefFoundError。全部改惰性 + `SDK_INT` 内联守卫 + honest
  null/throw + loud log（老 framework 接口根本不会调这些方法）。
  真机回归：金丝雀 + harness 29 PASS（01:12）。
- JVM 测不了仍归设备 harness 的：onPortDeliver 路由（JsBridge 分发入口
  private）、页面 round-trip 数据面、eval/iface-call 端到端。
- **第二批 +12 锁（3a1a5c5，33 锁全绿）**：GeckoBackForwardList 6
  （index 推导三态：HistoryList getCurrentIndex 尊重 / 平 List 回退末项 /
  空表 -1；hostile HistoryItem 降级；clone 独立且保 index）+
  GeckoWebSettings 6（默认值基线、setter 往返、toSessionSettings 的
  initOnly 三键映射 + build 独立性）。**又挖出一个真 bug 已修
  （7133517）**：clone 用空表重建 → copyBackForwardList() 的 currentIndex
  变 -1、getCurrentItem() 变 null，违反 framework 拷贝契约。真机回归
  harness 29 PASS（01:52）。**StateBridge 的 Bundle/Parcel 面明确不做
  JVM 测**（mockable jar 全桩，只有设备 harness 的 saveState/restoreState
  探针能诚实验证）。
- **第三批 +10 锁（31e2e16，43 锁全绿）**：ErrorBridge 5（Gecko→WebView
  错误码映射全表，断言用 WebViewClient 命名常量——生产数值全部对上真常量）
  + InterceptBridge 5（allow 路径 null 结果、请求面 flag 透传、null-uri
  短路、host 抛异常降级 allow 不崩 session）。**新增 JVM 不可测项记录**：
  DENY 返回值——`GeckoResult` 类初始化要活 UI Looper
  （`ThreadUtils.getUiHandler`），JVM 上一实例化就死，整个 deny 路径只能
  设备锁；**当前 harness 也没有 deny 值探针（P2-4 的缺口，下轮补）**。
  LoadRequest/JVM 构造走反射（protected ctor + final 字段）。
- **harness 拆分（4242ba3）**：`P0GlueActivity` 854 行触近 900 铁律，P2
  全段（saveState→visualState）抽到 `P2TransportProbes.run(...)`，440 +
  448 两文件；同一契约（探针只 append PASS 或抛，编排层转 FAIL）。真机
  回归 PASS 数与拆分前一致（02:27）。`.commandcode/` 已进 .gitignore。
- **P2-4 deny 值探针补上（7924558，harness 29 PASS，02:50）**：deny 的
  GeckoResult 此前两端都没验证（JVM 上 GeckoResult 类初始化要活 UI
  Looper）。新 `denyIntercept` 探针：eval 注入 deny 标记 iframe → app
  `shouldInterceptRequest` 返回非 null → 断言子帧从未离开 about:blank
  （DENY 在网络前取消加载）。**两条记死**：① Gecko 对 NXDOMAIN host 的
  iframe（blocked.example）不发 LoadRequest——deny 标记必须用可解析
  host + 查询参数（example.org/?sinytra-deny-probe=1）；② harness bug：
  loadError 探针换上的 errClient 从不还原，下游所有
  shouldInterceptRequest 探针会静默拿错 client——已在探针后还原。
  P2NavigationDelegate 增加 Log.d 入口日志（Sinytra/navigation）。

## 2. 下一步（按顺序，一次做一件）

1. **P2 剩余 patch 项**（真需要 firefox-patch / AOSP-patch 的只剩这两个）：
   - `shouldInterceptRequest` response-body 替换：LoadRequest 无
     method/headers/body 替换能力，Gecko 网络栈语义，扩展通道做不到。
   - framework 面 WebMessagePort：需 AOSP patch 放开 ctor 或 framework
     factory hook（决策点见 `ProviderAdapters.WebMessagePortFactory`）。
   Java 侧 bookkeeping 与 boundary 面已就绪，patch 一到只绑 transport。
2. **unit 测试扩面（43 锁，见 §1e）**：可 JVM 测的 bridge 已基本覆盖
   （MessageBridge/JavascriptBridge/SupportedFeatures/GeckoBackForwardList/
   GeckoWebSettings/ErrorBridge/InterceptBridge）；StateBridge Bundle 面、
   deny 值、页面 round-trip 归设备 harness（deny 值探针已补，02:50）。
   接下来排 **CTS** 全量。

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
