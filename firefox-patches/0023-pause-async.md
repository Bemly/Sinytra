# 0023 — Pause() fire-and-forget (M5-A1 pause path)

> 解决哪个问题：STATUS §1w M5-A1——activity stop/teardown 经
> surfaceDestroyed 进 `syncPauseCompositor`，主线程 park 不归
> （Round 11：watchdog 10s 转储 + kill 后仍 park + destroy 超时；
> v2 只搬走了 Binder hop，IPDL `SendPause` 照样等远端）。
> 为什么是 async 而不是 timeout：kill ≠ recovery 已三证（Round 1
> limbo、Round 3 transact 存活、Round 11 kill 后仍 park）——等远端
> 本身就是错的，v2 教训（永不在 UI 上等 remote，连 IPDL 也不等）
> 直接推广到 pause 路。`Pause()` 返回值本来就没人读（nsWindow 唯一
> 调用点忽略；恢复语义走本地 `mCompositorPaused` 旗），且 IPDL 同
> 通道保序——后续 `ResumeAndResize` 仍排在 pause 之后。
> 上游 bug：1855536（同类；NEW/P3）。

## 1. 修复（IPDL 一行 + 备案一摘）

- `PUiCompositorController.ipdl`：`sync Pause()` → `async Pause()`。
  C++ 零改（异步 `SendPause` 同样返回 bool；`RecvPause` 父侧签名不变）。
- `ipc/ipdl/sync-messages.ini`：摘 `[PUiCompositorController::Pause]`
  旧备案（改 async 后构建系统报 "can be delisted"——备案制反向生效）。
- 有意没动：`Resume/ResumeAndResize` 保持 sync（10s 预算 + 0022
  barrier 已覆盖；再观测到 resume park 另立项）；pause 的 null-surface
  handoff 保持 fire-and-forget。

## 2. 测试计划（M5-A1 pause 面）

- P0Render → HOME 盖掉：watchdog 零 episode（此前必 park）+ 返回出像素。
- noCache 单测：park 消失（此前 teardown 必 park + destroy 超时）；
  其 load#2 取数静默 ~20s 是独立 open 项（server 单线程 vs Gecko 侧），
  本 patch 只保"不 park、收尾干净"，不管取数。
- 回归：46 PASS harness（pause 语义变化面）+ teardown 复现器。

## 3. 状态

- **已验证定稿（2026-10-03，firefox `0a39bc4251fe`）**：cover 零 park
  + 返回满页像素；noCache park 消失（失败仍在，系取数静默 open 项）。
