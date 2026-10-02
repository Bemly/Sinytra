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
    private String mTitle;
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
    // Latest SessionState from onSessionStateChange (Gecko owns history;
    // we only translate it — ARCHITECTURE.md §4).
    @Nullable
    private GeckoSession.SessionState mSessionState;
    // Latest HistoryList from onHistoryStateChange — the live, authoritative
    // history object. onSessionStateChange snapshots may lag (empty entries
    // until the next flush), so copyBackForwardList prefers this.
    @Nullable
    private GeckoSession.HistoryDelegate.HistoryList mHistoryList;
    @Nullable
    private ContentBridge.Host mContentHost;
    @Nullable
    private GeckoSession.ProgressDelegate.SecurityInformation mSecurityInfo;

    public GeckoSessionBridge(@NonNull Client client) {
        mClient = client;
        mSession = new GeckoSession();
        mSession.setNavigationDelegate(new NavigationBridge(this));
        mSession.setHistoryDelegate(new GeckoSession.HistoryDelegate() {
            @Override
            public void onHistoryStateChange(@NonNull GeckoSession session,
                    @NonNull GeckoSession.HistoryDelegate.HistoryList historyList) {
                mHistoryList = historyList;
                Log.i(TAG, "history: onHistoryStateChange size=" + historyList.size()
                        + " index=" + safeIndex(historyList)
                        + " urls=" + snapshotUrls(historyList));
            }

            @Override
            public GeckoResult<Boolean> onVisited(@NonNull GeckoSession session,
                    @NonNull String url, @Nullable String lastVisitedURL, int flags) {
                return GeckoResult.fromValue(Boolean.FALSE);
            }

            @Override
            public GeckoResult<boolean[]> getVisited(@NonNull GeckoSession session,
                    @NonNull String[] urls) {
                return GeckoResult.fromValue(new boolean[urls.length]);
            }
        });
        ProgressBridge progress = new ProgressBridge(this);
        mSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(@NonNull GeckoSession session, @NonNull String url) {
                progress.onPageStart(session, url);
            }

            @Override
            public void onPageStop(@NonNull GeckoSession session, boolean success) {
                progress.onPageStop(session, success);
            }

            @Override
            public void onProgressChange(@NonNull GeckoSession session, int progressValue) {
                progress.onProgressChange(session, progressValue);
            }

            @Override
            public void onSecurityChange(@NonNull GeckoSession session,
                    @NonNull SecurityInformation securityInfo) {
                progress.onSecurityChange(session, securityInfo);
            }

            @Override
            public void onSessionStateChange(@NonNull GeckoSession session,
                    @NonNull GeckoSession.SessionState sessionState) {
                mSessionState = sessionState;
                Log.i(TAG, "history: onSessionStateChange size=" + sessionState.size()
                        + " index=" + safeIndex(sessionState)
                        + " urls=" + snapshotUrls(sessionState));
            }
        });
        mSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(@NonNull GeckoSession session,
                    @Nullable String title) {
                mTitle = title;
                mClient.onTitleChanged(title);
            }
        });
        mContentHost = null;
    }

    // P1 delegates that must not clobber each other: the provider owns one
    // Permission/Prompt/Content delegate each and fans out to sub-bridges.
    public void setExtraDelegates(@NonNull PermissionBridge.Host permHost,
            @NonNull PromptBridge.Host promptHost,
            @NonNull ContentBridge.Host contentHost) {
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
                mTitle = title;
                mClient.onTitleChanged(title);
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
    }

    // P2 intercept bridge (subframe allow/deny): installed separately so
    // NavigationBridge stays a pure translator.
    public void setInterceptBridge(@NonNull InterceptBridge intercept) {
        mSession.setNavigationDelegate(new P2NavigationDelegate(
                new NavigationBridge(this), intercept));
    }

    @NonNull
    public GeckoSession session() {
        return mSession;
    }

    // --- NavigationBridge.Host ---

    @Override
    public void onUrlChanged(@NonNull String url) {
        mUrl = url;
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
    public void onLoadError(int errorCode, int sslPrimaryError,
            @NonNull String description, @Nullable String failingUrl) {
        mClient.onLoadError(errorCode, sslPrimaryError, description, failingUrl);
    }

    // --- ProgressBridge.Host ---

    @Override
    public void onPageStarted(@NonNull String url) {
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
                return;
            }
            mBlankStartOutstanding = false;
            // Terminal-100 completion (Chromium contract): a finished load
            // always reports progress 100, even when Gecko emitted no (or
            // partial) ticks for it. State here, event in ClientFanOut
            // (which fires 100 before forwarding finished — order locked by
            // JVM test).
            if (mProgress < 100) {
                mProgress = 100;
            }
        }
        mClient.onPageFinished(success);
        if (success) {
            flushHistory();
        }
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

    /**
     * Current non-blank URL from Gecko-owned history (live list preferred,
     * session snapshot fallback), or null when history holds no usable URL.
     * Device-locked (CTS WebViewTest.testLoadUrl/testGetCurrentItem); JVM
     * cannot construct the Gecko history types.
     */
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
        if (url != null && url.regionMatches(true, 0, "about:", 0, 6)) {
            mExplicitAboutLoad = true;
        }
        mSession.loadUri(url);
    }

    public void reload() {
        mSession.reload();
    }

    public void stopLoading() {
        mSession.stop();
    }

    public void goBack() {
        mSession.goBack();
    }

    public void goForward() {
        mSession.goForward();
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
    // userAgentOverride), so no construction plumbing is needed — the
    // provider calls this before every navigation (set-then-load is the
    // CTS norm) and the facade defaults (JS off, mobile UA) apply
    // verbatim. Never throws: a closed session degrades silently.
    public void applyWebSettings(boolean javaScriptEnabled,
            @Nullable String userAgentOverride, boolean desktopMode,
            boolean wideViewport) {
        try {
            org.mozilla.geckoview.GeckoSessionSettings settings =
                    mSession.getSettings();
            // CTS triage log (UA-empty domino): the exact override string
            // pushed onto the live session.
            android.util.Log.d(TAG, "applyWebSettings js=" + javaScriptEnabled
                    + " uaOverride=" + userAgentOverride);
            settings.setAllowJavascript(javaScriptEnabled);
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
        } catch (Throwable t) {
            Log.w(TAG, "applyWebSettings threw", t);
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
        long startMs = android.os.SystemClock.uptimeMillis();
        try {
            mSession.close();
        } finally {
            android.util.Log.d(TAG, "session.close took "
                    + (android.os.SystemClock.uptimeMillis() - startMs)
                    + "ms");
        }
    }
}
