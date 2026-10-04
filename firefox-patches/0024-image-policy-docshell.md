# 0024 — loadsImagesAutomatically for non-network images (docshell-driven)

> 解决哪个 WebView API：`WebSettings.setLoadsImagesAutomatically(false)` /
> `setBlockNetworkImage` 对 data:/file: 图片的语义。CTS WebSettingsTest
> image 族（`testLoadsImagesAutomatically_blockLoadingImages`、
> `testLoadsImagesAutomatically_loadImagesWithoutReload`、`testBlockNetworkImage`）
> 在 data-URL 图片上失败。
> 为什么 public API 不够：GeckoView 无图片开关原语；0001 咨询点只挂在
> `HttpBaseChannel` 上，`nsDataChannel`/`nsFileChannel` 永不咨询——
> `LoadPolicy.blockSubresource` 对 data:/file: 子资源够不着（0020 同构结论）；
> `nsDocShell.allowImages`（IDL 已有）全树无写者，`ImageBlocker` 只认全局
> pref 且只看 http(s)。
> 上游 bug：未提（普通 embedder 不需要 WebView 图片语义）。

## 1. 症状（2026-10-03，真机 CTS 单测）

title-hold 落地后（`GeckoSessionBridge` finish-hold，JsTitleProbe G/H
titleLagMs 501→1ms，失败从 stale-`""` 前进到真值），
`testLoadsImagesAutomatically_blockLoadingImages` 仍 FAIL：

```text
expected:<[0]> but was:<[1]>  (WebSettingsTest.java:837)
```

即 `setLoadsImagesAutomatically(false)` 后 `DATA_URL_IMAGE_HTML`
（内联 `data:image/png`）仍被加载（title = naturalHeight = 1，而非 0）。
同因：`loadImagesWithoutReload` 的 data 半。`blockNetworkImage` 的 data
半（期望加载，"1"）当前恰好通过——修完后必须仍通过。

## 2. 根因链（两条，定位实锤）

1. **data:/file: 子资源不走 0001 咨询点。** `nsDataChannel.cpp`
   零 content-policy/ShouldLoad 引用（`HttpBaseChannel::ShouldIntercept`
   专属）；`LoadPolicy.blockSubresource` 开头的 scheme 门
   （非 http/file 直接 `return false`）对 data: 形同虚设——即使把门打开，
   necko 也从不来问。logcat 实锤：失败轮里 `Sinytra/response`
   无任何 `data:image` 咨询记录（仅 loadData one-shot 的 6 条）。
2. **Gecko 现成开关全是错的形状。**
   - `nsDocShell.allowImages`（`nsIDocShell.idl:180`，`mAllowImages`）
     全树零写者、零读者（`browser_capabilities.js` 测试除外）——write-only
     遗产位。
   - `image/ImageBlocker.cpp`（`content-policy` category，已注册，双进程
     生效）：只认全局静态 pref `permissions_default_image == DENY`（per-WebView
     语义装不下），且 `SchemeIsHttpOrHttps` 把 data: 提前放行。
   - `BrowsingContext` 无 allowImages 字段（`allowJavascript` 的
     `FIELD()` 镜像路线要动 BC 复制宏 + WindowContext + docshell 同步——
     否决，太大）。
   - 承重好消息（已核）：`imgLoader.cpp:753` 对**所有 scheme**（含 data:）
     调 `NS_CheckContentLoadPolicy` → `ImageBlocker::ShouldLoad` 必被触发，
     `aLoadInfo->GetLoadingDocument()` 在 content 进程可达 docshell。
     钩子点现成，缺的是 per-session 信号 + data: 放行。

## 3. 修复（四件，独立可验）

1. **Java（GeckoView `GeckoSessionSettings`）**：仿 `ALLOW_JAVASCRIPT`
   加 `ALLOW_IMAGES`（`initOnly=false`，默认 true）+ `isAllowImages/`  setter。
   无 IDL，无 JNI。
