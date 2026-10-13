package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;
import org.mozilla.geckowebview.runtime.SinytraLog;
import org.mozilla.geckoview.GeckoSession;

// Rescue + live-session outputs for one GeckoSessionBridge.
//
// Split from GeckoSessionBridge (pure move, zero behavior change): the
// commit-guard assembly (arm/disarm/poke), the exhaustion triage
// (cold-start budget vs close+reopen), the last-navigation re-issue state,
// the raw session outputs (reload/stop/close/settings push) and the
// stateless history index/format helpers. The bridge keeps delegate
// translation + mirror state (mUrl/mTitle/mProgress/history lists) and
// calls through here. Same package, same threads, same ordering.
public final class SessionRescue {
    private static final String TAG = "Sinytra/session";

    // Transport liveness for exhaustion triage (wired by the provider to the
    // JS transport readiness; null = unknown, assume ready = legacy path).
    // Cold extensions: bounded re-arms while the pipeline itself is still
    // coming up (PageStart refills like the reopen budget). Public: the
    // provider (different package) implements it.
    public interface Readiness {
        boolean isReady();
    }

    // Rescue path for guard exhaustion (session window never attached).
    // Implemented by the provider (owns the app context + filter table);
    // unset (tests) degrades reopen to a loud give-up.
    public interface ReopenHost {
        @NonNull
        android.content.Context appContext();

        void repushFilters();

        // Re-assert the view binding after close+open (fresh native window /
        // compositor). Surface resume events do not re-fire on their own —
        // device-observed 2026-10-10: post-reopen NotifyCompositorCreated
        // skipResume with no RecvResumeAndResize after it, page committed
        // with title but pixels stuck blank. Headless hosts no-op.
        void rebindView();
    }

    static final int MAX_COLD_EXTENSIONS = 4;

    // Unclaimed-takeover recovery delay (BISECT_SNC_20261013.md §6/§8 P0):
    // green rounds commit ~1-4s after the last claim (SOE + re-entry +
    // 200), so 10s clears them with margin while staying far inside the
    // 2-minute verdict window. A slow legitimate takeover (the app handling
    // externally for longer) eats one duplicate in-WebView load — accepted,
    // loudly logged.
    static final long OVERRIDE_RECOVERY_DELAY_MS = 10_000L;

    /**
     * Pure (JVM-locked): exhaustion triage. A pipeline that is still
     * bringing itself up gets waited out (re-arm, cold budget); only a
     * ready-but-silent pipeline earns close+reopen. Reopening into an
     * unready pipeline restarts the download against nothing and burns
     * the one reopen on cold start instead of on a real stall.
     */
    static boolean shouldWaitForTransport(boolean ready, int coldUsed) {
        return !ready && coldUsed < MAX_COLD_EXTENSIONS;
    }

    private final GeckoSession mSession;
    // Commit guard for lost LoadUri dispatches (cold-start window): armed on
    // every direct navigation, disarmed by the first commit signal
    // (PageStart / LocationChange) or by design-silent outcomes (policy
    // block / app override / stop / close). A healthy load pays zero
    // overhead; a lost dispatch is re-issued bounded (see the guard).
    private final NavigationCommitGuard mGuard = new NavigationCommitGuard();
    // Last navigation for post-reopen re-issue (guard exhaustion path).
    // Transient navigation state, rescue-owned like the guard generation.
    @Nullable
    private Runnable mLastDispatch;
    @Nullable
    private String mLastUrl;
    // Reopen budget: one rescue reopen per proof-of-life (PageStart refills).
    // A reopen that still finds no commit gives up loudly instead of looping.
    private boolean mReopenUsed;
    @Nullable
    private Readiness mTransportReady;
    private int mColdExtensions;
    @Nullable
    private ReopenHost mReopenHost;
    // Set on close(): a dead bridge must never reopen its session.
    private boolean mClosed;
    // Unclaimed-takeover recovery slot (BISECT_SNC_20261013.md §6 P0):
    // override=true→DENY kills the docshell commit (necessary in every SNC
    // black shape); the sufficient half (no reload, no commit) sits
    // app-side. Single slot: a newer claim for a different URL supersedes
    // (its timer goes inert on inequality); a same-URL re-arm only moves
    // the fields — the earliest timer wins (one storm episode is
    // millisecond-scale, so first-claim+N ≈ last-claim+N). Null = no watch.
    @Nullable
    private String mOverrideUrl;
    @Nullable
    private Runnable mOverrideDispatch;
    // One-shot force-allow named by the fired watch: the next consult for
    // the exact URL returns ALLOW without consulting (consumeRecoveryAllow).
    // A dispatch lost to the cold-start gap can strand it, but any new
    // navigation (arm), commit (noteCommit) or teardown (disarm) clears it —
    // at most one exact-URL consult, loudly logged, self-healing.
    @Nullable
    private String mRecoveryAllowUrl;

