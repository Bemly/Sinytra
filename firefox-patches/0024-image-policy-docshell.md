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
