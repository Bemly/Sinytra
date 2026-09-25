# CTS — Sinytra

> WebView 相关 CTS 的跑法与验收（ROADMAP.md §5 的落地页）。定稿 2026-09-23，
> 2026-09-26 按唯一部署路线收敛：**只在切换后的调试机上 `am instrument` 直跑**
> （root + AnyWebView 切换，见 `BOOTSTRAP.md` §2）；不走 ROM、不以 Linux 宿主
> tradefed 为门槛。CTS 版本：**android-cts-14_r7**（API 34 对齐真机）。

## 1. 环境约束（实测确认）

- CTS 官方包（cts-tradefed）只发 linux_x86，macOS 上无法原生跑 harness——
  所以不用 tradefed，直接从官方包抽测试 APK 在设备上跑。
- **包按设备 ABI 分两份**：`android-cts-14_r7-linux_x86-x86.zip`（x86 设备/
  模拟器）与 `android-cts-14_r7-linux_x86-arm.zip`（arm 真机，~9.7GB）。
  本机真机用 **arm 包**（x86 包里 CtsWebkitTestCases 只有 x86_64 APK，实测确认）。
- WebView 模块在 14_r7 叫 **`CtsWebkitTestCases`**（没有 `CtsWebViewTestCases`，实测）。
- 被测对象 = 设备当前 system WebView：跑 Sinytra 轮前必须已按 `DEVICE.md` §5
  切换并复核 `dumpsys webviewupdate` 的 Current。

## 2. 跑法（`am instrument` 直跑官方用例本体）

```bash
# 1) 抽 APK（zip 内 android-cts/testcases/，现场以 unzip -l 为准）
unzip android-cts-14_r7-linux_x86-arm.zip 'android-cts/testcases/CtsWebkitTestCases*'
# 2) 直跑
adb -s V885Q49L8TAMFEEE install -r CtsWebkitTestCases.apk
adb -s V885Q49L8TAMFEEE shell am instrument -w android.webkit.cts/androidx.test.runner.AndroidJUnitRunner
# 3) 清场
adb -s V885Q49L8TAMFEEE uninstall android.webkit.cts
```

设备准备清单（每次跑前）：
- 亮屏解锁、`settings put system screen_off_timeout 600000`；DuraSpeed 总开关关；
- 语言 en-US、关闭自动同步/定位弹窗；
- `settings get global hidden_api_policy` 保持默认（provider 属 hidden API 合法
  调用方，不要全局放松）；`getenforce` = Enforcing。

- 产出：instrumentation stdout 的逐用例结果，汇总到 `docs/STATUS.md`，每条 fail
  归因三选一（Gecko 语义差异 / 未实现 / 上游 bug）。**不是官方 CTS 通过率**
  （缺 tradefed 的设备准备与结果聚合），但跑的是官方用例代码本体。
- 可选扩展模块：`CtsPermission2TestCases` 中 WebView 相关包（如需要再抽）。

## 3. 验收顺序（对齐 ROADMAP §5）

1. Chromium 基线轮（已完成，§4）→ 参照系；
2. Sinytra 对照轮：切换路线主线化并通过真实路径验收（`BOOTSTRAP.md` §2.4）后，
   在同一台机上切到 Sinytra 跑同一套用例；
3. 以基线为分母：基线已 fail 的 4 条不计回归，其余每条 fail 逐条落档。

## 4. Chromium 基线轮实测（2026-09-23 04:5x）

`CtsWebkitTestCases`（14_r7 arm 包）直 `am instrument`：**285 用例 4 失败
（98.6%）**，用时 ~10.4 分钟。4 条 fail 全部在系统 Chromium 上同样出现
→ 环境归因，非 provider 语义：

- `GeolocationTest.testSimpleGeolocationRequestAccept{Always,Once}`
  （JS didn't get position ×2——真机定位服务未开）；
- `WebViewTest.testSetNetworkAvailable`（ConnectivityManager 依赖超时）；
- `WebViewTest.testCanInjectHeaders`（Referer 未达——CTS 本地测试
  服务器/缓存行为，无 tradefed 设备准备的已知依赖）。

**该 98.6% 就是 Sinytra 对照轮的基线参照系**；4 条 fail 在 Sinytra 轮不计入回归。
注：基线轮当时的 Current 包/版本没记录（09-20 档案是 `com.google.android.webview`
154）；设备现 Current 已是 `org.bromite.webview`（`DEVICE.md` §3）。复跑基线时
先切到 `com.google.android.webview` 并把包名+版本记进本节。
