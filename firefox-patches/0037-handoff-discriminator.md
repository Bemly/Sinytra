# 0037 — handoff 判别日志（transact 到底死在哪）

> 状态：**实现中**（待真机 verdict）。**零行为改动**，纯观测。
> 前置：0035（detached transact）+ 0036（async resume）均已验机械生效；
> SNC 死法收敛到"transact 发出、GPU 无 ENTER"，死点在半路。

## 1. 三个假设与一轮即判表

| 现象 | 结论 | 下一步 |
|---|---|---|
| send 有、txnbegin 无 | H3：detached 线程根本没跑（spawn/attach 失败） | 修线程机制 |
| txnbegin 有、finish 无、ENTER 无 | transit stall（transact 卡 Binder 半路） | 结合 isValid 往下二分 |
| ENTER 有、valid=false | H1 实锤：stale Surface（resize 释放后 parcel 在途） | 传前校验/跳过，或重取当前 surface |
| ENTER 有、valid=true | H2 实锤：GPU binder 线程卡住（parcel 到了不执行） | GPU 侧 binder/wedge 调查（debuggerd 蹲点） |
| 全部 finish（ms 级）+ PageStart | 病那一轮就没触发 | 不庆祝，如实记 |

## 2. 改动（2 文件，零行为）

- `gfx/layers/ipc/UiCompositorControllerChild.cpp`：detached 线程入口
  `handoff txnbegin seq=N`（0005 门内）。
- `GeckoServiceGpuProcess.java`：ENTER 行加 `valid=`（`Surface.isValid()`
  直调，try 包住；resize 释放即 invalid，正好覆盖 stale 形状）。

## 3. 成本

同 0035 管线（binaries + publish + provider 重编）。C++ 侧多一行日志；
Java 侧在 `debug` 门内，无 release 影响。
