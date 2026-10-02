# 0017 — Binder bounded failure (M5-A design)

> Root-fix for the GPU-process compositor hang (STATUS §1w): keep the GPU
> process, but turn the synchronous Binder dependency from "hang the UI
> forever" into bounded failure + recovery. Follows the 1880503 philosophy
> (timeout → kill/rebuild, never assume the remote completed).
>
> Non-goals (explicit): no fence hunting (fence-as-cause unproven, §4);
> oneway is M5-B experimental only, never presumed the cure (§5);
> no behavior change on the healthy path.

## 1. Failure model (device-verified)

```text
                 Surface lifecycle change
                          │
                          ▼
                syncPauseCompositor()       // GeckoSession.java:357:
                          │                 // "blocks UI thread" (by design)
                          ▼
                 Gecko native path (nsWindow.cpp)
                    ┌─────┴─────┐
                    │           │
                 IPDL          Binder
                    │           │
           10 s timeout      NO timeout
     (1880503, in tree)  ICompositorSurfaceManager.
                    │      onSurfaceChanged()
                    │           │
                    │       synchronized (GPU side)
                    │           │
                    │        [HANG observed here, twice]
                    │           │
                    └─────┬─────┘
                          │
                          ▼
                   UI thread blocked
                          │
                          ▼
                       ANR / teardown
```

Why 1880503 does not save us: on the resume path
(`SyncResumeResizeCompositor`, nsWindow.cpp:1466) the Binder hop runs
**before** the IPDL `ResumeAndResize`; on the pause path
(`SyncPauseCompositor`, nsWindow.cpp:1388) it runs **after** `SendPause`.
Either way the Binder hop itself is unwatched — no reply timeout exists
for plain (non-oneway) AIDL. Corroboration outstanding: none of our hang
logs contain 1880503's `Killing GPU process due to IPC reply timeout`
line, consistent with dying in the Binder hop before/around the IPDL leg.

Upstream refs: 1855536 (same ANR class, NEW/P3 — our bug's public
identity), 1880503 (IPDL-half fix, FF128, in our 158 tree), 1929209
(same symptom, Adreno-shader cause, FF133/134 fixed — N/A on Mali).

## 2. Call-site map (pin 34ed69f16167)

| # | Site | Thread | Sync? | Timeout? |
|---|---|---|---|---|
| 1 | `GeckoSession.onSurfaceDestroyed` → `Compositor.syncPauseCompositor()` (GeckoSession.java:8108) | UI | yes (JNI `dispatchTo=current`) | no |
| 2 | `nsWindow::SyncPauseCompositor` → `UiCompositorControllerChild::Pause()` = `SendPause()` (nsWindow.cpp:1388) | UI | yes (IPDL sync) | **10 s** → `KillProcess(minidump)` (1880503) |
| 3 | `nsWindow::SyncPauseCompositor` → `OnCompositorSurfaceChanged(id, nullptr)` (nsWindow.cpp:1395) | UI | yes (Binder transact) | **none — THE TARGET** |
| 4 | `nsWindow::SyncResumeResizeCompositor` → `OnCompositorSurfaceChanged(id, surface)` (nsWindow.cpp:1469) | UI | yes (Binder transact) | **none — THE TARGET** |
| 5 | … → `ResumeAndResize()` (nsWindow.cpp:1477) | UI | yes (IPDL sync) | 10 s (1880503) |
| 6 | Binder target: `RemoteCompositorSurfaceManager.onSurfaceChanged` (GeckoServiceGpuProcess.java:52) | GPU binder thread | `synchronized` impl (release/put) | n/a (callee) |

Recovery state machine already in tree (reuse, do not reinvent):

- `UiCompositorControllerChild::OnCompositorSurfaceChanged` already maps
  `NS_FAILED` binder results to
  `GPUProcessManager::NotifyRemoteActorDestroyed(token)`
  (UiCompositorControllerChild.cpp:377) — a timeout wrapper plugs into
  this exact branch.
- `NotifyRemoteActorDestroyed` → `OnProcessUnexpectedShutdown` →
  `HandleProcessLost` → widgets drop the dead compositor session →
  relaunch-or-continue-without-GPU (GPUProcessManager.cpp:1031).
- Compositor reinit respects intended paused/resumed state
  (`mCompositorPaused` set before the attempt — 1880503's first patch,
  nsWindow.cpp:1384/1421), so a mid-pause kill rebuilds paused, not blank.

## 3. M5-A proposal: never transact on UI (v2; v1 superseded)

v1 (watchdog-race: 5 s bound → invalidate → kill) **fired correctly on
device** (round 2: `Surface Binder transact timed out` after the Binder
hop hung 5 s post-IPDL-kill) but the host still died: round 3 proved an
in-flight transact survives two GPU kills and a rebirth, parking UI 35 s+
beside a healthy GPU. Killing releases the *process*, not the parked
*transaction*. Conclusion: bounding the wait is insufficient — the UI
thread must not enter the transact at all.

v2 (implemented): async dispatch of the JNI Binder call to the
compositor thread, fire-and-forget. Safe because the sync wait buys
nothing: the pause path uses the result only for the death-notify (the
worker performs it identically — `NotifyRemoteActorDestroyed`
self-dispatches to main from any thread); the resume path ignores the
result entirely (`nsWindow.cpp:1466` — no check); the GPU stores
surfaces last-writer-wins, so a racing newer surface supersedes. A
wedged worker dies with GPU teardown like any GPU-bound thread and never
blocks UI. Healthy-path overhead: one dispatch + one GlobalRef promote.

