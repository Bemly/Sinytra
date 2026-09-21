# STATUS — Sinytra

> 实现进展与待办（给新会话的交接页）。技术细节见 `ARCHITECTURE.md` /
> `API_MAPPING.md` / `BOOTSTRAP.md`，阶段定义见 `ROADMAP.md`。
> 更新时间：2026-09-21 深夜。设备：MOONDROP MD-PH-001 / Android 14 / API 34。

## 1. 当前位置

- **P-1：通过**（结论①：bootstrap 可行）。`GeckoRuntime.create`（~190ms）→
  libxul 加载（~30ms，`GeckoThread RUNNING`）→ child service bind → P0Render
  完整渲染 example.com（含进度 15→55→100 + `onPageStop success=true`），截图验证通过。
  约束见 `BOOTSTRAP.md` §1（vendor launch 故障见 §3）。
- **P0 glue：8/8 通过（连续两轮 P0 GLUE PASS，2026-09-21 22:37/22:39）**。
  `P0GlueActivity` harness（真机反射注入 `WebView + PrivateAccess`，绕开
  framework 切换）：`PASS provider construct / createWebView type /
  page1 finished / page1 progress-url / page2 finished / canGoBack /
  goBack finished / backForwardList size=2 / settings roundtrip`，
  `title1=Example Domain`。copy=0 根因已定性（见 §2a）：Gecko 有 history
  只是没及时 flush，`flushSessionState()` 后 `size=2 index=0`。
- DuraSpeed 总开关已关（见 §3），child bind 连续两轮 `0 failed binds`、
  `duraspeed block` 计数 0。工作区提交到 `976a8b0`（flush 诊断版）。

## 2. 下一步（按顺序，一次做一件）

1. **P0 生产修复（flush 落地 glue）**：harness 证明了 flush 能救回 history，
   但生产路径 `copyBackForwardList()` 不能依赖调用方先 flush——bridge 应在
   `onPageStop(success)` 后自动 `flushSessionState()`，让后续 copy 读到新鲜
   snapshot。改完重跑 P0Glue（flush 分支应走不到，直接首读 size=2）。
2. **P0 收尾**：glue 全绿后，把 `P0RenderActivity / P0GlueActivity /
   BootstrapProbeActivity` 三个探针 activity 退役或移到 `tests/`（别进出货 APK）；
   `ROADMAP.md` P0 验收打勾（loadUrl/reload/stop/goBack/goForward/canGo×2/
   url/title/progress + 三个 client 回调 + 基础 settings）。

## 2a. copy=0 诊断矩阵（先 flush，后 hidden-View A/B）

- GV153 实测（AAR 反编译）：`GeckoSession.flushSessionState()` 存在，无需
  attach `GeckoView` 即可调；`HistoryDelegate` 三方法全是 interface default
  （缺省 `onVisited/getVisited` 返回 null 即默认处理，`onHistoryStateChange`
  缺省空实现）——visited 应答与 `StateUpdated` 是独立分支。
- `GeckoView:StateUpdated → mStateCache.updateSessionState`：有
  `HistoryDelegate` 走 History handler，无则 Progress handler 接手更新 cache；
  `SessionState` 本身即 `HistoryList`（`size()`/`getCurrentIndex()` 直读）。
- 所以 `size=0 index=-1` 只有四种可能：① Gecko 没发带 historychange 的
  StateUpdated；② 发了但 bridge 没存住；③ 存住了但 copy 读的是旧快照；
  ④ headless 没及时 flush。P0Glue 已加三路打印（HistoryList / SessionState /
  snapshot）+ `flushSessionState()`，一次运行即可定性：

```text
HistoryList=2, SessionState=2, copy=0 → Sinytra bridge/cache bug
HistoryList=0, SessionState=2         → HistoryDelegate wiring 问题
HistoryList=0, SessionState=0         → Gecko 没 flush，再测 hidden GeckoView
挂 hidden GeckoViewHost 后变 2       → 才能证明 headless/active 是根因
```

- 顺序：先 `flushSessionState()` 看 SessionState 是否变 2（信息量最大的一步）；
  只有 flush 后仍 0，才上 hidden `GeckoViewHost` 做 A/B。
