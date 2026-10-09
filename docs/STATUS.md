# STATUS — Sinytra

> 实现进展与待办（给新会话的交接页）。技术细节见 `ARCHITECTURE.md` /
> `API_MAPPING.md` / `BOOTSTRAP.md`，阶段定义见 `ROADMAP.md`。
> 酷安黑屏主任务的测试方法与诊断交接见 `docs/COOLAPK.md`（新会话先读）。
> 更新时间：2026-09-27（**第三方宿主跑通**：patch 栈 0008–0010 落地，
> 自建 MiniWV 最小宿主真机渲染 example.com、child 进程挂 provider uid，
> 反射 harness **41 PASS + P0 GLUE PASS** 不退，JVM **78 锁**；切换态
> Current = `moe.bemly.geckowebview.debug`）。
> 设备：MOONDROP MD-PH-001 / Android 14 / API 34。

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

## 1g. 本地 GV 构建成功 + 替换校准全绿（2026-09-23 05:09）

- **`./mach build` 成功**（增量 31 分钟 + lite 重打包；objdir 21G）。
  mozconfig 定稿：`--enable-project=mobile/android` +
  `--target=aarch64-linux-android` + 固定 objdir + `--disable-crashreporter`
  + `--enable-geckoview-lite`（对齐 pin 的 Lite 包）。
- **替换接线四处坑全修（b73848b）**：① topsrcdir 相对层级差一级；②
  `FAIL_ON_PROJECT_REPOS` 拒收 Mozilla 脚本的项目级仓库 → settings 按开关
  放宽 `PREFER_PROJECT`——但该模式一旦有项目仓库就**忽略 settings 仓库**，
  需在 provider 里镜像 google()/mavenCentral() 才能解析 AAR 传递依赖；
  ③ Mozilla 脚本只映射 nightly/beta 模块名 → 替换模式下依赖坐标换
  `geckoview-nightly`；④ mozconfig 不开 lite 时本地产物是 omni（脚本警告
  确认）。
- **publish 任务名已变**：`publishWithGeckoBinariesDebugPublicationToMaven
  Repository` 在 153 树不存在，实际是
  `geckoview:publishDebugPublicationToMavenRepository`（1m15s）。
- **校准结论（harness 28 PASS + P0 GLUE PASS，05:09）**：本地树（lite,
  pin f1b6c0f8）与 Maven AAR 行为**完全一致**——`-PsinytraLocalGecko`
  通道可信，firefox-patches 开发闭环就绪（改树 → build binaries →
  publish → provider 替换构建 → harness 回归）。

## 1f. 本地 Gecko 启动 + CTS 定跑（2026-09-23，用户拍板）

- **FIREFOX_COMMIT 已 pin**：`f1b6c0f86b96b7e0688c26f65803576f27cdaf88`
  （tag `FIREFOX_153_0_RELEASE`，153.0 正式构建源码，对齐 GV AAR
  `153.0.20260810162159`；`browser/config/version.txt`=153.0 已核）。
  sibling checkout 在 `/Volumes//Projects/firefox`（分支 sinytra-pin；
  内置盘仅 ~7GB，偏离 BOOTSTRAP §4 的 ~/src 布局，已记录）。
- **`mach bootstrap`（mobile_android）已过**：NDK r29 + SDK + JDK + clang
  全套落 `/Volumes//Projects/mozbuild`（~13G）；生成的 mozconfig 补了
  `--target=aarch64-linux-android` + 固定 objdir + 关 crashreporter。
  **`./mach build` 已在后台启动**（首次全量构建，小时级）。
- **本地替换接线（默认关）**：`provider/build.gradle` 接了
  `substitute-local-geckoview.gradle`，`-PsinytraLocalGecko` 启用；日常
  构建继续走 Maven AAR，objdir 出来后切。
- **firefox-patches/ 已建**（README 记 stack 纪律 + pin + 首个 patch 拟稿
  条目：response-body 拦截）。树内勘察记死：GV 模块已迁
  `mobile/shared/modules/geckoview/`（不在 mobile/android/modules）；
  `mobile/android/components/geckoview/` 已有 `GeckoViewContentChannel`
  （IPDL）+ `GeckoViewStreamListener` 流原语——0001 patch 的候选地基。
- **CTS 定跑（docs/CTS.md 新文件）**：版本 android-cts-14_r7；官方
  tradefed 只发 linux_x86 → 路线 A（Linux 宿主跑正式）+ 路线 B（本机
  `am instrument` 直跑官方测试 APK 过渡）。**包按设备 ABI 分两份**，
  真机用 arm 包（x86 包里 CtsWebkitTestCases 仅 x86_64 APK，实测）；
  WebView 模块在 14_r7 已改名 **CtsWebkitTestCases**（无
  CtsWebViewTestCases）。arm 包已下载中；过渡第一轮跑 Chromium 基线。

## 1h. 0001 response-body 拦截接线——已打通（2026-09-24 00:33，全量 harness 29 PASS）

**目标**：`shouldInterceptRequest` 返回 body 的端到端（firefox-patches/0001）。
设计定稿见 `firefox-patches/0001-response-body-interception.md`（necko 层
nsINetworkInterceptController 路线）。

**已提交（全部落盘，两树工作区干净）**：
- Firefox 树（sinytra-pin）：`a76774863e81`（Group 1 Java 原语）、
  `6ef2cc7dc4be`（Group 3 C++ 控制器 + docshell 包装）、
  `51c645e25be2`（进程级共享 FilterState + ParentChannelListener 包装，
  修 per-instance observer SIGSEGV）、`1824f2745432`（filter 下发链
  Java→JS→C++）、`2a3ae2df8752`（**修复①：filter 推送 subject 改原生
  dispatcher**）、`98b57f6662ab`（**修复②：应答 bundle 补 handled +
  content-type 大小写无关**）。
- Sinytra（feat/p2-js-transport）：`0af97ca`（InterceptBridge.queryApp
  抽取）、`9d8205f`（ResponseBridge + provider 接线）、`2453d28`
  （interceptBody 探针）、`a1a310a`（queryApp 路径误导日志修正）。
  注意：Sinytra 这批依赖 0001 patch 的 GeckoView 类型
  （`WebRequestInfo`/`ResponseDelegate`），**默认 Maven AAR 构建会红**
  （7 个编译错误属预期），必须 `-PsinytraLocalGecko=true` 构建/测试
  （43 单元锁在该配置下全绿）；0001 patch 发布前主分支默认构建回到
  `186d3b6` 为止是绿的。

**两个根因（真机 logcat 定位，2026-09-24）**：

- **根因 ①（filter 推送断链）**：`GeckoViewNavigation.sys.mjs` 把
  `this.eventDispatcher`（Messaging.sys.mjs 的 DispatcherDelegate，**纯
  JS 包装、无 QueryInterface**）当 observer subject 传给 C++——
  `FilterPushObserver::Observe` 连入口日志都没打过（不是 QI 失败，是
  通知根本到不了 observer）。修复：subject 改传原生 dispatcher
  `window.arguments[0].QueryInterface(Ci.nsIGeckoViewEventDispatcher)`
  （= nsWindow::AndroidView，C++ NS_IMPL_ISUPPORTS 实现该接口，也正是
  后续 `GeckoView:OnRequestResponse` 查询要用的 dispatcher）。修复后
  链路全亮：`FilterPushObserver topic=` → `filters pushed: 1` →
  `ShouldPrepare HIT` → `ChannelIntercepted` → `OnSuccess`。
- **根因 ②（应答被丢）**：`GeckoSession.responseToBundle` 从不写
  `handled` 键，而 C++ `ResponseCallback::OnSuccess` 第一步要求
  `handled === true`，否则 `ResetInterception` → 回退网络 →
  body.example NXDOMAIN → `GeckoView:OnLoadError`（PageStart 后 185ms）
  → 页面从未存在 → JS transport 死 → eval 全超时（30s×12 个 timeout，
  harness 卡 ~8 分钟是这么来的）。修复：bundle 补 `handled:true`；顺带
  content-type 查询改大小写无关（embedder 写 "Content-Type"，原
  `headers.get("content-type")` 拿空）。修复后 `onPageFinished` 185ms
  返回、eval 读到 `sinytra-body-0001`、URL 身份断言过。
- **记死**：① `ChannelIntercepted dispatcher=0x0` 打印的是**实例成员**
  mDispatcher（ParentChannelListener 创建的实例无注入），实际分发走
  `sFilterState->mDispatcher` 共享单例——不是缺陷；② deny 探针
  （example.org）与 filter（body.example）不匹配，两套拦截面（P2-4
  LoadRequest DENY 近似 vs 0001 necko 替身）实测无碰撞；③ provider
  ctor 里 open 之前的 `setResponseDelegate` dispatch 确实无人接收
  （JS 模块在 open 后才注册），无害但属死代码——探针路径靠
  `setInterceptFilters` 的 open 后重发工作，生产接线可把
  setResponseDelegate 挪到 open 之后。
- **插桩日志随本轮进树**（C++ `parent=%d` 注册日志、JS dump、Java
  dispatching log，tag `Sinytra/response`），**定稿前降级/删除**（收尾
  清单第 1 条）。

**构建/测试命令（环境变量缺一不可）**：
```bash
# Firefox 树
cd /Volumes//Projects/firefox
MOZBUILD_STATE_PATH=/Volumes//Projects/mozbuild PATH="$HOME/.cargo/bin:$PATH" ./mach build binaries
MOZBUILD_STATE_PATH=/Volumes//Projects/mozbuild PATH="$HOME/.cargo/bin:$PATH" ./mach gradle geckoview:publishDebugPublicationToMavenRepository
# Sinytra
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
MOZBUILD_STATE_PATH=/Volumes//Projects/mozbuild PATH="$HOME/.cargo/bin:$PATH" \
./gradlew :provider:assembleDebug -PsinytraLocalGecko=true
# 真机（先 force-stop 再起，否则 am start 不重启）
adb -s V885Q49L8TAMFEEE install -r provider/build/outputs/apk/debug/provider-debug.apk
adb -s V885Q49L8TAMFEEE shell am force-stop moe.bemly.geckowebview.debug
adb -s V885Q49L8TAMFEEE logcat -c && adb -s V885Q49L8TAMFEEE shell am start -n moe.bemly.geckowebview.debug/org.mozilla.geckowebview.provider.GlueHarnessActivity
```

**收尾清单（2026-09-24 01:30 全部完成）**：① ~~插桩日志降级~~
（`59aa103b6d50`：C++ INFO→DEBUG、Java Log.i→Log.d、JS dump→debug``；
`adb logcat -s Sinytra/response` 仍可观测，降级后回归 **29 PASS**）→
② ~~复跑回归~~（29 PASS / P0 GLUE PASS）→ ③ ~~format-patch~~
（`firefox-patches/0001-response-body-interception.patch`，7 commits，
基线 f1b6c0f8）→ ④ ~~STATUS 收尾~~（本节）。~~**主分支合并评估（留给
用户拍板）**：`feat/p2-js-transport` 建议暂不合入 master——0001 patch
未发布到 Maven，合入会使 master 默认构建变红；待 0002 期间 patch
稳定或定下"默认构建要求本地 GV"的策略后再合。~~
**已拍板合入（2026-09-25，ff 49 commits @ ee1fd45，单维护者不维护旧线；
"默认构建红"实测定性：master 上不带 flag 编译报 20 个"找不到符号"，
全部是引用 0001-0005 patch 注入的 GeckoView 类型——属预期，见 §1k 末条）。**

## 1i. 0002 开工（2026-09-24 01:40）

- **设计定稿**：`firefox-patches/0002-request-info-and-subframe-standdown.md`。
  范围：① 请求信息保真（`WebRequestInfo` v2 method/headers，C++ 侧
  `isNavigation` 去硬编码）；② Sinytra 让位规则（filter 命中的子帧不再被
  P2-4 DENY 近似杀掉）。流式 body IPC / POST body / Range 明确推 0003。
- **拓扑记死（修正 0001 设计文档的进程假设）**：现代 Gecko 已无
  `dom.serviceWorkers.parent_intercept` pref——`HttpChannelParent` 为
  **每条** e10s 内容 channel 在 parent 进程建**真 nsHttpChannel**
  （HttpChannelParent.cpp:586）+ `ParentChannelListener` 挂回调链，拦截
  咨询全在 parent 侧（HttpBaseChannel.cpp:4493 `GetCallback` 链）；child
  docshell 的 `mInterceptController` 为 null 且**无需 child hook**。
  子资源/子帧是否已被 0001 天然覆盖 = 0002 探针的裁决性实验。
- **Group A 已落地（Sinytra @ 本节提交）**：`InterceptBridge.
  matchesFilterPrefix`（镜像 C++ `StringBeginsWith` 字面前缀；过滤器须带
  尾斜杠锚定 host 边界——单测注释记死）+ `decide()` 让位短路
  （`responseSurfaceOwns` 命中即 allow，不咨询不 DENY）。46 JVM 锁全绿
  （+3），真机回归 29 PASS。
- **下一步**：~~Group B~~ ~~探针~~ ~~format-patch~~ **0002 全部完成**
  （2026-09-24 02:22）：
  - **Group B 已落地**：C++ `ChannelIntercepted` 从 parent 侧真
    nsHttpChannel 提取 method（`GetRequestMethod`）/请求头
    （`nsIHttpHeaderVisitor` 拍平 "name:value"，全量透传 v1，敏感头
    收窄是显式后续决策）/`isNavigation`（TYPE_DOCUMENT||SUBDOCUMENT）
    + 新 `isTopLevel`（TYPE_DOCUMENT，WebView isForMainFrame 语义）；
    `WebRequestInfo` v2（bundle 缺省 GET/empty/false 向后兼容）；Sinytra
    `ResponseBridge.Host` 收完整 info、`queryApp` 6 参扩展、
    `SinytraResourceRequest.getMethod/getRequestHeaders` 转真值
    （首个冒号切分，坏对跳过）。Firefox @ `539b7ff6f329`，patch 文件
    `0002-request-info-and-subframe-standdown.patch`。
  - **拓扑推论真机实锤**：`PASS interceptSubresource body=sinytra-sub-0002`
    ——page 内 XHR 拿到 app 替身（网络是 NXDOMAIN，标记只可能来自
    app）＝ parent 侧 necko 拦截**天然覆盖子资源**；`PASS interceptIframe`
    ＝ filter 命中的子帧让位 + 替身渲染。0003 无需 child hook。
  - **探针卫生记死**：interceptIframe PASS 后必须移除注入的 frame——
    deny 探针断言"所有 frame 停在 about:blank"，脏 frame 会把它打 FAIL
    （首轮实测撞过，同 errClient 教训一类）。
  - **全量 harness 31 PASS**（29 + 2），JVM 49 锁全绿（+3）。
  - **0003 候选**（按 0002 设计文档 §4 边界）：POST body 透传、Range、
    流式 body IPC（替代 base64 16MB 上限）、敏感请求头收窄。

## 1j. 0003 完成（2026-09-25 00:33，全量 harness 32 PASS）

- **设计定稿 + 落地**：`firefox-patches/0003-response-body-streaming.md`。
  核心修正：0002 拓扑结论（查询全程在 app 进程）推翻了 0001 "流跨不了
  JVM 必须排干 base64" 的前提——**是 JNI 边界不是进程边界**，16MB 上限
  在解不存在的问题。复用上游现成原语（AGENTS §4）：
  `GeckoViewInputStream`（Java，@WrapForJNI 包装任意 InputStream）+
  C++ `GeckoViewInputStream : nsIAndroidContentInputStream`。
- **实现**：Java `GeckoViewResponseStreams` 注册表（register→id /
  `@WrapForJNI take(id)`）+ `responseToBundle` 直发 `bodyStreamId`
  （排干/base64/`RESPONSE_BODY_MAX_BYTES` 全删）+ C++ `Take(id)` →
  `ResponseBodyStream` 子类（暴露 protected ctor）→
  `StartSynthesizedResponse`；Sinytra `ResponseBridge` 删除 16MB drain，
  app 流原样 `builder.body()`。Firefox @ `62b7b46e280e`，patch 文件
  `0003-response-body-streaming.patch`。
- **裁决性实验**：`PASS interceptLargeBody verdict=17000014:PREFIX:
  0123456789:0123456789:SUFFIX`——17MB 替身（大于废除的 16MB 上限，
  网络侧 NXDOMAIN 不可达）精确渲染。旧路径必拒、流路径通过。
- **树内踩坑记录（build 系统）**：① `mach build binaries` 不含 export
  层——新增 GeneratedJNI 头必须 `mach build export` 后再 binaries，且
  wrapper 由 Gradle 注解处理器生成（publish 顺带产出）；② 新类必须进
  `widget/android/moz.build` 的 `classes_with_WrapForJNI`（字母序）；
  ③ `GeckoViewResponseController.h` 内联 `= default` 构造使
  StaticComponents.cpp 实例化 RefPtr 析构——头里必须给
  `ServiceWorkerInterceptController` 完整定义，前向声明靠 unified
  bundle 传递 include 侥幸，边界一动即炸（实测撞过）。
- **回归**：全量 harness **32 PASS**（31 + interceptLargeBody），JVM
  49 锁。拦截线（0001/0002/0003）至此完整：filter 下发 → 请求面保真
  → 子帧让位 → 子资源/子帧替身 → 无上限流式。
- **剩余候选**：敏感请求头收窄（Cookie/Authorization 目前全量透传，
  显式决策点）；~~master 合并评估仍挂起（等用户拍板）~~
  **已拍板合入（2026-09-25，见 §1k 末条）**。