2. **Parent JS（`GeckoViewSettings.sys.mjs onSettingsUpdate`）**：现有
   bundle→child `"SettingsUpdate"` 通道透传 `allowImages`（加一行，
   与 `suspendMediaWhenInactive` 同构）。顺序保证：set-then-load 下
   SettingsUpdate 先于导航 commit 到达 child（FIFO），没有竞态。
3. **Child JS（`GeckoViewSettingsChild.sys.mjs`）**：缓存值；两处应用到
   `docShell.allowImages`（IDL 现成 setter）——收到 SettingsUpdate 时作用
   于当前 docshell（flag 翻转 + provider 的 mutation-reload 路径），外加
   `document-element-inserted` 观察者作用于每个新文档（根元素插入时，
   任意 `<img>` 插入/起载之前；subframe docshell 经
   `nsDocShell.cpp:2573` 继承父值，无需逐帧处理）。
4. **C++（`ImageBlocker::ShouldLoad`）**：pref 路径原样保留；新增 docshell
   路径——loadingDocument 存在且其 docshell `allowImages == false` 时，
   image 类型直接 `REJECT_TYPE`（scheme 不限，但跳过
   `chrome:/resource:/moz-*` 内部面）。loadingDocument 为 null
   （parent 侧导航等）时回退现有行为。
5. **Provider（纯 Java，无 patch）**：`GeckoWebSettings` 现有
   `mLoadsImagesAutomatically`（默认 true）→ `applyWebSettings` 加
   `settings.setAllowImages(...)` + `toSessionSettings` 映射（allowJavascript
   同款非 initOnly 键）。`LoadPolicy` 图片分支原样保留（http(s) 双保险，
   不碰工作代码）。

## 4. 测试计划

- CTS 单测串行（切换态）：`testLoadsImagesAutomatically_blockLoadingImages`、
  `testLoadsImagesAutomatically_loadImagesWithoutReload`、
  `testBlockNetworkImage`（data 半必须仍 "1"）、
  `testLoadsImagesAutomatically_dataUriImagesLoaded`（回归，必须仍 "1"）。
- 回归：全量 harness（默认 true，零行为变化）+ JVM（settings 映射往返锁；
  ImageBlocker C++ 不可 JVM 测）+ `WebSettingsTest` 整类。
- 待确认（非门槛，记账）：flag-off 时 favicon 是否被连带拦（Chromium 语义
  未核；icon 测试全在默认 true 下跑）；`blob:` 图片归属（设计按"拦"，
  CTS 无覆盖）。

## 5. 边界（不做）

- `setBlockNetworkImage` 语义不动（只管网络图，data: 照载——CTS 如此要求）。
- `LoadPolicy` scheme 门不动；http(s) 图片走双路径（policy + docshell），
   verdict 一致，不拆。
- file: 图片被顺带治好（docshell 路径天然覆盖；此前 file 通道同样不咨询，
  属同类缺口），CTS file 族无图片高度断言，记 bonus 不单验。

## 6. 真机诊断实录与路线修正（2026-10-03，决定性）

按 §3 实现并上机后，data: 图片仍加载，逐跳诊断结论：

1. **链路前四跳全通**：provider `pushSettings`→bundle→parent→child
   `SettingsUpdate(false)` 到达（child 日志实锤），`this.docShell` 写后
   回读 false，`document-element-inserted` 观察者对同 tab 文档 match 并
   写入（回读 false）。
2. **actor 生命周期是断点**：child actor 在构造 ~50ms 后 `didDestroy`
   （BC 换代丢弃旧 actor），新 BC 的 actor 因"无变化不重发"永远拿不到
   值——与 0018 的 UA 洞同构。表现为：咨询时刻读到的永远是默认 true。
   沿途另踩三坑（记死）：`nsIObserverService.addObserver` 不接受 actor
   本体（Bug 1570744，用 QI 闭包；class 字段/原型两种 QI 写法都曾被拒，
   以 SelectionActionDelegate 的闭包式为准）；`nsIDocShell` 无
   `browsingContext` 给 JS（过滤改直接比 docshell）；`GetLoadingDocument`
   是 out-param 形式。
