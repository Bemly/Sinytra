package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;

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
    @NonNull
    private Runnable mRedispatch = () -> {};

    // Arms the guard for one navigation. The redispatch runnable must re-issue
    // the same navigation (it re-arms with a fresh generation, superseding
    // this chain). Any in-flight chain from a previous navigation is
    // superseded, never stacked.
    synchronized void arm(@NonNull Runnable redispatch) {
        mRedispatch = redispatch;
        int generation = ++mGeneration;
        schedule(generation, 1);
    }

    // Disarms: the navigation committed (PageStart / LocationChange), was
    // dismissed by design (policy block / app override), or the session died.
    // Pending checks from this generation go inert via the liveness guard.
    synchronized void disarm() {
        mGeneration++;
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
                Runnable fire;
                synchronized (NavigationCommitGuard.this) {
                    if (!isLive(generation, mGeneration)) {
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
                    schedule(generation, attempt + 1);
                }
            }, delayForAttempt(attempt));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "commit guard schedule threw", t);
        }
    }
}