## 1k. Gecko pin 升级 153.0 → 158.0a1（2026-09-25 完成，用户拍板）

- **定性**：158.0 正式 tag 未发布（预计 2026-11），"提高到 158" 的唯一
  现行形态 = **mozilla-central 158.0a1 最后一刻** `34ed69f161676c3ac7ca5f
  201fade6d081e5bcd2`（version.txt=158.0a1 已核；下一个 version bump
  commit `4c5c29cee1f9` → 159.0a1）。AAR 对齐 `geckoview-nightly:
  158.0.20260924093433`。158.0 stable 出来后再评估切 release tag。
- **rebase**：`sinytra-pin-158` = 34ed69f16167 + 0001-0003 共 9 commits
  逐个 cherry-pick，唯一冲突 `ParentChannelListener.cpp` include 区
  （158 重排了 include 列表），其余全部干净/自动合并（注册点逐一复核）。
- **全量构建 55 分钟全绿**（新 clang 自动拉取，独立 `objdir-158` +
  `mozconfig-158`；153 线当时保留 `objdir-opt` 可回退——2026-10-01 已退役，
  见 §1u）。**158 publish 产物
  artifactId 变为 `geckoview-default`**，`substitute-local-geckoview.gradle`
  把 nightly 坐标换成本地 geckoview-default——已验证咬合。
- **provider 接线**（`d2cafc0`）：compileSdk 36→**37.2**（158 的
  `android-components/.config.yml`；android-37.2 平台已 sdkmanager 补装——
  旧记录"本地无 37.1"过时）、坐标换 nightly、objdir 指向 objdir-158。
- **可借鉴项落地**：**0004**（@ `bc58ea7cf4df`）——`Synthesize()` 开头
  `SynthesizeServiceWorkerTainting(LoadTainting::Basic)`（ORB 加固，
  借鉴 wszgrcy 线；153 上不需要，158 起防御性补上；注意 `LoadInfo()`
  返回 `already_AddRefed` 不能直接判 bool，nsCOMPtr 接收）。**0005**
  （@ `b43672422aa1`）——日志纪律落地：C++ `SINYTRA_LOG` 宏 +
  运行时 pref `sinytra.log.enabled` 门（默认 false；AAR 双变体共用
  libxul，编译期门做不到——这是设计结论不是偷懒），Java
  ~~BuildConfig~~ `FLAG_DEBUGGABLE` 门（AGP 9 不再生成 BuildConfig，
  且 FLAG_DEBUGGABLE 才是"调试模式"本义）；provider debug 构建
  `src/debug/assets/geckoview-config.yaml` + `configFilePath`
  （assets 先拷 filesDir，wszgrcy 线同款做法）。
- **验证闭环（2026-09-25 05:1x）**：JVM **49 锁全绿** ×2；真机金丝雀
  渲染正常；全量 harness **32 PASS + P0 GLUE PASS** ×2（0004 后、
  0005 后各一轮）；日志门双向验证（pref on → `Sinytra/response`
  全程可见）。拦截线（0001-0005）在 158 上与 153 行为一致。
- **patch 重出**：`firefox-patches/0001..0005-*.patch` 五件，基线
  34ed69f16167（0001=7 commits、0002/0003/0004/0005 各 1）。
- **踩坑记死**：① U+F8FF 卷名字面路径经 Bash 传参编码不稳定（时好时坏），
  一律 `~/sinytra-vol` 符号链接或 python 探测；② 后台 mach 别设短超时
  （libxul 链接 >10min，超时静默杀链接），管道吞退出码，必须显式
  `echo $?` 核对——`| tail` 后 mach 失败会伪装成功；③ 158 树的
  `shared-settings.gradle` 从 `mobile/android/android-components/.config.yml`
  读 compileSdk 37.2；④ sed 批量替换会把宏定义体一起换掉（自递归），
  批量替换后必查定义行。

- **master 合入（2026-09-25，用户拍板：单维护者、完全不需要维护旧线）**：
  `feat/p2-js-transport` **ff 合入 master**（49 commits，f74925f → ee1fd45，
  线性历史）。合入后 master 的**默认构建（不带 flag）实测红：20 个
  "找不到符号"**（ResponseBridge/GeckoWebViewProvider 等引用 0001-0005
  patch 注入的 GeckoView 类型，stock nightly AAR 里没有）——**预期状态**：
  master 上一切构建/测试必须 `-PsinytraLocalGecko=true`（本地 objdir-158
  已就位，工作流见 firefox-patches/README.md）。若未来要默认绿：
  发布自建 AAR 到可达 Maven 或 mavenLocal，均为显式决策点，暂不做。
  （20 个是合入当时的数；现行数见 §4 构建段。）

## 1l. P1 收尾（2026-09-25，全量 harness 37 PASS / JVM 53 锁）

- **盘点**：P1 十项（CookieManager/权限/文件选择/下载/SSL/HTTP Auth/
  WebStorage/geolocation/查找/打印）中六项早已实装（history 族/
  singletons/httpAuth store/find/loadError 均有探针），三个接线断裂 +
  探针缺口是本次内容：
- **SSL 回调接线（e686481）**：`onReceivedSslError` 此前完全缺失。
  ErrorBridge.toSslPrimaryError（三个 Gecko SSL 码 → SSL_UNTRUSTED，
  非 SSL → -1）→ NavigationBridge.Host/GeckoSessionBridge.Client 加
  sslPrimaryError 参数 → ClientFanOut 分支：SSL 错误改走
  onReceivedSslError（Chromium 顺序：不再 also onReceivedError）。
  SslError 无证书（Gecko onLoadError 无证书信息，getCertificate()
  诚实 null）；SslErrorHandler 反射 token（cancel 即事实结果；proceed
  需 cert-override 原语 → P2 patch 候选，auth-handler 同先例）。
- **permission 决策回流（6843154）**：旧实现三处断裂
  （grant/deny 空壳×2 + onContentPermissionRequest 返回 null → 页面
  权限 promise 永久挂起）。重设计 Decision 契约（Host 恰好完成一次
  allow/deny，扇出全回退路径兜底 deny）+ autoDecision 表
  （storage/autoplay/EME 静默放行；notifications/XR/tracking 等
  静默拒绝——均无 WebView 面，不伪造 prompt；仅 geolocation 走 app）。
  媒体路径接真：getUserMedia → onPermissionRequest(VIDEO/AUDIO_
  CAPTURE) → grant/deny 路由回 MediaCallback。
- **PrintBridge 重写（d0c2b7e）**：旧实现从不写 destination fd（打印
  出空文件还报成功）。新核心 writeTo：saveAsPdf → PDF 流 → worker
  拷贝进 fd。不复用上游 GeckoViewPrintDocumentAdapter（急切 ctor 不合
  异步形态）。打印页内 PageStart→PageStop→PDF 22KB 验证。
- **探针（d99754b）**：cookiePolicy（我方 impl 标志往返）/
  print（%PDF 头 + 尺寸）/permissionPrompt（getUserMedia→prompt→deny
  →页面 rejected 双侧观测）/geolocationPrompt（deny 路径，摆脱设备
  定位依赖）。全量 harness **37 PASS + P0 GLUE PASS**，JVM **53 锁**。
- **download 事件：Java 接线正确但 Gecko 未分派 → 0006 候选**：
  ~~（本条结论被 §1m 反转：分派链在 opt 构建正常，真因是探针把
  setDownloadListener 调在绑定 Chromium 的 framework WebView 上；确定性
  attachment 探针已恢复，38 PASS。）~~
- **fileChooser**：接线完整（params→intent→confirm/dismiss）但无法
  无手势触发（Gecko 激活检查），无设备探针——CTS/手测覆盖，非缺口。
- **P1 状态：完成**。遗留决策点：SSL proceed 语义（0006）；~~download
  分派~~（§1m 已反转闭案）；fileChooser e2e 归 CTS。

## 1m. P2 第一批收口（2026-09-25，全量 harness 38 PASS / JVM 62 锁）

- **CookieManager 真实化（firefox-patches/0007 @ `dfcc04709482`）**：
  GV 无任何逐 cookie API（AAR javap 确认），patch 在 `StorageController`
  加四原语（GetCookie/SetCookie/RemoveSessionCookies/HasCookies，全局
  dispatcher + `GeckoViewStorageController.sys.mjs` 直控
  `nsICookieManager`）。provider 侧 `GeckoCookieManager` 重写：策略旗标
  映射 `ContentBlocking.setCookieBehavior`（**引擎现实与 facade 报告一致**：
  第三方未设/optOut → ACCEPT_FIRST_PARTY = WebView targetSdk≥21 默认）；
  同步 API 统一在 `Sinytra-cookie` HandlerThread 发起 + 有界 latch（UI
  调用者无死锁；`GeckoRuntime.getStorageController()` 断主线程 →
  controller 惰性取一次缓存，顺带修掉 removeAllCookies 的同款潜在雷）；
  `flush()` 保持 no-op（Gecko 自动落盘，无原语）。**真机定位两个坑记死**：
  ① `add()` 对 session cookie 也执行 expiry（"更 restrictive 者生效"），
  `expiry=0` 即存即死 → 默认 400 天（引擎 cap 同值）；② cenum 的 JS 访问
  是平的（`Ci.nsICookie.SCHEME_HTTPS`），写 `schemeType.` 嵌套即 TypeError
  → sendError → false。探针 `cookieJar`（set→get→removeSessionCookies→
  gone）。设计文档 `firefox-patches/0007-cookie-jar.md`；0006 编号改留给
  SSL-proceed。`GeckoRuntimeHolder.peek()` 新增（外部可在 runtime 缺席时
  诚实降级，不强制 UI 线程 create）。
- **敏感请求头收窄（Chromium 对齐）**：Chromium 的
  `shouldInterceptRequest` 本就不给 app 看 `Cookie`（拦截点之后由网络栈
  挂上）与 `Authorization`——官方路由是 `CookieManager.getCookie(url)`
  （0007 使其为真）。`SinytraResourceRequest.getRequestHeaders()` 在
  app 边界剥离两者（大小写无关；0002 内部面保持全量），+2 JVM 锁。
- **provider 卫生 prefs**：`src/main/assets/geckoview-config.yaml`
  （全构建）：connectivity-service / captive-portal-service 关闭、
  `services.settings.server` 钉黑洞（系统 WebView 不自主联网探测/拉
  Mozilla 远端配置）；debug 变体覆盖同文件追加 `sinytra.log.enabled`
  （0005 门不变）。走公开 `configFilePath` 机制，无 patch。
- **download 判决反转（0006 排查）**：P1 的"Gecko 未分派
  onExternalResponse"**是观察假象**。MOZ_LOG（DocumentChannel/
  URILoader/HelperAppService，`logging.*` prefs 在 opt 构建可用）实证：
  attachment 顶层加载 `forceExternalHandling: yes` →
  `GeckoViewExternalAppService.CreateListener rv=0` → Java
  `onExternalResponse` 全链**在 opt 构建上正常**；"PageStop success=true"
  在文档通道被接管时也照发，P1 探针把它误读为"当页面渲染"。**真 bug 是
  探针自己**：harness 的 framework `WebView` 绑定的是系统 Chromium
  provider（反射注入不换 mProvider），探针把 `setDownloadListener` 调在
  framework 对象上——全 harness 惯例是直调 `provider.*`，只有这一处
  例外。恢复的探针为**确定性**版本（进程内 ServerSocket 伺服
  Content-Disposition: attachment，无 blob/无手势/无上游 debug-gate）。
  P0RenderActivity 顺带加 `url` extra + onExternalResponse 观测（debug
  复现工具）。分派链残留决策点：`contentDisposition` 字符串上游 C++
  未透传（只提取 filename），Chromium 会给——记入 0006 残留。
- **0006 现状**：download 部分已闭（无需 patch）；剩余候选 = SSL
  proceed 语义（需 cert-override 原语）+ contentDisposition 透传。
- 回归：全量 harness **38 PASS + P0 GLUE PASS**，JVM **62 锁**全绿
  （53 + cookie 7 + 敏感头 2）。

## 1n. P2 第二批收口（2026-09-25，全量 harness 39 PASS / JVM 66 锁）

- **0008 contentDisposition 透传（零 Gecko 改动）**：C++ StreamListener
  本就遍历全部响应头（并合成 content-disposition 条目）——
  `ContentBridge` 大小写无关提取 `Content-Disposition`/`Content-Type`
  传给 `DownloadListener`（Chromium 契约字段），+2 JVM 锁。
- **0006 SSL proceed 定稿（firefox-patches/0006 @ `2f3d7e2e5b56`）**：
  `SslErrorHandler.proceed()` 接真。链路：`nsILoadURIDelegate.
  handleLoadError` 加失败 channel 参数 → child actor 在
  ERROR_CLASS_BAD_CERT 时取 `serverCert` DER → base64 进 OnLoadError 消息
  → parent actor stash 进新全局模块 `GeckoViewCertOverride` → Java
  `CertOverrideController.allowError(uri)`（新 geckoview 类）→
  `nsICertOverrideService` temporary override → 500ms 后经
  `ownerBridge().loadUrl` 重载。Java 侧 `android.webkit.SinytraSslErrorHandler`
  （包内子类；AOSP 基类 proceed/cancel 是空壳、ctor public @SystemApi，
  Chromium 胶水同为匿名子类——android.jar 把 ctor 剥成包私有，包内放置
  同时满足 javac 与运行期）。**踩坑记死**：① `docShell.failedChannel`
  JS getter 读错误页文档的副本（delegate 窗口期 null，两轮实测）→ 显式
  传参是唯一正解；② harness/framework WebView 绑定系统 provider 陷阱
  （同 download 探针）；③ **detached View.post 永不执行**（线程转储证实
  主线程空闲 epoll）——重载必须用主线程 Handler；④ 重载页首 eval 与
  poller 竞争 → 重试；⑤ IDL 变更先 `mach build export`（复现 §1g ①）。
  同步语义分歧已记录：temporary exception 按 host:port+证书会话内放行。
- **探针**：`sslProceed`（自包含：debug assets 自签 PKCS12 + 进程内
  SSLServerSocket，断言恰好 1 次 prompt + body 渲染，eval 带重试）。
- 回归：全量 harness **39 PASS + P0 GLUE PASS**（诊断移除后干净构建复跑
  确认）、JVM **66 锁**。
- **0006 从此闭案**：download（§1m 已反转）+ SSL proceed 均闭。P2 剩余：
  framework 面 WebMessagePort（AOSP 决策点）、SW+拦截并存语义（储备）、
  a11y、CTS 全量（~~Linux 宿主/ROM 阶段~~ → 切换后设备直跑，见 §1q）。

## 1o. P2 第三批：framework 面 WebMessagePort 落地（2026-09-25 深夜，全量 harness 40 PASS / JVM 74 锁）

- **AOSP 决策点解除（无需 AOSP patch、无需 factory hook）**：
  `ProviderAdapters` 旧记录"框架 ctor package-private"是对 android.jar
  桩 javap 的误读（@SystemApi 被剥离）——android14-release 源码里
  `WebMessagePort()` 是 `@SystemApi public`。与 SslErrorHandler 完全
  同构：同包放置满足 javac，真实 public ctor 满足运行期（SSL 先例
  真机已证）。
- **实现**：`android.webkit.SinytraWebMessagePort`（provider APK 内新
  类，同包子类；`Binding` 接口注入路由，不引 org.mozilla 依赖，SSL
  同款决策注入风格）。`GeckoWebViewProvider.createWebMessageChannel`
  接真：返回一对 framework 端口，路由进 MessageBridge，页面传输与
  boundary 面（LiveMessagePort）共用同一 JsBridge 通道。Chromium 奇偶
  语义锁定：closed port 后 post/setCallback 抛 ISE、callback 二次设置
  抛 ISE、onMessage 首参收端口自身（包装层把 MessageBridge 的 null
  端口面替换为 this）。诚实缺口不变：端口转移（getPorts）无 transport
  原语。
- **JVM +8 锁**（SinytraWebMessagePortTest，共 74）：closed-port/
  callback-once/端口身份/异常不上抛；mockable jar 限制记死——
  WebMessage.getData() 恒 null，数据内容归设备锁。**探针**：msgChannel
  收紧（fwNull 必须 false）+ 新 fwPort（端到端 via=page + 端口身份
  断言 + close delta）。
- **回归**：全量 harness **40 PASS + P0 GLUE PASS**（fwPort data=
  sinytra-fwport-echo via=page），JVM **74 锁**全绿。
- **拆分（卫生项）**：`GeckoWebViewProvider` 837→731 行（§1p 视觉接线后回涨到 787，仍 <900）——无操作
  View/Scroll delegate 抽到 `ProviderViewDelegates`（唯一活分支：
  file chooser onActivityResult 路由）；拆分后 harness 复跑 40 PASS
  不变。
- **新发现记死（视觉面缺口）**：framework WebView 路径从未接线视觉
  输出——`createWebView` 不挂 GeckoViewHost 子视图、ViewDelegate 全
  no-op；P0 的视觉验收一直是 P0Render 直用 GeckoView，harness 探针
  全是回调级。**任何视觉类语义（a11y、截图、Surface 生命周期）的前置
  = 视觉面接线**（GeckoViewHost attach 进 WebView 视图树 + Surface
  生命周期），且当前 harness 无法视觉验证（注入的 WebView 未进布局）。

## 1p. P2 第四批：视觉面接线落地（2026-09-25 深夜，全量 harness 41 PASS / JVM 74 锁）

