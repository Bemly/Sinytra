# firefox-patches — Sinytra

> 相对 Firefox 源码树的 patch stack。纪律见 AGENTS.md §3/§5 +
> `docs/BOOTSTRAP.md` §3：**每个 patch 只做一件事、独立测试、rebase 不解不升级**。

## Pin

- 基线：**mozilla-central 158.0a1 最后一刻**
  `34ed69f161676c3ac7ca5f201fade6d081e5bcd2`（2026-09-25 定稿；158.0 正式
  tag 未发布，出来后再评估切 release；`browser/config/version.txt`=158.0a1
  已核，下一个 version bump commit 即翻 159.0a1）
- 对齐 AAR：`org.mozilla.geckoview:geckoview-nightly:158.0.20260924093433`
  （daily 走 maven.mozilla.org）。注意 158 树本地 publish 产物的
  artifactId 是 **geckoview-default**（computeArtifactId 新规则），且
  `substitute-local-geckoview.gradle` 把 nightly 坐标换成本地
  geckoview-default——已验证咬合
- sibling checkout 位置：`/Volumes/（U+F8FF 卷）/Projects/firefox`。
  **卷名含 U+F8FF 字面路径经 Bash 传递编码不稳定（实测反复踩）**，一律走
  `~/sinytra-vol` 符号链接或 python 探测；磁盘以该卷为准（非系统盘）
- 分支：`sinytra-pin-158`（158 线，唯一活跃线）；`sinytra-pin`
  （153 线，已退役：2026-10-01 起 r29 NDK 已删，`objdir-opt` 不可再构建；
  分支仅保留 0001-0003 的 153 版 patch 历史，不再维护）
- 构建：`mozconfig-158`（独立 `objdir-158`，内容同 mozconfig 仅 objdir 不同；
  每线一个 mozconfig/objdir，互不覆盖）

## Stack（自下而上，编号即应用顺序）