    public SessionRescue(@NonNull GeckoSession session) {
        mSession = session;
    }

    public void setTransportReady(@Nullable Readiness readiness) {
        mTransportReady = readiness;
    }

    public void setReopenHost(@Nullable ReopenHost host) {
        mReopenHost = host;
    }

    // Guard liveness for redirect-leg decisions (bridge asks before arming
    // a post-commit redirect leg so a leg never hijacks an in-flight chain:
    // an armed chain's own redispatch already replays the full navigation
    // including its redirects). True from arm() until the chain ends
    // (disarm, exhaustion give-up, close); cold-extension and post-reopen
    // re-arms keep it true.
    private boolean mArmed;

    // Arms the guard for one navigation without resetting any other rescue
    // state. The redispatch runnable must re-issue the same navigation
    // WITHOUT re-arming (it runs inside this generation's budget; re-arming
    // would reset the attempt count and loop forever on a fully wedged
    // pipeline — device-verified).
    void arm(@NonNull Runnable redispatch, @Nullable String url) {
        mLastDispatch = redispatch;
        mLastUrl = url;
        mGuard.arm(redispatch, this::reopenSessionOnce);
        mArmed = true;
        // A new direct navigation supersedes any pending override watch.
        mOverrideUrl = null;
        mOverrideDispatch = null;
        mRecoveryAllowUrl = null;
    }

    // Disarms: the navigation committed (PageStart / LocationChange), was
    // dismissed by design (policy block / app override), or the session died.
    // Any pending override watch dies with the chain (a newer claim re-arms
    // after us via onAppOverride, which disarms first).
    void disarm() {
        mGuard.disarm();
        mArmed = false;
        mOverrideUrl = null;
        mOverrideDispatch = null;
        mRecoveryAllowUrl = null;
    }

    // App claimed a takeover (shouldOverrideUrlLoading true → LoadRequest
    // DENY). The guard chain stands down (no PageStart by design), and a
    // bounded recovery watch arms: no reload-or-commit within the delay
    // re-issues the URL once with a one-shot force-allow. Runs on UI
    // (delegate thread); without a Looper the timer never fires (unit
    // tests) while the slot stays state-testable.
    synchronized void onAppOverride(@NonNull String url,
            @NonNull Runnable recoveryDispatch) {
        disarm();
        mOverrideUrl = url;
        mOverrideDispatch = recoveryDispatch;
        scheduleOverride(url);
    }

    // One-shot force-allow (JVM-locked): true once for the exact URL the
    // fired watch named; misses (including null) return false and preserve
    // the flag, the caller falling through to the normal app consult.
    synchronized boolean consumeRecoveryAllow(@Nullable String url) {
        if (mRecoveryAllowUrl == null || url == null
                || !mRecoveryAllowUrl.equals(url)) {
            return false;
        }
        mRecoveryAllowUrl = null;
        return true;
    }

