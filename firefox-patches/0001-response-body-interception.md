# firefox-patches/0001 — response-body 拦截(v1:导航级 WebResponse)

> 状态:**已定稿落树**(2026-09-24;patch `0001-response-body-interception.patch`
> 7 commits,153 线真机打通 29 PASS,158 重放 @ `e79d4c7e1362`——见 README
> Stack 表)。纪律:一 patch 一件事、独立测试、冲突不解不升级
> (AGENTS.md §3/§5)。原始基线:`FIREFOX_153_0_RELEASE`
> (f1b6c0f86b96b7e0688c26f65803576f27cdaf88;现 158.0a1 线,见 README Pin 节)。

## 1. 解决哪个 WebView API

`android.webkit.WebViewClient.shouldInterceptRequest(WebView, WebResourceRequest)`
→ `WebResourceResponse`(app 供 body/status/headers,Gecko 侧替身加载)。

Sinytra 侧现状(P2-4,InterceptBridge):app 的非 null response 只能映射为
DENY——body/status/headers 无处安放;子资源(图片/CSS/XHR)根本不进
决策路径(只有导航进 onLoadRequest/onSubframeLoadRequest)。

## 2. 为什么 GeckoView public API 不够(AAR javap + 树内核实)

- GV153 `NavigationDelegate.onLoadRequest/onSubframeLoadRequest` 只返回
  `GeckoResult<AllowOrDeny>`——ALLOW/DENY 二值,无 response 通道。
- 决策发生在 **docshell 层**(`LoadURIDelegate.load` → EventDispatcher
  "GeckoView:OnLoadRequest" → Java 布尔 handled),不是网络层;
  子资源加载不经过该路径。
- `WebResponse` 类存在但只用于下载方向(`ContentDelegate.onExternalResponse`,
  Gecko→Java 流);没有 Java→Gecko 的响应体通道。
- 无 upstream bug 对应(实现期在 bugzilla 搜索 "shouldInterceptRequest
  webview" 补引;若已有 WIP 不重复造轮)。

## 3. 树内勘察结论(2026-09-23,pin 树实测)

- 决策链:`GeckoViewNavigation.sys.mjs`(parent,nsIBrowserDOMWindow/
  shouldLoadURI 路径,call sites 403/464/537)→ `LoadURIDelegate.load` →
  EventDispatcher → `GeckoSession.java:691` 消息处理 → Java delegate。
- **Java 供体流的原语现成**:`org.mozilla.geckoview.GeckoViewInputStream`
  (JNI 绑定 `mozilla.java.GeckoViewInputStream`)+ 子类
  `ContentInputStream`(content:// 经 ContentResolver 供体,JNI 绑定
  `mozilla.java.ContentInputStream`);C++ 侧 `nsIAndroidContentInputStream`。
  **子进程可用性已由 content:// 文档渲染证明**(child = 宿主 App 进程族,
  带 APK classloader + ContentResolver 可用)。
