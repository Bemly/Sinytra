package org.mozilla.geckowebview.session;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

// Owns one GeckoSession + its bridges, independent of any View (session must NOT
// depend on view per ARCHITECTURE.md §3). The host View attaches itself from
// the view layer (GeckoViewHost.bind). One instance = one WebView backend.
public final class GeckoSessionBridge
        implements NavigationBridge.Host, ProgressBridge.Host {
    public interface Client {
        void onUrlChanged(@NonNull String url);
        void onCanGoBackChanged(boolean canGoBack);
        void onCanGoForwardChanged(boolean canGoForward);
        boolean shouldOverrideUrlLoading(@NonNull String url);
        void onPageStarted(@NonNull String url);
        void onPageFinished(boolean success);
        void onProgressChanged(int progress);
        void onTitleChanged(@Nullable String title);
        void onLoadError(int errorCode, int sslPrimaryError, @NonNull String description,
                @Nullable String failingUrl);
    }

    private static final String TAG = "Sinytra/session";

    private final GeckoSession mSession;
    private final Client mClient;
    private String mUrl;
    // Pre-redirect URL of the current navigation (see loadUrl). Null until
    // the first loadUrl; getOriginalUrl falls back to getUrl() then.
    @Nullable
    private String mOriginalUrl;
    private String mTitle;
    // Title-freshness clock (CTS WebSettingsTest image family): Gecko delivers
    // ContentDelegate.onTitleChange up to ~500ms AFTER
    // ProgressDelegate.onPageStop for JS-onload titles (document.title set in
    // body/img onload; the TitleChange IPC lands later — JsTitleProbeActivity
    // G/H: title "0" reachable ~500ms after PageFinished). Chromium delivers
    // title BEFORE finished and CTS asserts getTitle() immediately after
    // loadDataAndWaitForCompletion (no poll), so forwarding PageStop at once
    // reads stale "". A success finish whose page produced no title yet is
    // held until the first post-stop title or TITLE_HOLD_TIMEOUT_MS (bridge
    // mirror state, same kind as mTitle/mUrl/mProgress above).
    private long mLastPageStartMs;
    private long mLastTitleMs;
    // True between PageStart and the forwarded finish (holds delay the
    // forward; the flag clears when the app-visible completion fires).
    // Guards the mid-navigation title adoption rule below.
    private boolean mNavigating;
    @Nullable
    private Hold mPendingHold;
    // Fresh WebView reports 100 (CTS INITIAL_PROGRESS): nothing is loading.
    // A new navigation drops it to 0 (onPageStarted) and a finished load
    // completes it back to 100 (onPageFinished) — Chromium contract.
    private int mProgress = 100;
    private boolean mCanGoBack;
    private boolean mCanGoForward;
    // True once the app explicitly loaded an about: URL. Gecko seeds every
    // fresh session with a pristine about:blank document that Chromium
    // never reports (fresh copyBackForwardList must be size 0 / current
    // null / index -1 — CTS WebBackForwardListTest.testGetCurrentItem).
    // The translation layer drops that leading entry unless the app asked
    // for it (GeckoBackForwardList ctor flag).
    private boolean mExplicitAboutLoad;
    // Pending history-traversal target (goBack/goForward/goBackOrForward):
    // the URL we asked Gecko to traverse to. Silent traversals (bfcache
    // restores with no history event) leave OUR translated list frozen, so
    // the copy path may report this index once arrival is confirmed (see
    // confirmedTraversalIndex). Cleared by new navigations and by Gecko
    // catching up. Device-locked (WebViewTest.testGoBackAndForward).
    @Nullable
    private String mPendingTraversalUrl;
    // Latest SessionState from onSessionStateChange (Gecko owns history;
    // we only translate it — ARCHITECTURE.md §4).
    @Nullable
    private GeckoSession.SessionState mSessionState;
    // Latest HistoryList from onHistoryStateChange — the live, authoritative
    // history object. onSessionStateChange snapshots may lag (empty entries
    // until the next flush), so copyBackForwardList prefers this.
    @Nullable
    private GeckoSession.HistoryDelegate.HistoryList mHistoryList;
    // Commit guard for lost LoadUri dispatches (cold-start window): armed on
    // every direct navigation, disarmed by the first commit signal
    // (PageStart / LocationChange) or by design-silent outcomes (policy
    // block / app override / stop / close). A healthy load pays zero
    // overhead; a lost dispatch is re-issued bounded (see the guard).
    private final NavigationCommitGuard mCommitGuard =
            new NavigationCommitGuard();

    @Nullable
    private ContentBridge.Host mContentHost;
    @Nullable
    private GeckoSession.ProgressDelegate.SecurityInformation mSecurityInfo;

    public GeckoSessionBridge(@NonNull Client client) {
        mClient = client;
        // new GeckoSession() is @UiThread (Autofill reads surface bounds
        // in the ctor), but the framework may create the provider on any
        // looper thread (CTS WebViewStartupTest background creation) — so
        // construction + delegate wiring hop to main, bounded (MainThread).
        mSession = org.mozilla.geckowebview.runtime.MainThread.call(() -> {
            GeckoSession session = new GeckoSession();
            session.setNavigationDelegate(new NavigationBridge(this));
            session.setHistoryDelegate(new GeckoSession.HistoryDelegate() {
                @Override
                public void onHistoryStateChange(@NonNull GeckoSession s,
                        @NonNull GeckoSession.HistoryDelegate.HistoryList historyList) {
                    mHistoryList = historyList;
                    adoptHistoryUrl(historyList);
                    Log.i(TAG, "history: onHistoryStateChange size=" + historyList.size()
                            + " index=" + safeIndex(historyList)
                            + " urls=" + snapshotUrls(historyList));
                }

                @Override
                public GeckoResult<Boolean> onVisited(@NonNull GeckoSession s,
                        @NonNull String url, @Nullable String lastVisitedURL, int flags) {
                    return GeckoResult.fromValue(Boolean.FALSE);
                }

                @Override
                public GeckoResult<boolean[]> getVisited(@NonNull GeckoSession s,
                        @NonNull String[] urls) {
                    return GeckoResult.fromValue(new boolean[urls.length]);
                }
            });
            ProgressBridge progress = new ProgressBridge(this);
            session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                @Override
                public void onPageStart(@NonNull GeckoSession s, @NonNull String url) {
                    progress.onPageStart(s, url);
                }

                @Override
                public void onPageStop(@NonNull GeckoSession s, boolean success) {
                    progress.onPageStop(s, success);
                }

                @Override
                public void onProgressChange(@NonNull GeckoSession s, int progressValue) {
                    progress.onProgressChange(s, progressValue);
                }

                @Override
                public void onSecurityChange(@NonNull GeckoSession s,
                        @NonNull SecurityInformation securityInfo) {
                    progress.onSecurityChange(s, securityInfo);
                }

                @Override
                public void onSessionStateChange(@NonNull GeckoSession s,
                        @NonNull GeckoSession.SessionState sessionState) {
                    mSessionState = sessionState;
                    adoptHistoryUrl(sessionState);
                    Log.i(TAG, "history: onSessionStateChange size=" + sessionState.size()
                            + " index=" + safeIndex(sessionState)
                            + " urls=" + snapshotUrls(sessionState));
                }
            });
            session.setContentDelegate(new GeckoSession.ContentDelegate() {
                @Override
                public void onTitleChange(@NonNull GeckoSession s,
                        @Nullable String title) {
                    onTitleArrived(title);
                }
            });
            return session;
        });
        mContentHost = null;
    }

    // P1 delegates that must not clobber each other: the provider owns one
    // Permission/Prompt/Content delegate each and fans out to sub-bridges.
    public void setExtraDelegates(@NonNull PermissionBridge.Host permHost,
            @NonNull PromptBridge.Host promptHost,
            @NonNull ContentBridge.Host contentHost) {
        // set*Delegate is @UiThread; the creator thread may be a background
        // looper (CTS WebViewStartupTest) — hop, bounded (MainThread).
        org.mozilla.geckowebview.runtime.MainThread.call(() -> {
            mSession.setPermissionDelegate(new PermissionBridge(permHost));
            mSession.setPromptDelegate(new PromptBridge(promptHost));
        mSession.setContentDelegate(new ContentBridge(new ContentBridge.Host() {
            @Override
            public void onDownloadStart(@NonNull String url, @Nullable String userAgent,
                    @Nullable String contentDisposition, @NonNull String mimeType,
                    long contentLength) {
                contentHost.onDownloadStart(url, userAgent, contentDisposition,
                        mimeType, contentLength);
            }

            @Override
            public void onTitleChanged(@Nullable String title) {
                onTitleArrived(title);
                contentHost.onTitleChanged(title);
            }

            @Override
            public void onFullScreen(boolean fullScreen) {
                contentHost.onFullScreen(fullScreen);
            }

            @Override
            public void onCloseWindow() {
                contentHost.onCloseWindow();
            }

            @Override
            public void onFocusRequest() {
                contentHost.onFocusRequest();
            }

            @Override
            public void onCrash() {
                contentHost.onCrash();
            }
        }));
            return null;
        });
    }

    // P2 intercept bridge (subframe allow/deny): installed separately so
    // NavigationBridge stays a pure translator. setNavigationDelegate is
    // @UiThread — hop, bounded (MainThread), same background-creation case.
    public void setInterceptBridge(@NonNull InterceptBridge intercept) {
        org.mozilla.geckowebview.runtime.MainThread.call(() -> {
            mSession.setNavigationDelegate(new SplitNavigationDelegate(
                    new NavigationBridge(this), intercept,
                    this::completeBlockedNavigation));
            return null;
        });
    }

    @NonNull
    public GeckoSession session() {
        return mSession;
    }

    // --- NavigationBridge.Host ---

    @Override
    public void onUrlChanged(@NonNull String url) {
        mUrl = url;
        mCommitGuard.disarm();
        mClient.onUrlChanged(url);
    }

    @Override
    public void onCanGoBackChanged(boolean canGoBack) {
        mCanGoBack = canGoBack;
        mClient.onCanGoBackChanged(canGoBack);
    }

    @Override
    public void onCanGoForwardChanged(boolean canGoForward) {
        mCanGoForward = canGoForward;
        mClient.onCanGoForwardChanged(canGoForward);
    }

    @Override
    public boolean shouldOverrideUrlLoading(@NonNull String url) {
        return mClient.shouldOverrideUrlLoading(url);
    }

    @Override
    public void onAppOverrideDeny(@NonNull String url) {
        // The app took over this URL (no PageStart by design): the commit
        // guard must stand down, or it would re-issue a taken-over URL.
        mCommitGuard.disarm();
    }

    @Override
    public void onLoadError(int errorCode, int sslPrimaryError,
            @NonNull String description, @Nullable String failingUrl) {
        mClient.onLoadError(errorCode, sslPrimaryError, description, failingUrl);
    }

    // --- ProgressBridge.Host ---

    @Override
    public void onPageStarted(@NonNull String url) {
        // Commit signal: the LoadUri dispatch landed (disarms the guard).
        mCommitGuard.disarm();
        // Chromium order: finished-before-next-start. A held success-finish
        // belongs to the previous navigation and must fire before this one
        // begins (CTS issues the next load right after the assert, so holds
        // never overlap there; redirects/app races flush here instead of
        // leaking order).
        firePendingHold();
        mLastPageStartMs = now();
        mNavigating = true;
        // Chromium resets progress on every new navigation; Gecko only emits
        // progress ticks while bytes are in flight (instant local loads may
        // emit none at all — CTS WebViewTest.testLoadUrl reads 0 forever).
        mProgress = 0;
        // Pairing bit for the pristine-blank swallow below: a blank Start
        // arms it, any non-blank Start disarms it. Stops pair with the most
        // recent Start, so only a blank Stop for a blank Start is swallowed —
        // a real load failing while mUrl is still blank still reports.
        mBlankStartOutstanding = isAboutBlank(url);
        mClient.onPageStarted(url);
    }

    @Override
    public void onPageFinished(boolean success) {
        if (success) {
            // Swallow the pristine initial about:blank completion: Gecko
            // reports it, Chromium never does, and forwarding it arms every
            // load-gate (CTS WebViewSyncLoader mLoaded) while the app's real
            // load is still in flight — later asserts then read stale blank
            // state. Paired by Start (mBlankStartOutstanding), so a real
            // load failing while mUrl is still blank still reports;
            // explicit about: loads (flagged at loadUrl time) always report.
            // (URL adoption for the blank slot happens lazily in getUrl(),
            // not here — history updates land after PageStop.)
            if (isAboutBlank(mUrl) && !mExplicitAboutLoad
                    && mBlankStartOutstanding) {
                mBlankStartOutstanding = false;
                mNavigating = false;
                return;
            }
            mBlankStartOutstanding = false;
            // Title-freshness hold (see mLastPageStartMs): about: documents
            // cannot have a title — forward at once. Otherwise forward at
            // once only when the title is both present and from this
            // navigation; fresh-title stops (static <title> lands between
            // start and stop) pay zero delay. Empty/stale stops hold: Gecko
            // reports a placeholder empty title at commit BEFORE PageStop,
            // which would fake a timestamp-only check (confirmed on device:
            // no hold armed, first getTitle() read still ""), and JS-onload titles
            // land after the stop.
            if (!isAboutBlank(mUrl)
                    && shouldHoldForTitle(mTitle, mLastTitleMs,
                            mLastPageStartMs)) {
                armFinishHold();
                return;
            }
        } else {
            mNavigating = false;
            mClient.onPageFinished(false);
            return;
        }
        fireFinishedSuccess();
    }

    // Title arrival: mirror state, stamp the clock, fan out, then release a
    // held success-finish (if any) so getTitle() is fresh when the app's
    // onPageFinished runs. Single funnel for both content-delegate sites.
    private void onTitleArrived(@Nullable String title) {
        if (!shouldAdoptTitle(title, mTitle, mNavigating)) {
            // Commit placeholder: Gecko reports "" for the new document
            // before its real title arrives. Adopting it would regress
            // getTitle() mid-navigation (Chromium keeps the previous title
            // until the new one lands) and spuriously satisfy title polls
            // (CTS waitForNonEmptyImage exits on "" and asserts too early).
            return;
        }
        mTitle = title;
        mLastTitleMs = now();
        mClient.onTitleChanged(title);
        // An empty arrival carries no news for a held finish (the hold
        // waits for a present, fresh title); only non-empty arrivals and
        // the timeout release it.
        if (title != null && !title.isEmpty()) {
            firePendingHold();
        }
    }

    // Pure (JVM-locked): adopt an incoming title unless it would regress a
    // known title to empty mid-navigation. Outside navigations (settled
    // pages, history rest state) empty is adopted honestly.
    static boolean shouldAdoptTitle(@Nullable String incoming,
            @Nullable String current, boolean navigating) {
        if ((incoming == null || incoming.isEmpty()) && navigating
                && current != null && !current.isEmpty()) {
            return false;
        }
        return true;
    }

    // Pure (JVM-locked in TitleHoldTest): forward at once only when the
    // title is both present and from this navigation. Times are
    // SystemClock.uptimeMillis() samples; same-ms ties read fresh (a title
    // stamped in the navigation's own start ms cannot predate it). An empty
    // title always holds: it is either the commit placeholder (a real title
    // follows after the stop) or a genuinely title-less page (the timeout
    // releases it unchanged — slow but correct).
    static boolean shouldHoldForTitle(@Nullable String currentTitle,
            long lastTitleMs, long lastPageStartMs) {
        if (currentTitle == null || currentTitle.isEmpty()) {
            return true;
        }
        return lastTitleMs < lastPageStartMs;
    }

    // Upper bound for the title-freshness hold: ~2x the observed ~500ms
    // TitleChange-after-PageStop lag (JsTitleProbeActivity G/H), covering
    // burst-slowness without stalling title-less pages forever.
    private static final long TITLE_HOLD_TIMEOUT_MS = 1000L;

    /** Pending success-finish awaiting title freshness. Single-fire. */
    private static final class Hold {
        boolean fired;
    }

    private void armFinishHold() {
        if (mPendingHold != null && !mPendingHold.fired) {
            // Defensive: PageStart flushes first so holds never overlap;
            // keep the earliest rather than stacking.
            Log.w(TAG, "finish hold already armed");
            return;
        }
        final Hold hold = new Hold();
        mPendingHold = hold;
        Log.d(TAG, "finish hold armed url=" + mUrl);
        final android.os.Handler handler;
        try {
            handler = new android.os.Handler(android.os.Looper.getMainLooper());
        } catch (Throwable t) {
            // No Looper (JVM unit tests): nowhere to wait, forward at once.
            firePendingHold();
            return;
        }
        try {
            handler.postDelayed(() -> {
                if (hold.fired) {
                    return;
                }
                Log.d(TAG, "finish hold timeout url=" + mUrl);
                firePendingHold();
            }, TITLE_HOLD_TIMEOUT_MS);
        } catch (Throwable t) {
            Log.w(TAG, "finish hold schedule threw", t);
            firePendingHold();
        }
    }

    private void firePendingHold() {
        Hold hold = mPendingHold;
        if (hold == null || hold.fired) {
            return;
        }
        hold.fired = true;
        mPendingHold = null;
        fireFinishedSuccess();
    }

    // Terminal-100 completion (Chromium contract): a finished load always
    // reports progress 100, even when Gecko emitted no (or partial) ticks
    // for it. State here, event in ClientFanOut (which fires 100 before
    // forwarding finished — order locked by JVM test).
    private void fireFinishedSuccess() {
        mNavigating = false;
        if (mProgress < 100) {
            mProgress = 100;
        }
        mClient.onPageFinished(true);
        flushHistory();
    }

    // Pairing bit for the pristine-blank finish swallow (see
    // onPageStarted/onPageFinished): only a blank Stop for a blank Start
    // is swallowed.
    private boolean mBlankStartOutstanding;

    private static boolean isAboutBlank(@Nullable String url) {
        // NOTE: compare full strings — a hardcoded length here was once 12
        // for the 11-char "about:blank" and silently never matched.
        return "about:blank".equalsIgnoreCase(url);
    }

    private static long now() {
        try {
            return android.os.SystemClock.uptimeMillis();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Current non-blank URL from Gecko-owned history (live list preferred,
     * session snapshot fallback), or null when history holds no usable URL.
     * Device-locked (CTS WebViewTest.testLoadUrl/testGetCurrentItem); JVM
     * cannot construct the Gecko history types.
     */
    // Mirror Gecko-owned history-current into mUrl when it carries news:
    // redirect finalization, back/forward targets, and any commit whose
    // LocationChange never arrived. Blank never overwrites real (initial
    // blank updates); equal is a no-op. Ordering risk (stale update landing
    // after a newer loadUrl) is negligible: Gecko emits history in commit
    // order. Device-locked (testLoadUrl/testGetCurrentItem/goBack flows).
    private void adoptHistoryUrl(
            @Nullable List<GeckoSession.HistoryDelegate.HistoryItem> list) {
        String current = currentHistoryUrl(list);
        if (current != null && !current.equals(mUrl)) {
            mUrl = current;
        }
        // Gecko caught up to (or moved past) our pending target: drop it so
        // the copy path reports Gecko state verbatim again. A stale
        // pre-traversal update carries the OLD index URL, never the target,
        // so it cannot clear prematurely.
        if (mPendingTraversalUrl != null
                && mPendingTraversalUrl.equals(current)) {
            mPendingTraversalUrl = null;
        }
    }

    /**
     * Confirmed pending-traversal index for the copy path, or -1. Applies
     * only when arrival is confirmed (current mUrl equals the recorded
     * target — LocationChange arrived): an unconfirmed pending target
     * reports nothing, so a lost traversal degrades to an honest stale
     * list instead of a fabricated move. The returned index is resolved
     * against the SAME snapshot the caller builds the list from.
     */
    // Cross-package (provider copy path): public by necessity, like the
    // other bridge state accessors above.
    @Nullable
    public String confirmedTraversalUrl() {
        if (mPendingTraversalUrl == null
                || !mPendingTraversalUrl.equals(mUrl)) {
            return null;
        }
        return mPendingTraversalUrl;
    }

    @Nullable
    private String currentHistoryUrl() {
        String fromLive = currentHistoryUrl(mHistoryList);
        if (fromLive != null) {
            return fromLive;
        }
        return currentHistoryUrl(mSessionState);
    }

    @Nullable
    private static String currentHistoryUrl(
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
            if (uri == null || isAboutBlank(uri)) {
                return null;
            }
            return uri;
        } catch (UnsupportedOperationException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    @Override
    public void onProgressChanged(int progress) {
        mProgress = progress;
        mClient.onProgressChanged(progress);
    }

    @Override
    public void onSecurityChanged(
            @NonNull GeckoSession.ProgressDelegate.SecurityInformation securityInfo) {
        mSecurityInfo = securityInfo;
    }

    // --- P0 navigation surface (called by GeckoWebViewProvider) ---

    public void loadUrl(@NonNull String url) {
        loadUrl(url, false);
    }

    /**
     * Navigation with an optional cache bypass (WebSettings.LOAD_NO_CACHE
     * must re-fetch instead of serving cache; the flag has no other
     * mapping — LOAD_CACHE_ONLY/ELSE_NETWORK stay honest gaps). The
     * one-shot path (Interception.navigateTo) always uses the plain form:
     * synthetic bodies never interact with the HTTP cache.
     */
    public void loadUrl(@NonNull String url, boolean bypassCache) {
        if (url != null && url.regionMatches(true, 0, "about:", 0, 6)) {
            mExplicitAboutLoad = true;
        }
        // Original URL (Chromium getOriginalUrl contract): the URL the load
        // was initiated with, before any server redirect. LocationChange
        // never fires for intercepted channels, so the redirect target
        // cannot be distinguished arriving — but the pre-redirect URL is
        // exactly what the app asked for (WebViewTest.testGetOriginalUrl).
        mOriginalUrl = url;
        mPendingTraversalUrl = null;
        // Optimistic URL: the app asked for this document now. LocationChange
        // never fires for intercepted channels and history updates land after
        // PageStop, so without this getUrl() reads stale state until Gecko
        // catches up (WebViewTest.testLoadUrl). Redirect finalization and
        // history navigation correct it via adoptHistoryUrl below.
        if (url != null) {
            mUrl = url;
        }
        // Arm the commit guard before dispatching: if the LoadUri message is
        // dropped (cold-start listener gap) no PageStart ever arrives and the
        // guard re-issues this same navigation, bounded. Any commit signal,
        // policy block, app override, stop or close disarms it.
        final String guardUrl = url;
        mCommitGuard.arm(() -> loadUrl(guardUrl, bypassCache));
        if (bypassCache) {
            mSession.load(new GeckoSession.Loader().uri(url)
                    .flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE));
        } else {
            mSession.loadUri(url);
        }
    }

    /**
     * Serves a file:// document the Gecko child cannot read itself
     * (provider-UID sandbox, FileBridge): same navigation bookkeeping as
     * loadUrl, but the bytes go through Loader.data (identity becomes
     * data:, the documented LoadDataHandler edge).
     */
    public void loadFileUrl(@NonNull String url, @NonNull byte[] data,
            @NonNull String mimeType) {
        mExplicitAboutLoad = false;
        mOriginalUrl = url;
        mPendingTraversalUrl = null;
        mUrl = url;
        final byte[] guardData = data;
        mCommitGuard.arm(() -> loadFileUrl(url, guardData, mimeType));
        mSession.load(new GeckoSession.Loader().data(data, mimeType));
    }

    /**
     * Serves a loadData document through Loader.data with the same
     * navigation bookkeeping as loadUrl. The data: identity is optimistic
     * (mirrors GeckoSession.Loader.createDataUri byte-for-byte:
     * {@code data:<mime>;base64,<NO_WRAP bytes>}, verified against the
     * GeckoView source, no reflection): without it mUrl stays null until
     * history adoption lands after PageStop, and a PageStop arriving first
     * is silently dropped for null URL (CTS load gates hang forever —
     * WebViewSyncLoader triple gate). Later history adoption overwrites an
     * identical value (no-op) or corrects drift exactly like loadUrl.
     * Device-locked (every CTS loadData test asserts through getUrl()).
     */
    public void loadDataUri(@NonNull byte[] data, @NonNull String mimeType) {
        mExplicitAboutLoad = false;
        String url = "data:" + mimeType + ";base64,"
                + android.util.Base64.encodeToString(data,
                        android.util.Base64.NO_WRAP);
        mOriginalUrl = url;
        mPendingTraversalUrl = null;
        mUrl = url;
        final byte[] guardData = data;
        mCommitGuard.arm(() -> loadDataUri(guardData, mimeType));
        mSession.load(new GeckoSession.Loader().data(data, mimeType));
    }

    /**
     * Completes a main-frame navigation the settings policy blocked
     * (SplitNavigationDelegate DENY): Gecko fires nothing after a
     * LoadRequest DENY, so without this every load-gate (CTS
     * WebViewSyncLoader triple gate, app spinners) hangs forever on a
     * blocked load. Synthesizes the terminal trio — progress 100,
     * onReceivedError + onPageFinished (failure path), picture via the
     * finish path — mirroring a failed load. Policy-block only:
     * app-override DENY stays silent (the app took over the URL).
     * Device-locked (CTS WebSettingsTest.testBlockNetworkLoads); the
     * verdict boolean is JVM-locked, the DENY value is not (Looper).
     */
    public void completeBlockedNavigation() {
        // Policy DENY produces no PageStart by design: stand the commit
        // guard down explicitly (same reason as the app-override path).
        mCommitGuard.disarm();
        // A DENY produces no Gecko PageStart to flush a held success-finish
        // (see onPageStarted): flush here so finished order never inverts.
        firePendingHold();
        mNavigating = false;
        mProgress = 100;
        mClient.onProgressChanged(100);
        mClient.onPageFinished(false);
    }

    public void reload() {
        mCommitGuard.disarm();
        mPendingTraversalUrl = null;
        mSession.reload();
    }

    public void stopLoading() {
        // The app cancelled: no commit will arrive, and the guard must not
        // resurrect a stopped navigation.
        mCommitGuard.disarm();
        mSession.stop();
    }

    public void goBack() {
        mCommitGuard.disarm();
        recordTraversalTarget(-1);
        mSession.goBack();
    }

    public void goForward() {
        mCommitGuard.disarm();
        recordTraversalTarget(1);
        mSession.goForward();
    }

    // Record the traversal target URL from the current snapshot (or null
    // when unresolvable — provider then reports Gecko state verbatim).
    private void recordTraversalTarget(int steps) {
        GeckoSession.HistoryDelegate.HistoryList live = mHistoryList;
        int target = live != null && !live.isEmpty()
                ? targetIndex(live, steps) : -1;
        List<GeckoSession.HistoryDelegate.HistoryItem> base = live;
        if (target < 0) {
            GeckoSession.SessionState state = mSessionState;
            if (state != null && !state.isEmpty()) {
                target = targetIndex(state, steps);
                base = state;
            }
        }
        String uri = null;
        if (target >= 0 && base != null && target < base.size()) {
            try {
                uri = base.get(target).getUri();
            } catch (UnsupportedOperationException
                    | IndexOutOfBoundsException e) {
                uri = null;
            }
        }
        mPendingTraversalUrl = uri;
    }

    public boolean canGoBackOrForward(int steps) {
        GeckoSession.HistoryDelegate.HistoryList live = mHistoryList;
        if (live != null && !live.isEmpty()) {
            return targetIndex(live, steps) >= 0;
        }
        GeckoSession.SessionState state = mSessionState;
        if (state != null && !state.isEmpty()) {
            return targetIndex(state, steps) >= 0;
        }
        if (steps < 0) {
            return mCanGoBack;
        }
        if (steps > 0) {
            return mCanGoForward;
        }
        return true;
    }

    public void goBackOrForward(int steps) {
        if (steps == 0) {
            reload();
            return;
        }
        mCommitGuard.disarm();
        recordTraversalTarget(steps);
        GeckoSession.HistoryDelegate.HistoryList live = mHistoryList;
        int target = live != null && !live.isEmpty()
                ? targetIndex(live, steps) : -1;
        if (target < 0) {
            GeckoSession.SessionState state = mSessionState;
            if (state != null && !state.isEmpty()) {
                target = targetIndex(state, steps);
            }
        }
        if (target >= 0) {
            mSession.gotoHistoryIndex(target);
        } else if (steps < 0 && mCanGoBack) {
            mSession.goBack();
        } else if (steps > 0 && mCanGoForward) {
            mSession.goForward();
        }
    }

    private static int targetIndex(
            @NonNull List<GeckoSession.HistoryDelegate.HistoryItem> list, int steps) {
        int current = safeIndex(list);
        if (current < 0) {
            return -1;
        }
        int target = current + steps;
        return target >= 0 && target < list.size() ? target : -1;
    }

    public void clearHistory() {
        mCommitGuard.disarm();
        mSession.purgeHistory();
        mSessionState = null;
        mHistoryList = null;
    }

    public boolean canGoBack() {
        return mCanGoBack;
    }

    public boolean canGoForward() {
        return mCanGoForward;
    }

    @Nullable
    public String getUrl() {
        // Lazy adoption: LocationChange never fires for intercepted channels,
        // and history updates land AFTER PageStop — so adopting at finish
        // time races empty history. Adopting on read sees the settled state
        // (WebViewTest.testLoadUrl asserts after completion). Only fills a
        // blank/unknown slot, never overwrites a real URL.
        if (isAboutBlank(mUrl)) {
            String adopted = currentHistoryUrl();
            if (adopted != null) {
                mUrl = adopted;
            }
        }
        return mUrl;
    }

    @Nullable
    public String getOriginalUrl() {
        return mOriginalUrl != null ? mOriginalUrl : getUrl();
    }

    @Nullable
    public String getTitle() {
        return mTitle;
    }

    public int getProgress() {
        return mProgress;
    }

    @Nullable
    public GeckoSession.SessionState sessionState() {
        return mSessionState;
    }

    @Nullable
    public android.net.http.SslCertificate certificate() {
        GeckoSession.ProgressDelegate.SecurityInformation info = mSecurityInfo;
        if (info == null || info.certificate == null) {
            return null;
        }
        try {
            return new android.net.http.SslCertificate(info.certificate);
        } catch (Throwable t) {
            Log.w(TAG, "certificate convert threw", t);
            return null;
        }
    }

    public List<GeckoSession.HistoryDelegate.HistoryItem> historySnapshot() {
        GeckoSession.HistoryDelegate.HistoryList live = mHistoryList;
        if (live != null && !live.isEmpty()) {
            return live;
        }
        GeckoSession.SessionState state = mSessionState;
        return state != null ? state : java.util.Collections.emptyList();
    }

    public boolean hasExplicitAboutLoad() {
        return mExplicitAboutLoad;
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
            Log.w(TAG, "applyWebSettings threw", t);
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
            Log.w(TAG, "applyImagesPolicy threw", t);
        }
    }

    // Ask Gecko to push the latest session data (incl. navigation history)
    // through onSessionStateChange. No GeckoView attach required.
    public void flushHistory() {
        try {
            mSession.flushSessionState();
        } catch (Throwable t) {
            Log.w(TAG, "flushSessionState threw", t);
        }
    }

    // One-shot dump of all three history sources for the copy=0 diagnosis
    // matrix (STATUS.md §2a): live HistoryList, SessionState, and the snapshot
    // copyBackForwardList actually reads.
    public void dumpHistorySources(@NonNull String where) {
        GeckoSession.HistoryDelegate.HistoryList live = mHistoryList;
        GeckoSession.SessionState state = mSessionState;
        List<GeckoSession.HistoryDelegate.HistoryItem> snapshot = historySnapshot();
        Log.i(TAG, "history: [" + where + "] live="
                + describe(live) + " state=" + describe(state)
                + " snapshot=" + describe(snapshot));
    }

    private static String describe(
            @Nullable List<GeckoSession.HistoryDelegate.HistoryItem> list) {
        if (list == null) {
            return "null";
        }
        return "size=" + list.size() + " index=" + safeIndex(list)
                + " urls=" + snapshotUrls(list);
    }

    private static int safeIndex(
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
    private static String snapshotUrls(
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

    public void close() {
        mCommitGuard.disarm();
        long startMs = android.os.SystemClock.uptimeMillis();
        try {
            // session.close is @UiThread; the creator thread may be a
            // background looper (CTS WebViewStartupTest) — hop, bounded.
            org.mozilla.geckowebview.runtime.MainThread.call(() -> {
                mSession.close();
                return null;
            });
        } finally {
            android.util.Log.d(TAG, "session.close took "
                    + (android.os.SystemClock.uptimeMillis() - startMs)
                    + "ms");
        }
    }
}