    // Fires the recovery flight for the named URL (the Handler path and
    // same-package tests): true when the slot still names url — sets the
    // one-shot force-allow and runs the dispatch. Stale timers (superseded
    // or cancelled slots) return false. Single flight: the slot clears
    // before dispatch, so a re-entrant consult consumes the flag, never
    // re-arms (synchronized is re-entrant on the UI thread).
    synchronized boolean fireOverride(@NonNull String url) {
        if (mOverrideUrl == null || !mOverrideUrl.equals(url)) {
            return false;
        }
        Runnable dispatch = mOverrideDispatch;
        mOverrideUrl = null;
        mOverrideDispatch = null;
        if (dispatch == null) {
            return false;
        }
        mRecoveryAllowUrl = url;
        SinytraLog.i(TAG, "override-recovery firing url=" + url
                + " (takeover claimed, no reload or commit within "
                + OVERRIDE_RECOVERY_DELAY_MS + "ms; one-shot force-allow)");
        // Cover the flight with a guard chain: a frozen serving tab shows
        // exactly this signature (dispatch landed = consult, then zero
        // progress) and the latched-silence escape reopens into a fresh,
        // unfrozen tab. Raw redispatch (no re-arm, guard invariant); the
        // override slot stays clear (single flight).
        mLastDispatch = dispatch;
        mLastUrl = url;
        mGuard.arm(dispatch, this::reopenSessionOnce);
        mArmed = true;
        try {
            dispatch.run();
        } catch (Throwable t) {
            SinytraLog.w(TAG, "override-recovery dispatch threw", t);
            mRecoveryAllowUrl = null;
        }
        return true;
    }

    private void scheduleOverride(@NonNull final String url) {
        final android.os.Handler handler;
        try {
            handler = new android.os.Handler(
                    android.os.Looper.getMainLooper());
        } catch (Throwable t) {
            return;
        }
        try {
            handler.postDelayed(() -> fireOverride(url),
                    OVERRIDE_RECOVERY_DELAY_MS);
        } catch (Throwable t) {
            SinytraLog.w(TAG, "override watch schedule threw", t);
        }
    }

    // Whether a guard chain is currently live (for redirect-leg arming).
    boolean isArmed() {
        return mArmed;
    }

    static boolean isAboutBlank(@Nullable String url) {
        // NOTE: compare full strings — a hardcoded length here was once 12
        // for the 11-char "about:blank" and silently never matched.
        return "about:blank".equalsIgnoreCase(url);
    }

    // Last app-visible non-blank commit (delivered PageStart). A redirect
    // LoadRequest for a different document after such a commit arms a
    // bounded resume watch (redirect legs whose target never commits stall
    // silently forever — device-verified S3 cross-origin switch). Cleared
    // by new direct navigations (their own guard chain covers the window).
    @Nullable
    private String mLastCommittedUrl;

    // App-visible commit (non-blank by construction: the caller delivers
    // only non-swallowed starts). Traversals/reloads keep it: their commits
    // re-set it, and the guard-quiet check in watchRedirectLeg governs.
    void noteCommit(@NonNull String url) {
        mLastCommittedUrl = url;
        // Commit arrived: the takeover happened (or the recovery landed) —
        // the override watch is unneeded.
        mOverrideUrl = null;
        mOverrideDispatch = null;
        mRecoveryAllowUrl = null;
    }

    void clearCommit() {
        mLastCommittedUrl = null;
    }

    /**
     * Pure (JVM-locked): arm a bounded resume watch for a post-commit
     * redirect leg. Only a quiet pipeline (no live guard chain — an armed
     * chain's own redispatch already replays the full navigation including
     * its redirects) after a delivered non-blank commit, for a redirect to
     * a different document, earns a watch. Same-document (fragment),
     * non-redirect, uncommitted, and blank navigations stay quiet: they
     * either expect no PageStart or are covered by their own chain.
     */
    static boolean shouldArmRedirectLeg(boolean guardArmed,
            @Nullable String committedUrl, boolean isRedirect,
            @Nullable String lrUrl) {
        if (guardArmed || committedUrl == null || !isRedirect || lrUrl == null
                || isAboutBlank(lrUrl)) {
            return false;
        }
        return isCrossDocument(committedUrl, lrUrl);
    }