- **方案**：`GeckoWebViewProvider` 构造时把 `GeckoViewHost` 作为
  **framework WebView 的子视图**挂进视图树（WebView 是 AbsoluteLayout——
  子视图参数必须是 `AbsoluteLayout.LayoutParams`，普通 LayoutParams
  会在 onLayout 强转崩）。子视图自己的 View 生命周期接管 Surface
  attach/detach/freeze，provider 永不自己管理像素（ARCHITECTURE §3：
  view 宿主 session surface）。挂载失败降级 headless + loud log，
  全部非视觉路径不受影响。
- **配套语义**：`onPause/onResume`（app 主动调用）映射
  `session.setActive(false/true)`（ARCHITECTURE §2 的后台原语）；
  `destroy()` 经 `GeckoViewHost.release()` 关 session（顺带解绑视图）。
- **harness**：WebView 底部停靠 700px 进 activity 布局（Chromium 绑定
  面空白不渲染，Gecko 子视图才是显示者；状态文本保持顶部可读）+
  新 `visualSurface` 探针（GeckoView 子视图存在 + isAttachedToWindow
  + getSession 非空三断言）。
- **像素级证据（screencap）**：底部停靠带内清晰渲染 Gecko 替身页
  `sinytra-body-0001`——Gecko 内容经 framework WebView 视图树真实
  显示，视觉缺口闭合。
- **a11y v1 结构性落地（uiautomator 证据）**：a11y 客户端（uiautomator
  dump）能从窗口树读到 Gecko 渲染的页面文本节点（正文 + iframe URL）
  ——遍历链路 WebView → GeckoView 子视图 → SessionAccessibility
  节点树天然连通（子视图挂载的免费收益）。
- **回归**：全量 harness **41 PASS + P0 GLUE PASS**（+visualSurface），
  既有 40 探针在子视图挂载后全部不变；JVM 74 锁、lint 门绿。

## 1q. 部署路线收敛（2026-09-26，用户拍板）

- **唯一目标**：已 root + LSPosed + AnyWebView 的现有手机上，经开发者选项
  「WebView 实现」把 Sinytra 切成 system WebView 跑起来。**不刷机**：自建/测试
  ROM、overlay、预装/系统签名、`aosp-patches/`、metadata 自声明正式路线、
  `WebViewLibraryLoader` 分流、ROM 阶段 CTS、Linux tradefed 门槛——全部作废。
  文档已同步（AGENTS 顶部/§5、BOOTSTRAP §2 重写、DEVICE §5、CTS、ROADMAP §4-10）。
- **随之反转的硬规则**：`com.android.webview.chromium.
  WebViewChromiumFactoryProviderForT` trampoline 从“只许进分支”变为
  “必须进主分支”——Android 14 `WebViewFactory` 硬编码该类名，不改 framework
  就没有第二个入口。约束：只做入口转发 + 描述符适配。
- **已有实证（`poc/dev-option-switch`，2026-09-22，153 线，未合入）**：
  - `ce86279`：manifest `WebViewLibrary=libxul.so` + versionCode 647900000
    → AnyWebView v1.3（scope=system）列入 Valid；
    `set-webview-implementation` Success，Current/Preferred 均为我方。
  - `00d3559`：trampoline 用 Proxy 实现真实 `WebViewFactoryProvider`（stub
    erase 导致直接 implements 报 AbstractMethodError）；保留 `PrivateAccess`
    + `super_setLayoutParams`（Activity measure NPE）；WebStorage/Geolocation
    `getInstance()↔Proxy` 自循环用重入标记断环；`FrameworkEntryActivity`
    （真 `new WebView()` + `onPageFinished`）PASS；P0Glue 在切换后 PASS。
  - applicationId 在分支上改成了 `firefox.bemly.moe`；**master 已定稿为
    `moe.bemly.geckowebview`**（2026-09-26，debug = `moe.bemly.geckowebview.debug`；
    Java 包/namespace 不变）。
  - 分支比 master 落后整个 158 升级 + 0001–0007 + P2 第二至四批，不能直接合，
    只能按上面三点在 master 上重做。
- **设备现状（2026-09-26 深夜，切换后）**：AnyWebView 1.3 + LSPosed v1.11.0 在位、
  SELinux Enforcing；Current/Preferred = `moe.bemly.geckowebview.debug`
  0.2.0-gv158（验收见 §1s）；旧 applicationId 的
  `org.mozilla.geckowebview.debug` 可卸载。

## 1r. 硬规则收口（2026-09-26，全量 harness 41 PASS + P0 GLUE PASS / JVM 74 锁）

- applicationId 定稿 `moe.bemly.geckowebview`（debug `.debug`），新包名下真机
  canary + 全量 harness 通过。
- **分层**：`GeckoRuntimeHolder` 下沉到最底层 `runtime/`（session/storage 不再反向
  依赖 provider）；`GeckoSessionBridge.attachTo(GeckoView)` 删除，open+setSession
  移到 `GeckoViewHost.attach`（session 不碰 View）；compat 改依赖自定义
  `CompatHost.Factory/WebViewBackend` 接口、由 provider 实现（compat 不再点名
  provider 类）。
- **latch**：`GeckoCookieManager` 的有界阻塞属 framework 同步契约的必需例外（类头注释
  已写明理由），代码不改；AGENTS §7 条款校正为允许 UI 线程有界等待。
- **行数**：`P2TransportProbes` 869 → 590，拦截类探针（0001–0003 + P2-4 deny）拆到
  `P2InterceptProbes`（304），编排层紧接调用、顺序不变。

## 1s. 切换验收 + 真实路径两条根因（2026-09-26 深夜，FRAMEWORK ENTRY PASS）

- **切换事实**：装新版（versionCode 999_158_001 + `WebViewLibrary` metadata）
  后 `install -r` 即在 Valid 列表（候选列表在本轮 system_server 启动时已含
  我方包名，无需再重启）；`cmd webviewupdate set-webview-implementation
  moe.bemly.geckowebview.debug` Success，Current/Preferred 即为我方。回滚
  目标 `com.google.android.webview` Valid 在册（DEVICE §5）。
- **验收四件全过**：① 金丝雀 P0Render 完整渲染 example.com（0 次
  `duraspeed block`、gpu+tab child 全起）；② 真实路径探针
  `FrameworkEntryActivity` **FRAMEWORK ENTRY PASS**（`getCurrentWebViewPackage`
  为我方 / `new WebView()` 经 trampoline / LayoutParams / pageFinished
  title=Example Domain / Gecko 子视图 attached / eval=3 / 单例 storage+geo
  provider-owned / CookieManager 往返）+ screencap 像素证据；③ 反射 harness
  **41 PASS + P0 GLUE PASS** 切换后复验不退；④ lint + JVM **78 锁**全绿
  （§1r 后 storage 单例 +4）。系统面冒烟：切换后无非 Sinytra 进程崩溃。
- **根因①（ctor 期子视图挂载必炸，仅真实路径）**：framework 在
  `View.<init>` 内部经 `setOverScrollMode → ensureProviderCreated` 构造
  provider（反射 harness 在 WebView 构造完成后才调 createWebView，永远踩
  不到）——此时 WebView 自身 ViewGroup 的 mChildren 未初始化，ctor 里
  `webView.addView(host)` NPE（"Attempt to get length of null array"）→
  旧代码 catch 后降级 headless。修复：`bind()`（setSession，无视图树操作）
  留在 ctor；`addView` 抽出 `attachViewHost()`（`getParent()` 幂等守卫），
  失败由 `ViewDelegate.onAttachedToWindow`（attach 时首个可靠时点）重试；
  headless 语义不变（永不 attach 即永不重试）。
- **根因②（setFrame 完全委托无 super）**：android14 `WebView.java` 里
  onMeasure/onSizeChanged/onScrollChanged 等框架自己先调 super，但
  `setFrame / requestFocus / dispatchKeyEvent / onHoverEvent /
  onGenericMotionEvent / performLongClick` 是**完全委托**——Chromium glue
  经 `PrivateAccess.super_*` 转发，我方 no-op 等于 frame 永远 (0,0,0,0)：
  子视图测量/布局照常（AbsoluteLayout 用测量值，surface 尺寸 1080x2112
  正确）但 surface 位置按断裂的父链定位 + 不透明窗口背景全遮 → 全屏空白
  （uiautomator 树无文本 + screencap 全粉定位的）。修复：
  `FrameworkPrivateAccess` 增六枚 super 转发（沿用既有反射模式），
  ViewDelegate 接线；`onCreateInputConnection` 仍 honest null（IME 归
  GeckoView 子视图聚焦路径，P2 语义）。
- **探针修正记死**：session 初始 about:blank 的 `onPageFinished` 会抢先
  放行 latch——必须按 url 过滤（`about:` 前缀不算，页面才计数）。

## 1t. 切换后第三方冒烟 + 真实宿主三个 patch（2026-09-27，0010 全绿）

- **结论**：第三方宿主 App 从"创建 WebView 即 SEGV"修到"example.com 完整
  渲染 + 回调链完整 + 41 PASS 不退"。0008/0009/0010 三个 Gecko patch
  落栈（全部真机实证），加上 provider 侧两修（`FrameworkPrivateAccess`
  void 拆箱 NPE、config assets 跨包安装）。
- **排查利器**：自建最小宿主 **MiniWV**（`moe.bemly.minimalwv`，
  /tmp 下 aapt2+javac+d8+apksigner 手工构建，纯 `android.webkit.WebView`
  + example.com + 状态栏打日志，targetSdk 34 独立 uid）——
  Obsidian 会跳默认浏览器、mywebview 自带行为噪音，最小宿主才是干净
  的真实路径探针。
- **0008（greomni）**：`GeckoThread.getMainProcessArgs` 用宿主 APK 拼
  `-greomni`，宿主没有 omni.ja → 组件清单读不到（"Could not read chrome
  manifest jar:<host>!/..."）→ prefs 服务缺失 → `sPImpl=null` →
  `Preferences::InitializeUserPrefs` SEGV（fault addr 0x8，tombstone +
  objdir libxul llvm-symbolizer 定位；宿主 APK==provider APK 时不可见）。
  修：greomni 经 classloader mozglue 路径推导 provider APK（0008.md §2
  排除记录：hidden API denied 警告两边一致属良性、webview zygote 无
  libxul、无双映射）。
- **0009（child services 跨包）**：0008 后 XRE 走到 RUNNING，但
  `ServiceAllocator`/`ServiceUtils` 按宿主包解析 child services 且服务
  `exported=false` → SecurityException → spawn 失败 native SEGV。修：
  `getComponentPackage`（classloader 推导 + getCurrentWebViewPackage
  sourceDir 验证 + 快路径回退）+ jinja 全部服务 `exported=true`。
  **踩坑**：jinja 是 GENERATED_FILE，`mach build binaries/export` 都不
  重生成，必须 `mach build` 增量全量触发；管道后 `echo $?` 是 tail 的
  退出码（README 纪律再次应验）；MOZCONFIG 忘设会打到 objdir-opt。
- **0010（ACCESS_NETWORK_STATE 守卫）**：宿主缺权限时
  `GeckoNetworkManager` 主线程 `getActiveNetworkInfo` SecurityException
  杀宿主（mywebview/MiniWV v1 实测）。修：权限守卫 + 降级
  UNKNOWN/NONE + warn 一次（Chromium 同语义）。
- **provider 侧两修**：① `FrameworkPrivateAccess.invoke` 对 void 型
  super_*（如 `super_setLayoutParams`）拆箱 NPE 误报失败；②
  `installConfig` 用宿主 `getAssets()` 读不到 provider APK 的 assets，
  第三方宿主卫生 prefs（联网探测/Remote Settings）静默失效——经
  `WebView.getCurrentWebViewPackage` 包上下文回退安装（`320f32f`）。
- **真机证据**：MiniWV（第三方宿主）example.com 完整渲染（screencap）、
  onPageFinished title=Example Domain、child 进程挂 provider uid
  （`moe.bemly.geckowebview.debug:gpu/tab…` 5 个）、`3 successful binds`；
  mywebview 缺权限宿主存活（0010 守卫日志）；Obsidian 不再 SEGV；
  反射 harness **41 PASS + P0 GLUE PASS** 不退。
- **记死（截图纪律，2026-09-27 用户拍板）**：设备画面判断必须**间隔多截
  几张**（≥3 张、间隔 4–5s），首帧加载/child 慢启动时单张截图必误判；
  首次加载可等 30s+ 再看回调日志。
- **安全记录**：child services exported=true 后，任何 App 可 bind 出
  provider uid 的 Gecko child（资源记账归 provider）。唯一部署形态是
  个人设备 root 环境，接受并记录。
- **MiniWV 残留观察**：首跳 `evaluateJavascript` 在第三方宿主里仍可能
  honest-null（transport 竞争，与 harness 同口径，重试即达）。

## 1u. 153 线退役（2026-10-01，用户拍板：全部到 158）

- `mozbuild/android-ndk-r29`（3.1G）已删，现构建链只用 r30
  （`objdir-158/config.status` 14 处引用核实）；`objdir-opt`（153 线）
  因此不可再构建，sibling `sinytra-pin` 分支仅保留 0001–0003 的 153 版
  patch 历史，不再维护。
- 活文档已清到 158：AGENTS 顶部 pin 注记、`provider/build.gradle`
  objdir 注释、`firefox-patches/README.md` 分支/检出说明、本节。
  以下刻意保留（历史事实，不改写）：STATUS §1b–§1k 的 153 实录与
  153→158 重放记录、各 patch 设计文档的原始基线注记、
  RELATED-PROJECTS 的取证快照、BOOTSTRAP 的 PoC 注记、代码注释里的
  GV153 勘察依据（结论在 158 上依然成立）。
- 待用户决策（sibling 内，不可逆，未动手）：删 `sinytra-pin` 分支
  与否、删 `objdir-opt`（~20G 级）与否。
  → **已执行（2026-10-01，用户拍板）**：`sinytra-pin` 分支已删（原
  `62b7b46e280e`；153 版 patch 历史以本仓 `firefox-patches/` 文件为准，
  无丢失）、`objdir-opt`（22G）已删。158 线是唯一线。

## 1v. CTS 定向收敛轮（2026-10-01/02，round6：0013/0014 落栈 + transport 两轮重写）

- **CookieManagerTest 12/14（2026-10-02，单测串行）**：GetInstance/Flush/
  AcceptCookie/SetCookie/SetCookieNullCallback/SetCookieCallback/
  RemoveCookies/RemoveCookiesNullCallback/RemoveCookiesCallback/
  SameSiteNoneRequiresSecure/SchemefulSameSite/b3167208 全过；
  testSameSiteLaxByDefault 挂（第三方无 SameSite 读侧未拦，engine 网络
  set 路径行为对齐，open）；testThirdPartyCookie 在 0017 v2 下已能稳定
  出 verdict（2026-10-03 三轮：load 全绿 → cookie-domain waitForCookie
  FAIL，产品 cookie 域问题非 hang；中间一轮 load 静默系内存压力所致，
  reboot（6.8G avail）后恢复；teardown 均为 IPDL-leg kill 预期行为，
  Binder-hop 零异常——详见 0017 设计文档 §8 rounds 5–7）。
  附带修好：removeAll/removeSession 空清谎报（0016 + `bdb5cb9`）。
- **HttpAuth（2026-10-02）：testCancel 单过**；testProceed 卡在第三 load
  静默（前两 load 各 2 prompt 正常，realm 正确；signon 假说证伪已回退；
  首要怀疑 session↔新 tab prompt 链就绪竞态，需专用复现）。
- **标题去重已上机待验**（WebChromeClientTest.testOnJsBeforeUnloadIsCalled
  的双静态标题；`c0c3811`）；console-on-data 确认为架构缺口（data: 页无
  content script，match_origin_as_fallback 只管 frame 继承）：记 honest
  缺口，ConsoleService 补丁待拍板；GeolocationTest 整类不可跑
  （UiAutomation 空，裸 instrument 环境限制，非产品信号）。

- **0013 resource://android 指 provider APK**（firefox `9202aa4`，本仓
  `b41f070`）：`GeckoAppShell.getPackageResourcePath()`（`nsResProtocolHandler::
  GetApkURI` 经 JNI 调）返回宿主 APK → CTS 进程里 sinytra-js 扩展
  manifest 报 NS_ERROR_FILE_NOT_FOUND → eval/postMessage/console 全死
  （0008 greomni 同类跨包问题）。修法与 0008 同构（classloader 推导
  mozglue 路径；regular embedder 回退不变）。publish 成功后真机实锤：
  CTS 进程 `GeckoAppShell: resource://android: using provider apk
  <provider base.apk>` + `ensureBuiltIn installed`，无 NS_ERROR。
  效果：PostMessage 从 8 全灭到 9 跑 3 过。
- **0014 同 host 的 Domain 存 domain cookie**（firefox `0e7bca2`，本仓
  `beb78c9`；0011 同文件后续，线性历史不重写）：CTS CookieTest.testDomain
  以 `domain=www.foo.com` 落在 `www.foo.com` 上——0011 的
  `cookieHost!==host` 门漏掉相等 case，存成 host cookie。记
  `sawDomainAttr`，有 Domain 属性即前导点存（校验仍拒不匹配域）。
  与 0013 同一批 binaries+publish。效果：**CookieTest 5/5 OK**。
- **loadData one-shot key 规范化**（本仓 `72e0f81`，纯 provider 侧）：
  PostMessage 单测 baseUrl `http://www.example.com` 到 necko 变成
  `https://www.example.com/`（HSTS 内升级 + 根尾斜杠）→ 精确 key
  miss → 落真实网络 → 真页无 onmessage → waitForTitle 超时。修：
  `LoadDataHandler.canonicalKey`（去 scheme、host 小写、空 path 归一
  `/`；存/取双侧用）。origin 检查本就不强制（MessageBridge 只记录），
  https 页照收 http-origin 消息。JVM +3 锁（123 全绿）。