| # | 文件 | 解决哪个 WebView API | 为什么 public API 不够 | 状态 |
|---|---|---|---|---|
| 0001 | response-body 拦截（导航级） | `shouldInterceptRequest` 返回自定义 body | GV 无 Java→Gecko 响应体通道（详见 0001 设计文档） | **已定稿**：`0001-response-body-interception.patch`（7 commits）；153 线真机打通（2026-09-24 29 PASS），**158 重放 @ `e79d4c7e1362`** |
| 0002 | 请求信息保真 + 子帧 DENY 让位 | `shouldInterceptRequest` 的 method/headers 语义、filter 命中子帧不被近似杀掉 | 0001 查询面 v1 只有 uri（详见 0002 设计文档） | **已定稿**：`0002-request-info-and-subframe-standdown.patch`；**158 重放 @ `2985d67b9eaa`** |
| 0003 | 流式响应体 | 替身体流式读取，无 16MB 上限 | base64+cap 基于过时假设（详见 0003 设计文档） | **已定稿**：`0003-response-body-streaming.patch`；**158 重放 @ `8efda7b417c0`** |
| 0004 | 合成响应 SW tainting（ORB 加固） | 顶层导航合成响应无 loading principal，ORB/跨源检查会拒 | `InterceptedHttpChannel` 的 SW 路径会 SynthesizeServiceWorkerTainting，自研路径不会——借鉴 wszgrcy 线（RELATED-PROJECTS §2.1） | **已定稿**：`0004-synthesized-sw-tainting.patch`（@ `bc58ea7cf4df`）；153 上不需要（真机已通），158 起防御性补上 |
| 0005 | 插桩日志 debug 门 | debug 构建多打、release 零输出（README「日志纪律」） | AAR debug/release 变体共用一个 libxul，编译期门做不到按变体区分 → 运行时 pref `sinytra.log.enabled`（默认 false） | **已定稿**：`0005-debug-only-instrumentation.patch`（@ `b43672422aa1`）；provider debug 构建经 `src/debug/assets/geckoview-config.yaml` 打开 |
| 0006 | SSL proceed（证书例外） | `SslErrorHandler.proceed()`（带病证书继续加载） | GV 无 cert-override 原语，失败证书只在错误时可达（delegate 通道带出）→ `nsILoadURIDelegate` 加 channel 参数 + 子 actor 捕获 + `GeckoViewCertOverride` stash → `nsICertOverrideService` temporary override（设计文档 `0006-ssl-proceed.md`） | **已定稿**：`0006-ssl-proceed.patch`（@ `2f3d7e2e5b56`）；设备 sslProceed 探针（自签 TLS + 进程内服务器）+ 39 探针回归 |
| 0007 | CookieManager cookie-jar 原语 | `CookieManager` 逐 cookie get/set/removeSessionCookies/hasCookies | GV 无任何逐 cookie API（jar 在 Necko）→ `StorageController` 四原语 + JS 模块 `nsICookieManager` 直控（设计文档 `0007-cookie-jar.md`） | **已定稿**：`0007-cookie-jar.patch`（@ `dfcc04709482`）；设备 cookieJar 探针 + 38 探针回归 |
| 0008 | greomni 指向 omni.ja 所在 APK | 全部 API——第三方宿主进程里 Gecko 起不来 | `GeckoThread` 用宿主 APK 拼 `-greomni`，宿主无 omni.ja → 组件清单读不到 → prefs 服务缺失 → XRE_main SEGV（设计文档 `0008-greomni-omnijar.md`） | **已定稿**：`0008-greomni-omnijar.patch`（@ `ae2e75763a03`）；MiniWV/mywebview/Obsidian 三宿主真机复验 |
| 0009 | child services 跨包解析/绑定 | 同上——XRE 无 child 即死 | 服务在 provider 包 manifest，intent 却按宿主包解析且 `exported=false`（设计文档 `0009-cross-package-child-services.md`） | **已定稿**：`0009-cross-package-child-services.patch`（@ `3c82e42ed1fd`）；child 进程挂 provider uid，宿主渲染正常 |
| 0010 | ACCESS_NETWORK_STATE 缺失降级 | 全局健壮性（WebView 语义） | 宿主缺权限时 GeckoNetworkManager 主线程 SecurityException 杀宿主（设计文档 `0010-network-state-permission-guard.md`） | **已定稿**：`0010-network-state-permission-guard.patch`（@ `1ddf48b43e23`）；mywebview（缺权限宿主）存活验证 |
| 0011 | Cookie 读写域收敛 | `getCookie` 域/path 精确 + `setCookie` RFC 默认 path | getCookiesFromHost 是 base-domain 级（漏 sibling 域），读缺 path/secure/expiry 过滤；写缺 Path 属性时硬编码 "/" 与服务端/JS 设的 cookie 分键导致同名重复（设计见 patch 头注释；ms 单位依据 nsICookie.idl） | **已定稿**：`0011-cookie-read-scoping.patch`（squash 两 commits 为一）；设备 bareCookie/CTS-exact 双探针 + harness 回归 |
| 0012 | 响应问询资源面 | `shouldInterceptRequest` 的资源类型/触发页（图片/混合内容/文件策略执行） | 0002 问询面只有 uri/method/headers：policy 执行（blockNetworkImage、mixed-content、file-access）无从判断 subresource 类型与所属页面，只能嗅 URL（设计见 patch 头注释） | **已定稿**：`0012-response-resource-face.patch`（C++ bundle 加 contentPolicyType + triggerUri；WebRequestInfo 加同名字段；nsIContentPolicy TYPE_* 值为准） |
| 0013 | resource://android 指向 provider APK | `evaluateJavascript` / `addJavascriptInterface` / `postMessage` / console / 一切依赖内置 sinytra-js 扩展的 API | `GeckoAppShell.getPackageResourcePath()` 返回宿主 APK 路径 → `resource://android/assets/sinytra-js/manifest.json` 在宿主包内不存在 → 扩展加载失败（NS_ERROR_FILE_NOT_FOUND）→ JS transport 死 → PostMessage 8 全灭 + console/beforeunload/Geo 用例失败（CTS pid 20099 logcat 实锤） | **已定稿**：`0013-resource-android-provider-apk.patch`（与 0008 同逻辑：classloader-derived provider APK 路径；`nsResProtocolHandler::GetApkURI` 经 JNI 调此方法）；publish 成功，待真机验证 |
| 0014 | 同 host 的 Domain 也存成 domain cookie | `CookieManager.setCookie` 带与 URL host 相等的 Domain 属性（CTS CookieTest.testDomain） | 0011 的 `cookieHost!==host` 门漏掉相等 case → 存成 host cookie；记 `sawDomainAttr`，有 Domain 属性即前导点存 domain cookie（校验仍拒不匹配域） | **已定稿**：`0014-cookie-domain-equal-host.patch`（0011 同文件后续；与 0013 同一批 binaries+publish）；待 CTS CookieTest.testDomain 验证 |
| 0015 | 合成响应追踪日志 | triage（CTS 合成后无完成的定位） | 0005 门控下的 Take 命中/MISS + Start 返回值 + finish 日志（默认关闭，零开销；已用其证实 Take 命中/Start rv=0） | **已定稿**：`0015-synthesis-trace.patch`（0001 同文件后续；独立编号保持栈线性） |
| 0016 | removeSessionCookies 诚实返回值 | `CookieManager.removeSessionCookies/removeAllCookies` 回调语义 | 预删计数会谎报（删失败也 true）+ 空清恒 true；改为有删净才 true（CTS CookieManagerTest.testRemoveCookiesCallback:337 第二次空清必须 false） | **已定稿**：`0016-removeSessionCookies-honest-result.patch`（0007 同文件后续；provider 侧 removeAllCookies 同修 `bdb5cb9`） |
| 0017 | Binder 有界失败+恢复（M5-A） | compositor teardown 的同步 Binder 依赖（1855536 类） | IPDL 跳有 1880503 的 10s 超时，Binder 跳（`ICompositorSurfaceManager.onSurfaceChanged`）无任何超时——UI 可被无限拖死（设计文档 `0017-binder-bounded-failure.md`；v1 watchdog 实射后证伪 kill 路线，v2 改异步永不进 transact；oneway 仅 M5-B 实验变体） | **验证中**：`0017-binder-async-surface.patch`（单文件；GPU ON 下 teardown 复现器转出 verdict + harness 46 PASS；验收 4/5 待多轮 verdicts） |
| 0018 | 导航时推送待定 UA override 到顶 context | `WebSettings.getUserAgentString` 默认值落线（CTS User-Agent 三件套） | parent 侧 UpdateSettings 只在 Java bundle CHANGES 时触发，不变的 override 到不了重建的顶 context → necko 带默认 UA；导航提交前同步推送待定值（channel 在 AsyncOpen 读 UA） | **已定稿**：`0018-ua-override-top-context.patch`（firefox `3db89990f8e7`；provider 侧空串 no-op `ab0f327` 同批；CTS testAccessUserAgentString/testUserAgentString_default/testUserAgentStringTest 全过） |
| 0019 | loopback-http 可读 Secure cookie | `CookieManager.getCookie` 第三方 Secure（CTS CookieManagerTest.testThirdPartyCookie） | 0011 读过滤只认 https scheme，与上游（非可信上下文才清 Secure 值；loopback 可信）偏离——jar 里有 cookie，读侧返回空 | **已定稿**：`0019-secure-cookie-loopback-read.patch`（firefox `cfd5589cdb02`；CTS testThirdPartyCookie 由 waitForCookie 超时翻绿） |
| 0020 | `file:///android_asset/` 解析到宿主 APK（设计稿，归属待定） | file 族（`LocalImageLoads/Iframes+XHR*Enabled`，`but was:<null>`） | `file:///android_asset/` 是 Android 虚拟路径，necko 无此概念（字面 ENOENT 静默死）；file 通道不走 0001 咨询点 | **验证中**：`0020-file-android-asset.patch`（3 commits：C++ 直解 + file-reads 键/镜像 + file 进程强制打开；设计文档 `0020-file-android-asset.md` §3b/§6）。file-4 矩阵 5/6（XHREnabled 为 app 私有区 honest gap）；JVM 180 锁 |
| 0021 | surface-handoff ENTER/EXIT trace（Phase-1 判别器） | §1w 白屏：排除"0017 v2 async 丢首帧"的回归嫌疑 | v2 只记慢/失败 transact；fire-and-forget 成功不证明 GPU 侧执行 | **验证中→首判已出**：`0021-surface-handoff-trace.patch`（firefox `e3930d2585c8`；白屏轮三 handoff 全送达，判别 A——故障在下游，v2 洗脱） |
| 0022 | resume-path handoff barrier（M5-A3 ordering fix） | Round 10：resume 查询 map 跑赢 handoff 落定（error 比 ENTER 早 ~6ms），首启空 map 必白 | v2 异步后 ResumeAndResize（IPDL）系统性跑赢 worker→Binder→map.put；暖 widget 靠旧 surface 偶过 | **已验证定稿**：`0022-resume-handoff-barrier.patch`（firefox `e27c1b9f09ee`；首启出像素 + cover 像素保持 + 零 resume-null/零 barrier 超时；全量 harness 46 PASS 仍欠，见 §4） |
| 0023 | Pause() fire-and-forget（M5-A1 pause 路） | Round 11：activity stop 经 surfaceDestroyed park 主线程于 syncPauseCompositor，kill 后仍 park | v2 只搬 Binder hop，IPDL SendPause 照等远端；Pause 返回值无人读，恢复走本地 mCompositorPaused | **已验证定稿**：`0023-pause-async.patch`（firefox `0a39bc4251fe`；cover 零 park + 返回像素；noCache park 消失；sync-messages.ini 摘备案） |
| 0024 | loadsImagesAutomatically 的 docshell enforcement | `setLoadsImagesAutomatically(false)` 后 data:/file: 图片仍加载（CTS image 族 `expected 0 but was 1`） | 0001 咨询只挂 HttpBaseChannel（nsDataChannel/nsFileChannel 永不咨询）；`nsDocShell.allowImages` 全树无写者；`ImageBlocker` 只认全局 pref 且只看 http(s) | **已定稿**：`0024-image-policy-docshell.patch`（7 commits：settings 键 + ImageBlocker docshell 检查 + child 应用 + 每导航强制推送 + 原地恢复 + 提交后补推 + 诊断门；设计文档 `0024-image-policy-docshell.md`）。loadData 确定门走 provider 侧 sanitize（备份式剥离）+ setter 即时推送 + 双 flag 放宽原地恢复（eval，零导航）；Gecko docshell 路径作纵深。image 族 4/4 verdict（串行，同一最终构建）+ harness 全绿 + JVM 174 锁。残留 1673953 型 pre-commit 静默（非本 patch 范围） |
| 0025 | ged stub 加载失败不投毒（自愈 + onError 速败） | 第三方冷进程里 `GetCookie/SetCookie/GetAllPermissions` 等 global 查询 100% 永不回答（含 stock 消息），而 session/actor 路径正常 | 上游 `addLazyGetter` 的 ged stub 先 unregister 再求值 `scope[name]`：加载期抛异常即永久无 listener，而 native 表仍报有 listener → 所有后续查询静默 hang（device 实锤；hasGeckoListener 恒 true）。上游无此形状的 bug（2026-10-08 已检索） | **验证中**：`0025-ged-stub-no-poison.patch`（1 commit：加载失败保留 stub + `callback.onError`；成功路径与旧代码一致）。JS 改动经 stage-package 进 omni（解包验 marker），publish 后 provider 重编验证 |
| 0026 | ChildConnection.bind 10s watchdog（启动路径 Binder 黑洞） | 全部 API——第三方宿主冷进程里 GPU bind 受理后永不回叫（无成功/失败/重试），transport 永不建立 | `bindService()==true` 只证 AM 受理；`mPendingBind` settle 只认 onServiceConnected/同步 false，Java 段零计时器；重试只认 error；重发撞 `Bind in progress` 空转（设计文档 `0026-bind-watchdog.md`；上游 1994984/1991502/1880503 同族） | **验证中**：`0026-bind-watchdog.patch`（1 commit：单文件 `GeckoProcessManager.java`，10s 计时 + 先 unbind 后异常完成，复用现有 retry→fallback 全链）。验收信号：黑洞下出现第二次 `updateBindings` 行 |
| 0027 | 通道结果日志（状态码 + 投递字节） | 同类空标题轮需二分才定案：Java 侧只有咨询决策与生命周期，channel 空体完成无人说话 | stock 状态/字节在 nsHttp MOZ_LOG（门一关即盲）；Java 层根本看不到响应码与字节数 | **已发布待验**：`0027-channel-outcome-logging.patch`（1 commit：`ParentChannelListener` OnStart 记 status + OnData 累字节 + OnStop 记 status/bodyBytes，全走 0005 `sinytra.log.enabled` 门，release 零成本；2026-10-09 快照 -8 已含，`mozilla::` 修后重 publish）。provider 重编后 debug 轮见行即验 |
| 0028 | resume-state 追踪（诊断，定稿前删除） | 回调绿+挂载正常+像素黑：两处 resume 沉默跳过（Notify 的 paused-skip、Sync 的 null-child skip）与“会话活+surface在+零帧”精确同形；v4 轮 UI 侧 resumed=1 后仍黑，须看 GPU 侧 paused | resume 成功/失败只在 gfxCriticalNote 打失败分支；跳过分支零日志，Java 侧无从区分“没跑过resume”与“跑过但没画” | **已发布待验**：`0028-resume-state-trace.patch`（`nsWindow.cpp` UI 侧四处 + `CompositorBridgeParent.cpp` GPU 侧 Schedule* skipPaused 两处 + `NotifyDidRender` 计数 + `UiCompositorControllerParent.cpp` RecvResumeAndResize 结果，全走 0005 门）。v5 判 B（GPU 就绪但 content 未调度）；v7 数帧 3（blank 期）后 85s 零帧 |
| 0029 | 收紧 GPU-bind stall 包络（首跳 stall 修） | foreign 首跳 30s stall：GPU 跨包 bind 黑洞（请求出、AMS 零记录、永不 settle）× C++ 3 次 launch 尝试，每次吃满 10s watchdog 才 SW fallback（v18 时间线 00.8/10.8/20.8/30.8 严丝合缝；self 同包秒级故 3s） | watchdog 10s 与 C++ WaitUntilConnected 对齐但只给黑洞送钱；健康 bind 均 ≤3s（self ~1s），误杀只提前进 SW-WR（渲染正确已证） | **构建中**：`0029-gpu-stall-envelope.patch`（`GeckoProcessManager.java` BIND_TIMEOUT 10s→3s + `StaticPrefList.yaml` max_launch_attempts 2→1；包络 30s→6s）。预测：commit total 43s→~15s，渲染不变（仍 SW fallback），self 零影响（timer 秒级取消）。用户拍板“时间全部改小”；Chromium 无此洞（webview_zygote 同包 fork，秒级 fallback）故 stock 3s |
| 0035 | handoff worker 解毒（wedged transact 不再毒化单线程） | SNC-fresh 黑屏：fresh GPU 上第二个 surface transact 永不返回（GPU 无 ENTER），单线程 worker 永久卡死 → 后续 handoff 全排队（只有 barrier 超时）→ 每次 layout 主线程重 park（ANR 链）→ sync IPC 超时杀 GPU → 永久黑，reopen 照 wedge | transact 的线程模型藏在 C++ worker 里，Java 侧无 detach 监听、无干预点，只能下沉 | **待验**：`0035-handoff-worker-decouple.patch`（1 commit：transact 搬 detached per-handoff 线程，worker 只打 send 即返；max-seq merge 语义不变；设计文档 `0035-handoff-worker-decouple.md`）。预测：全部 seq send→finish、resumed=1、example PageStart、像素验 |
| 0036 | ResumeAndResize 改 async（M5-A 的 IPDL 腿） | 0035 后 worker 不毒化，但 sync `ResumeAndResize` 照卡主线程 ~10s → reply timeout 杀 GPU → 且本机 GPU 重建失败（`Failed to launch after 2 attempts`），kill=行刑；reopen 沿同条 sync 路照死 | ack 只被打日志，从无恢复作用；Java 侧无回调，只能下沉改协议（0023 同构） | **待验**：`0036-resume-async.patch`（.ipdl sync→async + Child/Parent 去 out-param + nsWindow 两处 fire-and-forget + sync-messages.ini 摘条目；设计文档 `0036-resume-async.md`）。预测：零 wait timeout、零杀 GPU、零 ANR、example PageStart、像素验 |