    // Pure (JVM-locked): different documents ignoring the fragment —
    // case-insensitive, no normalization beyond that (redirect targets
    // arriving byte-identical still count as same and stay quiet).
    static boolean isCrossDocument(@NonNull String committedUrl,
            @NonNull String lrUrl) {
        return !stripFragment(committedUrl)
                .equalsIgnoreCase(stripFragment(lrUrl));
    }

    @NonNull
    private static String stripFragment(@NonNull String url) {
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }

    // Post-commit redirect leg entry: arms the standard bounded chain for
    // the target as a fresh top-level navigation (proven to commit: direct
    // loads draw). The redispatch is raw — routing through a re-arming
    // entry would loop (same invariant as every other redispatch).
    // Consults/trickle extend via the poke path, so slow-but-alive redirect
    // chains are never duplicated; the target's own PageStart disarms at
    // once on the healthy path. History wart (accepted): the retry lands as
    // its own entry instead of folding into the redirect chain —
    // REPLACE_HISTORY left for a follow-up (no CTS redirect test waits out
    // the 30s-class budget, so sequences are unchanged there).
    void watchRedirectLeg(@NonNull String uri, boolean isRedirect,
            @NonNull Runnable redispatch) {
        if (!shouldArmRedirectLeg(mArmed, mLastCommittedUrl, isRedirect,
                uri)) {
            return;
        }
        arm(redispatch, uri);
        SinytraLog.d(TAG, "redirectLeg watch url=" + uri);
    }

    /**
     * NeckO consult reached Java for any URL: the pipeline is alive, so a
     * quiet guard check extends instead of re-issuing (trickle networks
     * commit slower than the whole retry budget — re-issuing restarts the
     * download from zero and guarantees it never finishes).
     */
    void poke(@Nullable String url) {
        mGuard.poke(url);
    }

    // Proof of life: the pipeline serves this session — refill the
    // one-shot reopen budget (and the cold-extension budget) for
    // future stalls.
    void noteProofOfLife() {
        mReopenUsed = false;
        mColdExtensions = 0;
    }

    // Closed: a dead bridge must never reopen its session.
    void onClosed() {
        mClosed = true;
        disarm();
    }

    public void reload(@Nullable String currentUrl) {
        // Commit guard (same class as loadUrl/loadFileUrl/loadDataUri: a
        // dropped LoadUri dispatch yields PageStart-then-silence with no
        // recovery — device-observed on reload). Reuses the reopen path via
        // mLastDispatch/mLastUrl. The redispatch calls mSession.reload()
        // directly, never this method: routing back through reload() would
        // re-arm (fresh generation, attempt reset) and turn the bounded
        // retry into an infinite loop on a wedged pipeline (same reason as
        // the loadUrl raw-dispatch pattern). Boundaries: a reload on the
        // implicit about:blank still arms — its blank PageStart disarms at
        // once, zero overhead. stop() races an in-flight check via the
        // guard's generation liveness (stop disarms; the check goes inert),
        // existing mechanism, nothing new.
        final Runnable redispatch = () -> mSession.reload();
        arm(redispatch, currentUrl);
        mSession.reload();
    }

    void stop() {
        // The app cancelled: no commit will arrive, and the guard must not
        // resurrect a stopped navigation.
        disarm();
        mSession.stop();
    }

    void close() {
        onClosed();
        long startMs = android.os.SystemClock.uptimeMillis();
        try {
            // session.close is @UiThread; the creator thread may be a
            // background looper (CTS WebViewStartupTest) — hop, bounded.
            org.mozilla.geckowebview.runtime.MainThread.call(() -> {
                mSession.close();
                return null;
            });
        } finally {
            SinytraLog.d(TAG, "session.close took "
                    + (android.os.SystemClock.uptimeMillis() - startMs)
                    + "ms");
        }
    }