- **待验（进行中）**：新包自进程 FrameworkEntry PASS（含 filters pushed +
  ShouldPrepare HIT）；但重装后 CTS 进程连续三轮 filter 下发停在
  `filter observer registered` 无 `filters pushed`（旧包旧 child 时曾通）。
  已干净重编（`--rerun-tasks`）+ 重装 + 双 force-stop，清 child 后
  PostMessage 单测重跑中——先判定是混合产物偶发还是第三方进程系统性
  问题，再跑整类。
- ** transport 泄漏根因 + 两轮重写（2026-10-02）**：harness 同构探针
  实锤页面收到 11 个 `[object Object]` + 1 真数据——内部
  window.postMessage 全漏进页面 onmessage。CustomEvent 证伪（跨不过
  isolated/page 世界）；shim 层 marker 过滤失败（defineProperty 被拒 +
  存量 handler）；最终改 DOM 属性邮箱（单写者队列 + seq 水位，
  MutationObserver，无窗口事件）——harness `loadDataHttpUpgrade
  title=from_webview` 精确干净，P0 GLUE PASS。`handlePort` 同 task 双写
  合并丢 port 事件的坑一并修掉（队列化）。
- **PostMessage 8/9（2026-10-02，单测串行，最终构建全验）**：simple/close/
  wildcard/empty/multiple/messageChannel/webMessageHandler/
  webMessageDefaultHandler 全过（3–4 秒/个；messageChannel 经转移修复后
  翻绿）；唯 testReceiveMessagePort 挂——page→Java 端口转移
  （`message.getPorts()` null → 测试回调 NPE），需 Gecko MessagePort
  IPDL 级 plumbing（P2 patch 候选，独立设计）。app→page 转移、配对路由、
  stub 分发、邮箱队列本轮全部落地验证。
- **HttpAuth（2026-10-02）：testCancel 单过**；testProceed 卡在第三 load
  静默（前两 load 各 2 prompt 正常：proceed-wrong/cancel、proceed-null/
  cancel；第三 load 401 到但 prompt 永不到 Java）。signon 假说已证伪
  （关 toolkit 存储 + 清 profile 重跑依旧；已回退 pref）。首要怀疑：
  每次 load 切新 tab，第三次 prompt 时 session↔新 tab 的 prompt 链未就
  绪——需专用复现（triple-auth harness），本轮不追。
- **环境侧三类 hang（已定性，非产品逻辑，两次线程转储实锤）**：
  ① 新 tab 偶发卡 LIBS_READY→RUNNING（0% CPU 睡眠，无崩溃；parent 侧
  全正常，会话永等）；② parent Gecko JS 环静默（RUNNING 但无扩展回调/
  PageStart/下发）；③ teardown 经 surfaceDestroyed → syncPauseCompositor
  同步等 GPU IPC，GPU 失联时主线程卡死 + waitForIdleSync 连带挂（focus
  flakes 另计）。共同特征：只发生在 CTS 式高频启停/多 tab 堆积下；
  单 WebView 稳态（harness/MiniWV/FrameworkEntry）从未复现。收敛策略：
  串行单测 + fresh-everything 取证，不追 Gecko 原生层。
- **设备卫生（2026-10-02 实测）**：久测后 load 飙到 15+、MemAvailable
  掉到 1.4G、thermal 74℃，tab 连 LAUNCHED 都到不了（连带 AMS attach
  忽略）。`install -r` 之间必清：双 force-stop + `su -c 'echo 3 >
  /proc/sys/vm/drop_caches'`（实测 1.4G→4.7G），静置数分钟等 load 回落
  再跑；同版本连续跑不清 child（热复用稳，冷 burst 抖）。
- **hook 栈（2026-10-02）**：机上 zygisk_lsposed + Shamiko + jshook +
  sui + 5 个 Xposed 模块（smscode/momoxposed/hook/vendetta/
  noregionlimits），每次 fork 全过钩子。load 15 证伪（MTK 内核线程
  常驻 D，CPU 实际全闲）——卡点在 fork/attach 握手本身，大概率钩子栈
  + vendor AMS 扩展在 burst 下的间歇失速。用户配置，不动；flakes 归因
  到此为止，产品侧不再追。

## 1w. GPU-process hang / fallback status（2026-10-02，用户拍板）

> 主题（2026-10-03 修订）：0017 不再是"Binder 超时补丁"，而是
> **GPU-process Surface lifecycle recovery**（当前落地机制为 Binder
> bounded failure；startup 首帧 + 状态恢复待 Phase 1 判别后扩展，
> 拆成 M5-A1/A2/A3 三个 acceptance 面，见 0017 设计文档）。
> 出货门（写死）：**当前 GPU-ON 构建不满足出货条件，直到 startup
> 首帧呈现与 teardown failure 均被修复并完成 GPU-ON 全量回归**；
> M3/M3b 只做 fallback，不做正常模式。

### 已确认

- GPU-process ON 路径存在 compositor teardown hang：`syncPauseCompositor()`
  → 同步 Binder `ICompositorSurfaceManager.onSurfaceChanged()` 路径可导致
  UI thread 长时间阻塞（device trace 双实锤：surfaceDestroyed→syncPause
  与 surfaceChanged→syncResumeResize，两次主线程皆 park 在
  `BpBinder::transact`；GPU 子进程 20 分钟 CPU 零增长、全线程信号无响应）。
- GPU-process OFF（M3，debug config `layers.gpu-process.enabled=false`）
  后，同一 teardown/cover 复现器不再出现 compositor hang；46 项 harness +
  P0 GLUE 全部 PASS，watchdog 零报警（`:gpu` 子进程全程缺席）。
- GPU-process OFF + `webgl.allow-in-parent=true`（M3b）后，WebGL context
  创建、几何绘制与动画均正常（get.webgl.org 绿字 + 旋转立方体双帧）；
  同一 teardown 复现器仍无 compositor hang。
- 因此，当前证据支持：**触发故障所必需的是 GPU-process failure
  domain，而不是"硬件 GL 必须关闭"。**
- 当前 WebGL 像素表现已确认"功能可用"；与 GPU-process ON 的像素/面片
  颜色对照尚未完成，因此暂不宣称视觉等价。
- 视频冒烟：mp4 首帧海报正常渲染 + 用户手势点播正常播放（画面 verified；
  音频因无监听手段记未验证）。

### Fallback

- **M3：GPU process OFF**。优点：最大限度消除 GPU-process failure
  domain。已完成全量 harness 回归，无已知功能回归。已知代价：WebGL
  默认不可用（`FEATURE_WEBGL_NO_GPU_PROCESS`，Gecko 主动 disable）。
- **M3b：GPU process OFF + parent GL ON**。优点：保留 WebGL 功能，同时
  保持当前已验证的 compositor teardown 稳定性。当前已验证：WebGL
  context / draw / animation 可用；teardown 复现器无 hang。风险：GL/
  driver failure domain 回到宿主/parent process；目前未观测到 driver
  crash。当前状态：**可作为功能完整度更高的 fallback 候选，但仍需评估
  driver stability、WebGL 视觉一致性以及视频播放中性能。**

### Root-fix

- **M5-A：保留 GPU process 的当前主线 root-fix（"正解"降级，2026-10-03：
  未经验证前不称正解）**。目标：
  `ICompositorSurfaceManager.onSurfaceChanged()` 同步 Binder IPC →
  bounded failure → GPU/provider recovery → 复用现有
  `NotifyRemoteActorDestroyed` / GPU reinitialization 路径。设计原则沿用
  1880503：**超时后恢复/重建，而不是假设 remote 已经成功完成操作。**
  M5-A 完成后，重新开启 GPU process 做完整回归；M3/M3b 保留为 fallback。
  M5-A 是否同时消除 teardown hang 与 startup white screen，需实现后
  实测确认（见下）。
- 上游参照：1855536（同类 ANR，NEW/P3）/ 1880503（IPDL 10s 超时 + kill
  GPU + minidump，已在 158 树；不覆盖 Binder 跳——即本次靶子）/
  1929209（同症状不同病因：Adreno shader，133/134 已修；Mali 本机不适用）。
- 设计文档：`firefox-patches/0017-binder-bounded-failure.md`（M5-A 主线；
  oneway 仅作 M5-B 实验变体，不预设根治；不碰 fence）。

### White-pixel outage（2026-10-03，GPU ON 确定性复现）

- 现象：P0Render 直连 GeckoView，load 全回调绿（PageStop success=true，
  progress 100），但 SurfaceFlinger 层 `buffer=0x0`（从未 present），
  屏幕纯白；`[GFX1]: OnResumeComposition called with null Surface` →
  resume 失败。force-stop + 全新进程复现。
- M3 判别（同页同机，临时开 GPU OFF，事后已回退）：像素完美（蓝底/
  标题/色块）。结论只到 **GPU-process domain 必要**，不指认 driver
  （domain 内还有 compositor/Binder/Surface/BufferQueue 层）。
- reboot 诊断性 reset 结果：**白屏依旧**——"跨进程持久污染"假设被削弱；
  不是单个 App 进程状态（force-stop 已排除），也不是 reboot 可清的
  全局 rot。问题收敛为 **GPU ON 下 Surface attach/resume → 首帧
  presentation 的确定性失败**（钉死的是 buffer=0x0 + null Surface +
  never present，不是某个具体方法；0017 原只覆盖 teardown/pause-resume
  的 Binder hop，启动 resume 的 null-surface 需纳入覆盖或另立根因）。
  teardown（destroy/pause 路 Binder hang）与 startup（attach/resume 路
  零 buffer）很可能属同一 Surface/compositor lifecycle state machine
  的两面——扩展 0017 时不预设"两个无关 bug"，也不预设"null Surface
  = 重试即可"（先定 null 合法性：瞬态还是非法状态，再选 ordering
  修复还是 recovery/recreate；贸然 retry 会把 ordering bug 遮成
  "多试几次就好"，见 0017 设计文档 M5-A1/A2/A3）。
- 措辞纪律：内存只记 MemAvailable 压力现象，不叫 leak 实锤；M3 只证明
  domain 必要性，不做 driver 归因；reboot 只是诊断性 reset。
- **Round 9（2026-10-03，0021 判别 A）**：P0Render 全新进程 GPU ON 白屏
  复现（screencap×3 纯白，`P0 PASS success=true`），`Sinytra/surface`
  三 handoff（non-null→null→non-null）send→ENTER→EXIT 全齐、rv=0、
  毫秒级。handoff 已送达 GPU 侧并存下——**0017 v2 async 排序嫌疑洗脱**，
  故障在 remote/state/compositor 下游。下一步：compositor 是否取用
  surface / paused-state 恢复是否正确（M5-A2/A3），见 0021 文档 §4。
- **Round 10（2026-10-03，race 定位，修正 Round 9 结论）**：全量 logcat
  显示 `OnResumeComposition null` 比 handoff ENTER **早到约 6ms**（初启与
  cover 返回两轮皆然）——resume 查询 map 时 handoff 尚未落定。v2 把
  transact 搬离调用线程后，`ResumeAndResize`（IPDL）系统性跑赢
  worker→Binder→map.put；首启空 map 即白屏（确定性），暖 widget 靠旧
  surface 偶过。**v2 高度疑似白屏引入者**；修复 = resume 侧 ordering
  barrier（有界等本地 worker），非 retry。0021 文档 §4 已同步。
- **Round 11（2026-10-03，0022 barrier 验证）**：firefox `e27c1b9f09ee`
  （resume 双点切 AndWait，pause 不动）→ P0Render 全新进程 GPU ON 首启
  出像素（example.com 全页三连一致，零 resume-null、零 barrier 超时）；
  cover 往返像素保持（此前白→黑）。全量 harness：P0 段至 msgChannel 全
  PASS，后段 UI 疑似 park（25min 无进展、无 FAIL——teardown hang 家族
  M5-A1 形态；barrier 超时 2s 即放行且零超时日志，不构成新 park 类，
  暂判非回归）。46 PASS 全绿仍欠，M5-A acceptance 缺口如实记。
- **Round 13（2026-10-03，0023 pause 验证）**：firefox `0a39bc4251fe`
  （Pause async + 摘 sync 备案）→ P0Render HOME 盖掉 watchdog 零 episode
  （此前 Round 11 必 park）+ 返回满页像素；noCache park 消失、收尾干净
  （失败仍在：load#2 取数静默 ~20s 独立 open 项）。M5-A1 pause 面关闭。
- **Round 14（2026-10-03，M5-A2 显式验证）**：pause 中（HOME）`kill -9`
  :gpu → 新 :gpu 拉起、tabs 重连（CompositorBridgeChild
  AbnormalShutdown 照常处理）→ 返回满页像素（example.com 全文三语+，
  与 kill 前一致），零 resume-null。**recovery 恢复到 intended resumed
  state（非 stale-paused/blank）——1880503 companion 课交卷**，
  M5-A acceptance item 7 关闭。
- **Round 12（2026-10-03，harness 全绿补上）**：GPU ON + 0022 + 策略门
  构建（`dea324d`+`5990c7f`），§7 清场（7.9G）→ P0Glue **47 PASS /
  0 FAIL / P0 GLUE PASS**（uiautomator 全文计数；屏显只到首屏，
  用 dump 取全量——截图纪律补一条：长 harness 以 dump 为准）+
  FrameworkEntry **FRAMEWORK ENTRY PASS**（10 项，像素满页）。
  M5-A acceptance 缺口关闭（harness 面）；JVM 155 锁。

## 1x. CTS 19 类横扫（2026-10-02 在扫，GPU ON + 0017 v2；类间只清 CTS）

- DateSorterTest OK 5 / MimeTypeMapTest OK 6 / URLUtilTest OK 16 /
  TestProcessClientTest OK 4。
- TracingControllerTest：7 跑 2 FAIL（testTracingControllerCallbacks[OnUI]）。
- WebViewStartupTest：单跑 5/5（2026-10-03）：4 个原 OK + 后台创建经
  MainThread 有界 hop 修好（`8648b30/b9da970/8e8e47e/c04d42d`：session
  构造/open/close + delegate 安装 hop 到 main；creator looper 不变）。
  全类跑仍卡第 4 位（testGetWebViewLooperOnUiThread，4th fresh process
  在 `Loading provider` 后零输出——我方代码未进入； unmanaged 栈
  （debuggerd/SIGQUIT 在此 ROM 无输出，procfs wchan 全员 get_signal
  残疾）。同轮另有收获：连续单跑第 3 发 `failed to attach`
  （zygote fork/attach 握手失速）——与 §1v 钩子栈+vendor burst
  失速同族，产品侧不再追；全类跑改串行+间隔（已有 hygiene 仍不够，
  下次类级跑加间隔）。
- WebBackForwardListTest：testGetCurrentItem **已过**（2026-10-03 单跑
  OK）——STATUS 旧判定的 mProgress=15 卡死随 UA/title 多米诺同愈。
- WebHistoryItemTest：testWebHistoryItem 仍 FAIL，但 load+icon 两门已通
  （2026-10-03 健康窗口）：icon fallback 上限补丁 `6cd0fa0` 生效——
  `/favicon.ico` 服务器 200、测试越过 icon 门（:140）走到第二 load
 （br_tag，:153）。br_tag 服务器 200 但 10s 无 PageStart，随后
  `[GFX1-]: Killing GPU process due to IPC reply timeout`——§1w
  GPU/M5-A 域（0017 v2 已在运行构建内，验收未闭）。load 门此前
  4 次 PageStart 缺席 = parent-JS 静默家族（压力相关，见内存线）。
  结论：history-item 剩余失败归 M5-A，不再单测 chasing，等 0017
  多轮验收后重取。
- WebSettingsTest：大面积 `unexpected timeout`（PollingCheck 系）+ 
  testAccessUserAgentString 确定性 FAIL（server 回显 UA 为空）。
- WebViewClientTest：大面积 `unexpected timeout` + 一例
  `mLoaded=true, mNewPicture=true, mProgress=0`（finished 但进度零）。
- **UA 多米诺（已闭案，2026-10-03）**：`applyWebSettings` 推 Chrome UA，
  necko 实际带 Firefox 默认 UA（`query UA` vs `applyWebSettings` 双日志
  实锤）→ `setUserAgentOverride` 的 `dispatchUpdate` 只在 bundle CHANGES
  时触发，不变的 override 到不了重建的顶 context。修：Gecko 侧 0018
  （导航提交前同步推送待定值，firefox `3db89990f8e7`，本仓 patch 落栈
  `896bb3e`）+ provider 侧空串 no-op（`ab0f327`；CTS 要求 `set("")`
  保持默认，我方旧实现存 verbatim 致线上传空）。验证：CTS
  `testAccessUserAgentString`（曾 FAIL）/`testUserAgentString_default`/
  `testUserAgentStringTest` 三过；JVM 140 锁全绿。title 空同源（回显空
  UA 所致），随之消除。
- **扫表卫生教训**：19:10 的 harness 进程（25420）残留 2 小时且主线程
  卡死（episode=12），疑毒化共享 GPU 致 batch4 团灭——此后类间必清
  provider 全进程；batch4 结论待 hygiene 后重跑确认。
