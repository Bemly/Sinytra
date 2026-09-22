# CTS — Sinytra

> WebView 相关 CTS 的跑法、过渡方案、验收标准（ROADMAP.md §5 的落地页）。
> 定稿 2026-09-23。CTS 版本：**android-cts-14_r7**（API 34 对齐真机，
> 官方包 9.7GB，`dl.google.com/dl/android/cts/android-cts-14_r7-linux_x86-x86.zip`）。

## 1. 环境约束（实测确认）

- CTS 官方包（cts-tradefed）**只发 linux_x86**，macOS 上无法原生跑 harness。
- **包按设备 ABI 分两份**：`android-cts-14_r7-linux_x86-x86.zip`（x86 设备/
  模拟器）与 `android-cts-14_r7-linux_x86-arm.zip`（arm 真机，~9.7GB）。
  本机真机用 **arm 包**（x86 包里 CtsWebkitTestCases 只有 x86_64 APK，
  实测确认）。
- 本机（MOONDROP MD-PH-001）是 `user release-keys`——CTS 正是为 user build
  设计的，设备侧无障碍；缺的是 Linux 宿主。
- 本机 macOS 无 Docker。两条路任选：

| 路线 | 宿主 | 设备连接 | 备注 |
|---|---|---|---|
| A（正式） | Linux 物理机/CI box | USB 直连 | 标准 cts-tradefed，结果 XML 官方可比 |
| B（本机过渡） | macOS 原生 | USB/adb | 不用 tradefed：从官方包抽测试 APK，`am instrument` 直跑（见 §3） |

## 2. 正式跑法（路线 A，Linux 宿主到位后执行）

```bash
# 宿主（Linux x86_64, JDK 17, aapt/adb 已装）
unzip android-cts-14_r7-linux_x86-x86.zip && cd android-cts/tools
./cts-tradefed
# tradefed 控制台内：
run cts -m CtsWebViewTestCases --serial <device>
```

设备准备清单（每次跑 CTS 前）：
- 亮屏解锁、设定 `settings put system screen_off_timeout 600000`；
- 语言 en-US、关闭自动同步/定位弹窗（CTS 用例自带大部分假设）；
- `adb -s V885Q49L8TAMFEEE shell settings get global hidden_api_policy` 保持默认
  （WebView provider 属 hidden API 合法调用方，不要全局放松）。

WebView 相关模块清单（ROADMAP §5 "WebView 相关用例全量"的范围界定）：
- `CtsWebViewTestCases`（核心，`android.webkit` 全量行为用例）
- `CtsPermission2TestCases`（WebView 权限相关包）
- `CtsWebkitAliasTestCases`（如存在）
- 目标：每模块跑完记 pass/fail 率 + 每条 fail 归因三选一
  （Gecko 语义差异 / 未实现 / 上游 bug），逐条记入 `docs/STATUS.md`。

## 3. 过渡跑法（路线 B，已定稿，本轮执行）

不跑 tradefed，直接从官方 zip 抽出测试 APK 用 instrumentation 直跑——
**跑的是官方 CTS 用例代码本体**，缺的只是 tradefed 的设备准备与结果聚合：

```bash
# 1) 抽 APK（zip 内 android-cts/testcases/）
unzip android-cts-14_r7-*.zip 'android-cts/testcases/CtsWebViewTestCases.apk'
# 2) 直跑（x86_64/arm64 按 testcases 内动态分 APK，现场以 unzip -l 为准）
adb install -r CtsWebViewTestCases.apk
adb shell am instrument -w android.webkit.cts/androidx.test.runner.AndroidJUnitRunner
```

- 产出：instrumentation stdout 的逐用例结果，人工汇总到 `docs/STATUS.md`
  （fail 逐条归因）。**不算官方 CTS 通过率**，只作 Gecko 差异的先行摸底。
- 跑完 `adb uninstall android.webkit.cts` 清场。
- 注意：本机真机当前装的是系统 Chromium WebView；**要测 Sinytra 需先按
  BOOTSTRAP §2.1 把 provider 预装/签名成系统实现并切换**——过渡跑分两轮：
  第一轮对 Chromium 基线（校准用例与环境的通过率），第二轮对 Sinytra
  （需 ROM 阶段条件，见 §4 顺序）。

## 4. 验收与顺序（对齐 ROADMAP §5）

1. 路线 B 第一轮（Chromium 基线）→ 记录模块级通过率作参照系；
2. Sinytra 对照轮等 ROM 阶段（BOOTSTRAP §2.1 overlay + provider 预装）；
3. 路线 A 全量 = P2 出货门槛；fail 归因三条渠道
   （Gecko 语义差异 / 未实现 / 上游 bug）逐条落档。