    // Commit-guard exhaustion: bounded redispatches found no commit — not
    // even a blank PageStart, i.e. the session window never attached
    // natively. Rescue once per proof-of-life: close + reopen the session
    // (fresh native window; delegates/filters persist on the bridge) and
    // re-issue the last navigation under a fresh bounded chain. A reopen
    // that still finds no commit gives up loudly — same white screen as
    // before, but diagnosed instead of silent. Runs on main (the guard
    // fires on the main handler; open/close hop direct-run there).
    private void reopenSessionOnce() {
        // The firing chain is consumed here; cold-extension and post-reopen
        // paths re-arm below, give-up paths leave it disarmed.
        mArmed = false;
        if (mClosed) {
            return;
        }
        // Transport triage (2026-10-08 coolapk fresh-process round):
        // exhaustion fired while the JS transport itself was still
        // installing (first ack landed ~32s in, past both 14s chains).
        // Reopening into an unready pipeline restarts the download
        // against nothing and burns the one reopen on cold start.
        // Wait it out instead (bounded); only a ready-but-silent
        // pipeline earns close+reopen.
        boolean ready = true;
        try {
            Readiness readiness = mTransportReady;
            if (readiness != null) {
                ready = readiness.isReady();
            }
        } catch (Throwable t) {
            SinytraLog.w(TAG, "transport readiness threw", t);
        }
        if (shouldWaitForTransport(ready, mColdExtensions)) {
            mColdExtensions++;
            SinytraLog.i(TAG,
                    "commit guard exhausted but transport not ready; waiting"
                            + " (cold extension " + mColdExtensions + "/"
                            + MAX_COLD_EXTENSIONS + ") url=" + mLastUrl);
            Runnable last = mLastDispatch;
            if (last != null) {
                mGuard.arm(last, this::reopenSessionOnce);
                mArmed = true;
                try {
                    last.run();
                } catch (Throwable t) {
                    SinytraLog.w(TAG,
                            "redispatch after cold extension threw", t);
                }
            }
            return;
        }
        if (mReopenUsed) {
            SinytraLog.w(TAG, "commit guard exhausted after reopen;"
                    + " giving up url=" + mLastUrl);
            return;
        }
        mReopenUsed = true;
        SinytraLog.i(TAG,
                "commit guard exhausted; reopening session once url="
                        + mLastUrl);
        ReopenHost host = mReopenHost;
        if (host == null) {
            SinytraLog.w(TAG, "no reopen host; giving up");
            return;
        }
        try {
            mSession.close();
        } catch (Throwable t) {
            SinytraLog.w(TAG, "session close before reopen threw", t);
        }
        try {
            android.content.Context app = host.appContext();
            mSession.open(org.mozilla.geckowebview.runtime.GeckoRuntimeHolder
                    .get(app));
        } catch (Throwable t) {
            SinytraLog.w(TAG, "session reopen threw; giving up", t);
            return;
        }
        // Fresh native window: re-assert the view binding BEFORE the
        // navigation is re-issued, so the new compositor gets a surface +
        // resume while content is still on its way (never throws: a failed
        // rebind degrades to headless, commit path unaffected).
        try {
            host.rebindView();
        } catch (Throwable t) {
            SinytraLog.w(TAG, "rebindView after reopen threw", t);
        }
        try {
            host.repushFilters();
        } catch (Throwable t) {
            SinytraLog.w(TAG, "filter repush after reopen threw", t);
        }
        Runnable last = mLastDispatch;
        if (last != null) {
            mGuard.arm(last, this::reopenSessionOnce);
            mArmed = true;
            try {
                last.run();
            } catch (Throwable t) {
                SinytraLog.w(TAG, "redispatch after reopen threw", t);
            }
        }
    }

