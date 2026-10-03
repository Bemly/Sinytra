# 0021 — surface-handoff ENTER/EXIT trace (Phase-1 discriminator)

> 解决哪个问题：STATUS §1w 白屏——在扩展 0017 之前，先排除
> "0017 v2 async 自己把首帧 handoff 排序弄丢"的回归嫌疑，避免把
> patch 引入的回归误判成底层 GPU/Surface 根因。
> 为什么现有日志不够：v2 worker 只记慢/失败 transact；fire-and-forget
> 的 `transact()` 返回成功只证明 Binder 接受/排队，**不证明
> `RemoteCompositorSurfaceManager.onSurfaceChanged` 执行过**。
> 上游 bug：不适用（纯诊断插桩；定稿时按 0005 纪律保留或删除）。

## 1. 插桩（两处，对应一次 handoff 的两端）

- 调用方 `gfx/layers/ipc/UiCompositorControllerChild.cpp`（v2 worker 内）：
  单调 seq + `handoff send`（widgetId / surfaceNull / t_send）+
  `handoff finish`（ms / rv，替代原来只记慢路径）。
  门：0005 同构运行时 pref `sinytra.log.enabled`，tag
  `Sinytra/surface`（`Sinytra/` 前缀纪律）。
- 远端 `GeckoServiceGpuProcess.java`（`RemoteCompositorSurfaceManager.
  onSurfaceChanged`）：`remote ENTER`（widgetId / surfaceNull /
  t_enter / thread）+ `remote EXIT`（dtMs）。
  门：`BuildConfig.DEBUG`（0005 Java 纪律；release 零输出）。
- 关联键：(widgetId, surfaceNull, 单调时间戳——两侧同为
  CLOCK_MONOTONIC)。无 AIDL 改动（不值得为诊断改接口）；
  burst 下按 seq 隐含顺序 + 时间邻近配对，quiet startup 精确。

## 2. 判决表（P0Render 全新进程，GPU ON，白屏复现轮）

```text
A. transact 成功 + remote ENTER/EXIT 俱全 → handoff 已送达，
   故障在 remote/state/compositor（往 M5-A2/A3 状态恢复查）。
B. transact 成功 + remote ENTER 缺席 → handoff/ordering/lifecycle
   路径（往 v2 排序/barrier 查；见 0017 §4a，先定 null 合法性）。
```

## 3. 测试计划

- 白屏复现器（P0Render force-stop + 全新进程，GPU ON）抓
  `Sinytra/surface` 全程：send→ENTER→EXIT 是否完整。
- 回归：46 PASS harness 不退（插桩只加日志，行为零改）。
- 定稿：判决完成后按 0005 纪律决定保留（降级 DEBUG）或删除；
  若 A/B 指向独立根因，另立 patch（一次一 patch）。

## 4. 状态

- **首判已出（2026-10-03，判别 A）**：P0Render 全新进程 GPU ON 白屏复现
  （screencap 三连纯白 + `P0 PASS success=true`），`Sinytra/surface`
  三 handoff（non-null → null → non-null）全部 send→ENTER→EXIT、
  rv=0、毫秒级。handoff 已送达——v2 async 排序嫌疑洗脱，故障在
  remote/state/compositor 下游（M5-A2/A3 方向）。插桩保留至定稿。
- 后续：compositor 侧是否取用 surface / paused-state 是否正确
  （1880503 companion 课），进 Phase 2 设计。
