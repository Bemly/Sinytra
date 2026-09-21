# STATUS — Sinytra

> 实现进展与待办（给新会话的交接页）。技术细节见 `ARCHITECTURE.md` /
> `API_MAPPING.md` / `BOOTSTRAP.md`，阶段定义见 `ROADMAP.md`。
> 更新时间：2026-09-21 晚。设备：MOONDROP MD-PH-001 / Android 14 / API 34。

## 1. 当前位置

- **P-1：通过**（结论①：bootstrap 可行）。`GeckoRuntime.create`（~190ms）→
  libxul 加载（~30ms，`GeckoThread RUNNING`）→ child service bind → P0Render
  完整渲染 example.com（含进度 15→55→100 + `onPageStop success=true`），截图验证通过。
  约束见 `BOOTSTRAP.md` §1（vendor bad-process 问题见 §3）。
- **P0 glue：7/8 通过**。`P0GlueActivity` harness（真机反射注入
  `WebView + PrivateAccess`，绕开 framework 切换）在干净环境跑出：
  `PASS provider construct / createWebView type / page1 finished /
  page1 progress-url / page2 finished / canGoBack / goBack finished`，
  `title1=Example Domain`。**唯一挂的是最后一步 `copyBackForwardList`
  （`size=0 index=-1`）**，修了但还没在干净环境验证（见 §2）。
- 工作区干净（已提交到 `3c39d45`），构建 `BUILD SUCCESSFUL`。

## 2. 下一步（按顺序，一次做一件）

1. **拿 `P0 GLUE PASS`**：手机重启→亮屏解锁→`P0RenderActivity` 先跑确认 child
   起来→立刻跑 `P0GlueActivity`。预期：含 `backForwardList size>=2` 全绿。
   相关修：`e1459c9`（live `HistoryList`）+ `3c39d45`（补 `onVisited/getVisited`
   应答，`GeckoView:StateUpdated` 状态机才往下走）。
   命令：
   ```bash
   adb -s V885Q49L8TAMFEEE shell am start -S -n \
     org.mozilla.geckowebview.debug/org.mozilla.geckowebview.provider.P0GlueActivity
   ```
   判 pass：logcat `Sinytra/p0glue` 出现 `P0 GLUE PASS`。
2. **P0 收尾**：glue 全绿后，把 `P0RenderActivity / P0GlueActivity /
   BootstrapProbeActivity` 三个探针 activity 退役或移到 `tests/`（别进出货 APK）；
   `ROADMAP.md` P0 验收打勾（loadUrl/reload/stop/goBack/goForward/canGo×2/
   url/title/progress + 三个 client 回调 + 基础 settings）。
3. **P1 开工**：按 `ROADMAP.md` §3 逐项认领（CookieManager 落地 P2 patch 前先保持
   honest-default；权限/文件选择/下载/SSL/HTTP Auth/WebStorage/geolocation/
   查找/打印）。

## 3. 已知阻塞：vendor bad-process（环境问题，非 glue bug）

- 现象：非重启首跑时 `loadUrl` 卡 `about:blank` 30s 超时；logcat 配对出现
  `AiuiAmsExt: duraspeed block ... bringUpServiceLocked` +
  `ActivityManager: Unable to launch app ... : process is bad`；Gecko 侧
  `ServiceAllocator: 0 successful binds` + `BindException: Cannot connect`。
- 根因（已读 android14-release 源码确认）：
  `ActiveServices.bringUpServiceInnerLocked` → `ProcessList.startProcessLocked`
  遇 `Intent.FLAG_FROM_BACKGROUND` 先查 `AppErrors.isBadProcess(processName, uid)`，
  命中直接返回 null、不 fork。Gecko child 永远走后台 bind（`BIND_AUTO_CREATE` +
  `BIND_IMPORTANT`，见 GV153 `ServiceAllocator.bindServiceDefault`），永远带
  `FLAG_FROM_BACKGROUND`。
- “bad” 的写入：`AppErrors.handleAppCrashLSPB`——进程短时间内再 crash
  （`now < crashTime + MIN_CRASH_INTERVAL`，默认 2min）或超
  `PROCESS_CRASH_COUNT_LIMIT`（默认 12/12h）即 `markBadProcess`。
  触发器是 MTK DuraSpeed：每次 service 拉起都被 `AiuiAmsExt` 拦截，
  偶发杀掉正在启动的 child（crashhelper 首当其冲，重装/重启后首启 100% 复现）。
- **关键修正**（之前误判过）：`force-stop` 清不掉——`forceStopPackage(doit=true)`
  只调 `resetProcessCrashTime`（清 crash 计数器），**不清 `mBadProcesses` 名单**；
  `clearBadProcess` 全 AOSP 仅一处调用：在 `startProcessLocked` 的显式启动分支
  （无 `FLAG_FROM_BACKGROUND`）里。child 永远后台 bind → 永不清 → 死锁，
  只能重启（内存态清零）。禁 `com.mediatek.duraspeed` 包无用（hook 在
  `system_server` 内）；Doze 白名单/`deviceidle disable` 无用（管心跳不管 bring-up）。
- Fennec 同机同症状（冷启动也报 `process is bad`），证实非我方回归；
  用户手动“强停+清缓存+点图标”能恢复 Fennec，主进程显式启动走了清除分支。
- 调试纪律：保持亮屏解锁（Doze+锁屏冻心跳、截图全黑属正常）；
  先看 `am_proc_start ... :<child>` 是否出现，再看 denials；
  跑 harness 前先跑 `P0RenderActivity` 确认 child 起得来（金丝雀）。

## 4. 已实现文件速览

```text
provider/src/main/java/org/mozilla/geckowebview/
├── provider/
│   ├── GeckoWebViewFactoryProvider.java  # WebViewFactoryProvider 实现 + Statics
│   ├── GeckoWebViewProvider.java         # WebViewProvider 实现（P0 导航 live，其余 loud-todo）
│   ├── GeckoRuntimeHolder.java           # 宿主进程级 GeckoRuntime 单例（UI 线程 create）
│   ├── GeckoBackForwardList.java         # SessionState/HistoryList → WebBackForwardList 转译
│   ├── CompatWebSettings.java            # android.webkit.WebSettings facade
│   ├── P0GlueActivity.java               # P0 E2E harness（反射注入 WebView+PrivateAccess）
│   ├── P0RenderActivity.java             # P0 渲染探针（GeckoView + loadUri，直显）
│   ├── BootstrapProbe.java / BootstrapProbeActivity.java  # P-1 六项探针
├── session/  (GeckoSessionBridge + NavigationBridge + ProgressBridge)
├── settings/ (GeckoWebSettings 纯状态 + toSessionSettings)
├── storage/  (GeckoCookieManager honest-default + GeckoWebStorage→StorageController)
├── view/     (GeckoViewHost：bind/release，不进 session 依赖)
framework-stubs/  # compileOnly 的 android14 hidden API stubs（WebViewFactoryProvider/
                  # WebViewProvider/WebViewDelegate/WebViewFactory/PacProcessor/
                  # TokenBindingService；createWebView 参数 erase 到 Object，见其 README 注释）
```

构建：Java 17 + Gradle wrapper 9.4.1 + AGP 9.2.0，`compileSdk=36`（GeckoView 153
构建链要求）/`targetSdk=34`（对齐真机），GV 153 stable 已 pin（`155+` 要
compileSdk 37.1，本地 SDK 快照未暴露，暂不升）。详见 `DEVICE.md` §4。
