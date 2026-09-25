# DEVICE — Sinytra

> 调试机档案、目标 API、切换流程（实测，已验证）。真机调试前读本文件。
> 这台机就是唯一部署形态的样机：root + LSPosed + AnyWebView，不刷机
> （AGENTS 顶部、`BOOTSTRAP.md` §2）。

## 1. 设备档案

| 项 | 值 |
|---|---|
| 设备 | MOONDROP MD-PH-001 |
| adb serial | `V885Q49L8TAMFEEE`（所有 adb 命令一律加 `-s V885Q49L8TAMFEEE`） |
| Android 版本 | 14（`ro.build.version.release=14`，codename REL） |
| API 等级 | 34（`ro.build.version.sdk=34`） |
| build 类型 | `user`，`release-keys`，`ro.debuggable=0`（production build，**不刷、不改**） |
| root 方式 | Magisk（`/system/bin/su -> ./magisk`）；`adb root` 不可用，`adb shell su -c id` 可拿到 `uid=0(root)` |
| Xposed | LSPosed v1.11.0 (7209)，zygisk 版（Magisk 模块 `zygisk_lsposed`） |
| WebView 切换模块 | AnyWebView **v1.3**（`com.thinkdifferent.anywebview`，versionCode 4）；作用域 = 系统框架 |
| SELinux | `Enforcing`（2026-09-26 实测）。机上装有 `Disable_SELinux`/`selinux_mode` 模块但当前未生效；验收一律在 Enforcing 下做，`getenforce` 先核 |
| 电源管理 | MTK DuraSpeed「前台优先模式」总开关**必须关**（否则 Gecko child bind 被 veto，见 `STATUS.md` §3） |

## 2. 目标 API 与 validity 约束（定稿）

- `targetSdk = 34`（对齐这台调试机 Android 14）；`compileSdk` 跟随 pin 的
  GeckoView 构建链要求（158 线 = 37.2，见 §4）。
- Provider APK 的 `targetSdkVersion` 必须 ≥ 33（TIRAMISU）：`dumpsys
  webviewupdate` 显示 `Minimum targetSdkVersion: 33`。
- `versionCode` 必须 ≥ `Minimum WebView version code`（本机 **647807131**，
  user build 强制；过低 → `Invalid ... reason: Version code too low`，
  机上 `com.huawei.webview` 就是这么被拒的）。
- manifest 必须带 `com.android.webview.WebViewLibrary`（= `libxul.so`），
  否则 AnyWebView 不会把包列进候选、`WebViewUpdateService` 也判无效。
- `minSdk` 按 GeckoView 要求定（GeckoView 底线 Java 17），
  不许低于 GeckoView nightly pin 版本的 minSdk。

## 3. 机上现有 WebView（2026-09-26 `dumpsys webviewupdate` 实测）

- Current / Preferred：`org.bromite.webview` 108.0.5359.156（经 AnyWebView 放行）。
- Valid：`org.bromite.webview`、`com.google.android.webview.canary` 156.0.8072.0、
  `com.android.webview` 126.0.6478.246、`com.google.android.webview` 155.0.8059.4。
- Invalid：`com.huawei.webview` 12.1.3.373（Version code too low）。
- 已装 `org.mozilla.geckowebview.debug`（master 构建）但**不在列表里**——master
  manifest 还没有 `WebViewLibrary` metadata（切换路线尚未主线化，`STATUS.md` §2）。

## 4. 构建与 adb 固定流程

```bash
adb -s V885Q49L8TAMFEEE devices -l          # 确认在线
adb -s V885Q49L8TAMFEEE shell echo shell_ok # shell 通路
adb -s V885Q49L8TAMFEEE shell \
  "getprop ro.build.version.release; getprop ro.build.version.sdk; getenforce"

# 需要 root 时：不用 adb root，用 Magisk su
adb -s V885Q49L8TAMFEEE shell "su -c id"
adb -s V885Q49L8TAMFEEE shell "su -c 'dumpsys webviewupdate'"

# 构建（master 必须带本地 Gecko，见 BOOTSTRAP §3）+ 安装（debug 带 .debug 后缀）
./gradlew :provider:assembleDebug -PsinytraLocalGecko=true \
  && adb -s V885Q49L8TAMFEEE install -r provider/build/outputs/apk/debug/provider-debug.apk
```

> 构建环境（2026-09-25 校准）：`JAVA_HOME=/opt/homebrew/opt/openjdk@17`，
> Gradle wrapper 9.4.1 + AGP 9.2.0，`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
>（platforms 34/36/37.0–37.2 + build-tools 34–36），
> `MOZBUILD_STATE_PATH=~/sinytra-vol/Projects/mozbuild`，`PATH` 前置 `~/.cargo/bin`。
> `compileSdk = 37.2` 跟随 GeckoView 158 构建链（android-components/.config.yml）；
> `targetSdk = 34` 不变（对齐本机）。

- 需要读 framework 侧状态一律先 `dumpsys webviewupdate`，再看 logcat
  （tag 前缀 `Sinytra/<模块>`），不要猜。

## 5. 切换 / 回滚流程（唯一部署路线）

```bash
# 0) 前置：LSPosed 里 AnyWebView 已启用、作用域勾「系统框架」（改作用域需重启）
# 1) 装 provider 后确认进了 Valid 列表
adb -s V885Q49L8TAMFEEE shell "su -c 'dumpsys webviewupdate'" | grep -A12 'WebView packages'
# 2) 切换（等价「开发者选项 → WebView 实现」）
adb -s V885Q49L8TAMFEEE shell cmd webviewupdate set-webview-implementation <provider 包名>
# 3) 复核 Current/Preferred 均为我方
adb -s V885Q49L8TAMFEEE shell "su -c 'dumpsys webviewupdate'" | head -12
# 回滚（切换是全局的，系统 App 也立刻用 Sinytra——跑前先确认这条可用）
adb -s V885Q49L8TAMFEEE shell cmd webviewupdate set-webview-implementation com.google.android.webview
```

- 重装 provider（`install -r`）会让 WebViewUpdateService 重新评估，装完再核一次
  Current；新包若判无效（如 versionCode 低于下限）会自动回落到其他 Valid 包。
- 不需要系统签名、不需要预装、不改 framework；不许为此刷机或改 `/system`。
