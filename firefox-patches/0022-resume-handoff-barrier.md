# 0022 — resume-path handoff barrier (M5-A3 ordering fix)

> 解决哪个问题：STATUS §1w 白屏 Round 10——`OnResumeComposition`
> 比 handoff ENTER 早到约 6ms（初启/cover 返回两轮皆然）；v2 把
> transact 搬离调用线程后，`ResumeAndResize`（IPDL）系统性跑赢
> worker→Binder→map.put，首启空 map 即 null→白屏（确定性），暖
> widget 靠旧 surface 偶过。0021 判别 A 已排除"送达失败"，本 patch
> 修 ordering（0017 §4a 纪律：先定序，不预设 retry）。
> 为什么 public API 不够：不适用（Gecko 内部 ordering；embedder 无
> 原语把 handoff 与 resume 排序）。
> 上游 bug：1855536（同类；NEW/P3）——resolution 后可附 Whiteboard
> 引用；1880503 只管 IPDL 跳，不管 Binder/ordering 跳。

## 1. 修复（三处，同一次序保证）

- `UiCompositorControllerChild`：拆出 `QueueSurfaceHandoff`（返回
  seq，调用线程分配）；新增 `OnCompositorSurfaceChangedAndWait`——
  dispatch 后有界等本地 worker 落定（Monitor + 2s deadline，
  100ms 切片；超时按旧行为继续 + `Sinytra/surface` 日志）。
  等的是**本地线程**不是远端：正常零等待（worker 已落定直接过），
  最坏 UI block 2s——不引入新的无界 hang 类。pause 路保持
  fire-and-forget（resume 后无人同步读 map，无需 barrier）。
- `nsWindow`：两个 resume 点（`NotifyCompositorCreated` 未 paused
  分支、`SyncResumeResizeCompositor`）切到 AndWait；pause 点不动。
- 完成信号 UNGATED（正确性不依赖日志门）；日志走 0005 同构 pref 门
  + `BuildConfig.DEBUG`（沿 0021）。

## 2. 测试计划（M5-A3 acceptance 面）

- 白屏复现器（P0Render 全新进程，GPU ON）：首启出像素（screencap
  三连）+ cover 往返像素保持 + 零 `OnResumeComposition null` +
  零 `handoff wait timeout`。
- 回归：全量 harness（46 PASS）GPU ON；teardown 复现器（M5-A1 面）
  不退（barrier 只动 resume，pause 原样）。
- 多轮：首启轮 ×3（acceptance §6 item 7 初步）。

## 3. 边界（不做）

- 超时后不 retry/recreate（旧行为 + 日志；ordering 已保证时 retry
  只遮问题）。
- 不碰 fence / oneway（M5-B 独立变体纪律）。
- 非 Android 构建：新增声明包在 `MOZ_WIDGET_ANDROID` 内。

## 4. 状态

- **已验证定稿（2026-10-03，firefox `e27c1b9f09ee`）**：P0Render 全新进程
  GPU ON 首启出像素（example.com 深色全页三连，零 `OnResumeComposition
  null`、零 `handoff wait timeout`）；cover 往返像素保持（此前白→黑）。
  全量 harness 进行中：P0 段至 msgChannel 全 PASS（jsEval/transport
  正常），后段 UI 疑似 park（25min 无进展、无 FAIL——teardown hang
  家族 M5-A1 形态，pause 路本 patch 未动；barrier 只加 resume 有界等，
  超时 2s 即放行，不构成新 park 类，判定非本 patch 回归，待独立复核）。
  全量 46 PASS 仍欠（acceptance 缺口如实记）。