- channel 侧地基:`GeckoViewContentChannel : nsBaseChannel`
  (`OpenContentStream`)+ `GeckoViewContentProtocolHandler`(content:// 注册)。
  `nsBaseChannel.OpenContentStream` 即"用现成流充当响应体"的官方模式。

## 3a. 勘察修订(2026-09-23 晚,实测钉死,推翻 §4 原 docshell 方案)

- **决策全在 parent 进程**:子帧导航的 allow/deny 也是 child docshell
  同步跨进程问 parent chrome(我们的 deny 探针经 parent 侧
  `GeckoSession.java:691` 处理器就是证据)。Java 回包(含 body 字节)
  永远落在 parent;docshell/channel 在 child。
- docshell 层的替身加载(loadURI 重定向 / error-page 机制 /
  sinytra-response: scheme)**全部保不住 URL 身份**(location.href 变
  data:/内部 scheme)——对 WebViewAssetLoader 类"页面 JS 读自己 origin"
  的场景是致命语义缺口。
- Gecko 中唯一保 URL 身份的 body 替换机制 = **nsIInterceptedChannel
  (ServiceWorker 拦截那一族)**,位于 child 的 necko 层,顺带天然覆盖
  子资源。
- **结论:v1 直接按网络层架构实现**(原 §4 的 docshell 方案作废);
  "导航级先行"不再有意义,因为网络层原语天然覆盖导航 + 子资源。

## 4. v1 设计(修订:necko 层 nsIInterceptedChannel 路线)

**Java 侧(Group 1,可独立构建,无消费端时惰性)**:
- 新 `GeckoSession.ResponseDelegate`(default 无操作):会话级拦截应答口。
  `onRequestResponse(session, WebRequestInfo)` 返回 `WebResponse` 或 null
  (null = 不接管)。`WebRequestInfo` v1 仅 uri + isNavigation(method/
  headers 进 0002)。
- `GeckoSession.setResponseDelegate()`;parent 侧 EventDispatcher 注册
  `"GeckoView:OnRequestResponse"` 查询处理:invoke delegate(后台线程,
  body InputStream 排干为字节,上限 16MB 懒加载/v2 流式)→ 应答
  `{handled:true, uri, statusCode, contentType, encoding, headers, bodyB64}`
  或 `{handled:false}`。
- body 走 base64 而非流引用:channel 在 child,Java 流对象跨不了 JVM;
  v1 接受内存上限,0002 评估流式 IPC。

**C++ 侧(Group 3,勘察定稿 2026-09-23 晚)**:
- **钩子契约是 necko 公开接口** `nsINetworkInterceptController`
  (`netwerk/base/nsINetworkInterceptController.idl`):
  `shouldPrepareForIntercept(uri, channel)` + `channelIntercepted(channel)`。
  SW 只是它的一个实现(`ServiceWorkerInterceptController`,
  NS_DECL_NSINETWORKINTERCEPTCONTROLLER);消费端模式 =
  new 一个 controller 挂进 channel 的 notificationCallbacks 链
  (`FetchDriver.cpp:1692`、`RemoteWorkerChild.cpp:80` 即此模式)。
- **合成响应**:`nsHttpChannel::RedirectToInterceptedChannel()`(nsHttpChannel.cpp:12273)
  经 `GetCallback(controller)` 解析 controller → 命中后内部重定向到
  `InterceptedHttpChannel::CreateForInterception(...)` → controller.
  channelIntercepted 拿到 `nsIInterceptedChannel` →
  `SetSynthesizedStatus/SetSynthesizedHeader + StartSynthesizedResponse(流)`
  ——URL 身份由内部重定向天然保持,无需 nsHttpChannel 手术。
- **实现形态**:`mobile/android/components/geckoview/` 新
  `GeckoViewResponseController.cpp`(实现 nsINetworkInterceptController):
  shouldPrepareForIntercept 查进程级 uri 前缀表(Java 注册侧经
  EventDispatcher 下发,命中才跨进程查询);channelIntercepted 经
  EventDispatcher 原生桥向 parent Java 发 `GeckoView:OnRequestResponse`
  查询,取回 Group 1 应答(bundle:b64/status/headers)→ 内存流
  StartSynthesizedResponse。注册进 callback 链:对齐 FetchDriver 模式,
  在 GeckoView 的 docshell 加载路径挂接(具体挂点 = 实现期第一件事:
  确认 child docshell loads 的 notificationCallbacks 聚合位置)。
- **不确定项(实现期解决)**:① child 进程 EventDispatcher 原生桥对
  C++ 发起查询的支持面(widget/android/EventDispatcher.cpp);
  ② callback 链挂点的最小侵入位置;③ 每请求查询的节流(uri 前缀表
  在 child 命中后才跨进程,无前缀命中零开销)。

**JS 侧(Group 2)**:无(查询不经 JS actor——necko C++ 直接走
EventDispatcher 的 C++ 接口)。

**Java 侧**(geckoview):
- `NavigationDelegate` 增可选方法(带 default null,零 ABI 破坏):
  `GeckoResult<WebResponse> onLoadRequestResponse(GeckoSession, LoadRequest)`。
  语义:返回非 null = "本加载由 app 供体应答"(WebResponse.uri 必须等于
  请求 uri,statusCode/headers/contentType 可设,body 为 InputStream);
  返回 null = 走原 onLoadRequest(AllowOrDeny)不变。
- 消息协议:`GeckoView:OnLoadRequest` 的应答扩展为
  `{handled:false} | {handled:true} | {handled:true, responseId:<int>}`;
  body 不走 JSON——Java 侧把 `WebInputStream`(新类,包可见,包一个
  app 传入的 InputStream)登记进进程级注册表,responseId 由 C++ 侧经
  JNI 取回流实例(复用 GeckoViewInputStream 的 JNI 绑定模式,新增
  `WebInputStreamWrappers` 生成)。

**C++ 侧**(mobile/android/components/geckoview/):
- 新 `GeckoViewResponseChannel : nsBaseChannel`:`OpenContentStream` 从
  JNI 取 Java 流包成 nsIInputStream,statusCode/contentType 透传
  (nsBaseChannel 的 SetContentType / status 路径)。
- 注册:`GeckoViewNavigation.sys.mjs` 的 `LoadURIDelegate.load` 应答为
  `handled+responseId` 时,不再 `NS_ERROR_ABORT`——改经
  `docshell.loadURI(sinytra-response:<responseId>?...)` 风格的内部重定向,
  由 GeckoViewResponseProtocolHandler 产 channel,真实 URI 语义由
  channel 的 `SetOriginalURI/LoadInfo` 保持(location.href 为原 uri)。
  (备选:nsIInterceptedChannel 化——工程量更大,v2 再评。)

**JS 侧**:`LoadURIDelegate.load` 返回值从 bool 扩为
`{handled, responseId?}`(向后兼容:现有调用点只判 truthy)。

## 5. 边界(v1 明确不做,防止范围蔓延)

- 只覆盖**导航级**(onLoadRequest/onSubframeLoadRequest 能到达的加载)。
  子资源(图片/CSS/XHR/fetch)的网络级拦截 = 0002(necko child 进程
  http-channel hook,独立 patch 独立测试)。
- body v1 支持一次读完整/流式均可(Java InputStream 语义透传);
 Range 请求不在 v1。
- 不动 WebResponse 既有下载语义。

## 6. 测试计划(独立测试,先于合入)

- GeckoView 侧:geckoview junit(`mobile/android/geckoview/src/test/`)
  ——mock session 上注册 onLoadRequestResponse 返回固定 body,断言
  `loadUri` 后 onPageStop 成功 + location.href 为原 uri + body 渲染。
- Sinytra 侧:harness 新探针 `interceptBody`——TestClient.
  shouldInterceptRequest 对标记 host 返回 `WebResourceResponse("text/html",
  "utf-8", ByteArrayInputStream("<h1>sinytra-body</h1>".bytes))`,
  eval 断言 iframe `contentDocument.body.textContent` 等于标记
  (body 真被替身,而非 DENY)。
- 回归:替换构建全量 29 探针 + CTS 过渡轮不劣化。

## 7. 工作流(README 已记,此处为 patch 专用顺序)

```bash
# 树改动逐文件落 patch(0001-*.patch 用 git format-patch 生成):
#   Java: GeckoSession.java / WebInputStream.java(新)/ WebResponse.java(如需扩展)
#   C++ : GeckoViewResponseChannel.{h,cpp}(新)/ moz.build / AndroidBridge.idl(如需)
#   JS  : LoadURIDelegate.sys.mjs / GeckoViewNavigation.sys.mjs
cd /Volumes//Projects/firefox && git checkout sinytra-pin
# 应用 → mach build binaries → mach gradle geckoview:publishDebugPublicationToMavenRepository
cd /Volumes//Projects/Sinytra
./gradlew :provider:assembleDebug -PsinytraLocalGecko=true
adb install -r … && 跑金丝雀 + interceptBody 探针 + 全量 29 探针
```

## 8. 树内踩坑记录(2026-09-24 实施期,自 STATUS §2 迁入)

- AutoJSAPI 在 `mozilla/dom/ScriptSettings.h`;
- 合成 API 是 `SynthesizeStatus/SynthesizeHeader`(无 Set 前缀);
- xpidl 生成的 callback 是 `OnSuccess(数据, cx)`(cx 在尾);
- moz.build 列表严格字母序;dom/serviceworkers 头要用 `mozilla/dom/` 限定路径。