3. **修正 v2（已落栈 0024d）**：仿 0018，在 `GeckoViewNavigation` 导航
   hook 里每次导航强制向顶 actor 重发全量 settings（`pushAllowImagesToTop`，
   顺带创建尚不存在的 actor 并刷新缓存），child 缓存 + 活 docshell 应用
   + observer 三件不变。
4. **loadData 确定性门（provider 侧，不依赖 Gecko 时序）**：loadData 文档
   的字节是 provider 手里攥着的——`LoadDataHandler.neutralizeBlockedImages`
   在 images-off 时剥 `<img>` src/srcset（只动 img 标签，script/文本原样；
   JS 后注入图片是已知缺口）；`applyWebSettings` 已透传 flag；
   loosening 时按原参数重发（`reissueLoadDataIfCurrent`，content-hash/
   canonical-key 双向校验，命中才重发否则老 reload），且 mutation
   listener 只在真正放宽时重载（收紧/同值不再注无辜导航）。
   Gecko docshell 路径保留作纵深（默认 true 下零行为变化）。

## 7. 验证状态（2026-10-04 定稿）

- image 族 4/4 绿（带间隔串行单测，同一最终构建）：
  `testLoadsImagesAutomatically_blockLoadingImages`、
  `testLoadsImagesAutomatically_loadImagesWithoutReload`、
  `testBlockNetworkImage`、
  `testLoadsImagesAutomatically_dataUriImagesLoaded`。
- 途中抓到并修好两个真 delivery 缺口（均有单测 + 真机 verdict）：
  ① loosening 经 setter 必须即时推送（此前只在导航前推，poll 永不到）；
  ② 恢复必须覆盖双 flag 放宽（此前只认 allowImages 变化，
  blockNetworkImage 放宽静默）。恢复走 provider eval（零导航）+
  child 双保险，幂等。
- 残留：1673953 型 pre-commit 静默 flake（同一门偶发，与负载/时序相关；
  trio-in-one-process 2/3 即此例）。上游定性为符合预期的 fission 行为，
  非 0024 范围；verdict 轮按标准卫生 + 串行 + 记录执行。
- 回归：全量 harness `P0 GLUE PASS`（本轮构建），JVM 174 锁全绿。

## 8. 设备对照实验（2026-10-04；2026-10-04 凌晨修正措辞）

- T1（`blockLoadingImages`，此前绿 3 次）在同一设备上连挂两次，失败形态与
  T2/T3 完全一致（首个 loadData 在 LoadRequest 后零回调）。
- 同一 APK 90 分钟内绿→挂→绿→挂。结论只到：**该回归并非输入决定性的
  deterministic failure，而是明显具有 timing/state dependence；
  产品缺陷仍不能排除**（非确定性故障一样可以是产品 bug；此前 GPU
  teardown 问题本身就是 timing-sensitive 的）。“与代码改动无关”的旧表述
  作废，改为：**目前未观察到失败与已完成 Java-side 修改存在相关性**
  （修改未消除问题 ≠ 修改无影响）。
- 设备状态：重启后 load 依然 ~15（开机即有；CPU 全闲、Mem 7G），vendor
  D 态线程基线。`Timeout executing service ...tabN` 出现过；但 Chromium
  对照同机通过（Chromium 无跨包 tab 进程模型，该对照只证明无 tab 依赖的
  架构不受影响，不定位我方病因）。
- 定位共识（有价值部分）：provider `onLoadRequest` 决策已完成，失败稳定在
  ALLOW → PageStart 之间；Java bridge 在此段无观测点；UI 健康、Current
  是我方仅排除 provider/Java 层，不证明 child 内部正常。
- 不再自行 reboot（DEVICE §6 + 请示约束）。

## 9. 2026-10-04 凌晨追查（同一 APK 绿挂交替，全部证据已收敛）

- 同一 APK：T1 在 90 分钟内绿→挂→绿→挂；探针同机 2 秒完成同形导航。
  确定性产品回归不成立（确定性 bug 不会自己好）。
