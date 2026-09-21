# DEVICE — Sinytra

> 调试机档案与目标 API（实测，已验证）。真机调试前读本文件。

## 1. 设备档案

| 项 | 值 |
|---|---|
| 设备 | MOONDROP MD-PH-001 |
| adb serial | `V885Q49L8TAMFEEE`（所有 adb 命令一律加 `-s V885Q49L8TAMFEEE`） |
| Android 版本 | 14（`ro.build.version.release=14`，codename REL） |
| API 等级 | 34（`ro.build.version.sdk=34`） |
| build 类型 | `user`，`release-keys`，`ro.debuggable=0`（production build） |
| root 方式 | Magisk（`/system/bin/su -> ./magisk`）；`adb root` 不可用，`adb shell su -c id` 可拿到 `uid=0(root)` |

## 2. 目标 API（定稿）

- `compileSdk / targetSdk = 34`（对齐这台调试机 Android 14）。
- Provider APK 的 `targetSdkVersion` 必须 ≥ 33（TIRAMISU）：Android 14 的 AOSP
  `WebViewFactoryProvider.isCompatibleImplementationPackage()` 要求
  `targetSdkVersion >= MINIMUM_SUPPORTED_TARGET_SDK(33)`，实测机上
  `dumpsys webviewupdate` 显示 `Minimum targetSdkVersion: 33`。
- `minSdk` 按 GeckoView 要求定（GeckoView 底线 Java 17），
  不许低于 GeckoView nightly pin 版本的 minSdk。

## 3. 机上现有 WebView（2026-09-20 实测）

- 当前在用：`com.google.android.webview` 154.0.8037.22（targetSdk 36）。
- 另装：`com.google.android.webview.canary` 155.0.8055.0（targetSdk 37）、
  `com.android.webview` 126.0.6478.246（targetSdk 34）。
- 切 provider 前后一律用 `dumpsys webviewupdate` 确认生效。

## 4. 调试方法（这台机固定流程）

```bash
adb -s V885Q49L8TAMFEEE devices -l          # 确认在线
adb -s V885Q49L8TAMFEEE shell echo shell_ok # shell 通路
adb -s V885Q49L8TAMFEEE shell \
  "getprop ro.build.version.release; getprop ro.build.version.sdk"

# 需要 root 时：不用 adb root，用 Magisk su
adb -s V885Q49L8TAMFEEE shell "su -c id"
adb -s V885Q49L8TAMFEEE shell "su -c 'dumpsys webviewupdate'"

# 日常装 Provider 调 P-1/P0（applicationId 带 .debug 后缀）
./gradlew :provider:assembleDebug \
  && adb -s V885Q49L8TAMFEEE install -r provider/build/outputs/apk/debug/provider-debug.apk
```

> 构建环境（2026-09-21 实测）：`JAVA_HOME=/opt/homebrew/opt/openjdk@17`，
> Gradle wrapper 9.4.1 + AGP 9.2.0，`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
>（platforms 34/36/37.1 + build-tools 34.0.0）。
> `compileSdk = 36` 纯为满足 GeckoView 153 的构建链；`targetSdk = 34` 不变（对齐本机）。

- 本机是 `user` build（非 `userdebug/eng`），没有 emulator 那种“忽略 provider
  签名检查”的便利；要进“开发者选项 → WebView 实现”切换，
  provider APK 需按系统 provider 要求签名/预装，不许靠改 framework 签名检查绕过。
- 需要读 framework 侧状态一律先 `dumpsys webviewupdate`，再看 logcat
  （tag 前缀 `Sinytra/<模块>`），不要猜。