设计文档：`0001/0002/0003/0006/0007/0008/0009/0010-*.md`（树内勘察、地基选型、边界、测试计划）。

可重现性（2026-09-27 实测补记）：0001→0010 按编号 `git apply` 到 pin 点
（0008–0010 未做逐 commit 树哈希核对，待下次全量重放时补核）。

顺序依赖（2026-10-07 clean-room 全栈重放实锤，`git am` 逐个验证）：
0020 的后两个 commit（0020e file-reads 镜像 + 0020f file 进程隔离）必须
落在**完整 0024a-g 之后**——0020e 锚定的 `allowImages` 键与 child 处理是
0024 建的（0024 编号在后、实际先写）。按编号直打会在 0020e 报冲突；
正确顺序是 0001–0019 → 0020-01 → 0024a-g → 0020e/f → 0021–0023，
最终树内容等价（零手改），仅 commit 落点与编号错位。

## 工作流

```bash
cd ~/sinytra-vol/Projects/firefox        # U+F8FF 卷名，见 Pin 节
git checkout sinytra-pin-158             # 158 线，唯一活跃线
export MOZCONFIG="$HOME/sinytra-vol/Projects/firefox/mozconfig-158"
export MOZBUILD_STATE_PATH="$HOME/sinytra-vol/Projects/mozbuild"
export PATH="$HOME/.cargo/bin:$PATH"
#（逐个应用/修改 patch 后）
./mach build binaries                    # 增量；新增 GeneratedJNI 头先 build export
./mach gradle geckoview:publishDebugPublicationToMavenRepository
# 本地产物 artifactId=geckoview-default；substitute 脚本换 nightly 坐标
cd ~/sinytra-vol/Projects/Sinytra
export MOZCONFIG="$HOME/sinytra-vol/Projects/firefox/mozconfig-158"  # 必带：
# 默认 mozconfig 指向已删的 objdir-opt，缺了它 substitute 脚本的
# `mach environment --verbose` 失败，provider 配置阶段报缺 compileSdk
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  ./gradlew :provider:assembleDebug -PsinytraLocalGecko=true
# 真机回归：CanaryRenderActivity 金丝雀 + GlueHarnessActivity 全量探针（现 41 PASS）
```

