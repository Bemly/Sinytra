# 0035 — surface-handoff worker 解毒（decouple transact 脱离单线程）

> 状态：**实现中**（firefox `sinytra-pin-158`，待真机 verdict）。
> 上游缺口：GV 无 surface-handoff 超时/恢复原语（0005/0021/0022 只是观测+有界等待），
> 本 patch 是第一个 Gecko 侧行为修正。

## 1. 定性（device-verified，SNC-fresh 黑屏三轮 + 无 prewarm 对照轮）

- fresh GPU 出生数秒内，若来了一次 surfaceChanged（SNC 启动 resize / 导航期
  layout），`CompositorThread` 单线程 worker 里的 Binder transact
  `RemoteCompositorSurfaceManager.onSurfaceChanged` **永不返回**（GPU 侧连
  0021 `remote ENTER` 都没有；同一条路 2 秒前 10ms 走完）。
- 后果链（全在共用代码里）：0022 barrier 2s 超时 → 主线程进 sync
  `ResumeAndResize` 卡 ~10s → IPDL 超时杀 GPU → `resumed=0` →
  `RequestNewSurface` 后无 surface 重驱 → 零 PageStart，永久黑。
- **单线程毒化**：worker 卡死后，后续所有 handoff runnable 排队永不执行
  （只有 barrier 超时、无 send）→ 之后每次 layout 主线程重 park →
  ANR 连爆 →"软件卡死"。一次 wedge，永久毒化。
- provider 侧无作案工具（129 提交筛空：无导航期碰 surface 代码；死轮新机制
  零开火；prewarm 开关对照轮死法逐帧复刻 → 洗脱）。reopen 恢复被证伪
  （新 session 照 wedge）——病根在 worker transact 本身。

## 2. 为什么 public API 不够

Binder transact 的超时/线程模型藏在 C++ worker 里，Java 侧（`CompositorController`
全是 package 方法、无 detach 监听）既看不到 wedge，也干预不了。只能下沉。

## 3. 改动（`gfx/layers/ipc/UiCompositorControllerChild.cpp`，单文件）

`QueueSurfaceHandoff` 保持：seq 分配（调用线程）+ barrier（2s，有界）+
`sHandoffCompleted` max-merge（乱序后到的新 surface 本来就 supersede 旧的，
语义不变）。只改 transact 的执行位置：

- worker runnable 只打 send log，然后把 transact 扔给 **detached per-handoff
  线程**（`std::thread` + daemon attach 靠 generated JNI stub 内
  `GetEnvForThread` 自举；`RefPtr self` + `Surface::GlobalRef` 保活；
  `PR_SetCurrentThreadName("HandoffTxn")` 留尸检名），自己立即返回。
- transact 线程内：finish log → monitor max-merge 通知 → 慢/失败
  `gfxCriticalNote` → 失败走原 `NotifyRemoteActorDestroyed`，一字不改。

效果：一次 wedge 只死一个 detached 线程，worker 永不堵塞；
GPU 缓过来后，后续 handoff 照常 finish → barrier 通过 → ResumeAndResize
成功 → commit。GPU 真死时退化到现有行为（超时→杀→重建），不更坏。

不碰：0022 barrier 时限、IPDL 10s 超时与杀 GPU（上游安全行为，动它风险大，
记 follow-up）；transact 本体语义；日志门（0005 pref / 0021 注释不动）。

## 4. 风险

- 线程/handoff：surfaceChanged 低频，detached 线程开销可忽略；shutdown 期
  在途 transact 持 RefPtr/GlobalRef 自保，monitor 是进程静态。
- 乱序完成：max-merge 本来就处理（旧 surface 迟到被新覆盖，正确）。
- `mProcessToken` 跨线程写：现状已是 worker 线程写，主线程读 IPDL 回调；
  本 patch 只是换一条后台线程，同等暴露。

## 5. 测试（独立）

- 设备 verdict（SNC fresh，cache-buster）：全部 seq send→finish、
  `resumed=1`、example PageStart→PageStop、像素验；此前三轮死法的
  逐项反转（无 timeout、无杀 GPU、无 ANR）。
- 回归：canary 冷启动 + 反射 harness（GlueHarness/FrameworkEntry）。
- provider 侧零改动（本 patch 纯 Gecko）。
