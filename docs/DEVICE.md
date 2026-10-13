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

## 3. 机上现有 WebView（2026-09-26 深夜切换后 `dumpsys webviewupdate` 实测）

- Current / Preferred：`moe.bemly.geckowebview.debug` 0.2.0-gv158
  （versionCode 999158001；切换验收见 `STATUS.md` §1s）。回滚：
  `cmd webviewupdate set-webview-implementation com.google.android.webview`。
- Valid：我方 debug 包、`org.bromite.webview` 108.0.5359.156、
  `com.google.android.webview.canary` 156.0.8072.0、
  `com.android.webview` 126.0.6478.246、`com.google.android.webview` 155.0.8059.4。
- Invalid：`com.huawei.webview` 12.1.3.373（Version code too low）。
- 旧 applicationId 的 `org.mozilla.geckowebview.debug` 可卸载。

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

> 构建环境（2026-10-14 实地核验：本机即构建机，无外置卷/`sinytra-vol`）：
> `JAVA_HOME=~/Projects/mozbuild/jdk/jdk-17.0.20.1+1/Contents/Home`（Temurin 17），
> Gradle wrapper 9.4.1 + AGP 9.2.0，
> `ANDROID_HOME=~/Projects/mozbuild/android-sdk-macosx`（platforms 34 + 37.2），
> `MOZBUILD_STATE_PATH=~/Projects/mozbuild`，`PATH` 前置 `~/.cargo/bin`
> （另有 `~/.gradle/gradle.properties` 把 toolchain 指到 mozbuild JDK）。
> `compileSdk = 37.2` 跟随 GeckoView 158 构建链（android-components/.config.yml）；
> `targetSdk = 34` 不变（对齐本机）。旧 `/opt/homebrew` + `~/sinytra-vol`
> 路径已失效，勿用。

- 需要读 framework 侧状态一律先 `dumpsys webviewupdate`，再看 logcat
  （tag 前缀 `Sinytra/<模块>`），不要猜。
- **截图纪律（2026-09-27 拍板）**：判断设备画面必须间隔多截几张
  （≥3 张、间隔 4–5s），首帧加载/child 慢启动时单张截图必误判；
  首次页面加载等 30s+ 再核对回调日志。

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

## 6. 测试卫生与 reboot 纪律（2026-10-03 拍板）

- **reboot 是诊断工具，不是常规回归步骤；当前阶段没有必要主动重启。**
  常规卫生按梯度来：双 force-stop（CTS + provider uid，顺带清其 child）
  → `su -c 'echo 3 > /proc/sys/vm/drop_caches'` → 静置数分钟；
  健康看 `MemAvailable`（`free` 列误导），<2G 不开 verdict 轮。
- 只有具备明确实验目的才 reboot（例如：force-stop 已排除单进程
  状态，需区分"跨进程持久态"与"确定性缺陷"——2026-10-03 白屏
  诊断即此例；或改动 kernel/driver 需重初始化）。reboot 需在
  STATUS/设计文档留下目的 + 前后对照，否则不做。
- 全仓审计：没有任何文档要求以重启为常规步骤（BOOTSTRAP §1 与
  STATUS §3 早已证伪"必须重启"；LSPosed 改作用域需重启是 Android
  机制要求，与本条无关）。
- **reboot 前必须先请示用户（2026-10-04 硬约束）**：本机重启/开机要电源键
  手动确认，远端 `su -c reboot` 可能把设备扔在 USB 之外回不来
  （2026-10-03 夜实锤）。Agent 不得自行 reboot；需要时说明理由等拍板。

## 7. 内存清腾与 shell 泄漏纪律（2026-10-03 实测）

> 起因：`MemAvailable` 跌到 ~1G，force-stop + drop_caches 纹丝不动。
> 根因：机上 shell 命令审计（`content://moe.bemly.kokoro.provider`）给
> **每次 `adb shell` 调用留一个 ~90MB 的 `app_process` 僵尸**（`ps` 可见
> 上百个，`AnonPages` 7.7G）。清掉即回 8.5G——真正的内存大户是调试
> 行为本身，不是被测对象。

- **批量原则**：一个目的只用一次 `adb shell`，多命令用 `;` 串起来；
  循环里调 `adb shell` 必须 `< /dev/null`（它会吞掉循环的 stdin，
  2026-10-03 扫表循环只跑一轮即退出的教训），且循环体禁用逐项 shell。
- **每次手机测试前必清进程（强制步骤）**：任何上机（单测/探针/复现/screencap）
  前，先跑 §7 清僵尸命令 + 常规双 force-stop（CTS + provider uid），再看
  门限。不是"内存看着还行就跳过"——僵尸按调用次数线性涨，一轮串行扫表
  （50+ 次 shell）不中途清一次就会把后面 verdict 毒掉。
- **每次手机测试前必删旧包（强制步骤，与清进程同级）**：装包前先
  `pm list packages` 核对——旧 applicationId（`org.mozilla.geckowebview.debug`）、
  过期测试包（旧 CTS/harness/MiniWV）有则 `uninstall` 再装；跑后清场删测试包
  （CTS 例见 `CTS.md` §2）。防 Valid 列表/签名/数据残留污染 verdict
  （旧包旧 child 曾毒掉 filter 下发链，见 `STATUS.md` §1v）。
- **SNC verdict 轮用用户手法（2026-10-14）**：`force-stop` 连点两次 +
  清 SNC 存储空间和缓存（`pm clear com.snc.test.webview2`，连 Gecko
  profile 一起清，真正 fresh）再开测——只清进程不清存储的轮次不算数。
- **verdict 轮前必清僵尸**（一次调用，防自杀写法——朴素
  `pkill -f kokoro.provider` 会连本次会话一起杀掉，本次会话的
  logger 同样匹配，退出码 137）：

```bash
adb -s V885Q49L8TAMFEEE shell "su -c 'for p in \$(pgrep -f kokoro.provider); do if tr \"\0\" \" \" < /proc/\$p/cmdline 2>/dev/null | grep -q pkill; then continue; fi; kill -9 \$p 2>/dev/null; done; sleep 2; cat /proc/meminfo | grep MemAvailable'" < /dev/null
```

- **不碰**：`lspd`（切换依赖）、`system_server`、`systemui`、
  `zygote64`、`webview_zygote`、`phone`、adbd。只清审计僵尸 +
  常规双 force-stop；GMS/messaging/vending 等停后 ~60s 会自动复活，
  不要追杀（它们不是大头）。
- 门槛不变：`MemAvailable` ≥ 2G 才开 verdict 轮（本轮实测 1.07G →
  8.5G）。`MemAvailable` 看这一行，不要看 `free`。
  2G 是**实验健康门（reproducibility gate），不是产品运行最低要求**：
  它不是因果阈值；以后调
  成 2.5G/1.8G 不影响任何技术结论。
- 不为"健康"主动 reboot（§6 纪律）：轻量 hygiene 建健康窗口即可，
  除非某轮实验明确要验证跨进程/系统状态。
- **临时截图当轮删（2026-10-10 用户立规）**：`screencap`/`uiautomator dump`
  pull 回本地亲眼验证后，`/sdcard` 上的临时图当轮 `rm` 掉；只删本轮自建的，
  历史文件与其他目录一律不动。
