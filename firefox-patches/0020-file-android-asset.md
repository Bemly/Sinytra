# 0020 — file:///android_asset/ 解析到宿主 APK（file:// 身份保留）

> 解决哪个 WebView API：`loadUrl(file:///android_asset/...)` 及一切
> file:///android_asset/ 子资源（img/iframe/XHR）。CTS WebSettingsTest
> file 族 4 项全以 `but was:<null>` 挂（页面根本没加载）。
> 为什么 public API 不够：`file:///android_asset/` 是 Android 虚拟路径，
> necko 无此概念（`netwerk/protocol/file/` 内零引用）——字面取
> `/android_asset/...` 本地路径，ENOENT 后静默死（onLoadRequest 之后无
> PageStart/PageStop/error，history 恒 blank；实测见 §1）。GeckoView 无
> asset 解析原语；file 通道不走 0001 的 HttpBaseChannel 咨询点，Java 侧
> 够不着（universal 前缀只有 "http"，且 nsFileChannel 从不咨询）。
> 上游 bug：未提（普通 embedder 不加载 file:///android_asset/）。

## 1. 症状（2026-10-03，WebSettingsTest 串行单测）

`testLocalImageLoads / testIframesWhenAccessFromFileURLsEnabled /
testXHRWhenAccessFromFileURLsEnabled / testAccessAllowFileAccess`（asset 半）
全部 `expected:<...> but was:<null>`——连静态 `<title>` 都没到达。
logcat（datadir 同构，asset 同理）：

```text
Sinytra/navigation: onLoadRequest file:///data/.../datadir.html
Sinytra/response: filter observer registered parent=1
GeckoSession: handleMessage GeckoView:PageStart uri=file:///...   (+1.4s)
... silence: no PageStop, no onLoadError, history stays about:blank ...
```

等待门被 blank-finish 配对竞态漏过（见 §4 遗留），断言读到空标题。

## 2. 根因链（两条，互不覆盖）

1. **`file:///android_asset/*`：虚拟路径，无人解析。** Chromium 的
   WebView 把它映射到宿主 APK 的 assets；Gecko 取字面路径
   `/android_asset/webkit/...` → ENOENT。APK 本体 0644 world-readable，
   child 有权读——缺的是映射，不是权限。
2. **`file:///data/...`（app 私有文件）：权限。** tab child 以 provider
   UID 运行，`drwx------` 的对方 uid 目录不可读。provider 与 app 同 UID，
   可读——已由 provider 侧 `FileBridge` 合成（`178fd50`，data: 身份；
   该路径断言全是内容断言，够用）。本 patch 不碰这条（见 §5 边界）。

排除记录：

- **LoadPolicy 误拦**：`blockNavigation` 对 asset 豁免
  （`!allowFileAccess && !asset`），LoadRequest 侧 ALLOW 已验证——
  死点在 necko 之后，不在决策层。
- **0001 filter 缺 file 前缀**：即使加上，nsFileChannel 也不咨询
  `nsINetworkInterceptController`（只有 HttpBaseChannel 查）——
  加前缀无用，不加。

## 3. 修复（C++，nsFileProtocolHandler::NewChannel 分流）

`file:///android_asset/<rest>` → 从**宿主 APK** zip 读
`assets/<rest>` 字节 → `NS_NewInputStreamChannel(fileURI, stream,
contentType)` 合成——**file: 身份保留**（originalURI 就是传入的
file URI；iframe `location.href`/XHR origin 语义不变，无 jar: 换
scheme 的 origin 风险）。

- 宿主 APK 路径（**开放项，动手前先定**）：`:tab` 等 provider-UID
  子进程被多宿主共享，host APK 不能是进程全局量。候选：
  ① `sinytra.host.apk` pref（app 进程下发；但 pref 也是进程/全局
  级，多宿主同机覆盖——除非每宿主独立 child set，尚未证实）；
  ② per-session 设置透传（`sessionContextId` 同构：Java key → parent
  模块 → 子进程复刻 → file handler 按 channel 归属取；粒度对，
  管道大）。先实证 file 通道拓扑（parent/child 哪侧建通道、
  ENOENT/EACCES 哪一种）再选——勿盲写。
- MIME：C++ 侧小表（html/png/jpg/gif/css/js/txt，余
  application/octet-stream）。
- 安全：zip 条目命名空间内查找，`../` 逃逸天然失败（无此条目 →
  正常 404 错误通道，顺带终结今日的"静默死"）；
  只读 `assets/` 前缀，不碰 APK 其他条目。
- 缺文件 → 正常失败通道（onLoadError/PageStop(false)），不再静默。

