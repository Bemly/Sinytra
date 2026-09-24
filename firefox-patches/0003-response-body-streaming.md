# firefox-patches/0003 — 流式响应体(同进程 JNI 流,移除 16MB 上限)

> 状态:**已定稿落树**(2026-09-25;153 线 @ 62b7b46e280e,158 重放见
> README Pin 节)。纪律:一 patch 一件事、独立测试、冲突不解不升级
> (AGENTS.md §3/§5)。原始基线:`FIREFOX_153_0_RELEASE`
> (f1b6c0f86b96b7e0688c26f65803576f27cdaf88)+ 0001/0002 stack
> (@ 539b7ff6f329)。

## 1. 解决什么

0001 引入的响应体通道把 app 的 InputStream 排干成字节、base64 装进
应答 bundle、16MB 封顶拒绝。Chromium 的 `shouldInterceptRequest` 语义是
**流式**:app 的 InputStream 被增量读取,无大小上限。0003 移除排干路径,
让替身体按 Chromium 语义流式到达 Gecko。

## 2. 为什么 0001 的假设不成立了(0002 拓扑修正的推论)

0001 设计的前提是"channel 在 child,Java 流对象跨不了 JVM"。0002 勘察
钉死了真实拓扑:parent 侧每条 e10s channel 有**真 nsHttpChannel**,拦截
查询全程发生在 **app 进程**(C++ `Dispatch` → JNI → Java 处理器 →
GeckoResult 回 JNI → C++ `OnSuccess`)——是 **JNI 边界,不是进程边界**。
Java 流对象完全可以直接递给 native:base64 + 16MB 上限在解一个不存在的
问题,还带来双倍内存(字节副本 + base64 膨胀)和 O(n) 排干延迟。

## 3. 现成原语(复用,AGENTS §4;零新 JNI 机制)

- Java:`org.mozilla.geckoview.GeckoViewInputStream`(`@WrapForJNI
  @AnyThread`,上游就是给 content:// 流用的)——`setInputStream`
  包装**任意** Java InputStream,native 经 JNI `available/read/close`。
- C++:`mobile/android/components/geckoview/GeckoViewInputStream.h` 的
  `GeckoViewInputStream : nsIAndroidContentInputStream`,构造直接收
  `mozilla::java::GeckoViewInputStream::LocalRef`。

## 4. v1 设计

- **Java 注册表** `GeckoViewResponseStreams`(geckoview 新类):
  `register(GeckoViewInputStream) → long id` + `@WrapForJNI static
  take(long) → GeckoViewInputStream`(取出即注销)。进程级
  ConcurrentHashMap,id 原子递增。
- **`GeckoSession.responseToBundle`**:不再排干/base64——body 非空时
  包成 `GeckoViewInputStream` 注册,`bundle.putLong("bodyStreamId", id)`;
  body 为空则不带该键。`RESPONSE_BODY_MAX_BYTES`/排干循环/base64 全删。
- **C++ `ResponseCallback::OnSuccess/Synthesize`**:`bodyStreamId`
  (`JS::ToInt64`,缺省 0)→ `GeckoViewResponseStreams::Take(id)` →
  `ResponseBodyStream`(本文件子类,暴露 protected ctor)→ 直接进
  `StartSynthesizedResponse`;id 缺失合成空体。字段名 `bodyB64` 废除。
- **moz.build**:`widget/android/moz.build` 的 `classes_with_WrapForJNI`
  字母序插入 `GeckoViewResponseStreams`(bindgen 产 wrapper 的前提;
  0001 踩坑记录的字母序规则再次生效)。
- **Sinytra `ResponseBridge`**:16MB drain 逻辑删除,app body 原样
  `builder.body(app.body)`——关闭语义归 Gecko 侧包装器。
- **构建注意(树内踩坑记录)**:① `mach build binaries` 不含 export 层,
  新增 GeneratedJNI 头后要 `mach build export` 再 binaries;② 新类先进
  `classes_with_WrapForJNI` 才有 wrapper;③
  `GeckoViewResponseController.h` 的内联 `= default` 构造使
  StaticComponents.cpp 实例化 RefPtr 析构——头里必须给
  `ServiceWorkerInterceptController` 完整定义(前向声明靠 unified
  bundle 传递 include 侥幸,统一边界一动就炸,本次实测)。

## 5. 边界(v1 不做)

- 不做跨进程流(不需要,见 §2);POST body 透传维持不做(Chromium
  `shouldInterceptRequest` 语义同样不给——POST body 不进 WebResourceRequest)。
- **Range(2026-09-25 修订,据 RELATED-PROJECTS 比对降级)**:原列为后续
  候选,实测无需任何 Gecko 改动——0002 已把请求头(含 `Range`)全量透传
  给 app,app 直接合成 206 + `Content-Range` 应答即可(wszgrcy 线的
  LocalAssetRequestInterceptor 正是这么做的,媒体 seek 可用)。
  唯一注意:206 语义正确性(边界/多段)归 app,Gecko 侧 `InterceptedHttp
  Channel` 原样投递。此候选**关闭**。
- 注册表泄漏面:`take` 未被调用的流活到进程结束——查询只应答一次,
  C++ 必取,风险接受;若未来出现重复应答再补超时清扫。

## 6. 测试计划

- Sinytra JVM:无新锁(流路径全在 Gecko 侧;ResponseBridge 不再有可
  JVM 观察的排干逻辑)——回归靠既有 49 锁。
- 设备 harness 新探针 **interceptLargeBody**:app 返回 **17MB**(大于
  废除的 16MB 上限)替身页,断言 `textContent.length == 17000014` +
  首尾 17 字符模式(PREFIX/循环数字/SUFFIX)。裁决性实验:旧路径
  (base64+cap)对 >16MB 必拒(NXDOMAIN 报错页),渲染出标记只能是
  流路径。既有 interceptBody/interceptSubresource/interceptIframe 三探针
  回归锁流路径不劣化。
- 回归:全量 harness 预期 **32 PASS**(31 + interceptLargeBody)。

## 7. 工作流(同 0001/0002,README 已记)

```bash
cd /Volumes//Projects/firefox && git checkout sinytra-pin
# 落树 → mach build export && mach build binaries
# → mach gradle geckoview:publishDebugPublicationToMavenRepository
cd /Volumes//Projects/Sinytra
./gradlew :provider:assembleDebug -PsinytraLocalGecko=true
# 金丝雀 + 全量探针 + interceptLargeBody
```
