# 0036 — ResumeAndResize 改 async（M5-A 的 IPDL 腿）

> 状态：**实现中**（firefox `sinytra-pin-158`，待真机 verdict）。
> 前置：0035（worker 解毒，опыт 已验：send 继续、finish 走 detached tid）。
> 0023（Pause async）の姊妹 patch，同手法、同理由。

## 1. 定性（device-verified，SNC-fresh 黑屏四轮 + 0035 轮）

0035 之后 worker 不再毒化，但死法换了个位置：nav-time surfaceChanged →
transact 照 wedge（GPU 侧无 ENTER）→ 0022 barrier 2s 超时 → 主线程进 **sync**
`ResumeAndResize` 卡 ~10s（watchdog 栈、ANR 链）→ reply timeout 杀 GPU →
`resumed=0`。且杀完 **GPU 重建失败**（`Failed to launch GPU process after
2 attempts`，整轮零新 GPU 出生）——kill 在这台机上不是恢复，是行刑；
永久黑。reopen 照 wedge（新 session 同一条 sync 路）。

## 2. 改动（0023 同构）

- `PUiCompositorController.ipdl`：`sync ResumeAndResize(...) returns (bool)`
  → `async ResumeAndResize(...)`；`sync-messages.ini` 摘条目。
- Child/Parent 两侧签名去 `aOutResumed`（`SendCachedValues` 同改）；
  Parent 侧保留 `ForceIsFirstPaint` + `NotifyClientSizeChanged` +
  `ResumeCompositionAndResize` 调用与 GPU resume log。
- `nsWindow.cpp` 两处（`NotifyCompositorCreated`、
  `SyncResumeResizeCompositor`）：fire-and-forget + dispatched 日志；
  `resumed==false → RequestNewSurface` 整段删除（kill 后它产不出新
  surface pass，device-verified 无下文）。
- `Resume()`（sync）不动：`SyncResumeCompositor` 路未涉案，diff 最小。

## 3. 为什么安全（0023 同款三段论）

- barrier（0022）仍在 async 发送之前跑：GPU 拿到最新 surface 才谈 resume，
  首 present 语义不变。
- channel 保序：Pause（async 在前）→ ResumeAndResize（async 在后）不乱序。
- ack 无人读：两处旧 caller 只拿它打日志 + 触发已被证伪的
  RequestNewSurface。真丢 resume 由下一次 surfaceChanged 重驱
  （第三方宿主 layout 频繁；静态布局 + 丢 resume 的组合认栽，比
  kill-everything 罕见一个量级）。

## 4. 预测（verdict 标准）

- SNC fresh 点 example：全部 seq send→finish（ms 级或 detached 慢 finish）、
  **零 `wait timeout`、零杀 GPU、零 ANR**、example PageStart→PageStop、像素验。
- 允许：单个 transact 照 wedge（GPU 忙）——只要主线程 2s 内返回、
  后续 handoff 照 finish、页照出，即为修好（transient 自愈正是设计目标）。
- 若仍黑：看卡在哪节——barrier 后无 `RecvResumeAndResize` = GPU 真死
  （转 GPU 重建调查）；有 Recv 但无帧 = content 侧（转 frameloader 方向）。

## 5. 风险与回滚

- 单文件族 7 处，纯机械；IPDL regen 由构建覆盖，编译即验签名对齐。
- 回滚 = revert 单 commit + 重编（AAR 快照 -27 之前行为）。