环境备忘：rust 用 rustup 工具链（homebrew rustc 缺 android target 的
std；`~/.cargo/bin` 已放 rustc/cargo/rustdoc shim，构建 PATH 需前置）。
后台跑 mach 时**不要给命令设短超时**（libxul 链接 >10min，超时会静默
杀掉链接且管道吞退出码——2026-09-25 踩过）；管道后必须显式 `echo $?`
核对 mach 退出码。

JS patch（`.sys.mjs` 等 omni 内容）验证铁律（2026-10-03，0018 三小时
乌龙）：`mach build binaries` 与 `mach build` 都不重打 omni；唯一重打
路径是 `make -C objdir-158/mobile/android/installer stage-package`
（packager.py `--format omni` 直出 `dist/geckoview/assets/omni.ja`）。
且**验 omni 内容必须解包查条目，不许裸搜二进制**：`dist` 那份是
store（字符串可见），AAR/APK 链压出来的是 deflate（字符串被压缩藏
住）——裸搜会误判"没进包"（0018 教训：四份全有， verdict 不动另有
原因）。标准动作：
`python3 -c "import zipfile; z=zipfile.ZipFile(<omni>); print(<marker> in
z.read(<entry>).decode())"`，逐级验 dist → merge 中间件 → AAR →
APK（APK 里 omni 本体又是一层 zip，先解外层再解内层）。

