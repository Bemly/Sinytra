package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Commit guard for direct navigations (loadUrl / loadDataUri / loadFileUrl).
//
// Root cause (device-verified, third-party host cold start): GeckoSession.load()
// short-circuits through NavigationDelegate.onLoadRequest and then fire-and-forgets
// "GeckoView:LoadUri" over the session EventDispatcher. EventDispatcher.dispatch
// drops the message with only a Log.w ("No listener for ...") when Gecko is
// already ready but no listener is registered yet — the session window / parent
// handler still attaching in the ~1s window after runtime creation. The
// app-visible result is onLoadRequest-then-silence: no PageStart, no channel,
// no error, forever. (EventDispatcher.dispatchToThreads "no listener" branch;
// the sibling ResponseFilters loss is documented in Interception.)
//
// The guard re-issues the navigation when no commit signal arrives within a
// bounded window. Commit signals (PageStart / LocationChange) disarm it, so a
// healthy load pays zero overhead. Policy-blocked and app-overridden loads
// disarm explicitly — they produce no PageStart by design, and retrying those
// would reload a URL the app took over. Retries are bounded (MAX_ATTEMPTS)
// with backoff covering a slow parent bring-up (~14s total). A newer arm
// supersedes pending retries via the generation guard (same pattern as the
// Interception filter retries).
//
// NeckO-progress awareness (2026-10-08 coolapk trickle round): a commit
// (PageStart) can legitimately exceed the whole ~14s budget on a starved
// network while bytes ARE flowing — every necko consult (ShouldPrepare,
// via ResponseBridge.onConsulted) pokes the guard, and a poke resets the
// attempt ladder instead of re-issuing. Re-issuing (let alone close+reopen)
// a progressing load restarts its download from zero, which on a trickle
// guarantees it NEVER finishes — strictly worse than waiting (Chromium and
// Fennec both just wait). Attempts therefore count CONSECUTIVE QUIET
// checks: a chattering pipeline never exhausts; a truly silent one (zero
// consults — the §1z lost-dispatch class) exhausts exactly as before.
//
// Total-refire cap (2026-10-08 S3 fission-suppression round): pokes reset
// the ladder, so a pipeline with ANY consult activity never exhausts —
// while its refires keep restarting the very load that would commit
// (each fresh loadUri lands behind the same poisoned predecessor and its
// commit is suppressed forever; device-verified 6s churn, 8.75MB re-download,
// zero PageStarts in 16min). Fires therefore ALSO count cumulatively per
// arm: past MAX_TOTAL_FIRES without a commit the approach is futile and the
// chain is forced into exhaustion (close+reopen = fresh session whose
// initial navigation commits — Canary-proven). Slow-but-alive loads are
// unaffected (their commit disarms long before; a 60s-header server sees
// ~8 refires under the cap).
//
// When even the bounded retries find no commit (session window never attached
// natively — device-verified class: zero PageStarts including blank), the
// guard fires the exhaustion action ONCE per arm: the bridge uses it to
// close + reopen the session (fresh native window) and try once more. A
// reopen that still finds no commit gives up loudly — same white screen as
// before, but diagnosed instead of silent.
//
// JVM-safe by construction: no GeckoResult / GeckoSession references (those
// need a live UI Looper — see InterceptBridgeTest). Scheduling degrades to a
// no-op without a Looper (unit tests); only the pure delay/liveness helpers
// are locked there.
final class NavigationCommitGuard {
    // 1-based backoff: a committed load reports PageStart long before the
    // first check (PageStart fires at commit, not at completion), so a
    // silent guard at 2s is almost certainly a lost dispatch, not a slow
    // network. Three attempts cover ~14s of slow parent bring-up.
    static final int MAX_ATTEMPTS = 3;

    // Total refires per arm before the chain is forced into exhaustion
    // (see class header): ~2,6,14,22…s cadence, so 12 refires ≈ 90s envelope.
    static final int MAX_TOTAL_FIRES = 12;

    // Pure (JVM-locked): attempt is 1-based; 2s doubling to an 8s cap.
    static long delayForAttempt(int attempt) {
        long delay = 2000L << (attempt - 1);
        return Math.min(delay, 8000L);
    }

    // Pure (JVM-locked): a scheduled check is live only when its generation
    // still matches the latest arm/disarm.
    static boolean isLive(int scheduledGeneration, int currentGeneration) {
        return scheduledGeneration == currentGeneration;
    }

    // Pure (JVM-locked): the check's decision. Progress (a necko consult
    // since the last check) resets the ladder to attempt 1 instead of
    // re-issuing — the load is alive, only slow. Returns -1 when the
    // bounded quiet retries are spent (fire exhaustion).
    static int nextAttempt(boolean progressSeen, int attempt) {
        if (progressSeen) {
            return 1;
        }
        if (attempt >= MAX_ATTEMPTS) {
            return -1;
        }
        return attempt + 1;
    }

