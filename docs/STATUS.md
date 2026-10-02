# STATUS — Sinytra

> 实现进展与待办（给新会话的交接页）。技术细节见 `ARCHITECTURE.md` /
> `API_MAPPING.md` / `BOOTSTRAP.md`，阶段定义见 `ROADMAP.md`。
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
adb -s V885Q49L8TAMFEEE logcat -c && adb -s V885Q49L8TAMFEEE shell am start -n moe.bemly.geckowebview.debug/org.mozilla.geckowebview.provider.P0GlueActivity
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
  set 路径行为对齐，open）；testThirdPartyCookie 两度跑到第二 load 无
  verdict（teardown/GPU 卷入，待健康窗口重取）。
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
  跑 harness 前先跑 `P0RenderActivity` 确认 child 起得来（金丝雀）。

## 4. 已实现文件速览

```text
provider/src/main/java/org/mozilla/geckowebview/
├── provider/
│   ├── GeckoWebViewFactoryProvider.java  # WebViewFactoryProvider 实现 + Statics 单例族
│   ├── GeckoWebViewProvider.java         # WebViewProvider 实现（P0-P2 接线，扇出到下两行）
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
provider/src/debug/  # 探针与 harness（release dexdump 0 引用）：P0GlueActivity（编排）+
                     #   P2TransportProbes / P2InterceptProbes / P1SystemProbes / P0RenderActivity /
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