- 已证伪的假说：load-15 基线（绿时也 15，无区分度）；自家进程 tab 超时日志
  （拿的是 provider 进程的，与 CTS 无关）；tab 出生慢（02:12 实例出生仅
  晚 250ms 照样挂）；tab 死亡（无 crash/tombstone，常驻 7 个）；UI 卡死
  （watchdog 零记录，主循环全程健康）；Current 回落（仍是我方）。
- Chromium 对照：同机同测试通过——但 Chromium 无跨包 tab 进程模型，对照只
  证明"无 tab 依赖的架构不受影响"，不定位我方病因。
- `warmUp()`（预起 tab 进程）试过一次：未证实有效 + 有全系统内存代价，
  已回退不合入，记候选（需冷启动 A/B 计时）。
- 当前最窄事实：LoadRequest 应答后、PageStart 前静默；tab 出生+连上；
  UI 健康；Gecko/IPC 黑盒内无观测点。下一步观测手段（未做）：
  `geckoview-config.yaml` 加 `logging.*`（opt 可用，§1m 先例），看
  content 侧 docshell/channel 在哪一步停。
- v4（relocate + 原地恢复） serve 侧 wire 可验证，restore 侧因上述挂起
  未能验证；T2/T3 verdict 仍欠。0024 未定稿。
- 下一步（已定，2026-10-04）：停止 Java glue 猜测，用 `geckoview-config.yaml`
  + `MOZ_LOG` 进入 Gecko 可观测性。要求：先定目标 module → 验证配置进入
  child → 再跑故障；分 navigation/docshell 与 child/IPC 两条线；目标是找到
  PASS 与 FAIL 时间线的第一个 diverge 点（T0 onLoadRequest(ALLOW) → T5 PageStart）。

## 10. MOZ_LOG diverge 定位（2026-10-04）：停在 real-channel 交棒之后

- 手段：`src/debug/assets/geckoview-config.yaml` 加
  `env: MOZ_LOG: DocumentChannel:5,nsDocShell:5,Process:5`（automation.md
  在树内已核；`GeckoDebugConfig` 日志确认读取；`[Child xxxx]` 日志确认进
  child；provider-only 构建即可）。
- PASS 与 FAIL 在 channel 层**完全一致**：`DocumentChannel ctor →
  DocumentChannelChild ctor → RecvRedirectToRealChannel →
  OnRedirectVerifyCallback(rv=0) → dtor`（dtor 是正常交棒）。
- **diverge 点在其后**：PASS 继续解析到 PageStart；FAIL 的 real data:
  channel 再无任何动静（`nsDataChannel` 无 MOZ_LOG 点，`nsDocShell`
  全程零输出），child 进程活着（20 秒后干净退出），UI 主循环健康，
  tab 出生+连上。父侧 `RedirectToRealChannelFinished(0)` →
  `FinishReplacementChannelSetup(0)` → `ResumeSuspendedChannel` 全绿。
- 即：parent 把棒交出去了，child 接棒后 data: 真通道静默。剩余假说只剩
  content 主循环任务级停顿（lost wakeup / sync IPC 长尾，UI watchdog 看
  不到 tab 线程）。待手段：hang 住时抓 tab child 线程栈（debuggerd 在本
  ROM 无输出是已知限制）或 parser/docshell-inner module 日志。
- 上游对照（2026-10-04，用户指认，已核实记 `SOURCES.md`）：
  bug 1673953 comment 12（m_kato）——process switching 期间
  `BrowserChild::DestroyWindow` 摘 progress listener → `NS_BINDING_ABORTED`
  丢 → 新窗口 PageStart 被压，"may seem to be a timing issue"；且单跑不换
  进程、全套跑才换（comment 13），复现靠循环（comment 11）。
  我方 loadData 逐导航换 BC/tab + ALLOW 后 PageStart 缺席，与该机制逐项
  对得上：优先按此查（fission 换进程触发条件 / listener 空窗），不再当环境问题。
- 纪律重申：verdict 轮按标准 CTS 流程走（双清 + 静置 + 串行 + 记录），
  不再为 flake 改逻辑；reboot 先请示。
