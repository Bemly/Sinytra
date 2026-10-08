# 0026 — ChildConnection.bind() 有界化（启动路径 Binder 黑洞的 watchdog）

> 状态：实现完成待验证（sibling commit → format-patch 导出 → 本地 build →
> publish → provider 重编 → harness 回归 → 酷安死轮捕获）。
> 上游 bug：[1994984](https://bugzilla.mozilla.org/show_bug.cgi?id=1994984)
> （GPU launch 超时 5s→10s，Android 高发）、
> [1991502](https://bugzilla.mozilla.org/show_bug.cgi?id=1991502)
> （跨 runtime 单例 GPU 绑定撞 owner，repeat-until-timeout）、
> [1880503](https://bugzilla.mozilla.org/show_bug.cgi?id=1880503)
> （bounded-failure 哲学：超时后恢复/重建）、
> [1673953](https://bugzilla.mozilla.org/show_bug.cgi?id=1673953)
> （Fission 进展压制，同族不同层）。

## 1. 问题（真机实锤，非推测）

第三方宿主冷进程里，`ServiceAllocator $gpu updateBindings` 出现一次后一切停止：
无 tab bind、无成功、无失败、无 fallback、无子进程出生、无 AM 痕迹
（`-b system/events` 双 buffer 零 bind 记录）、父 Gecko 主线程 futex 睡零 CPU、
Java→C++ JNI 直调可达（C++ observer 注册成功）、所有经 EventDispatcher 的
JS 消息永不到（install ack/filter/存储/PageStart 全死，零 `No listener` 行）。

调用链（`sinytra-pin-158 @ 1887be4f95` 行号）：

```text
GPUProcessManager.cpp:450 EnsureGPUReady → LaunchGPUProcess
→ GeckoChildProcessHost.cpp:749 AsyncLaunch → … → LaunchAndroidService
→ java GeckoProcessManager.start() :848 (:865 runOnLauncherThread, 立返)
→ startInternal :1004 → getConnectionForStart → ChildConnection.bind() :266
→ bindService() == true → mPendingBind 永 pending（死因）
→ onBinderConnected 永不到 → mHandlePromise 永 pending → C++ Then 链永不跑
→ mProcessState 永留 CHANNEL_INITIALIZED（无 ++PROCESS 日志、无子进程）
→ WaitUntilConnected 的 10s 计时器在下游空转（attempt++，静默 DestroyProcess）
→ Java 重试是 no-op（getConnectionForStart 复用同一死连接，"Bind in progress"
  直接返回同一死承诺，无新 updateBindings、无新 bindService）
```

关键不对称：`mPendingBind` settle 只认两条路——`onServiceConnected` 回调，
或 `bindService()` 同步 false（`completeFailedBind` 的 `Log.e`）。`bindService()
== true` + 回调永不到 = 永久 pending，无日志、无异常、无重试（`retry`
只认 error，不认超时）。Java 段**零计时器**（全文件 grep 验证）。

绿轮对照（同机）：bind 健康 → 子进程出生 → `WaitUntilConnected` 10s 超时×3 →
`Failed to launch GPU process after 3 attempts` → SW-WR fallback → 全链恢复
（31s 之谜即 3×10s）。绿轮的故障在计时器覆盖范围内；死轮死在计时器上游。

## 2. 为什么 provider 侧修不了

bind 承诺的 settle 点全在 Gecko 树内（`ChildConnection.bind/onBinderConnected/
completeFailedBind` + `start/retry/handleBindError`）。provider 侧能观测到的
只有"无回调"（guard 熄火分诊已覆盖到 give-up），无法从外部 fail 掉 Gecko 内部
的 promise，更无法触发它的 retry。必须在承诺的生产点加计时。

## 3. 设计（H1，唯一推荐）

`ChildConnection.bind()` 新建 `mPendingBind` 后起一次性 10s 计时
（`ThreadUtils.postToUiThreadDelayed` + 回调内 hop 回 launcher，保证
`assertOnLauncherThread`）。到期仍 `mPendingBind == 本次实例 && mChild == null`
则：先 `unbindService()`（**顺序不可换**：先摘除 AM 侧请求，否则重试撞上
`Bind in progress` 重蹈 no-op 陷阱；`unbindService()` 自带 defunct/死绑定保护），
再以 `ServiceAllocator.BindException("...bind timed out")` 异常完成承诺——
直接复用现有 `start→handleBindError→retry (MAX_RETRIES=3)→fromRetryLog→
C++ OnProcessLaunchError→attempts++→DisableGPUProcess→SW-WR fallback` 全链，
**零新恢复逻辑**。

- 常量 `BIND_TIMEOUT_MS = 10_000`：与 C++ 单次 `WaitUntilConnected` 对齐，
  最坏包络 ≈ 绿轮 31s（Java 3 次 × C++ 3 次以较小者先收敛，文档写明叠加上限）。
- 取消点：`onBinderConnected` 双分支 + `completeFailedBind`（`removeCallbacks`
  同实例）。迟到 fire 靠实例比对自证 no-op（UI→launcher hop 是异步的）。
- 竞态：超时回调与正常 settle 同跑 launcher 线程，实例比对即互斥，无锁。
- 迟到 connect（超时后远端连上）：`onBinderConnected` 仍设置 `mChild`（承诺
  已被置 null，`mPendingBind == null` 分支跳过 complete）——下次 `bind()` 直接
  返回该 child，反而获救。收敛方向正确，不用特殊处理。

次钩（不做，记录）：`GPUProcessManager.cpp:439-442` 后台门静默 abort；
`GPUProcessHost.cpp:240-253` `CompleteInitSynchronously` 无界 `task.Wait()`。
死轮到不了 connect，两处都不在本次路径上。

## 4. 验收（device-verified 标准）

1. 健康回归：harness 全绿 + P0 GLUE（provider 进程 bind 健康路径零行为差——
   计时器只在 settle 前到期才开火，健康 bind 秒级完成即取消）。
2. 模拟黑洞（若可复现）：`bindService==true` 永不回叫 ⇒ T+ε 内出现第二次
   `updateBindings` 行（Java 重试建新连接的指纹）⇒ 有界总时间内 blank
   PageStart + SW-WR（与绿慢轮可见序列一致）。
3. 酷安死轮捕获：fresh 轮若再死，log 必须出现 `Bind timed out after` + 重试
   `updateBindings` + 最终 fallback 或恢复；"永久静默"签名从此作废。

## 5. 回滚

revert 本 patch（单文件 `GeckoProcessManager.java`）。行为开关未做（常量即
文档； spanning 定时器只在 bind 未 settle 时存在，健康路径零残留）。
