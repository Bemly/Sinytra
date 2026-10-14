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

    private static final String TAG = "Sinytra/session";

    private int mGeneration;
    private int mAttempt;
    private boolean mArmed;
    private long mWindowStartMs;
    private long mLastHeartbeatMs;
    @NonNull
    private Runnable mRedispatch = () -> {};
    @Nullable
    private Runnable mExhausted;
    private boolean mExhaustedFired;

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
        mArmed = true;
        mAttempt = 1;
        int generation = ++mGeneration;
        schedule(generation, 1);
    }

    // Disarms: the navigation committed (PageStart / LocationChange), was
    // dismissed by design (policy block / app override), or the session died.
    // Pending checks from this generation go inert via the liveness guard.
    synchronized void disarm() {
        mArmed = false;
        mGeneration++;
    }

    // Heartbeat: the pipeline is alive for the armed navigation (a consult
    // arrived, a channel is preparing/completing). Defers firing while
    // heartbeats flow: a slow-but-moving navigation (multi-redirect chains
    // slower than the 2s first window — device-verified bing.com apex<->www
    // ping-pong) must never be killed mid-flight. Returns whether an armed
    // window was restarted (false = inert: never armed, disarmed, or already
    // exhausted — never resurrects a dead guard). A truly wedged pipeline
    // sends no heartbeats, so the bounded retries still converge exactly
    // as before. NOTE: deferring is not enough by itself — the fire path
    // must also SKIP the redispatch when heartbeats arrived mid-window
    // (see schedule); freezing the attempt count alone still murders every
    // 2s (device-verified second half, same bing chain).
    synchronized boolean poke() {
        if (!mArmed) {
            return false;
        }
        mLastHeartbeatMs = nowMs();
        schedule(++mGeneration, mAttempt);
        return true;
    }

    // JVM-safe wall clock (ordering only): System.currentTimeMillis needs
    // no Looper, unlike the Handler scheduling below.
    private static long nowMs() {
        return System.currentTimeMillis();
    }

    private void schedule(final int generation, final int attempt) {
        if (attempt > MAX_ATTEMPTS) {
            return;
        }
        mWindowStartMs = nowMs();
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
                Runnable fire = null;
                synchronized (NavigationCommitGuard.this) {
                    if (!isLive(generation, mGeneration)) {
                        return;
                    }
                    // Pipeline moved mid-window (consult/channel/error
                    // arrived after this check was scheduled): skip the
                    // redispatch AND the attempt advance, open a fresh
                    // window at the same attempt. Killing here would abort
                    // a live redirect chain; the chain's own commit (or
                    // its silence) settles the guard instead.
                    if (mLastHeartbeatMs > mWindowStartMs) {
                        schedule(++mGeneration, attempt);
                        return;
                    }
                    fire = mRedispatch;
                }
                android.util.Log.d(TAG, "commit guard firing attempt="
                        + attempt);
                try {
                    fire.run();
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "commit guard redispatch threw", t);
                }
                synchronized (NavigationCommitGuard.this) {
                    if (!isLive(generation, mGeneration)) {
                        return;
                    }
                    if (attempt >= MAX_ATTEMPTS) {
                        fireExhaustedLocked();
                        return;
                    }
                    mAttempt = attempt + 1;
                    schedule(generation, attempt + 1);
                }
            }, delayForAttempt(attempt));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "commit guard schedule threw", t);
        }
    }

    // Fires the exhaustion action once: the bounded retries found no commit.
    // Caller holds the monitor (or is inside the synchronized fire path).
    // Disarms first so a late heartbeat can never resurrect a guard that
    // already gave up.
    private void fireExhaustedLocked() {
        if (mExhaustedFired) {
            return;
        }
        mExhaustedFired = true;
        mArmed = false;
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