## 3b. 实施记录（2026-10-04，以终稿为准，弯路只记结论）

- **宿主归属（开放项已关闭）**：pref 方案（bundle key + 双进程
  `Services.prefs` 镜像）证伪——content 子进程的
  `setStringPref` 静默失效（同 handler 写后立刻读不到，5/5；
  读正常；仅未声明 pref 存疑，decalred 的 strict pref 写有效）。
  actor 自检（sendQuery）死（当时无父 actor）；父 actor 推送
  （actorCreated）可达但追不上首通道（50ms 级 race）。
  **终解：进程 env**（`SINYTRA_HOST_APK`，provider 经
  `GeckoRuntimeSettings.extras` 的 `envN` 注入——MOZ_LOG 同款通道；
  `Os.setenv` 到不了 zygote-fork 的子进程，实证父有效子空）。
  C++ 经 `getenv` 同步读取，无 race、无 IPC、无 pref 依赖。
- **UAF 实锤 + 修复**：`nsIZipReader` 局部量导致返回后首读即
  parent SIGSEGV（fault 0x0；tombstone 符号化到
  `CheckAllowLoadByTriggeringRemoteType` 内联帧是误导——真凶是
  释放后的 archive 读；nsJARChannel 用 thunk 持 reader 即先例）。
  修法：通道创建时排干进内存（`CopyData`，32MB 上限回落），
  reader 当场释放，通道只拥有字节。
- **子帧 CONTENT_BLOCKED（0x805E0006）根因链**：
  file 子帧在 `web` 进程 content 触发 →
  `CheckAllowLoadByTriggeringRemoteType` 拒（`IsWeb()` 分界；
  对应 bug 1538028 权限模型）→ `DisconnectListeners` 静默死。
  解法两件套（缺一不可，均已验证）：
  ① `browser.tabs.remote.separateFileUriProcess=true`（file 文档进
  专用 `file` 进程；Android 默认 false 是省进程的刻意选择，我方在宿主
  进程内经 `onSettingsUpdate` 强制打开——只影响我方宿主，不碰 Fennec；
  只读 file 场景零成本）；
  ② `security.fileuri.strict_origin_policy` 按导航翻转（读需要同源；
  `sinytraFileReadsAllowed` bundle 键 = fileFromFile||universal，
  双进程 pref 镜像；declared pref 子进程写有效）。
- **LoadPolicy 矩阵**（JVM 锁）：asset 主帧永远放行；asset 子帧仅当
  file reads 允许才放行（否则 Disabled 变体无从拦起）；
  非 asset file 子帧守 master 开关。三处豁免口径统一见单测。
- **诚实缺口（XHREnabled）**：`file:///data` app 私有区子资源——
  子进程 EACCES，且 file 通道无 0001 式咨询点（provider 读不到字节，
  也无处可替）。要根治需 file 通道级拦截管线（content→parent 咨询 +
  合成/重定向，0020 后续项）；单测无覆盖，暂记 gap，不当 flake 追。

## 4. 测试计划

- CTS 单测串行：`testLocalImageLoads`（48）、
  `testIframesWhenAccessFromFileURLs{Enabled,Disabled}`、
  `testXHRWhenAccessFromFileURLs{Enabled,Disabled}`、
  `testAccessAllowFileAccess`（asset 半；datadir 半靠 FileBridge）。
- 回归：全量 harness（file 分流只在 android_asset 前缀触发，
  http/data 不受影响）+ JVM（LoadPolicy 矩阵 + env/health 锁）。
- 遗留（本次不修，记账）：① 真失败的错误面——今日 file 死是静默的，
  0020 后缺文件走正常错误通道，验证时顺带确认；② blank-finish 配对
  在"真 load 永不到"时漏放行等待门（§1 机理），待错误面可观测后复核
  是否需要收紧。

## 5. 边界（不做）

- `file:///data/...` app 私有文件：FileBridge 已覆盖（`178fd50`）。
- `content://`：无 CTS 需求，不碰。
- 子资源相对路径解析：base URL 仍是 file:///android_asset/...，
  相对引用天然成立，无需 base 注入。

## 6. 验证状态（2026-10-04）

- `testLocalImageLoads`、`testAccessAllowFileAccess`、
  `testIframesWhenAccessFromFileURLs{Enabled,Disabled}`、
  `testXHRWhenAccessFromFileURLsDisabled` 全绿（串行；含 per-navigation
  strict 镜像 + file 进程强制打开的阳性验证）。
- `testXHRWhenAccessFromFileURLsEnabled`：honest gap（§3b）。
- 本 patch 未定稿：file 全绿 + 全量 harness 回归后，再标定稿。
