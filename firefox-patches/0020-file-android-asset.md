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

## 4. 测试计划

- CTS 单测串行：`testLocalImageLoads`（48）、
  `testIframesWhenAccessFromFileURLs{Enabled,Disabled}`、
  `testXHRWhenAccessFromFileURLs{Enabled,Disabled}`、
  `testAccessAllowFileAccess`（asset 半；datadir 半靠 FileBridge）。
- 回归：全量 harness（file 分流只在 android_asset 前缀触发，
  http/data 不受影响）+ JVM 151 锁。
- 遗留（本次不修，记账）：① 真失败的错误面——今日 file 死是静默的，
  0020 后缺文件走正常错误通道，验证时顺带确认；② blank-finish 配对
  在"真 load 永不到"时漏放行等待门（§1 机理），待错误面可观测后复核
  是否需要收紧。

## 5. 边界（不做）

- `file:///data/...` app 私有文件：FileBridge 已覆盖（`178fd50`）。
- `content://`：无 CTS 需求，不碰。
- 子资源相对路径解析：base URL 仍是 file:///android_asset/...，
  相对引用天然成立，无需 base 注入。