    // Pure (JVM-locked): cumulative refire cap. Pokes keep the ladder alive
    // for trickle patience, but every refire restarts the download — past
    // this many refires without a commit, restarting is futile (S3) and the
    // chain must escalate to reopen instead of churning forever.
    static boolean shouldForceExhaust(int totalFires) {
        return totalFires > MAX_TOTAL_FIRES;
    }

    private static final String TAG = "Sinytra/session";

    private int mGeneration;
    @NonNull
    private Runnable mRedispatch = () -> {};
    @Nullable
    private Runnable mExhausted;
    private boolean mExhaustedFired;
    // Set by poke() (necko consult observed); consumed by the next check.
    // Attempts count consecutive QUIET checks (see class header).
    private boolean mProgress;
    // Cumulative refires this arm (never reset by pokes; reset by arm()).
    // Caps the self-sustaining churn where refires restart a load whose
    // commit is suppressed forever (see class header).
    private int mTotalFires;

    // Arms the guard for one navigation. The redispatch runnable must re-issue
    // the same navigation WITHOUT re-arming (it runs inside this generation's
    // budget; re-arming would reset the attempt count and loop forever on a
    // fully wedged pipeline — device-verified). Any in-flight chain from a
    // previous navigation is superseded, never stacked.
    synchronized void arm(@NonNull Runnable redispatch) {
        arm(redispatch, null);
    }

    // Same, plus a one-shot exhaustion action fired when the bounded retries
    // still find no commit (session window never attached natively). Null =
    // no action (retries just stop).
    synchronized void arm(@NonNull Runnable redispatch,
            @Nullable Runnable onExhausted) {
        mRedispatch = redispatch;
        mExhausted = onExhausted;
        mExhaustedFired = false;
        mTotalFires = 0;
        int generation = ++mGeneration;
        schedule(generation, 1);
    }

    // Disarms: the navigation committed (PageStart / LocationChange), was
    // dismissed by design (policy block / app override), or the session died.
    // Pending checks from this generation go inert via the liveness guard.
    synchronized void disarm() {
        mGeneration++;
        mProgress = false;
    }

    // NeckO-progress poke: a consult reached Java for any URL, so the
    // pipeline is alive — the next check extends instead of re-issuing.
    // Never poked by Java-side dispatch echoes (those would mask a real
    // stall); only the necko consult path calls this.
    synchronized void poke() {
        mProgress = true;
    }

    private void schedule(final int generation, final int attempt) {
        if (attempt > MAX_ATTEMPTS) {
            return;
        }
        final android.os.Handler handler;
        try {
            handler = new android.os.Handler(
                    android.os.Looper.getMainLooper());
        } catch (Throwable t) {
            // No Looper (JVM unit tests): the guard stays inert; the
            // synchronous dispatch above already ran.
            return;
        }
        try {
            handler.postDelayed(() -> {
                int next;
                Runnable fire = null;
                synchronized (NavigationCommitGuard.this) {
                    if (!isLive(generation, mGeneration)) {
                        return;
                    }
                    boolean extend = mProgress;
                    mProgress = false;
                    next = nextAttempt(extend, attempt);
                    if (next < 0) {
                        fireExhaustedLocked();
                        return;
                    }
                    if (!extend) {
                        mTotalFires++;
                        if (shouldForceExhaust(mTotalFires)) {
                            android.util.Log.w(TAG,
                                    "commit guard forcing exhaustion after "
                                            + mTotalFires
                                            + " refires without commit");
                            fireExhaustedLocked();
                            return;
                        }
                        fire = mRedispatch;
                    } else {
                        android.util.Log.d(TAG, "commit guard extending"
                                + " (necko progress seen)");
                    }
                }
                if (fire != null) {
                    android.util.Log.d(TAG, "commit guard firing attempt="
                            + attempt);
                    try {
                        fire.run();
                    } catch (Throwable t) {
                        android.util.Log.w(TAG,
                                "commit guard redispatch threw", t);
                    }
                }
                synchronized (NavigationCommitGuard.this) {
                    if (!isLive(generation, mGeneration)) {
                        return;
                    }
                    schedule(generation, next);
                }
            }, delayForAttempt(attempt));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "commit guard schedule threw", t);
        }
    }

    // Fires the exhaustion action once: the bounded retries found no commit.
    // Caller holds the monitor (or is inside the synchronized fire path).
    private void fireExhaustedLocked() {
        if (mExhaustedFired) {
            return;
        }
        mExhaustedFired = true;
        Runnable exhausted = mExhausted;
        if (exhausted == null) {
            return;
        }
        android.util.Log.w(TAG, "commit guard exhausted; no PageStart after "
                + MAX_ATTEMPTS + " attempts");
        try {
            exhausted.run();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "commit guard exhausted threw", t);
        }
    }
}
