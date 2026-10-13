package org.mozilla.geckowebview.session;
import org.mozilla.geckowebview.runtime.SinytraLog;

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
// Consult latch (2026-10-08 coolapk COMMIT-SUPPRESSED round, pid 794):
// the cap above still lets a consulting-but-never-committing pipeline churn
// (13 refires, every one HIT→pass-through→OnSuccess→RedirectToRealChannel
// with zero PageStarts, then reopen died identically on the naked retry —
// refiring restarts the download against a content side that never consumes).
// Once ANY necko consult reaches Java for this arm, the dispatch provably
// landed, so re-issuing can never help: the latch freezes refires (every
// check extends) while keeping the quiet path intact. A latched chain ends
// by commit/error-disarm (normal), never by exhaustion; a truly silent one
// (zero consults — the §1z lost-dispatch class) exhausts exactly as before.
// Real network failures still surface via onLoadError (disarm), so waiting
// is Chromium-identical, not a hang.
//
// Latch-escape hole (2026-10-09 MiniWV black-hole rounds, B1/Bing shape):
// the latch freezes at the FIRST consult — but in a black-hole round every
// pre-recovery dispatch is doomed (tab/GPU cannot link channels yet), and
// the first consults only arrive post-recovery (headers flow once SW-WR
// fallback lands). Freezing then means no post-recovery dispatch is ever
// issued: the doomed channels never link and the arm extends forever —
// a recoverable stall converted into a permanent black (pre-latch code
// occasionally survived this via a lucky post-recovery refire, v18-shape).
// So a latched-but-utterly-quiet arm gets ONE rescue refire (a lone fresh
// post-recovery dispatch), then escalates to the existing reopen path if
// still silent. Chatter (any consult) resets the quiet count, so the R2
// churning class the latch was built for never earns a rescue.
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
// Latched wall-clock cap (2026-10-13 SNC GPU-kill round, pid 32605):
// chatter resets the quiet ladder, so a consulting-but-never-committing
// arm can extend FOREVER — while its commit is suppressed downstream by a
// dead compositor (fresh-GPU nav-time handoff wedge → barrier timeout →
// sync ResumeAndResize IPC timeout → Gecko kills the GPU → resumed=0 →
// zero PageStarts although the main document answers 200 + OnSuccess).
// The quiet ladder cannot see this shape (every check extends on consult
// progress), so latched arms ALSO carry a consult-to-commit deadline:
// past it without a commit the chain is forced into exhaustion
// (close+reopen) no matter how lively the chatter. The cap is generous
// on purpose — a legit trickle commits within seconds of first bytes;
// only a suppressed pipeline consults for a full minute with zero commit.
// Chatter still buys patience up to the cap (the trickle contract holds),
// it just no longer buys eternity.
//
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

    // Latched wall-clock cap (see class header): consult-to-commit
    // deadline for latched arms. Chatter resets the quiet ladder but must
    // not buy eternity — past this without a commit the pipeline is
    // suppressed downstream (dead compositor class) and only a fresh
    // session can recover. Generous vs any legit trickle-to-commit gap.
    static final long LATCHED_WALL_CAP_MS = 60_000;

    // Pure (JVM-locked): latched wall-cap decision. Unset (0) never fires;
    // otherwise fires once the consult-to-commit age reaches the cap.
    static boolean shouldForceWallExhaust(long firstConsultUptimeMs,
            long nowUptimeMs) {
        return firstConsultUptimeMs > 0
                && nowUptimeMs - firstConsultUptimeMs >= LATCHED_WALL_CAP_MS;
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

    // Pure (JVM-locked): the check's extend decision. Either fresh necko
    // progress or the consult latch (dispatch provably landed) extends;
    // only a quiet check on an unlatched arm re-issues.
    static boolean shouldExtend(boolean progressSeen, boolean consultLatched) {
        return progressSeen || consultLatched;
    }

    // Pure (JVM-locked): cumulative refire cap. Pokes keep the ladder alive
    // for trickle patience, but every refire restarts the download — past
    // this many refires without a commit, restarting is futile (S3) and the
    // chain must escalate to reopen instead of churning forever.
    static boolean shouldForceExhaust(int totalFires) {
        return totalFires > MAX_TOTAL_FIRES;
    }

    // Latched-quiet escape (see class header): consecutive quiet checks while
    // latched (extending checks tick every 2s — extend resets the ladder to
    // attempt 1). After RESCUE_AT of utter post-latch silence the pre-recovery
    // dispatches are presumed doomed and one rescue refire is earned; after
    // EXHAUST_AT the arm escalates to reopen. Any consult (progress) resets
    // the count, so chattering pipelines never trigger this path.
    static final int LATCHED_QUIET_RESCUE_AT = 8;
    static final int LATCHED_QUIET_EXHAUST_AT = 16;

    // Pure (JVM-locked): latched-quiet decision. Returns 1 once (single
    // rescue refire) and 2 (force exhaustion) at the bounds, else 0
    // (keep extending). `rescued` guards the one-rescue budget.
    static int latchedQuietDecision(int quietCount, boolean rescued) {
        if (quietCount >= LATCHED_QUIET_EXHAUST_AT) {
            return 2;
        }
        if (!rescued && quietCount >= LATCHED_QUIET_RESCUE_AT) {
            return 1;
        }
        return 0;
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
    // Consult latch (see class header): once any consult reaches Java for
    // this arm, the dispatch provably landed — every later check extends,
    // never refires. Reset by arm()/disarm() with the rest of the chain.
    private boolean mConsultLatched;
    // Uptime (SystemClock.uptimeMillis) of the first consult that set the
    // latch; 0 = none yet. Drives the latched wall-clock cap (see class
    // header): a consulting-but-never-committing arm must still escalate.
    // Reset by arm()/disarm() with the rest of the chain.
    private long mFirstConsultUptimeMs;
    // Cumulative refires this arm (never reset by pokes; reset by arm()).
    // Caps the self-sustaining churn where refires restart a load whose
    // commit is suppressed forever (see class header).
    private int mTotalFires;
    // Consecutive quiet checks while consult-latched (reset by any consult
    // progress, by arm() and by disarm()). Drives the latch-escape rescue
    // (see class header): utter post-latch silence earns one rescue refire,
    // sustained silence escalates to reopen.
    private int mLatchedQuiet;
    // Whether the one rescue refire was already spent this arm.
    private boolean mRescued;

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
        mConsultLatched = false;
        mFirstConsultUptimeMs = 0;
        mLatchedQuiet = 0;
        mRescued = false;
        int generation = ++mGeneration;
        schedule(generation, 1);
    }

    // Disarms: the navigation committed (PageStart / LocationChange), was
    // dismissed by design (policy block / app override), or the session died.
    // Pending checks from this generation go inert via the liveness guard.
    synchronized void disarm() {
        mGeneration++;
        mProgress = false;
        mConsultLatched = false;
        mFirstConsultUptimeMs = 0;
        mLatchedQuiet = 0;
        mRescued = false;
    }

    // NeckO-progress poke: a consult reached Java for any URL, so the
    // pipeline is alive — the next check extends instead of re-issuing.
    // Never poked by Java-side dispatch echoes (those would mask a real
    // stall); only the necko consult path calls this. The first poke also
    // sets the consult latch (see class header): from then on this arm only
    // extends, because its dispatch provably landed and re-issuing can only
    // restart a load whose commit is suppressed downstream. The URL is
    // log-only (the guard itself stays URL-blind).
    synchronized void poke(@Nullable String url) {
        mProgress = true;
        if (!mConsultLatched) {
            mConsultLatched = true;
            mFirstConsultUptimeMs = android.os.SystemClock.uptimeMillis();
            SinytraLog.d(TAG, "commit guard consult-latched url=" + url
                    + "; refires suspended for this navigation");
        }
    }

    // Test-only: whether the consult latch is set (same-package tests).
    synchronized boolean isConsultLatched() {
        return mConsultLatched;
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
                    if (mConsultLatched && shouldForceWallExhaust(
                            mFirstConsultUptimeMs,
                            android.os.SystemClock.uptimeMillis())) {
                        SinytraLog.w(TAG,
                                "commit guard latched wall-cap; consulting"
                                        + " without commit for "
                                        + LATCHED_WALL_CAP_MS
                                        + "ms, escalating to reopen");
                        fireExhaustedLocked();
                        return;
                    }
                    boolean extend = shouldExtend(mProgress, mConsultLatched);
                    boolean progressSeen = mProgress;
                    mProgress = false;
                    next = nextAttempt(extend, attempt);
                    if (next < 0) {
                        fireExhaustedLocked();
                        return;
                    }
                    if (!extend) {
                        mLatchedQuiet = 0;
                        mTotalFires++;
                        if (shouldForceExhaust(mTotalFires)) {
                            SinytraLog.w(TAG,
                                    "commit guard forcing exhaustion after "
                                            + mTotalFires
                                            + " refires without commit");
                            fireExhaustedLocked();
                            return;
                        }
                        fire = mRedispatch;
                    } else if (progressSeen) {
                        // Chatter: the pipeline is alive (trickle, subresource
                        // consults) — patience, and the latch-escape clock
                        // restarts. Churning pipelines never earn a rescue.
                        mLatchedQuiet = 0;
                        SinytraLog.d(TAG, "commit guard extending"
                                + " (necko progress seen)");
                    } else if (!mConsultLatched) {
                        SinytraLog.d(TAG, "commit guard extending"
                                + " (necko progress seen)");
                    } else {
                        // Latched and utterly quiet: no consult since the
                        // latch (or since the last progress). Pre-recovery
                        // dispatches are presumed doomed — one rescue refire,
                        // then escalation to reopen (see class header).
                        mLatchedQuiet++;
                        int escape = latchedQuietDecision(mLatchedQuiet,
                                mRescued);
                        if (escape == 2) {
                            SinytraLog.w(TAG,
                                    "commit guard latched-quiet; escalating"
                                            + " to reopen");
                            fireExhaustedLocked();
                            return;
                        }
                        if (escape == 1) {
                            mRescued = true;
                            mConsultLatched = false;
                            mTotalFires++;
                            if (shouldForceExhaust(mTotalFires)) {
                                SinytraLog.w(TAG,
                                        "commit guard forcing exhaustion"
                                                + " after "
                                                + mTotalFires
                                                + " refires without commit");
                                fireExhaustedLocked();
                                return;
                            }
                            SinytraLog.i(TAG,
                                    "commit guard latched-quiet rescue refire"
                                            + " (post-recovery dispatch)");
                            fire = mRedispatch;
                        } else {
                            SinytraLog.d(TAG, "commit guard extending"
                                    + " (latched, quiet "
                                    + mLatchedQuiet + ")");
                        }
                    }
                }
                if (fire != null) {
                    SinytraLog.d(TAG, "commit guard firing attempt="
                            + attempt);
                    try {
                        fire.run();
                    } catch (Throwable t) {
                        SinytraLog.w(TAG,
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
            SinytraLog.w(TAG, "commit guard schedule threw", t);
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
        SinytraLog.w(TAG, "commit guard exhausted; no PageStart after "
                + MAX_ATTEMPTS + " attempts");
        try {
            exhausted.run();
        } catch (Throwable t) {
            SinytraLog.w(TAG, "commit guard exhausted threw", t);
        }
    }
}
