package org.mozilla.geckowebview.session;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;
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

    public void close() {
        mSession.close();
    }
}