- **实测结论（2026-09-21 22:37/22:39，两轮一致）**：goBack 的 `onPageFinished`
  时刻三路全空（`live=null state=null snapshot=size=0`），`flushSessionState()`
  后 Gecko 立刻补发 `onSessionStateChange size=2` + `onHistoryStateChange size=2`
 （`urls=[example.com/, example.org/]`），post-flush 三路全 `size=2 index=0`。
  定性：**HistoryList=0 + SessionState=0 → flush 后全 2 = Gecko 有 history
  只是 headless 没及时 flush**；HistoryDelegate wiring 正常（flush 后立刻收到），
  不是 bridge/cache bug。hidden-View A/B 不必做。生产修复：在
  `onPageStop(success)` 后自动 flush（见 §2 下一步 1）。
3. **P1 开工**：按 `ROADMAP.md` §3 逐项认领（CookieManager 落地 P2 patch 前先保持
   honest-default；权限/文件选择/下载/SSL/HTTP Auth/WebStorage/geolocation/
   查找/打印）。

## 3. 已知阻塞：vendor launch 故障（测试环境问题，非 glue bug）

- 现象：偶发 `loadUrl` 卡 `about:blank` 30s 超时；logcat 配对出现
  `AiuiAmsExt: duraspeed block ... bringUpServiceLocked` +
  `ActivityManager: Unable to launch app ... : process is bad`；Gecko 侧
  `ServiceAllocator: 0 successful binds` + `BindException: Cannot connect`。
- **文案修正**：`ActiveServices` 只要 `startProcessLocked()` 返回 null 就统一
  打印 `process is bad`——这句日志≠已命中 `mBadProcesses`，只是“AMS 没能给
  Service 起进程”（DuraSpeed veto 同样走这句）。模型是双路：AOSP
  bad-process（background bind + bad 名单→null；显式启动才
  `resetProcessCrashTime` + `clearBadProcess`，两者是不同操作）与 MTK
  DuraSpeed vendor veto 都能让 `startProcessLocked` 返回 null。
- 最强证据（排除纯 bad 名单解释）：卸载重装换新 UID 后首启仍
  `duraspeed block` 失败——`mBadProcesses` 按 `processName+uid` 查询，
  旧 UID 残留命中不了新 UID，至少这次失败的根因是 vendor veto。
  重装/重启后首启 100% 复现 crashhelper 被拦，指向 DuraSpeed。
- 之前误判：①“重启是必需步骤”——错。重启只是清 `system_server`/AMS
  内存态（`mBadProcesses` + vendor 调度状态），暂时恢复，不代表 GeckoView
  要求重启。P0 不以重启为正常条件，目标是 DuraSpeed 排除后连续跑十几轮
  不 reboot。②“force-stop 能清 bad”——错。`forceStopPackage` 只调
  `resetProcessCrashTime`（清计数器），不清 `mBadProcesses`；且
  `package` 主进程显式启动清的也是主进程名，不等于清 `:gpu/:tabN`。
  禁 `com.mediatek.duraspeed` 包无用（hook 在 `system_server` 内）；
  Doze 白名单/`deviceidle disable` 无用（管心跳不管 bring-up）。
- Fennec 同机同症状，证实非我方回归；其“强停+清缓存+点图标恢复”不能倒推
  标准行为（前后台判定窗口/vendor whitelist/manifest 配置都可能不同）。
- 下次失败先分支再动手：`dumpsys activity processes` 看有无 `:gpu/:tabN`
  进 `Bad processes` + logcat 看 `DuraSpeed|AiuiAmsExt|Unable to launch`：
  有 block 无 bad→vendor 问题；有 bad→AOSP 问题；都有→vendor 先杀 child
  再被推进 bad、两套机制放大。P0 期间优先把测试包加 DuraSpeed whitelist/
  后台无限制（DuraSpeed 本职就是限后台 service），而不是反复重装猜状态。
- **已解决（2026-09-21 深夜，不重启，无重装）**：root 起 DuraSpeed 界面
  （`su -c 'am start -n com.mediatek.duraspeed/.DuraSpeedMainActivity'`，
  shell 直接起会 `SecurityException: not exported`）→截图确认
  “前台优先模式”总开关开着→`input tap` 关总开关（uiautomator 复核
  `checked=false`）。另把测试包写进 `app_list.db`（`status 0→1`，root
  替换 + `force-stop com.mediatek.duraspeed` 生效，DB 语义待定，总开关是
  主因）。效果：whitelist 前 `duraspeed block` 28 条 + 全 `0 successful
  binds`；关总开关后两轮 `block` 计数 0、`successful binds` 全过、
  P0Render 完整渲染（progress 15→55→100）、P0Glue 连续两轮 PASS。
  **“必须重启”彻底证伪**：同一开机会话内从全失败到全 PASS。
  注意：这是测试环境手段，量产 ROM 仍需 vendor 电源管理白名单（BOOTSTRAP）。
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