- **三修复验证（2026-10-03，`dea324d`+`5990c7f`）**：
  `testBlockNetworkLoads` **OK**（主帧策略门 + 失败终局合成；装包后首轮
  冷启动抖动一次，复跑绿——cold-burst 家族）；`testAccessAllowFileAccess`
  datadir 半绿（FileBridge 端到端实锤，失败移到 291 行 asset 半）；
  cache 族 stall 待 logcat 定位（`loadAssetUrl` 是 http 非 file）。
  0020 现为 file 族剩余杠杆（asset 3 + AllowFileAccess 半），归属设计优先。
- **loadData 双重编码根因（已修，2026-10-03，`8b3c848`）**：data: 回退路径把
  文档 percent-encode 后交 `loadDataUri(byte[], mime)`，而 byte[] 重载自己包
  `data:<mime>;base64,<BASE64>`——base64 体按字面解码，所有 loadData 页渲染成
  `"%3Chtml%3E..."` 纯文本：无 `<title>`、无脚本。判别探针
  `JsTitleProbeActivity`（`642cc6b`，`--es probe <A-H>`）：修前 data: 全空、
  http-base 正常、延迟 3s 依旧空（排除 settings 竞态）；修后 A/B/C=SINYTRA42、
  D=1、E=STATICTITLE、F 挂 `#JSRAN`。CTS 单测翻绿：
  `testLoadsImagesAutomatically_{httpImagesLoaded,dataUriImagesLoaded,default}`
  + UA 三件套；JVM 144 锁（+1 raw-bytes 锁）。残留同族
  `blockLoadingImages/loadImagesWithoutReload` 仍 `expected 0 but was ""`——
  G/H 探针证明 blocked/error-img 页标题"0"最终可达（~500ms 滞后于
  PageFinished，快页 D 滞后仅 1ms）：PageFinished-标题新鲜度竞态，
  待排（filter 冷带宽 vs TitleChange IPC，见下轮）。
- **WebSettingsTest 串行单测全扫（2026-10-03，51/51：36 OK / 15 FAIL）**：
  整类跑 24 FAIL 含压力假象（UA/镜像单测在整类里挂、单跑全过——§1v
  burst 家族；`adb shell` 吞循环 stdin 坑已记：`< /dev/null`）。
  单测真相（fresh process/个，`fail` 全为产品信号）：
  - file:// 族 4（2026-10-03 进展：`AllowFileAccess` datadir 半已绿，
    FileBridge `178fd50` 端到端实锤，失败移到 291 行 asset 半；余下全卡
    `file:///android_asset/`）：对 Gecko 是字面路径 `/android_asset/...`
    （Android 虚拟路径，necko 直接 ENOENT 静默死——onLoadRequest 后无
    PageStart/PageStop/error，history 恒 blank）；
    `file:///data/...` 另有 provider-uid 无权读（0700 对方 uid，见下）。
    0020 方向：`file:///android_asset/` → host APK jar 映射（Gecko 小 patch，
    0013 同构；APK 0644 world-readable，child 可读）+ provider 侧读
    app 私有文件合成（见下条，已落地）。
  - 空标题族 3（`blockLoadingImages/loadImagesWithoutReload/BlockNetworkImage`，
    `expected 0 but was ""`）：blocked/error-img 页标题"0"滞后 PageFinished
    约一 poll（G/H 探针；D 快页 1ms）——新鲜度竞态，未闭。
  - `BlockNetworkLoads`（2026-10-03 已闭，`dea324d`+`5990c7f`：主帧策略门
    + 失败终局合成，单测 OK）：主帧 http 未被拦（历史）。
  - cache 族 3（2026-10-03 根因分层，非 cache 语义）：`noCache` load#2
    consult 正常（HIT/OnSuccess），随后 localhost 取数静默 ~20s（根因未明：
    server 单线程 vs Gecko 侧，open）→ 测试等待超时 → teardown 起 infra
    EmptyActivity 盖掉 WebViewCtsActivity → stop → surfaceDestroyed →
    主线程 park 在 `syncPauseCompositor`（watchdog 10s 转储）→ 1880503
    kill 照发 → **main 在 kill 后仍 park** → destroy 超时收尾（Round 1
    limbo 第三实例）。M5-A1 形状就此定稿：kill ≠ recovery，需要
    post-kill unpark + 应用层恢复；pause 路要吃 v2 同款待遇（永不在 UI
    上等 remote，连 IPDL 也不等）。BYPASS_CACHE 保留（重取语义仍需它）。
    `eval timeout`/icon 等待同为连带受害者。
  - file-XHR/iframe `*Disabled` 全过（拦截侧正确）、`UseWideViewPort`/
    `PluginsEnabled`/`JavaScriptCanOpenWindowsAutomatically`（20s future 超时，
    无 onCreateWindow 桥）各 1，`AllowMixedMode`（`Path not set: /secure.html`
    测试 infra 侧）、`XHR*Enabled` 见 file 族。
- 待扫：WebViewTest（最大）、WebViewTransport、Ssl、Zoom、DataDir、
  RenderProcess×2、ServiceWorker×2、DarkMode×2、Geolocation（已知不可跑）、
  PacProcessor（已知要 patch）、SharedWebView。

## 1y. 空标题竞态 + image 族（2026-10-03 起，2026-10-04 闭环）

> 2026-10-04 更新：image 族 4/4 verdict（串行单测，同一最终构建）+
> harness 全绿 + JVM 174 锁；0024 定稿。途中两个真缺口已修：
> setter 即时推送（此前 loosening 无 delivery）与双 flag 放宽恢复
> （此前只认 allowImages 变化）。残留 1673953 型 pre-commit 静默 flake
>（trio-in-one-process 2/3 即此例；上游定性符合预期，非本 patch 范围）。
> 细节见 `firefox-patches/0024-image-policy-docshell.md` §6/§7/§10。
- **空标题竞态闭案**：Gecko TitleChange 比 PageStop 晚 ~500ms（JS-onload
  标题）+ commit 先报空占位 → CTS 完成后立即读标题必空。修两处
  （`GeckoSessionBridge`）：成功完成在标题"非空且属本页"前 hold（静态标题/
  about/失败零延迟；JsTitleProbe G/H titleLagMs 501→1ms）+ 导航中不把标题
  回退到空占位（Chromium 语义；保 `waitForNonEmptyImage` 类 poll）。
  JVM TitleHold 锁，harness 全绿不退。
- **image 族**：`blockLoadingImages` **OK**；另两条逻辑侧全绿但缺健康窗口
  verdict。链路：`0024` Gecko 栈（settings 键 + ImageBlocker docshell 检查
  + child 应用 + 每导航强制推送，patch 文件已落）作纵深；loadData 确定性门
  作主（sanitize 剥 src + loosening 按原参数重发 + 仅放宽才 reload）。
  诊断副产品记死：actor 随 BC 换代 ~50ms 即死（0018 同款洞，v2 已补）；
  `addObserver` 不收 actor 本体（Bug 1570744，QI 闭包为准）；IDL 无
  `browsingContext`（直接比 docshell）；`GetLoadingDocument` 是 out-param；
  substitute 构建必须每次 export MOZCONFIG（缺则 1 秒挂，屡踩）。
- **JVM 175 锁全绿**，lint 绿。
- **设备（2026-10-03 夜）**：load 持续 ~15（MTK D 态堆积，僵尸已排除），
  child tab `Timeout executing service`，导航 LoadRequest 后零回调——容量型
  flake，非逻辑。`su -c reboot` 已发（含目的 ledger），之后 USB 层无设备
  15 分钟+，疑卡启动/掉线，待人手确认。回来后：卫生 → T2/T3 verdict →
  全量 harness → 0024 定稿。

## 1z. Bilibili 第三方宿主诊断（2026-10-05，粉丝页 verdict：内容 PASS）

- **报障**：主界面→消息→新增粉丝页显示 B 站自带“WebView 不可用”兜底。
  真机实锤（`tv.danmaku.bili:web`，MWebActivity inflate 期）：
  `MissingWebViewPackageException: NameNotFoundException: moe.bemly.geckowebview.debug`，
  起在 framework `getPackageInfo`，一行 provider 代码未跑到——与胶水/Gecko 逻辑无关。
- **根因①（HMA 藏 provider，用户侧配置，已解）**：用户 HideMyApplist 把我方包藏掉；
  放行后同路径异常消失。bromite 对照同挂（`NameNotFoundException: org.bromite.webview`）
  同愈——与引擎无关，只与“非系统包是否可见”有关；stock（SYSTEM flag）在全程可见。
  机制记死：HMA hook 点在 system_server（lspd scope 只有 system），按受害者（B 站）
  过滤 PMS 查询；它藏不住住进受害者进程内的注入（webviewpp 开进 B 站 scope 后
  B 站自杀，HMA 再全也盖不住自进程 maps/dex 检查——B 站 scope 保持为空）。
- **根因②（掉登录，查案人切 provider 切丢的）**：cookie jar 按 provider 隔离，
  当晚多次切换把 B 站会话切没了，页面空转。用户重登后同路径在我方完整渲染：
  consult→load→`page-start newFans`→像素→全文 11 条（含回关按钮），screencap×3。
  头像占位：0.25KB/s 网络下子资源未到，image 策略默认放行，待网络正常时复核，
  不当 bridge 缺口追。
- **头像缺口证伪（2026-10-05 04:49，同机同页像素对照）**：`blate2.png` 上
  7 个实拍头像全出；剩下 4 个灰机器人（bili_87677282977/bili_70837481505/
  东坡居士的小弟的小弟/因为如）在 stock 对照轮同号同占位——那就是这些号的
  真实默认头像，不是我方缺口。前一条“页从不升级占位”作废（logcat 轮转 +
  首屏采样偏差导致的误判，证据优先，旧结论删除不留）。
  2x 加载 bound（相对 stock 冷启动到头像全实图）已接受为验收线，测量留到
  网络健康窗口（今晚 150KB/s 烂到 0.00KB/s，绝对计时无意义，不记）。