```text
UI thread (syncPause/syncResume)
  │
  ├─ IPDL legs (unchanged, 10 s budget via 1880503)
  │
  └─ Binder: OnCompositorSurfaceChanged()
       │
       └─ promote Surface → GlobalRef, post to CompositorThread, RETURN
            │
            └─ worker: transact → on NS_FAILED, NotifyRemoteActorDestroyed
               (slow/failed transacts logged with ms/rv; healthy ms-level
               transacts fully silent)
```

Remaining ordering note (resume path): the surface handoff may now land
*after* `ResumeAndResize` where it used to land before. The GPU applies
surfaces last-writer-wins and the parent keeps its own `mSurface` copy,
so the worst case is a stale frame until the handoff lands — validated
by screencap regression (§7), infinitely preferable to a dead host.
Never-pretend-paused holds trivially: no wait exists to pretend about.

## 4. Explicitly NOT claimed

- That the GPU waits on a destroyed surface's fence. Unproven; the
  `synchronized` GPU-side monitor, renderer/driver stalls (cf. 1929209),
  and Binder-thread exhaustion are equally live hypotheses. 0017 does
  not need the GPU-side cause: it bounds the UI-side wait regardless.
- That killing the GPU always recovers video/WebGL mid-stream. Covered
  by acceptance §6 items 3–5.

## 5. M5-B (experimental variant, separate patch)

`oneway` on `ICompositorSurfaceManager.onSurfaceChanged`. Eliminates the
UI park, but changes sync-protocol semantics (ordering vs. other Binder
objects not guaranteed; a dead GPU stays dead silently — UI lives, GPU
rots). Ship only with Surface recreate / ordering / black-screen
regression (acceptance §6 item 5 multi-round). Never merged as "the fix"
without that evidence.

## 6. Acceptance (M5-A lands iff ALL hold)

1. Binder hang → UI thread recovers no later than N (+ slack).
2. GPU process reliably isolated/rebuilt (no manual force-stop).
3. Surface recreate: no black screen, no stale surface.
4. Next navigation: PageStart/PageStop/HIT/OnSuccess normal.
5. Multi-round teardown/recreate green with GPU ON (else the hang just
   became "restart GPU each time but black next surface" — not fixed).
6. Healthy-path behavior unchanged (perf/features; M3/M3b harness deltas
   as the comparison baseline).

## 7. Test plan

- Unit: none possible (native IPC + threads); JVM covers nothing here.
- Device: CTS teardown reproducers (`testThirdPartyCookie`,
  `testOnJsBeforeUnloadIsCalled` — both previously died WITHOUT verdict)
  must return clean verdicts across ≥3 consecutive runs with GPU ON;
  full `P0GlueActivity` harness (46 PASS baseline, M3 build) re-run with
  GPU ON + patch; screencap triplicates on cover/recreate rounds.
- Upstream report: land with paired minidump intact (1880503's
  diagnostics must keep working — the kill path is shared).

## 8. Validation log (GPU ON + 0017 v1)

- **Round 1 (2026-10-02, `testThirdPartyCookie`)**: both loads green
  (HIT+OnSuccess+PageStop), then teardown cover → main stuck in
  `syncPauseCompositor ← onSurfaceDestroyed` (watchdog dump). At +10 s
  the **IPDL leg won the race**: `[GFX1-]: Killing GPU process due to
  IPC reply timeout` (1880503) — 0017's Binder watchdog correctly never
  armed (pause path runs SendPause before the Binder hop). New GPU +
  tabs relaunched. **But the host made zero forward progress afterwards**
  (0 CPU/30 s, SIGQUIT unserviced, no verdict, no ANR — silent limbo).
  Lesson: GPU-level kill+relaunch is necessary but not sufficient;
  app-level recovery (acceptance items 4–5) is the real bar, and this
  round is the 1880503-only baseline (0017 changes nothing on the IPDL
  leg — no regression attributable to the patch).
- Still needed: a round that hangs in the **Binder hop** (resume path,
  Binder-before-IPDL) to observe the 0017 watchdog fire
  (`Surface Binder transact timed out`) and the forced recovery.
- **Round 2 (2026-10-02, same reproducer, GPU ON + 0017 v1)**: loads green,
  teardown cover → main stuck `syncPause ← onSurfaceDestroyed` →
  13:08:42 IPDL kill → **13:08:47 `Surface Binder transact timed out;
  invalidating GPU endpoint` — 0017 FIRED**. The Binder hop hung ~5 s
  *after* the IPDL kill, proving it an independent hang site (the design
  premise). Fresh GPU + tabs relaunched. **But the host still ANR'd at
  the 60 s input deadline with zero further logs** — process-level
  recovery again did not resume app-level flow. Open: where main parks
  post-rebuild (still inside the original transact vs. a second park).
  v2 instrumentation (transact exit log gen/ms/rv + per-check watchdog
  dumps) targets exactly this question.
- **Round 3 (2026-10-02, GPU ON + 0017 v1 + exit instrumentation)**:
  same path; per-check dumps proved main parked in the SAME
  `transactNative ← onSurfaceChanged` **35 s after both kills beside a
  healthy reborn GPU** — killing releases the process, not the parked
  transaction. v1 direction falsified → v2 (async, never transact on UI).
- **Round 4 (2026-10-02, GPU ON + 0017 v2 async)**: loads green →
  teardown cover stall → IPDL kill → **verdict 0.1 s later**
  (`testThirdPartyCookie` FAIL at the pre-existing cookie-domain
  waitForCookie, identical to M3 — hang gone, verdict alive). Single
  watchdog episode, no re-wedge.
- **Harness (2026-10-02, GPU ON + 0017 v2)**: **46 PASS + P0 GLUE PASS**,
  zero FAIL, gpu child healthy throughout, zero watchdog/kill/async log
  anomalies. Acceptance items 3 (no black screen; visualSurface +
  render probes green) and 6 (healthy path unchanged) hold on this build.
