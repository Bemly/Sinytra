# firefox-patches/0001 — response-body 拦截(v1:导航级 WebResponse)

> 状态:设计定稿,patch 未落文件。纪律:一 patch 一件事、独立测试、
> 冲突不解不升级(AGENTS.md §3/§5)。基线:`FIREFOX_153_0_RELEASE`
> (f1b6c0f86b96b7e0688c26f65803576f27cdaf88)。

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

## 4. v1 设计(导航级替换,一个 patch 一件事)

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