升级 Firefox 版本时：逐个 rebase，冲突不解决不许升级（AGENTS.md §5）。
2026-09-25 153→158 重放实录：9 commits 仅 1 冲突
（`ParentChannelListener.cpp` include 区重排），`GeckoSession.java`/
`components.conf`/`widget moz.build`/JS 两处全部自动合并。

## 日志纪律（2026-09-25 定，0005 落地后校正）

插桩日志的目标是**排查**：debug 构建要多打（入口/出口/关键分支），release
构建不启用。机制按层（以 0005 实际落地为准）：

- C++：一律走 `SINYTRA_LOG` 宏，**运行时** pref `sinytra.log.enabled` 门
  （默认 false，首调缓存）。不用 `#ifdef DEBUG`：AAR debug/release 变体共用
  同一个 libxul，编译期门区分不了 provider 变体；
- 树内 Java（geckoview 模块）：该模块自己的 `BuildConfig.DEBUG` 门；
- provider Java：`src/debug` sourceSet 隔离探针；运行期判定用
  `ApplicationInfo.FLAG_DEBUGGABLE`（AGP 9 不再生成 BuildConfig）；
- JS：`debug` 级别（0001 收尾已把 dump 降级）；
- 开关：provider debug 构建经 `src/debug/assets/geckoview-config.yaml`
  打开 `sinytra.log.enabled`，release 不带该配置。

tag 一律 `Sinytra/<模块>`；上游共享文件里的插桩必须在定稿时收敛到上述门内
或删除，不许裸奔进 release。