    // Live-push WebSettings state onto the session. Every mapped key is
    // non-initOnly in GV158 (allowJavascript/userAgentMode/viewportMode/
    // userAgentOverride/allowImages), so no construction plumbing is needed — the
    // provider calls this before every navigation (set-then-load is the
    // CTS norm) and the facade defaults (JS off, mobile UA) apply
    // verbatim. Never throws: a closed session degrades silently.
    public void applyWebSettings(boolean javaScriptEnabled,
            @Nullable String userAgentOverride, boolean desktopMode,
            boolean wideViewport, boolean loadsImagesAutomatically,
            boolean fileReadsAllowed) {
        try {
            org.mozilla.geckoview.GeckoSessionSettings settings =
                    mSession.getSettings();
            settings.setAllowJavascript(javaScriptEnabled);
            settings.setAllowImages(loadsImagesAutomatically);
            settings.setUserAgentMode(desktopMode
                    ? org.mozilla.geckoview.GeckoSessionSettings
                            .USER_AGENT_MODE_DESKTOP
                    : org.mozilla.geckoview.GeckoSessionSettings
                            .USER_AGENT_MODE_MOBILE);
            settings.setViewportMode(wideViewport
                    ? org.mozilla.geckoview.GeckoSessionSettings
                            .VIEWPORT_MODE_MOBILE
                    : org.mozilla.geckoview.GeckoSessionSettings
                            .VIEWPORT_MODE_DESKTOP);
            settings.setUserAgentOverride(userAgentOverride);
            // File-reads flag (0020): the bundle re-dispatches on change,
            // so the strict-origin mirror tracks the latest navigation.
            settings.setSinytraFileReadsAllowed(fileReadsAllowed);
        } catch (Throwable t) {
            SinytraLog.w(TAG, "applyWebSettings threw", t);
        }
    }

    // Pushes the image policy onto the live session NOW (setter path, no
    // navigation). The Gecko bundle only re-dispatches on change, so
    // same-value sets are free no-ops. Hops to main, bounded (MainThread):
    // setters may arrive on any thread (CTS drives them off-UI), while the
    // session lives on main. Never throws: delivery failure degrades to the
    // next pre-navigation push. Device-locked (needs a live session).
    public void applyImagesPolicy(boolean loadsImagesAutomatically) {
        try {
            org.mozilla.geckowebview.runtime.MainThread.call(() -> {
                mSession.getSettings()
                        .setAllowImages(loadsImagesAutomatically);
                return null;
            });
        } catch (Throwable t) {
            SinytraLog.w(TAG, "applyImagesPolicy threw", t);
        }
    }

    // Stateless history index/format helpers (verbatim from the bridge):
    // index resolution against a Gecko-owned snapshot and one-shot log
    // formatting for the copy=0 diagnosis matrix.

    static int targetIndex(
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> list, int steps) {
        int current = safeIndex(list);
        if (current < 0) {
            return -1;
        }
        int target = current + steps;
        return target >= 0 && target < list.size() ? target : -1;
    }

    @Nullable
    static String currentHistoryUrl(
            @Nullable List<GeckoSession.HistoryDelegate.HistoryItem> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        int index = safeIndex(list);
        if (index < 0 || index >= list.size()) {
            return null;
        }
        try {
            String uri = list.get(index).getUri();
            if (uri == null || "about:blank".equalsIgnoreCase(uri)) {
                return null;
            }
            return uri;
        } catch (UnsupportedOperationException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    static String describe(
            @Nullable List<GeckoSession.HistoryDelegate.HistoryItem> list) {
        if (list == null) {
            return "null";
        }
        return "size=" + list.size() + " index=" + safeIndex(list)
                + " urls=" + snapshotUrls(list);
    }

    static int safeIndex(
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> list) {
        if (list instanceof GeckoSession.HistoryDelegate.HistoryList) {
            try {
                return ((GeckoSession.HistoryDelegate.HistoryList) list)
                        .getCurrentIndex();
            } catch (UnsupportedOperationException e) {
                return -1;
            }
        }
        return list.isEmpty() ? -1 : list.size() - 1;
    }

    @NonNull
    static String snapshotUrls(
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (GeckoSession.HistoryDelegate.HistoryItem item : list) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            try {
                sb.append(item.getUri());
            } catch (UnsupportedOperationException e) {
                sb.append("<invalid>");
            }
        }
        return sb.append(']').toString();
    }
}