- **偶发 pre-commit stall（已修，3 commits 着陆）**：LoadUri 冷启动
  listener-gap（EventDispatcher 无 listener 静默丢弃，源码实锤），修法 =
  provider 侧 commit guard（`session/NavigationCommitGuard.java` 有界重发
  2s/4s/8s；PageStart/LocationChange 解锁；policy/app-override/stop/close
  显式撤防；`21a4e05/397f83a/7e913cf`，JVM +4 锁 186 全绿，lint 绿）。
  **活捉验证**：harness 当轮 `onLoadRequest example.com → 2s 零提交 →
  commit guard firing attempt=1 → 重发 → PageStart → GLUE PASS**（47 PASS
  零退，健康加载零误伤）。
- **新 signature：post-consult wedge（未闭，单实例 5612）**：PageStart +
  ShouldPrepare HIT + ChannelIntercepted + OnSuccess 全齐后 5 分钟零
  progress/零 PageStop/零 error（与 pre-commit 不同 signature，guard 正确
  静默，非回归）。同网络 stock 对照 100s 全渲染：有引擎差异信号，但 stock
  缓存今晚已暖、我方缓存冷，缓存混杂未排除。下一步：双方都冷的 URL 对等
  比较，再定是 ResetInterception 后 channel 卡住还是缓存差。2x 计时以此闭
  案为前置（stalled 轮是 ∞x——今晚 stock 死网 100s 即分母）。
- **MOZ_LOG 鉴别轮（consult 层结案，25123）**：fate 表完整——主 HTML +
  11 个子资源（reporter/mirror/vue/router/relationship-h5/bmg）**全部
  pass-through，零 policy-block，零 synthesize，12/12 OnSuccess 毫秒级**。
  LoadPolicy 无辜（默认全放行），app 拦截器无辜。consult 层三轮实锤无 bug。
  此后 3 分钟暗屏、零 progress——结合当时网速回落到 0.38KB/s，字节未到。
- **无表循环缺陷（已修，`9708dc6`）**：守卫重发经 `loadUrl` 回灌会 re-arm，
  全 wedged 管道下退化为无限 2s 循环（19748 轮实锤 attempt=1 风暴；该轮无
  tab、白屏——风暴是否压住 tab 待定，修后未再现）。修法：重发走无记账
  `dispatchLoad`，重试留在守卫自代预算内。修后轮零误发。
- **consult 可观测性（`daf9bf7`，常驻）**：`pass-through/synthesize/policy-block`
  按 URL 打 Log.d，与既有导航日志同口径。后轮 fate 表即此产出。
- **候选缺口（未闭）**：`:web` 在 runtime 就绪前后台线程调 CookieManager
  （`cookie op before runtime exists`×7），我方 drop（FALSE），Chromium
  随时可写；宿主若预置登录 cookie 会静默丢失——待专项确认。
- **诊断卫生**：`[diag]` 临时日志（consult/query/pass-through/synthesize/page-start/
  attach 六处）同轮加齐、同轮 `git checkout` 清掉，干净构建重装，Current 不变。
  教训：日常号上对照矩阵少切 provider（每次切换换一个 jar，登录态即丢）。

## 1aa. 酷安 WebView 慢载诊断（2026-10-08，真机 verdict：setup 卡死已修，渲染待健康网络复验）

- **报障**：WEBVIEW TEST 点 WebView 标签即崩（已修，见下）；
  酷安帖内外部链接（github release 页）有 UI 无内容、长时间黑/白屏。
- **崩溃根因（已修，`490ec28`）**：`AbstractMethodError:
  setAcceptFileSchemeCookiesImpl`——公开 android.jar 只有 static 壳，
  真身是 hidden abstract（device framework.jar dexdump 实锤
  `setAcceptFileSchemeCookiesImpl(Z)V` + `allowFileSchemeCookiesImpl()Z`）。
  同 §1o 的 WebMessagePort-ctor 同家族坑。修法：`GeckoCookieManagerImpl`
  补两 hidden 方法（故意无 `@Override`）+ `mAcceptFileSchemeCookies`
  位（默认 true）+ file: 读写门。JVM +6 锁（198 全绿），真机复点
  标签页存活（同 PID 三轮，零 FATAL），example.com 秒渲染对照通过。
- **慢载根因①（已修，`328a7b8`）**：酷安在 fragment setup 期主线程串行
  调 5 次 void `setCookie`（CookieHelper），我方旧实现每调 park 主线程
  5s（storage 答案在酷安进程永不到，见下）= 25s + ANR
  （FeedDetailActivityV8 Input-dispatch timeout 实锤）+ 登录 cookie
  全丢（token/ddid/DID/AppSupported/darkMode 五连 refused）。修法：
  主线程 void-set 改 fire-and-forget（Chromium 的 void 语义本就 async；
  后台调用者保持阻塞路径，CTS set→get 照旧）。修后子资源 OnSuccess
  同秒连爆（咨询链全速），setup 零 park。
- **慢载根因②（未闭，Gecko 侧）**：酷安进程内 storage 答案 100% 不归——
  `EventDispatcher` 反射探针：`nativeReady=true attached=true
  GetCookie=Y SetCookie=Y`，query 已发出（query 线程活），永无回答；
  **stock 对照 `GetAllPermissions` 同挂**——0007 patch 无辜，是上游
  global-dispatcher Java→Gecko 投递死亡（session/actor 路径正常：
  onLoadRequest 照到）。harness 同机构造全绿（cookieJar 真 jar 往返）。
  需 Gecko 侧（MOZ_LOG/主线程响应性）专项嚼，不在 provider 侧追。
- **修 M1 途中自己挖的坑（已填）**：跨 op completion chain
  （void-set 后 get 保序）——serial query 线程上 await 已完成
  GeckoResult 会自死锁到 budget（replay post 只能跑在被 park 的
  同一 looper），拖过调用方 deadline 致 harness cookieJar hasCookies
  2/2 FAIL。已删 chain（Chromium 的 void-set 本就无 landing 保证，
  教训记在 `GeckoCookieManager` 注释）。
- **B（已修已验，`8d0f537`）**：pre-runtime void 写排队——`PendingWrites`
  有界 FIFO（200，溢出丢最老 + loud），首个 controller 落定时在 query
  线程 FIFO flush；callback 变体维持 fail-fast（显式投递承诺）；get/
  remove/has 预运行时保持诚实空值。JVM +4 锁（FIFO/溢出/drain/排队断言，
  199 全绿）；真机 `flushing 12 pending cookie writes`，42 丢弃归零。
  酷安/B 站类"启动期后台同步登录 cookie"不再静默丢失（§1z 候选缺口
  同愈）；post-runtime 投递死亡（②）另案，未动。
- **C（已修，`dca400c`）**：`ensureBuiltIn` 装不上时 bounded 重试至 ack
  （首试 4s 覆盖冷启动，8s 封顶 ×5 ≈ 36s 窗口；成功/5 次耗尽停手，
  耗尽 reset 重装位待下次 bind 重启）。旧代码"sInstallStarted 常 true、
  pending 越积、进程终身 transport 死"。JVM +3 锁（delay 阶梯）。
  真机 fresh 酷安轮：bind→2s installed→delegate bound→filters pushed×3
  → coolapk-link PageStart，同轮咨询链进入。
- **渲染面现状（截至 12:18，未闭）**：C 轮管线全绿（extension/filter/
  PageStart/LocationChange 到 github，子资源 OnSuccess burst）但 release
  页仍黑；a11y 零文本 = parser 饿死；同窗网速 0.10–2.66KB/s。平行对照：
  FrameworkEntry（framework 真路径）同 URL ~90s **完整渲染**
  （header/tabs/notes/截图/changelog/assets 全像素，pageFinished 到，
  138 OnSuccess；唯一 FAIL 是 eval transport 未起——transport 与渲染
  无关，另记）；Canary 同页 ~60s 全绘；Fennec github 主页 ~50s 绘出。
  结论：重页面在烂网下酷安进程黑、其余路径可绘——差值仍在酷安进程
  组合内（②投递死亡致登录态丢 + 稀疏 getCookie 5s park 未清 +
  commit-guard 慢-commit 误撕 10:21 轮实锤 exhaust），待健康网络窗口终裁。
  今晚烧设备轮次到此为止，不再开"只能证明还是挂的"轮次。
- **0025（已落地，`a910904`）**：ged stub 投毒 hardening（加载失败保留
  stub + `callback.onError` 速败；成功路径与旧代码一致）。子 agent 并行
  溯源（EventDispatcher.java/NativeQueue/GeckoThread/Messaging/stub 全链，
  行号级）：ready/attached/hasListener 全真 + 永不回答 + stock 消息同挂
  + session 路径正常，最小解释即 stub 首触消费后模块未接替（native 表
  不清，Y 恒真）。上游无此形状 bug（已检索）。JS 改动经 stage-package
  进 omni（三级解包验 marker：dist→AAR→APK 全有）才 publish+重编。
  真机 fresh 酷安轮（12:55）：**零超时**（此前轮次连发 5s 定时超时），
  extension 2s installed，filters pushed×3，coolapk-link PageStart，
  github redirect→HIT→pass-through→LocationChange，79 子资源同秒咨询
  全裁决——管线全绿。但 release 页 4 分钟仍黑（a11y 零节点，网速
  0.10–1.14KB/s）：parser 饿死，首绘被大 HTML/阻塞脚本饿住。
  0025 的 retry 路径本轮未触发（首试即赢，race 赢了）——验证的是"不再
  毒化"，自愈重发待输掉 race 的轮次实证。黑屏余量 = 纯网络饥饿 + ②。
  终裁条件：健康网络下 fresh 酷安轮一次绘出即全案关闭。
- **bromite 对照（用户要求实测，已跑）**：切 bromite 同 App 同链接，
  ~60s **完整渲染**（header/tabs/notes/截图全像素）。用户结论成立：
  stock 就是能到。连带证伪"纯网络"单因解释（同网同机我们黑）。
  机制差值定位：Chromium/Fennec 在慢网上只是等；我方 guard 在慢
  commit 下 close+reopen**从零重下**——trickle 下永世不得翻身
  （10:21/11:44 exhaust 实锤）；另咨询链是额外开销。修法即本轮
  guard 进展感知（`cee3e7e`）：咨询即延长、只对真静默重下。
  JVM +3 锁（202 全绿），harness 46 PASS / GLUE PASS（健康路径零变化
  已验）。慢网终裁仍待，但此后慢网行为 = 等（与 Chromium 同构），
  不再是杀。
- **熄火分诊（已修已验，`b8673b6`）**：exhaustion 按 transport 就绪分诊——
  未就绪则 cold-extension 等待 ×4（约 56s，不烧 reopen 预算；PageStart
  回填），就绪仍静默才 close+reopen。真机 fresh 轮：cold 1/4→4/4 逐级
  等待日志齐出，无提前 reopen（旧代码 28s 即烧掉唯一 rescue）。
  install 重试窗同步放宽到 60s（首 ack 曾 32s 才到）。JVM +6 锁
  （triage 谓词 3 + delay 阶梯更新，205 全绿），harness 46 PASS。
  附带债务：`GeckoSessionBridge.java` 1126→1190 行（动前已超 1000 铁律，
  拆分单独立项，不在本轮）。
- **仍未闭（诚实口径）**：部分 fresh 酷安进程 Java→Gecko-JS 全死
  （extension/filter/storage 零回答，0025 亦救不回；session 回声正常；
  同构建他轮全绿）。晚间 bisect 显示 reload 亦静默（reload 无恢复机制，
  另案）。字节级 race 已排除网络单因（bromite 1.5MB 绘出 vs 我方
  1.7MB 纯白，同分钟）。怀疑方向收敛到冷进程 early-window 派发丢失
  + 某常驻态污染（重启/双 runtime/stale peer 待查），需 MOZ_LOG 或
  健康网络长轮裁决，不再盲烧轮次。
- **渲染面现状**：M1 后标题到、子资源 burst、spinner 转，但 release 页
  10 分钟仍黑（a11y 零文本节点 = parser 饿死）。同机同网对照：
  Canary 直连同 URL ~60s 完整渲染；Fennec github 主页 ~50s 绘出——
  引擎与网络可交付，酷安进程组合（② + 烂网 0.03–2.6KB/s 实测 +
  commit-guard 在慢 commit 下误撕——10:21 轮实锤 exhaust，属下轮候选修点）
  仍需健康网络窗口复验。
   commit-guard 的"2s 无 PageStart = 丢 dispatch"假设在 trickle 网络下
   不成立（TLS+首字节可超 14s 预算），是下轮候选修点（只停手不拆 session
   的方向），但今晚未动——先拿健康网络定责任再说。
- **filter 冷预算（已修，`ddfc6b9`，无设备轮次）**：纸面推演钉死确定性缺口——
  filter 重试 5 attempts（~15.5s）与 install 看门狗 ~60s 窗口（首 ack 曾 32s
  才到）错位：16–60s 落地的 install 发现 filter 重试早已耗尽，transport
  就绪但 filter 永不下发（necko 查空表）。修法沿 `b8673b6` 冷扩展同构：
  未就绪等待改耗独立冷预算（`MAX_COLD_WAITS=10` ≈ 63.5s ≥ install 窗口），
   5-attempt 只计入活管线推送。JVM 207 锁全绿（+2），lint 绿；健康路径
   （首试即赢）零行为变化。完全死进程（60s 亦无 ack）另案，仍待 MOZ_LOG/
   健康网络裁决。reload 无 guard 另案未动（`GeckoSessionBridge` 已超行数，
   不碰）。
- **黑屏根因（已定位已修，`66ee067`，A/B/A 三轮实锤）**：我方 `session.open()`
  即提交 about:blank 并向 App 派发 `onPageStarted(about:blank)`——Chromium
  永不派发。酷安在该回调里 `Uri.getQueryParameter`（非层级 URI 直接抛
  `UnsupportedOperationException`，`NewWebViewFragment.kt:14`）→ fragment
  初始化中断 → **WebView 永不 attach**（a11y dump 无 WebView 节点；修后
  dumpsys 见 `CoolWebView` 全尺寸 VISIBLE 内含 `GeckoViewHost`）→ session
  在 headless 下照常加载（字节/标题/回调全通）→ 永久黑屏。此解释覆盖此前
  全部"管线绿但黑"轮次（含 12:55 的 79 咨询 + 4 分钟黑：画进了没挂载的树）。
  修法：隐式初始 blank Start 对 App 静默（finish 侧早有对称吞吃；
  显式 `loadUrl("about:blank")` 照常派发，Chromium 对齐）+ 纯谓词 JVM 锁。
  验证：JVM 210 锁（+3），lint 绿；harness **47 PASS 行 0 FAIL + GLUE PASS**；
  真机 verdict 轮零 `onPageStarted threw`（修前每轮必抛），视图树挂载确认。
- **A/B/A 带宽对照（同机背靠背，整机 rx 计数）**：我方窗 22KB/45s（0.5KB/s）
  黑 → bromite 窗 2.6MB/60s（43KB/s）90s 完整绘出 → 我方窗 3MB/80s
  （38KB/s）标题到仍黑（彼时 view 未挂载，属上条）。时变带宽实锤（86 倍摆动），
  此前"同分钟"对照未做窗口会计是漏洞；以后对照必带 rx delta + 交替顺序。
- **仍未闭（诚实口径）**：① 修后轮视图已挂载但撞上 trickle 窗（0.7KB/s，
  目标头 5 分钟不到），像素 verdict 需一个"修复+带宽"双齐窗口（本轮保持加载，
  带宽回来即见分晓）；② 15:48 轮目标 `onLoadRequest` 700ms 内 8 连发（次次咨询
  裁决，2s 后才 PageStart）——通道 churn，单轮目击，待复现定位（necko 重试 vs
  我方 0001 包装抖动）；③ reload 无 guard、`:web` 读侧诚实空、bridge 拆分债务依旧。
- **守轮 verdict（子 agent 独立观测，26 分钟）**：修后轮 15:59→16:25 仅 +415KB
  （~0.6KB/s），命运线零新增（16:06 后无一条 Gecko Page/Progress/Title/Error），
  进程存活且页面在前台。三截图纯黑、a11y 21 节点零文本、无标题。结论
  `STILL_BLACK_TRICKLE`：纯带宽窗未到，不满足 MOZ_LOG 升级条件（rx 增长但仍黑
  才升级——本轮连增长都没有）。带宽判断以整机 rx delta 为准，不以状态栏为准。
- **8 连发溯源（子 agent 纸面，`/tmp/cool2.log` pid 20493）**：8 次 LR 间隔
  3/2/22/9/64/89/496ms，8/8 全 `HIT→pass-through→OnSuccess`（零 synthesize），
  每次配对宿主 `shouldOverrideUrlLoading` 同 URL（ALLOW）；头部 3 发与首个裁决
  无关（docshell 层重开火）。排序：R1 页面驱动重复顶层导航（shim JS，最高；
  `OnLoadRequest` 只在 browserDOMWindow 开火，necko 重试不经此路）；
  R2 宿主 shouldOverride 双载；R3 necko 通道重试（只解释 HIT 多重不解释 LR
  多重，降级）；R5 guard 重发**已证伪**（量级/目标/撤防三不符）。下轮证伪预测
  已就绪：`SplitNavigationDelegate.onLoadRequest` 加
  `isRedirect/hasUserGesture/triggerUri` 一行日志（R1 证实条件）+
  `shouldOverride` 返回值/`loadUrl` 配对日志（R2 证实条件）。
- **reload guard（子 agent 实现，`de44369`）**：`reload()` 加 guard（raw
  `mSession.reload()` 重发 + `reopenSessionOnce` 熄火；`stop/goBack/goForward`
  不动）。JVM 210 绿，lint 绿，harness **47 PASS 行 0 FAIL + GLUE PASS**。
  上条 ③ 的 reload 项关闭；`:web` 读侧与 bridge 拆分仍 open。
- **完全死进程首捕（子 agent verdict 轮，pid 30970）**：fresh 轮 guard 走完
  cold 1/4→4/4 → reopen → 仍静默 → `giving up`（16:43:37），**全程零 PageStart、
  transport 永未就绪**（无 install ack、无 filter、无 storage、无 title）。
  onLoadRequest 照发（session 回声正常）——② 号"Java→Gecko-JS 全死"类的完整形态，
  且本轮 60s+ 熄火窗后亦未自愈。下一步：MOZ_LOG（necko/Gecko 主线程响应性）
  是唯一剩余手段，待专用轮次（需配 pref，不重编）。
- **churn 取证日志上机（`428518d`，dex 已验）**：LR 四字段
  `isRedirect/hasUserGesture/triggerUri` + `shouldOverride override=` +
  宿主入口 `loadUrl` 行；harness 47 行 0 FAIL + GLUE PASS 不退。下轮绿管线
  直接判 R1/R2。
- **`:web` 读侧关闭（子 agent 实现，`20b7f96`）**：`ControllerGate` 纯 monitor
  （signal/await + deadline + 中断恢复；唤醒链=发布者或超时，无 GeckoResult/
  Looper，M1 自死锁不可复现），后台读有界等首 controller（5s 对齐 storage
  定时），主线程保持诚实空值（ANR 同构已避），写侧 queue/fail-fast 不动。
  JVM 216 绿（+6），lint 绿，同批 harness 绿。`GeckoCookieManager` 621→740 行
  （<1000，合规）。§1z/§5-3 的读侧项关闭；bridge 拆分债务仍 open。
- **churn 判决（子 agent 设备轮，取证版构建）**：**R1（页面/shim 驱动）**——
  18 发 LR 全 `isRedirect=false/hasUserGesture=false/triggerUri=null`，宿主
  `loadUrl` 全程仅 1 发（首发），18 个 `shouldOverride=false` 一一配对；
  **R2（宿主双载）死**（零新增宿主 loadUrl）。guard 重发三证伪维持。同轮兼捕
  第二例完全死进程（cold 4/4→reopen→give-up，零 PageStart；本轮零新 tab 进程、
  零 `No listener for` 行；挂载正常、无 crash 回归、install 时间戳无 mixed-build）。
- **bridge 拆分还债（子 agent 实现，未提交→已合入）**：`SessionRescue.java`
  新文件 387 行（guard/熄火/cold/reopen/reload/stop/close/settings + 纯静态
  history helpers）；`GeckoSessionBridge.java` 1238→**972**（<1000 ✓）。
  Readiness/ReopenHost 留桥上垫片，provider 零改动。JVM 217 绿（锁只增不减），
  lint 绿，取证构建 harness 47 行 0 FAIL + GLUE PASS（拆分设备验证关闭）。
- **死进程取证门上机（`764750e`，只动 debug yaml）**：`MOZ_LOG:
  DocumentChannel:5,nsDocShell:5,BrowsingContext:5,Process:5,timestamp` +
  `geckoview.logging: Debug`（JS-dispatch 层真门，MOZ_LOG 照不到 Messaging/
  GeckoViewNavigation）。provider 重编即可（无 Gecko 改动，不触发 omni 铁律）。
  Canary 验门：`Adding debug configuration` + `Adding environment variables
  {MOZ_LOG=…}` 双行齐出。verdict 轮保持 OFF（扰动时序），捕获轮专用。
- **DEAD_CAUGHT（子 agent 取证轮，pid 10970，`/tmp/moz-dead.log` 6731 行）**：
  共享前缀（runtime 建→parent BC 0x1/0x2→chrome xhtml channel→EnsureBuiltIn
  START）与 Canary 健康基线逐行一致；**第一个 diverge：`filter observer
  registered / transport 就绪` 缺席** → 无 `Process GetNewOrUsed for
  webIsolated=<目标>` → 无子进程出生（本轮零新 :tab/:gpu）→ 无 `[Child]
  DocumentChannel ctor uri=<目标>` → 零 PageStart。install 重试 2→5→`never
  acked`，filter 重试随后 give-up，guard cold 4/4→reopen→give-up。`No listener
  for` 零行，`onPageStarted threw` 零行（无回归），挂载正常。结论：目标导航从未
  到达 Gecko 层；Gecko 本体健康（Canary 同机构造绿），死亡点在宿主进程内
  transport/扩展通道建立之前。另证伪"无 MOZ_LOG 即无信息"：M1-M4 的静默本身
  就是反证（necko 下全死 → 查 dispatch/actor 层——本轮属后者）。
  下一步候选：首触投毒假说（首个早窗口 dispatch 永久毒化 install 状态机，
  0025 同族——验证法：延迟首次接触 N 秒，看死亡率是否变化；需小补丁/开关），
  或 MiniWV 最小宿主复现（区分酷安特有 vs 通用竞态）。
- **P-CLEAN（addon-DB 验证轮，pid 18066，`/tmp/moz-dead3.log` 7246 行）**：
  死后 addonStartup.json.lz4/extensions.json/prefs.js 与点中前快照**字节一致**，
  本地解析全 OK（extensions.json 含 sinytra-js active+visible；lz4 解出 enabled），
  非空壳——腐蚀假说死，礁堡转 first-touch 方向。附带：`.startup-incomplete`
  自 16:42 起残留（之后无一轮干净完成 startup，与连死一致，非独立证据）。
- **反转信号（接触时机，零代码即可验证）**：immediate-contact 进程（harness/
  Canary/FrameworkEntry/MiniWV，activity 起即建 WebView）**从不死**；delayed-
  contact（酷安，刷 40s feed 后才进 WebView）**间歇死**——与早窗口理论预测反向。
  死轮实测 304 线程 + ART "blocking GC / suspend fail" 风暴；绿轮线程数/GC 未量
  化。首怀疑：宿主进程负载（线程数/GC 压力）下的 Gecko 初始化/首触竞争，而非
  接触早晚本身。验证法：下轮无论死绿必采线程数 + GC 风暴行做对照；另做 idle-vs-
  browse 对照（静置 60s 直开链接 vs 重刷后开）。Fennec/干净进程常绿与此一致
  （宿主干净，非接触早）。
- **P-NOLOAD（压力对照轮）**：idle 306 线程死 vs 加压 319 线程死（+4%，GC 同量级
  且方向反了）——负载假说死。~300 线程是酷安宿主基线，不是 feed 产物。
- **A/B/A 反转（18:25/18:28）**：5 连死后，同机只换构建（`66ee067` worktree →
  1.4s install ack；再装回 `20b7f96` 内容 → 1.8s 全绿）——此前"构建致因"结论
  **作废**；16:42–18:19 是环境窗口问题（与构建无关；`Sinytra-bisect*` worktree
  保留，用完删）。机上现为 `20b7f96` 内容（取证日志在，无 MOZ_LOG）。
- **S3 新形态（29341 轮，bring-up 2s 绿）**：link 提交 + install ack + github LR
  （`isRedirect=true`，取证日志首功）→ 20 分钟后标题到 → body 咨询零完成
  （`OnSuccess` 计数 0）、零目标 PageStart、零错误、a11y 空、挂载正常。返回键
  有效（UI 线程活——UI 楔死理论死）。逐流 `ss`：动的都是广告/推送，github 流
  早已消失（没建起来，不是建了卡住）。教训：18:28 命运线被 buffer 转掉（20
  分钟后才首 pull）——关键轮 2 分钟一 pull。
- **咨询线程模型（纸面）**：C++ 经 session EventDispatcher 把每次咨询发到 UI
  线程同步问 App（`Dispatch+callback`，necko 异步等；0001 patch:644）。UI 活着
  故 S3 不是咨询卡 UI。`kill -QUIT` 在 user 包无输出（§1x 记录不变）。
- **stop-first 否决（纸面推演，未动手）**：重发前 `stop()` 杀的是当前加载，
  给不了前序 link 缺失的 STOP——fission 压制不解除，白忙。redirect resume 的
  价值仅在"commit 慢但终到"（给慢链多几次干净重发的机会），不解决压制本身。
  压制解除的钥匙仍在上游语义（前序窗口的 STATE_STOP），待定。
- **S3 观察轮（待跑）**：当前构建不动，密集 pull 看 refire 后的咨询是否发生
  （dispatch 着陆否），+ rx + 提交三件套。问题清单：refire 咨询零发生=派发死；
  咨询发生无提交=压制；两者时间线即答案。
- **extends-forever 形态（29220 轮，cap 设计补丁）**：progress 持续流动时 guard
  只 extend 不 fire（本轮仅 2 次 fire），耐心正确——cap 只收敛 burst-gap churn，
  不碰流动管线（extends 不计入 `mTotalFires`，by design）。同轮 fission=1 正常
  推进：fission 压制说被削弱（压制若存在也不是 S3 主因；主因仍是 commit 慢 +
  refire 重启的叠加）。churn-#8 曾经提交的反例亦佐证压制非必然。
- **marker 已清（`058afab`）**：预载 marker 临时日志删除，干净构建已上机
  （APK raw 字节验无残留）。教训：含空格字串的 dex 检查必须 raw 匹配，token
  化必漏（此前两次 verification artifact 皆出于此）。
- **直载 DRAWN（Canary 同 URL，`PageStop success=true`）**：同页直载 2 分钟完整
  渲染（header/tabs/正文/截图全像素）。引擎能画此页；S3 只发生在 link→github
  redirect 链之后。机制定稿：link 提交（我方咨询暂停通道促成 early commit）→
  跨站切进程 → 新文档 listener 迁移失败（1673953 形状：前序窗口没见到
  STATE_STOP——link 无 PageStop——后续通知被压住）→ 字节走完但 session 全聋。
  修法：redirect leg 提交后无 commit 则有界 resume（fresh top 导航，直载已证
  可提交），见下条（`d26f514`，待 post-commit 轮验证）。
- **P-WATCHDOG_SILENT 修正（非计时器 bug）**：死轮 sole `updateBindings` 是前台
  bookkeeping，bind 请求本身从没发起——0026 的计时器守"请求后无应答"，死轮死在
  "请求前"，故零开火符合设计（计时器只在 `bind()` 新建承诺时 arm）。0026 未证伪，
  价值待定（post-request hang 仍可能在他处出现）；同时 JS 事件 inventory 修正：
  全局事件（SetCookie/GetCookie/SetLocale/EnsureBuiltIn）**到达** parent JS，
  死的是 session dispatcher 事件（ResponseFilters/LoadUri，需已 attach 的窗口）
  与一切需应答/续跑的路径——session 窗口从未 attach 是一切 session 侧死亡之母。
- **发起链问题（当前主犯）**：死轮 C++ 从未请求任何 child launch（GPU 首发都没有，
  更谈不上 tab）。LoadUri 派发（需 attach 窗口的 listener）与 GPU 需求（需 content
  launch）互为前提——首跳丢失（§1z listener-gap）+ attach 又依赖 GPU 门时即成
  自持死锁；绿轮是竞态撞上正确顺序。下一步：C++ 发起链纸面（LaunchGPUProcess/
  EnsureGPUReady 调用者 + 前置条件 + 静默跳过路径），定位"首个 bindService 永不
  被调" 的门后，再定是 provider 可驱（early demand 注入）还是 Gecko patch。
- **early demand 注入落地（`2ef0d14`，公开 API 零 patch）**：
  `GeckoProcessManager.getInstance().preload(GPU, CONTENT)`（`warmUp()` 只暖
  CONTENT 不够；E0/首 Ensure 是环外唯二破环动作，preload 等价手动 E0），首个
  provider 构造时 one-shot（单例复用 + 预载队列使重复无害；只暖启动窗），
  runtime 就绪后、`open()` 之前，失败 loud 日志继续走（永不打断构造）。
  JVM 224 绿（纯接线，device-covered），lint 绿，harness 47 行 0 FAIL +
  GLUE PASS。债务：`GeckoWebViewProvider.java` 1138→1167（动前已超 1000 铁律，
  拆分单独立项；本次只压了自有注释）。验证法：死亡率统计（连打 8 轮 fresh，
  90 秒定死绿；≤1/8 死即破环成立，≥3/8 死则转 G4 frameloader 方向）。
- **GPU-bind 挂起（S2 新主犯，日志对照实锤）**：绿轮 `ServiceAllocator $gpu
  updateBindings` 后必跟 tab bind（慢绿轮：GPU 3 次失败→SW-WR fallback→
  compositor 初始化→blank PageStart→install ack，31s 之谜即 3×~10s）；
  死轮 GPU bind 请求一次后无成功、无失败、无 fallback，tab 零出生。父 Gecko
  主线程 futex 睡零 CPU = 等这个 init（condvar，非 binder 帧，故线程栈无特征；
  早前 P-IDLE 结论修正：不是"空闲无事做"，是等 GPU init 等到天荒地老）。
  JS 全死是果不是因（parent main 被 GPU init parked，JS 模块一个都跑不起来：
  install/filter/storage/BCWebProgress 全灭，但 native 线程照转）。0009 的跨包
  bind（coolapk→provider 导出 service）在某些启动下落入"请求发出、AMS 无记录、
  无失败、无超时"的黑洞——`dumpsys activity services` 死轮宿主零 bind 记录。
- **0026 落地验证中（`cb5d19c0` / `af35103`）**：`ChildConnection.bind()` 10s
  watchdog（`armBindTimeout/cancelBindTimeout`，到期先 `unbindService` 再以
  `BindException` 异常完成，复用现有 retry×3→C++ fallback 全链；取消点：
  onBinderConnected 双分支 + completeFailedBind）。harness 47 行 0 FAIL +
  GLUE PASS（watchdog 构建，健康 bind 秒级取消零行为差）。验收信号：黑洞下
  第二次 `updateBindings` 行 + 有界时间内 blank PageStart + SW-WR。
- **构建纪律（2026-10-08 血泪，後人免踩）**：① 验 Java 编译查**内部类**
  （`GeckoProcessManager$ChildConnection`，不是外部类——1.5 小时 phantom 追查
  纯属验错文件，构建管线从头到尾无辜）；② AAR 内容检查与 publish 命令分开跑
  （chained 命令竞态误报缺失）；③ 本地 SNAPSHOT 重发后 provider 构建加
  `--refresh-dependencies`（防 24h 快照缓存吃掉新包）。另：`mach gradle` 的
  configuration-cache/up-to-date 组合在此树可信（脏快照一说已证伪）。
- **构建纪律（2026-10-08 血泪三条，後人免踩）**：① 验 Java 编译查**内部类**
  （`GeckoProcessManager$ChildConnection`，不是外部类——1.5 小时 phantom 追查
  纯属验错文件，构建管线从头到尾无辜）；② AAR 内容检查与 publish 命令分开跑
  （chained 命令竞态误报缺失）；③ 本地 SNAPSHOT 重发后 provider 构建加
  `--refresh-dependencies`（防 24h 快照缓存吃掉新包）。另：`mach gradle` 的
  configuration-cache/up-to-date 组合在此树可信（脏快照一说已证伪）。
- **redirect resume 落地（`d26f514`，未验证）**：`SessionRescue` 新增 armed 跟踪
  + commit 记忆 + `watchRedirectLeg`（谓词 JVM 锁 6 则）；`NavigationBridge`
  加 `onRedirectLeg` 回调（主帧 LR 全量信息）；静默 redirect 用 raw dispatch
  重发（REPLACE_HISTORY 留后续；终局静默维持现状）。三文件行数 993/486/103
  全合规；JVM 224 绿；lint 绿；harness 47 行 0 FAIL + GLUE PASS。但首个 verdict
  轮 P-DEAD 死在首导航（leg 未 armed，符合设计）——resume 路径仍待一个
  post-commit 轮验证。
- **连死率 ~50% 且与构建无关**：绿（66ee067×1、20b7f96×2、master×N）死（de44369
  后连死 5 轮 + 本轮）交错，A/B/A 已证同构建可绿可死。S2（transport 不建）是
  当前唯一 blocker。下一个判别：Gecko 主线程 `comm` 定位 + wchan/stack 归因
  （binder 等 bind vs futex 空闲）——Java 主线程 epoll-idle 已无信息量，要的是
   名为 Gecko 的 native 线程。
- **R2 COMMIT-SUPPRESSED 轮（2026-10-08 夜，pid 794，`/tmp/cool_r2.log`）**：
  bring-up 全绿（filter 下发→consult 1:1→gpu/tab24/tab29/tab28 秒级出生→
  零 cookie 超时→零 threw，挂载 dumpsys 确认 `CoolWebView` 内含全尺寸
  `GeckoViewHost`）但 release 页零 commit：200 到→每 attempt
  `RedirectToRealChannel BC=2`（reopen 后 BC=5）→content 永不消费→
  `OnSuspendTimeout`→guard churn×13→reopen→×3→give-up，a11y 21 节点零文本
  黑屏。rx +1MB 零 commit——纯网络单因再死一次。`aDestinationProcess=-1`
  经源码核为正常 Nothing 路径（open-promise 交 content，非"进程未知"；
  误读已纠）。`d26f514` 未 arm 符合设计（全程零前序 commit，非 S3 形）。
  S3-观察轮关闭：refire 后咨询发生已证实（HIT×6 一一配对）。
  主线程 70% 是 RenderThread/mali 自旋画 spinner（task comm 实锤），非
  guard/necko 空转；四 child 存活 idle、logcat 零行（门只照亮 parent）。
  取证门仍 ON（nsHttp 满屏）——下轮 verdict 前关回 OFF。
  候选修点 B（provider 小改）：consult-complete（本 URL 见过 ShouldPrepare
  HIT）后 guard 只 extend 不 refire——本轮 13 次重发零 commit 已证 churn
  无用且堆通道；listener-gap 丢 dispatch 另由首跳无 HIT 覆盖。
- **R3 闩验证轮（2026-10-09，pid 7262，`8d82ff4` 闩构建，`/tmp/cool_r3b.log`）**：
  firing 13→0（`consult-latched; refires suspended`×2 即时闩锁），LR 严格
  2 发（link+github redirect，无 churn），link commit（PageStart 到），
  binds/cookie/flush/filter×3/install 全绿，rx +1MB。github 仍零 commit：
  200 到→`RedirectToRealChannel BC=2`→content 永不消费→guard extend×48
  至今（设计行为：只等不杀）。churn 凶手闭案——闩是纯收益（R2 的烧毁 stopped）。
- **R4 fission-off 诊断轮（2026-10-09，pid 14274，`/tmp/cool_r4b.log`）**：
  `fission=0` 生效（Connecting 全零，R3 对照 fission=1），但同签名复现：
  link commit→github headers 2s 即到→`TriggerRedirect BC=2 dest=-1`（无切换）
  →无 Finished→无 PageStart。Fission 无辜——stall 与跨站切换无关。
  纠正 R2 笔误：child 日志并非零行（是 pid 过滤漏了子进程行）；tab1 消费 link
  全程有日志，tab27/tab6/tab16 secondary 进程 `ready-for-script` 后永寂
  （无 SettingsUpdate/BC/channel——无人寻址它们，是果）。
- **  收敛（R3+R4）：content 侧第二文档永不链接**。link（首文档，PPDC 认领路径
  `DocumentChannelParent Init` 有）commit；github（parent-side 推测性路径孤儿
  listener c760：无 PPDC、无 DocumentChannelParent、无 content
  DocumentChannelChild）necko 全通但 handoff 永不完成。R2-reopen
  （fresh session 直接 github，BC=5）同死佐证非 redirect 专属、拦截器无辜
  （phase-2 零咨询裸 load 同死）。待决：在 tab1 活 docshell 上第二文档链接
  失败的确切一步（open-promise resolve 去向 / AsyncOnChannelRedirect content
  核准 / SH 状态），或 coolapk 进程全局的 content 失聪。
  下一步：实验 E——MiniWV-2 最小第三方宿主直载 github（foreign+direct 格），
  判定 redirect-chain 专属 vs 进程全局；fission pref 已回退（诊断性，无收益
  不留）。
- **实验 E 施工 + 环境退化（2026-10-09 00:50，轮作废）**：`/tmp/miniwv2.apk`
  （子 agent 构建，moe.bemly.minimalwv/target34，url extra 覆盖；`build.sh`
  可重跑）+ `/tmp/cool-round.sh` 改 dump 驱动定位（feed 布局漂移，固定坐标
  作废；另修 check 窗口过紧 + stdout 污染）。A 轮对照绿（foreign 直载
  example 3s 完）。B 轮教训两条：① MiniWV 换 URL 必须重装（task intent 复用
  吃掉 extra，force-stop 不清）；② 取证门 ON 的 nsHttp 洪水 2 分钟吃光
  buffer，app 行全轮转——门已默认 OFF（`f1030c7`）。00:40 后环境退化：
  AMS `Ignoring finishAttachApplication`（miniwv/provide-child 多 UID），
  新起 MiniWV resumed 但零日志（21 行），轮次作废。按 DEVICE §6：reboot 需
  明确目的 + 用户拍板，不在深夜烧轮；明晨卫生窗口重跑 E（门 OFF 后 app 行
  可留存 + rx 会计 + 5 分钟窗）。
- **bromite 同槽位对照首轮（2026-10-09 08:06，用户立规首用）**：同机同网
  （整机 ~0.3-13KB/s trickle）同 MiniWV 同 example.com——bromite 3 秒完整
  finish（title=Example Domain，rx +59KB）；我方同条件 PageStart 到但 2 分钟
  无 finish（rx +13KB）。结论：finish-path 有产品缺口，不是纯网络（PageStart
  后 ProgressChanged/PageStop 投递或子资源挂起，另案；commit 闩无辜——它在
  PageStart 即解除）。Current 已切回我方并复核（Current/Preferred 均为
  debug 包）。以后网络归因照此模板：bromite 先行。
- **空标题根因：http 缓存投毒（2026-10-09 08:24 二分定锤）**：二分构建
  （88ab6ad：无闩+门开 = 好的那周等价）裸 example 照样黑——闩与门双双无辜。
  门开着的这轮抓到实锤：`OnStopRequest requestFromCache: 1,
  aIsFromNet=0`（necko 根本没联网）+ `ResponseHeadersComplete: 0`；
  3 分钟后换 `?bisect1`（同构建同网，绕缓存键）即 `title=Example Domain`
  渲染。结论：裸 URL 的缓存条目是空体（trickle 中断轮写入），之后每轮命中
  毒条目、瞬间“成功”、标题永不到；data:  bypass 缓存故一直正常；bromite
  独立缓存故正常。闩/门/fission 与此无关（同轮同机对照已排除）。
  教训（方法纠正）：以后每个改动配 MiniWV-example **cache-buster 渲染
  verdict**（title+eval），只看回调等于没验；pm clear 不能当常规手段
  （08:27 清后 refetch 仍可能在烂网下写新毒——写毒路径另案：中断/空体缘何
  被当 good 存）。当前设备装的是 HEAD 构建（闩在），worktree 已删，仓净。
- **日志门跟宿主bug（2026-10-09 09:50 现场修复）**：第三方宿主永不
  debuggable，按宿主门控等于 foreign 全盲（MiniWV 零 D 行实锤）。
  `initLogGate` 改取 provider 自身安装 flags（`b6648b2`）；setprop 补
  `log.tag.Sinytra` 无斜杠全局名（带斜杠 property 非法，device 实锤）。
  同轮新行首亮：`consult-latched url=`、`onTitleChange title=`（空标题是
  Gecko 侧报空，非桥弄丢）、`finish hold timeout titleEmpty=true`。
   本轮单通道干净（tab28，HIT→pass-through→OnSuccess→Start→空标题→Stop），
   字节数待 0027 publish 后 `OnStopResponse bodyBytes=` 一锤。
- **MiniWV 黑屏 verdict（2026-10-09，现状）**：MiniWV（foreign uid）
  直载 example.com+buster 连续黑，回调与呈现分离——consult→PageStart→
  title（`len=14`）→PageStop→finish 全绿、挂载三级链全尺寸、GPU resumed=1，
  但像素恒黑。对照矩阵（同机背靠背、buster 防毒缓存、rx 会计）：
  Canary（self+直连）3/3 绿、FrameworkEntry（self+WebView 栈）1/1 绿
  （`FRAMEWORK ENTRY PASS` 十项）、MiniWV 1/7 绿。结论：因子是
  **host uid（跨包呈现）**，WebView 栈无辜；foreign 偶发逃逸一次
  （race 形，非纯环境——触发态 load/mem/僵尸/thermal 绿黑无差）。
- **已证伪（各一行，不赘）**：挂载（dumpsys 三级链 1080x2112 +
  SurfaceFlinger BLAST 层在）；UI/GPU resume（双 `resumed=1`、
  `skipPaused` 全零）；content-link（v6 MOZ_LOG：TriggerRedirect
  dest=3→Finished rv=0→PPDC/Parent/Child 齐→fission 2→3 迁移正）；
  tab 归属错位（消费 tab 正确）；body 截断（`brotlihandler decompress
  334→out=577`，necko 完整——**0027 bodyBytes 系传输（压缩）口径，
  小数值≠截断**，注记已进 patch）；finish-path 产品缺口（bromite
  同槽对照曾绿，系 exposed 缓存/时序差，非独立缺口）；闩/门/fission
  （同轮同机对照排除在先）。
- **仪器（现状）**：0027 outcome（status+bodyBytes，0005 门，快照 -8 起
  活）；0028 resume-state（UI 侧 Sync/Notify + GPU 侧 Schedule* skipPaused
  + RecvResume 结果 + `NotifyDidRender` 计数，同门，诊断补丁定稿前删）。
  空标题= Gecko 侧报空（`onTitleChange` 实锤，非桥弄丢）。
- **进行中**：MiniWV 已 6 连绿（`pm clear` 后 v15/v16/v17 +
  不清连跑 v18/v19/v20，title/finish/像素/渲染帧全齐）——当前构建
  foreign 可 100% 开，与用户记忆（昨天之前 100%）一致，**代码无回归**。
  黑区间（v1–v7、B10/B12）全在清前旧 profile 下，清后零复黑——
  profile 态投毒定案（但清前未采 profile 文件清单，**毒是哪个文件
  未识别，这是缺口**；再黑先采 `ls/du` 再清）。偏差承认：v3 的
  “空体完成 B 判”是误读（334 系 br 压缩口径，v6 已纠）；“坏窗 flake”
  中间结论作废（后被 A/B 分裂证伪）；“跨包呈现 bug”降级为
  “跨包首跳 stall + 旧 profile 下必死”（清后同栈即绿，呈现路无辜）。
- **残留真问题（非呈现，是首跳 stall）**：绿轮每轮 guard 重发 7–8 次
  （`onLoadRequest×8`，首跳 dispatch 落地但 200/首字节迟迟不到，
  约 50s 才 commit）。清 profile 没修掉它——它一直都在，只是旧
  profile 下连 rescue 也救不活。3s 即开（00:50 A 轮）vs 50s 才开
  （现在）的差即此。下步二选一：① 修首跳（necko/QUIC/Alt-Svc 面，
  另案）；② 先转酷安真页（MiniWV 探针已绿，主任务优先）。
  酷安真页（github release）复验排其后（cool-round.sh 同条件）。
- **方法纪律（本次沉淀）**：改动配 MiniWV-example **cache-buster 渲染
  verdict**（title+像素双准，只看回调=没验）；对照必带 rx delta
  （wlan0+tun0，时变带宽 86 倍摆动实锤）+ 交替顺序；网络归因主证用
  bromite 同槽对照（用户立规）；pm clear 不当常规手段；verdict 轮
  取证门保持 OFF（开门只 single 捕获轮，用完即关——v6 开/关各一次已验）；
  MiniWV 换 URL 必重装（task intent 复用吃 extra）。
- **构建纪律（本次沉淀）**：跨命名空间 helper 必 `mozilla::` 限定；
  多行宏编辑后必看尾行（吞换行挂过一次）；publish 后 provider 必
  `--refresh-dependencies`；本机默认 12 clang 卡死时 `mach build -j2`
  （用户暂行要求，不进永久纪律）；AAR 进包验字符串用 raw 匹配
  （token 化必漏）；snapshot 命名（-8/-10/-11/-12）与 patch 含量的
  对应关系以 AAR 实测为准，不以提交顺序猜。
- **环境新变量（未定罪，记录）**：机上 `tun0` 在 verdict 轮 carrying
  流量；前台曾见 `moe.nb4a/sagernet`（VPN 客户端，不在 §1v hook 栈
  清单里）。待用户方便时确认隧道用途，不打扰、不预设归因。
- **fission 归因卡在 2/3（2026-10-09 晚，三批全 void，停烧）**：v27+v30
  有效绿（迁移停止+快commit）后，补样批（v31–v34）4 void、百度批
  （B1–B5，用户改测百度主页）5 void——三批 14/15 void，全是 GPU 死窗
  形（`Bind timed out` + 无 real PageStart + `DocumentChannelChild 0`），
  fission 既未成立也未证无辜。按纪律停烧轮次，ledger 封存。
- **纸面新结论（零设备成本，`/tmp/mini_b1.log` vs `/tmp/mini_v30.log`）**：
  0029 生效中——B1 的 3s cadence 超时 + `after 2 attempts` + SW-WR
  fallback + tab4 出生并 RUNNING（fallback 后 0.1s 即 bind 成功）。死点已
  下移到 fallback   下游：百度 200 头 ×3 到（44.7s，三次 dispatch 全 HIT/
  pass-through/OnSuccess），但 content 侧 `DocumentChannelChild ctor`
  永不到（tab RUNNING 3s 在前），无 OnStop、无 PageStart。绿 v30 的 ctor
  在 tab RUNNING 后 0.06s、OnStart 之前——当前 blocker 是 R2 家族的
  “content 永不消费”（RedirectToRealChannelFinished aRv=0 后静默），
  不再是 GPU bind 本身。待判：fallback 是否与失聪相关（v27 绿轮有无
  fallback 待查；R2 无 fallback 同形——纸面未闭）。
- **fallback⊥失聪（2026-10-09 晚，纸面 2×2 闭环，零设备成本）**：v27/v30
  绿（无 fallback、有 ctor）× R2 黑（无 fallback、无 ctor，
  `/tmp/cool_r2.log` 零 `Bind timed out`）× B1 黑（有 fallback、无 ctor）
  × v18 绿（有 fallback、管线放行）——fallback 与 content 失聪正交，
  fallback 无辜也无功，不再当判别轴。失聪形精化（B1）：parent
  RedirectToRealChannel（aRv=0）→ 1.5s 后静默 dtor（ParentProcess
  DocumentChannel + LoadListener，  无 IPC 错误、无 crash，content tab
  RUNNING 后零行）；dtor 触发源在轻量门下不可见（necko cancel/docshell
  侧需 nsHttp 全门 single 捕获轮，COOLAPK §5-0b 纪律，逮到死轮再开）。
- **bromite 同槽对照更正（2026-10-09 20:0x，用户抓获像素作废）**：
  首轮判“bromite 2s 绿”只验了标题（`onPageFinished title=百度一下`
  2s 到，rx +1.37MB）没验像素，违“标题+像素双准”纪律——screencap 真看
  是百度页内 JS 起的 `baiduboxapp://` 调端跳转撞 `ERR_UNKNOWN_URL_SCHEME`
  的错误页，无“百度”二字。百度主页是脏测试页（任何引擎都会被它的调端
  覆盖像素），像素 verdict 双方作废。网络隔离结论保留在日志证据上
  （bromite 2s 取到 HTML 并 finish，我方同条件 80s 零标题）——像素级绿
  需换干净 URL 重验。
