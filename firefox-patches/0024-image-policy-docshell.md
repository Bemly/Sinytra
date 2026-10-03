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

## 7. 验证状态（2026-10-03）

- `testLoadsImagesAutomatically_blockLoadingImages`：**OK**（确定性门）。
- `testLoadsImagesAutomatically_loadImagesWithoutReload` /
  `testBlockNetworkImage`：逻辑侧已全绿（images-off 断言经 sanitize+hold；
  JVM 175 锁），剩余失败全部是设备容量型 flake（load 持续 ~15，
  child tab 进程 `Timeout executing service`，导航在 LoadRequest 后零回调；
  健康窗口下同路径通过）。待健康窗口补 verdict。
- 本 patch 未定稿：verdict 补齐 + 全量 harness 回归后，再标定稿。

## 8. 设备对照实验（2026-10-04，image 族 verdict 被迫暂停）

- T1（`blockLoadingImages`，此前绿 3 次）在同一设备上连挂两次，失败形态与
  T2/T3 完全一致（首个 loadData 在 LoadRequest 后零回调）。此前绿、未改
  相关代码——纯环境退化，与逻辑无关。
- 设备状态：重启后 load 依然 ~15（非堆积，是开机即有；CPU 全闲、Mem 7G），
  系 vendor D 态线程基线（STATUS §1v 旧判）。`Timeout executing service
  ...tabN` + 新 tab 进程出生后仍无提交。
- 结论：当前设备做不了 verdict 轮。等自然恢复（静置/日常使用后 load 回落）
  或用户决策后再跑；不再为此改代码、不再自行 reboot（DEVICE §6 + 请示约束）。
