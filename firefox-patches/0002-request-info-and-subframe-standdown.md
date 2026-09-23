# firefox-patches/0002 — 请求信息保真(method/headers) + 子帧 DENY 让位

> 状态:设计定稿,patch 未落文件。纪律:一 patch 一件事、独立测试、
> 冲突不解不升级(AGENTS.md §3/§5)。基线:`FIREFOX_153_0_RELEASE`
> (f1b6c0f86b96b7e0688c26f65803576f27cdaf88)+ 0001 stack(@ 59aa103b6d50)。

## 1. 解决哪个 WebView API

`shouldInterceptRequest(WebView, WebResourceRequest)` 的**请求面保真**:
Chromium 语义里 app 能看到 `getMethod()` / `getRequestHeaders()` /
`isForMainFrame()`,并据此区分图片/CSS/XHR/iframe 导航再决定替身内容。
0001 v1 的查询面只有 `uri + isNavigation`(且 C++ 侧 `ChannelIntercepted`
硬编码 `isNavigation=true`),app 拿不到 method/headers——过滤器命中的
XHR 和导航无法区分,请求头类替身逻辑(条件 GET、Range 等)无从谈起。

另修 Sinytra glue 侧的**拦截面碰撞**:filter 命中的子帧导航(iframe)
会先被 P2-4 的 `onSubframeLoadRequest` DENY 近似杀掉,necko 替身永远
收不到 channel(0001 调试时预判过,deny 探针用 example.org 未命中
filter 所以没暴露)。

## 2. 为什么 GeckoView public API 不够(树内勘察 2026-09-24)

- 查询协议 `GeckoView:OnRequestResponse` 的 payload 由 0001 C++ 构造,
  只有 `{uri, isNavigation}`;channel 的 method/headers 在 C++ 手里
  (`nsHttpChannel` request head),Java 侧 `WebRequestInfo` 是 0001
  Group 1 新增类型,v1 字段即 uri+isNavigation——扩展需继续走 patch。
- `WebRequestInfo.fromBundle` / bundle 组装都在 0001 引入的代码里,
  不破坏任何上游 ABI。

## 3. 拓扑勘察结论(2026-09-24,修正 §0001 设计文档的进程假设)

- **现代 Gecko 已无 `dom.serviceWorkers.parent_intercept` pref**——
  parent 侧拦截是常态架构:`HttpChannelParent` 为**每条** e10s 内容
  channel 在 parent 进程建**真 nsHttpChannel**
  (netwerk/protocol/http/HttpChannelParent.cpp:586 起,method/headers/
  upload 全量重建),`ParentChannelListener`(nsINetworkInterceptController
  实现)挂进其回调链。
- 咨询入口 `HttpBaseChannel::ShouldIntercept`
  (netwerk/protocol/http/HttpBaseChannel.cpp:4493)→ `GetCallback(controller)`
  → `ParentChannelListener::ShouldPrepareForIntercept` → 0001 的
  GeckoViewResponseController(进程级共享 FilterState)。
- **推论:子资源(img/css/xhr/fetch)与子帧导航的 channel 同样在 parent
  侧咨询**——0001 的 ParentChannelListener 包装可能已天然覆盖,但未
  实证(0001 探针只测了顶级导航)。0002 第一件事:探针实证。
- child 进程 docshell 的 `mInterceptController` 为 null
  (nsDocShell.cpp:508-515 只包 parent 分支)——child 侧无人咨询,
  与 parent 侧真 channel 架构自洽,无需 child 侧 hook。

## 4. v1 设计

**Group A(Sinytra glue,可先行独立提交)**:
- `InterceptBridge` 增让位规则:Host 增
  `responseSurfaceOwns(uri)`(provider 实现 = filters 非空且 uri 前缀
  命中);`decide()` 在命中时短路返回 allow(null),不再咨询 app、
  不再 DENY——necko 层已拥有该 URI 的应答权。前缀匹配抽成静态纯函数
  (JVM 可测)。
- provider `setInterceptFilters` 语义不变;让位规则随 filters 生效。

**Group B(Firefox patch,WebRequestInfo v2)**:
- C++ `ChannelIntercepted`:从 intercepted channel 取真身
  (`nsIChannel` → `nsHttpChannel`),提取 method
  (`nsHttpRequestHead::Method()`)与请求头(跳过空值头;`isNavigation`
  按真实 loadInfo/navigation 状态传,替代硬编码 true),bundle 增
  `method` / `headers`(name:value 数组,复用 0001 的拍平格式)。
- Java `WebRequestInfo` 增 `method` / `requestHeaders`(Bundle 兼容:
  旧 C++ 不发新键时字段取缺省,向后兼容)。
- Sinytra `ResponseBridge.shouldIntercept` 把 method/headers 透传进
  `SinytraResourceRequest`(`WebResourceRequest.getMethod/
  getRequestHeaders` 开始返回真值;headers 做
  `Map<String,String>` 拍平转译)。
- 隐私决策点(定稿拍板):Cookie/Authorization 是否透传给 app——
  Chromium `shouldInterceptRequest` 语义是"请求头可见但不保证完整";
  v1 先全量透传 + loud 注释,0003 按需收窄。

**明确不做(防蔓延)**:流式 body IPC(16MB base64 上限继续用,
0003 评估);POST body 透传(0003);Range(0003);child 侧 hook
(勘察证明不需要)。

## 5. 测试计划(独立测试,先于合入)

- Sinytra JVM:`InterceptBridge` 让位规则(prefix 命中/未命中/filters 空
  三态)、`SinytraResourceRequest` method/headers 透传、ResponseBridge
  bundle→holder 转译。
- 设备 harness 新探针:
  ① `interceptSubresource`——替身页内 `<img src="https://body.example/
  pixel">`(或 XHR),断言子资源拿到 app body(实证 parent 侧覆盖子资源,
  §3 推论的裁决性实验);
  ② `interceptIframe`——filter 命中的 iframe 导航不再被 DENY,且渲染
  替身 body(让位规则的端到端锁)。
- 回归:全量 harness(29 PASS 基线)+ 新探针,CTS 过渡轮不劣化。

## 6. 工作流(同 0001,README 已记)

```bash
cd /Volumes//Projects/firefox && git checkout sinytra-pin
# Group B 落树 → mach build binaries → mach gradle geckoview:publish…
cd /Volumes//Projects/Sinytra
./gradlew :provider:assembleDebug -PsinytraLocalGecko=true
# 金丝雀 + 全量探针 + interceptSubresource/interceptIframe
```

环境变量与真机流程见 STATUS §1h 命令块;收尾时 `git format-patch`
落 `0002-*.patch` + README stack 表。