- **Bing 同槽对照（2026-10-09 20:13–20:17，双像素亲眼 verdict）**：bromite
  `?v1bing01` 3s finish（`title=搜索 - Microsoft 必应`，rx +1MB），截图亲眼
  必应主页全像素（搜索框/壁纸/资讯，无调端劫持，干净对照页）；我方
  `?v1bing02` 同条件：dispatch/HIT/redirect（cn.bing.com）/200 头 ×3 全通，
  GPU bind 黑洞（3s 超时×2）后零标题零 finish，截图亲眼纯黑。标题像素双
  黑 vs 双绿——网络/设备/URL 全无辜，故障隔离在我方 content 失聪，与百度
  批同形。Current 已切回我方。
- **二分修正（2026-10-09 20:58–21:01，“重装即治”作废，用户抓获）**：
  同包（今天）Bing `?v1bing04` 黑（黑洞×2、无 ctor、像素亲眼纯黑）→ 紧跟
  example `?v1bxex03` 同黑（黑洞×2、无标题、像素亲眼纯黑），而 4 分钟前
  同包 example `?v1bxex02` 全绿。页面无辜（Bing/example 同包交替黑/绿，只
  与本轮是否抽中 bind 黑洞有关）；“重装治好”是同时换包换页换时间的三
  变量误判，作废。当前唯一判别轴 = 本轮有无黑洞（有则黑、无则绿），与
  包版本（昨天/今天都绿过也都黑过）、页面（Bing/example）无关。连黑窗口
  17:08 起未解除。5 包归档已开（`Sinytra-apks/`：昨天 88ab6ad + 今天
  c890114，缺 3 后补；构建纪要：JDK/SDK 走 mozbuild 自带，文档旧
  `/opt/homebrew` 路径已失效待修）。
