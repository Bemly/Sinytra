package org.mozilla.geckowebview.session;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckowebview.provider.GeckoRuntimeHolder;

// Owns one GeckoSession + its bridges, independent of any View (session must NOT
// depend on view per ARCHITECTURE.md §3). The host View is attached separately
// via attachTo(view). One instance = one WebView backend.
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
    }

    private static final String TAG = "Sinytra/session";

    private final GeckoSession mSession;
    private final Client mClient;
    private String mUrl;
    private String mTitle;
    private int mProgress;
    private boolean mCanGoBack;
    private boolean mCanGoForward;
    // Latest SessionState from onSessionStateChange (Gecko owns history;
    // we only translate it — ARCHITECTURE.md §4).
    @Nullable
    private GeckoSession.SessionState mSessionState;
    // Latest HistoryList from onHistoryStateChange — the live, authoritative
    // history object. onSessionStateChange snapshots may lag (empty entries
    // until the next flush), so copyBackForwardList prefers this.
    @Nullable
    private GeckoSession.HistoryDelegate.HistoryList mHistoryList;

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
    }

    @NonNull
    public GeckoSession session() {
        return mSession;
    }

    public void attachTo(@NonNull Context context, @NonNull GeckoView view) {
        GeckoRuntimeHolder.attachView(context, view, mSession);
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

    // --- ProgressBridge.Host ---

    @Override
    public void onPageStarted(@NonNull String url) {
        mClient.onPageStarted(url);
    }

    @Override
    public void onPageFinished(boolean success) {
        mClient.onPageFinished(success);
        if (success) {
            flushHistory();
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
    }

    // --- P0 navigation surface (called by GeckoWebViewProvider) ---

    public void loadUrl(@NonNull String url) {
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

    public List<GeckoSession.HistoryDelegate.HistoryItem> historySnapshot() {
        GeckoSession.HistoryDelegate.HistoryList live = mHistoryList;
        if (live != null && !live.isEmpty()) {
            return live;
        }
        GeckoSession.SessionState state = mSessionState;
        return state != null ? state : java.util.Collections.emptyList();
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
        mSession.close();
    }
}
