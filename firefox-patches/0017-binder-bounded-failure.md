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

## 3. M5-A proposal: bounded wait + invalidate + rebuild

```text
UI thread
  │
  └─ sync compositor path
       │
       └─ Binder: ICompositorSurfaceManager.onSurfaceChanged()
            │
            ├─ normal return → continue existing path (zero behavior change)
            │
            └─ bounded wait expires (N ms; N ≤ IPDL 10 s budget, TBD §6)
                  ↓
            invalidate remote endpoint (treat as NS_FAILED equivalent)
                  ↓
            NotifyRemoteActorDestroyed(token)
                  ↓
            GPU kill + compositor rebuild (existing machine, §2)
                  ↓
            Surface state re-sync (RequestNewSurface path already exists
            for the Resume-false branch, nsWindow.cpp:1483)
```

Design constraints (from review):

- Never "pretend paused": after timeout the caller must enter the
  recovery path, not continue destroying the surface as if the
  compositor had stopped (UAF/stale-surface risk).
- Healthy-path overhead must be ~zero: no extra thread hop, no extra
  IPC when the Binder answers promptly.
- Where to bound: candidates are (a) JNI/C++ around the
  `mCompositorSurfaceManager->OnSurfaceChanged` call with a timed wait
  on a worker + token invalidation, or (b) Java-side async dispatch
  with UI-thread timeout callback into the same invalidation branch.
  Decision during implementation; both land in the same recovery branch.
- N (timeout): bounded above by the 10 s IPDL budget so the Binder leg
  can never out-wait the IPDL leg; exact value from experiment (§6).

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