- **闩逃生补丁验证中（2026-10-09 22:05，`77ceb11`）**：闩住后彻底静默
  16s 准一次救援重发、再静默 16s 转重开（`NavigationCommitGuard` 单文件
  +4 JVM 锁，lint/239 锁绿，归档第 3 包，已装机）。首轮 Bing `?v1bing05`
  走干净快路（ctor→200→title17→finish，子资源流，像素亲眼深色主页含
  搜索框/备案号，壁纸未绘）——本轮无黑洞，救援分支未被触发，修法未验证；
  连黑窗口似已过（22 点后首绿）。救援是否真救黑洞轮，待自然黑洞轮命中
  （不抽奖烧轮）。
- **blank 掐链修复上机即绿（2026-10-09 22:45，`78aa07d`，归档第 4 包）**：
  隐式 blank 的 PageStart/LocationChange 不再 disarm（只补预算），真提交
  才 disarm（`GeckoSessionBridge` 两处，lint/239 锁绿）。Bing `?v1bing07`
  1s 全绿（ctor→200→闩→title17→finish→子资源流，像素亲眼壁纸全绘，与
  bromite 同图）——快路无退化，但本轮同样无黑洞，blank 修复与救援分支
  均未被黑洞验证。22 点后连绿 3 轮，窗口确认已过。


## 2. 下一步（按顺序，一次做一件）

1. ~~**切换路线主线化**~~（2026-09-26 完成，§1s）：metadata + versionCode
   （`4ca8c58`）→ trampoline（`4ca8c58`，Proxy 转发；PoC 的 storage/geo
   断环不再需要——单例已 provider-owned）→ `FrameworkEntryActivity` 迁入
   `src/debug`（`f2a830c`）+ 真实路径视图接线修复（`2ea2713`）。
   验收四件全过：Valid + Current 为我方、FRAMEWORK ENTRY PASS、反射
   harness 41 PASS 不退。
2. ~~**切换后全量回归**~~（2026-09-27 完成，§1t）：切换态真实路径探针
   （FRAMEWORK ENTRY PASS）+ 反射 harness 41 PASS 复验；第三方冒烟从
   "创建即 SEGV"修至渲染可用——自建 MiniWV 最小宿主（干净探针）+
   mywebview + Obsidian，根因 = Gecko 侧三个部署形态缺口，patch
   0008/0009/0010 落栈 + provider 侧两修。残留：更广的日常重度 App
   矩阵按需扩测（MiniWV 已是可复用的最小真实路径探针）。
3. **CTS Sinytra 对照轮**（2026-09-30 在跑）：切换后直跑 `CtsWebkitTestCases`
   （CTS.md §2），对照 Chromium 基线 98.6%，fail 逐条归因落档。a11y/视觉类用例一并汇入。
   - 首轮全量（Picture 合成前）：CookieManagerTest 即卡死——`WebViewSyncLoader`
     以 `mLoaded/mNewPicture/mProgress` 三条件门加载完成，我方 `setPictureListener`
     空实现致 `mNewPicture` 永假。修：`b9ed3c1` 按成功 onPageFinished 合成
     onNewPicture + capturePicture() 返回空 Picture（mid-load 失效通知诚实缺口）。
   - 次轮全量（Picture 合成后，`PostMessageTest.testSimpleMessageToMainFrame`
     `loadDataWithBaseURL: P1/P2` 抛崩进程止，跑完 7 类）：A–G 共 26 fail +
     1 崩溃点（loadDataWithBaseURL 未实现）。失败面：CookieManager 11
     （set/get/remove/SameSite/第三方；`getCookie` 返 `""` 而 CTS 要 null，
     `setCookie("name=test")` 落 jar 失败，`removeSessionCookies` 回调无应答）/
     CookieTest 5（Domain/Path/空值；同 jar 落盘面）/ Geolocation 4（2 超时 +
     insecure-origin 仍给位置 + reject 无 prompt）/ HttpAuth 3（load 超时）/
     PacProcessor 3（trampoline `createPacProcessor: Not implemented`）。
     DateSorter/MimeTypeMap 全绿。WebView 主体类尚未跑到（崩溃点之后）。
   - 关键发现记死：Gecko 报 `blocking all storage access requests`（localhost
     页 cookie 被拦截）——默认 cookieBehavior（ACCEPT_FIRST_PARTY）下 CTS 本地
     测试服务器场景疑被当第三方存储掐掉；`setCookie("name=test")` 这类无属性
     裸 cookie 落 jar 失败与此同源候选。下一步：先定点复现裸 setCookie +
     核 cookieBehavior 在 CTS 进程的实际值，再动 0007/策略。
4. **a11y v2 深度对齐（等反馈）**：v1 遍历链路已通（§1p）；剩 WebView 节点自身的
   AccessibilityNodeProvider/onProvideVirtualStructure 合并、焦点/
   performAccessibilityAction 映射。先拿 TalkBack 手测 + 第 3 步 CTS a11y 失败清单
   再动手——盲改可能打断已通的子视图遍历。
5. **SW + 拦截并存语义**（储备，显式决策点）：参考语义已建档
   （`RELATED-PROJECTS.md` §2.1）；未被真实场景需要前不接线（AGENTS §5）。

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
  不是 bridge/cache bug。hidden-View A/B 不必做。生产修复已落地：
  `onPageFinished(success)` 后自动 flush（`GeckoSessionBridge.onPageFinished`）。

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
  注意：切换成 system WebView 后所有 App 的 Gecko child 都受 DuraSpeed 影响——
  总开关必须保持关闭（DEVICE §1）；不做 ROM，没有“vendor 白名单”这条路。
- 调试纪律：保持亮屏解锁（Doze+锁屏冻心跳、截图全黑属正常）；
  先看 `am_proc_start ... :<child>` 是否出现，再看 denials；
  跑 harness 前先跑 `CanaryRenderActivity` 确认 child 起得来（金丝雀）。

## 4. 已实现文件速览

```text
provider/src/main/java/org/mozilla/geckowebview/
├── provider/
│   ├── GeckoWebViewFactoryProvider.java  # WebViewFactoryProvider 实现 + Statics 单例族
│   ├── GeckoWebViewProvider.java         # WebViewProvider 实现（P0-P2 接线，扇出到下两行；1053→986，
│   │                                     # 消息端口 plumbing 已抽到 ProviderMessagePorts，§7 行数合规）
│   ├── ProviderMessagePorts.java         # framework WebMessage 通道 + 端口路由表（Host 取 MessageBridge）
│   ├── ClientFanOut.java                 # app 回调扇出（WebViewClient/WebChromeClient 分发）
│   ├── ProviderAdapters.java / FrameworkTokens.java  # framework token 注册表 + adapters
│   ├── GeckoBackForwardList.java         # SessionState/HistoryList → WebBackForwardList 转译
│   └── CompatWebSettings.java            # android.webkit.WebSettings facade
├── session/  # GeckoSessionBridge + 专职 bridge（Navigation/Progress/Content/Error/Find/
│             #   Intercept/Js/Message/Permission/Prompt/Print/RenderProcess/Response/State…）
├── settings/ (GeckoWebSettings 纯状态 + toSessionSettings)
├── storage/  # 单例族实现：CookieManager（0007 真 cookie jar）、WebStorage、Geolocation、
│             #   ServiceWorker、Tracing、WebIconDatabase、WebViewDatabase
├── compat/   # androidx.webkit boundary glue（P2-8；18 项诚实 feature 集 + LiveMessagePort）
├── view/     (GeckoViewHost：bind/release + attach(open+setSession)，session 不碰 View)
└── runtime/  (GeckoRuntimeHolder：宿主进程级 GeckoRuntime 单例，最底层；UI 线程 create，peek() 诚实降级)
provider/src/main/assets/sinytra-js/  # 内置 WebExtension（JS transport：eval / interface /
                                      #   WebMessage 端口；协议见 content.js 头注释）
provider/src/debug/  # 探针与 harness（release dexdump 0 引用）：GlueHarnessActivity（编排）+
                     #   TransportProbes / InterceptProbes / SystemCapabilityProbes / CanaryRenderActivity /
                     #   FrameworkEntryActivity（切换后真实路径验收，§1s）/
                     #   BootstrapProbe + BootstrapProbeActivity
framework-stubs/  # compileOnly 的 android14 hidden API stubs（WebViewFactoryProvider/
                  # WebViewProvider/WebViewDelegate/WebViewFactory/PacProcessor/
                  # TokenBindingService；createWebView 参数 erase 到 Object，缘由见
                  # WebViewFactoryProvider.java 注释）
```

构建：Java 17 + Gradle wrapper 9.4.1 + AGP 9.2.0，`compileSdk 37.2`（GV 158
构建链要求，android-components/.config.yml）/`targetSdk=34` `minSdk=26`（对齐
真机），GV `geckoview-nightly:158.0.20260924093433` 已 pin（mozilla-central
158.0a1 最后一刻 `34ed69f16167`，见 AGENTS.md 顶部）。master 默认构建红属
预期——2026-09-26 实测不带 flag 16 个编译错误，全部引用 patch 注入的
GeckoView 类型/方法：0001/0002（`WebRequestInfo`、`GeckoSession.ResponseDelegate`/
`setResponseDelegate`）、0006（`CertOverrideController`）、0007
（`StorageController` 四个 cookie 方法）。一切构建/测试须
`-PsinytraLocalGecko=true`（§1k）。详见 `DEVICE.md` §4。
