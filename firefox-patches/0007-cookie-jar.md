# firefox-patches/0007 — CookieManager cookie-jar 原语（StorageController）

> 状态：**已定稿落树**（2026-09-25，158 线 @ `dfcc04709482`，设备 38 探针回归 +
> `P0 GLUE PASS`）。纪律：一 patch 一件事、独立测试（AGENTS.md §3/§5）。
> 基线：158.0a1 `34ed69f16167` + 0001-0005 stack。

## 1. 解决哪个 WebView API

`android.webkit.CookieManager` 的逐 cookie 面：`getCookie(url)` /
`setCookie(url, value[, cb])` / `removeSessionCookies` / `hasCookies`。
provider 的 `GeckoCookieManager`（P0 起 honest-default：get 返 ""、set 返
FALSE、旗标只是进程内布尔）由此转真。

## 2. 为什么 public API 不够（AAR javap + 树内核实）

- GV158 `StorageController` 全表只有 clearData 族 + permissions——**零逐
  cookie 访问**；AAR 里 cookie 相关类只有 `ContentBlocking$CookieBehavior`
  行为常量。cookie jar 全部住在 Necko（`nsICookieService`/`nsICookieManager`）。
- 结论：必须 patch。行为旗标（`setAcceptCookie`/`setAcceptThirdPartyCookies`）
  不需要 patch——经 `ContentBlocking.setCookieBehavior` 映射（provider 侧
  Java-only）。

## 3. 地基选型

- **通道**：复用 `StorageController` 既有的全局派发器模式
  （`EventDispatcher.getInstance().queryString/queryBoolean` →
  `GeckoViewStorageController.sys.mjs` `onEvent`，事件名注册在
  `GeckoViewStartup.sys.mjs` 的 ged 列表）。新消息四个：
  `GeckoView:GetCookie / SetCookie / RemoveSessionCookies / HasCookies`。
- **写路径**：首选 `nsICookieService.setCookieStringFromHttp(uri, string,
  channel)` 想复用引擎全套 Set-Cookie 解析器（Expires 日期、cookieBehavior
  执行、SameSite 规则）——**实测不可行**：C++ 入口
  `NS_ENSURE_ARG(aChannel)`（CookieService.cpp:449），程序化 set 没有真实
  channel。降级为 JS 侧最小解析（首对 name=value + 常见属性
  Path/Domain/Secure/HttpOnly/Max-Age/Expires/SameSite，未知属性忽略——与
  Chromium 行为一致）+ `nsICookieManager.add()`；`add` 返回
  `nsICookieValidation`，成败判定精确（非 FromHttp 路径的静默拒绝）。
  cookieBehavior 执行靠 JS 预检 `getCookieBehavior(false) ==
  BEHAVIOR_REJECT`（`add` 是"后门"，不走行为检查）。
- **读路径**：`getCookiesFromHost(host, {}, aSorted=true)`（RFC 6265 排序
  内建）+ `isSecure` 按 https 过滤（WebView 原生级读取：HttpOnly 包含）。
  host 用 `uri.asciiHost`（ACE 归一，对齐 `add` 的存储形式）。

## 4. 踩坑记死（真机定位）

1. **session cookie 即存即死**：`add()` 文档明示 "expiry time will also be
   honored for session cookies; the more restrictive of the two will take
   effect"——`expiry=0` 的 session cookie 存进 jar 立即被"已过期"过滤
   （`AddInternal` 原样存 `CookieStruct`，读取时按 `expiry < now` 滤）。
   修：session cookie 默认 `now + 400 天`（引擎自身 cookie 寿命 cap 同值）。
2. **cenum 的 JS 访问是平的**：`Ci.nsICookie.SCHEME_HTTPS` 直接挂接口
   （上游 `SiteDataTestUtils.sys.mjs` 同款），写成
   `Ci.nsICookie.schemeType.SCHEME_HTTPS` 抛 TypeError → sendError →
   Java 侧 false（首轮真机实测撞过）。
3. **Domain cookie**：`add()` 用**前导点**标记 domain cookie
   （".example.com"，且至少两段子域否则抛）；`Domain` 属性须与 URI host
   做后缀匹配（Chromium 语义），否则返回 false。

## 5. Java 面（provider 侧，不在本 patch 内）

- 策略表：`(acceptCookie, thirdParty)` → `CookieBehavior`：
  false→ACCEPT_NONE；true+optIn→ACCEPT_ALL；true+未设/optOut→
  ACCEPT_FIRST_PARTY（=WebView targetSdk≥21 默认报告值 false，**引擎现实
  与 facade 报告一致**）。RuntimeSettings setter 断主线程 → 非主线程
  post。
- 同步 API：WebView CookieManager 是同步契约（Chromium 阻塞调用线程）。
  查询统一在专属 `Sinytra-cookie` HandlerThread 上发起（GeckoResult 的
  accept 链需要 Looper dispatcher；GeckoResult.poll 有 Looper 线程禁用），
  调用线程只等 latch（有界 5s，超时诚实降级 + loud log）——UI 线程调用者
  无死锁。`GeckoRuntime.getStorageController()` 断主线程 → controller 惰
  性取一次缓存（同时修掉旧 `clearByFlags` 的同款潜在雷）。
- `flush()` 保持 no-op：Gecko 自动落盘（cookies.sqlite 批量写），无强制
  flush 原语（netwerk/cookie IDL 无 flush）。

## 6. 测试

- 设备：`P1SystemProbes.cookieJar`（set → get 含 marker → hasCookies=true
  → removeSessionCookies=TRUE → marker 消失），并入全量 38 探针回归。
- JVM（`GeckoCookieManagerTest`，60 锁的一部分）：策略表映射 ×3、facade
  旗标往返 ×2、null 参守卫 ×2（不触 runtime）。
- 限制（显式记录，后续决策点）：手工 Set-Cookie 解析弱于引擎解析器
  （Expires 为宽松 Date.parse；PSL 不在 add 层校验）；per-WebView 第三方
  粒度 v1 为全局（需 per-context cookieBehavior 才能对齐）。
