# firefox-patches/0006 — SSL proceed（SslErrorHandler.proceed 接真）

> 状态：**已定稿**（2026-09-25，158 线 @ `2f3d7e2e5b56`；设备 sslProceed
> 探针 + 全量 39 探针回归 + P0 GLUE PASS）。纪律：一 patch 一件事、独立
> 测试（AGENTS.md §3/§5）。基线：158.0a1 `34ed69f16167` + 0001-0005/0007
> stack。解决：`android.webkit.WebViewClient.onReceivedSslError` 的
> `SslErrorHandler.proceed()`（"带病证书继续加载"）。

## 1. 为什么 public API 不够

GeckoView 的 `NavigationDelegate.onLoadError` 只给错误码 + 可选错误页 URL，
**没有证书信息、没有"继续"语义**；`nsICertOverrideService`（Firefox
Add-Exception 的地基）没有任何 Java/GeckoView 暴露。失败证书只在 Gecko
错误路径上可达（`docShell.failedChannel.securityInfo`，桌面 cert-error 页
同源）。

## 2. 链路设计

1. **捕获（child actor）**：`LoadURIDelegateChild.handleLoadError` 在
   `errorClass == ERROR_CLASS_BAD_CERT` 时从**delegate 入参的失败
   channel**取 `securityInfo.serverCert` → DER → base64 塞进
   `GeckoView:OnLoadError` 消息（`certBase64`）。
   - **接口扩展（两轮真机定位后的最终形态）**：`nsILoadURIDelegate.
     handleLoadError` 增加 `in nsIChannel aFailedChannel` 参数（uuid
     bump；GV 专属接口，唯一 C++ 调用点在 `nsDocShell::DisplayLoadError`）。
     首选的"读 `docShell.failedChannel`"方案两轮实测不可行：① `mFailed
     Channel` 原本在 delegate 调用**之后**的 `LoadErrorPage` 才赋值；
     ② JS 可见的 `nsIDocShell.failedChannel` getter 读的是**错误页文档**
     的 `doc->GetFailedChannel()`（`nsDocShell.cpp:4795`），更要等错误页
     文档建立——delegate 窗口期两者皆 null。显式传参是唯一正解。
   - **构建坑记死（复现 STATUS §1g ①）**：IDL 变更同样要先
     `./mach build export` 再 `binaries`——xpidl 生成的
     `dist/include/nsILoadURIDelegate.h` 不更新，docshell 编译报
     "too many arguments"。
2. **stash（parent actor + 全局模块）**：`LoadURIDelegateParent` 见
   `certBase64` 即调 `GeckoViewCertOverride.stash(uri, cert)`（新全局
   ES 模块，`GeckoViewStartup` ged 注册 `GeckoView:AllowCertError`）。
3. **proceed（Java→Gecko）**：Java 侧 `CertOverrideController.allowError(uri)`
   （新 geckoview 类，`queryBoolean`）→ 模块查 stash →
   `nsICertOverrideService.rememberValidityOverride(host, port, {}, cert,
   temporary=true)` → 返回成败。消费即删（重复失败经错误流重新捕获）。
4. **provider 接线**：
   - `android.webkit.SinytraSslErrorHandler`（**包内子类**）：框架基类
     `proceed()/cancel()` 是空壳，ctor public @SystemApi（"Only for use by
     WebViewProvider implementations"），Chromium 胶水同为匿名子类
     （WebViewContentsClientAdapter#onReceivedSslError）。public SDK
     android.jar 把 ctor 剥成包私有 → 子类放 android.webkit 包内同时满足
     javac 可见性与运行期 public。类名全新，不触碰 WebMessagePort 撞包
     约束的实质（同包不同名）。
   - `ClientFanOut.onLoadError` SSL 分支：proceed → `allowError(uri)` →
     成功则经 `ownerBridge().loadUrl` 延迟 500ms 重载（导航已终结，
     proceed = 重载；Firefox 语义）。cancel = 错误页原地不动。

## 3. 踩坑记死

- **失败时序（两轮实测）**：首选"读 `docShell.failedChannel`"两轮皆空——
  ① `mFailedChannel` 原在 delegate 调用**之后**的 `LoadErrorPage` 才赋值；
  ② JS 可见的 `nsIDocShell.failedChannel` getter 读**错误页文档**的
  `doc->GetFailedChannel()`（`nsDocShell.cpp:4795`），更要等错误页建立。
  显式传参（IDL 加参）是唯一正解。首轮真机
  `allowError: no stashed certificate` 即此因。
- **错误类**：证书错是 `nsINSSErrorsService.ERROR_CLASS_BAD_CERT`（=2），
  不是 `ERROR_CLASS_SSL_PROTOCOL`。
- **override 服务要求主线程**：ged 处理器在 gecko 主线程 ✓；
  `rememberValidityOverride` 无 bits 参数（按证书指纹容忍），port=-1
  内部按 443。
- **同步语义分歧（记录）**：Chromium proceed 只放行当前导航；本实现是
  Firefox temporary exception——同 host:port+证书在会话内持续放行
  （Gecko override 粒度如此）。
- **reload 三连坑（探针侧定位，均为通用陷阱）**：
  1. **framework WebView 陷阱（同 download 探针）**：harness 的 framework
     WebView 绑定系统 Chromium provider，reload 必须走
     `ownerBridge().loadUrl`；
  2. **detached View.post 陷阱**：`webView.post/postDelayed` 的 runnable
     进 View 的 RunQueue，**未 attach 的 View 永不执行**（线程转储证实主
     线程空闲在 epoll）——必须用主线程 `android.os.Handler`；
  3. **eval 与 poller 竞争（同 STATUS §1d）**：重载页首 eval 超时，重试
     （4s×10）后成功。
- **构建坑（复现 STATUS §1g ①）**：IDL 变更先 `mach build export` 再
  `binaries`，否则 xpidl 头不更新、docshell 编译报 "too many arguments"。

## 4. 测试

- 设备 `sslProceed` 探针（自包含）：debug assets 内置自签 PKCS12
  （CN=127.0.0.1 + SAN IP），进程内 `SSLServerSocket`；导航 →
  `onReceivedSslError`（恰好 1 次）→ proceed → override → 重载 →
  body 渲染断言（eval 带重试）。恢复原 WebViewClient（errClient 纪律）。
- JVM：`SinytraSslErrorHandlerTest`（decision 路由 ×2 锁）。
- 回归：全量 harness **39 探针 + P0 GLUE PASS**（诊断移除后的干净构建
  复跑确认）、JVM 66 锁。
