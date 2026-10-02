package org.mozilla.geckowebview.provider;

import android.net.Uri;
import android.webkit.DownloadListener;
import android.webkit.SinytraSslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckowebview.session.ContentBridge;
import org.mozilla.geckowebview.session.InterceptBridge;
import org.mozilla.geckowebview.session.MessageBridge;

// Client callback fan-out: Gecko bridge hosts -> WebViewClient/WebChromeClient.
// Split out of GeckoWebViewProvider (AGENTS.md §7 file-size rule).
final class ClientFanOut
        implements org.mozilla.geckowebview.session.GeckoSessionBridge.Client,
        ContentBridge.Host,
        org.mozilla.geckowebview.session.FindBridge.Host,
        MessageBridge.Host, InterceptBridge.Host,
        org.mozilla.geckowebview.session.ConsoleBridge.Host {
    private static final String TAG = "Sinytra/session";
    private static final int FILE_CHOOSER_REQUEST = 0x5EED;

    interface Owner {
        @NonNull
        WebView webView();

        @NonNull
        GeckoWebViewFactoryProvider factory();

        @NonNull
        org.mozilla.geckowebview.session.GeckoSessionBridge ownerBridge();

        @Nullable
        WebViewClient webViewClient();

        @Nullable
        WebChromeClient webChromeClient();

        @Nullable
        DownloadListener downloadListener();

        void setFileChooserCallback(@Nullable ValueCallback<Uri[]> callback);

        @Nullable
        ValueCallback<Uri[]> fileChooserCallback();

        void fireVisualState();

        /** Current 0001 response-surface filters (never null; may be empty). */
        @NonNull
        String[] interceptFilters();

        /** Live WebSettings state for policy enforcement. */
        @NonNull
        org.mozilla.geckowebview.settings.GeckoWebSettings webSettingsState();

        /** Re-push 0001 filters: recovers a startup-raced push (see Interception). */
        void repushResponseFilters();

        /** Kick a best-effort favicon fetch for the current page. */
        void fetchFavicon();
        /** App filters plus the universal prefix (what Gecko actually gets). */
        @NonNull
        String[] effectiveInterceptFilters();

        @NonNull
        org.mozilla.geckowebview.session.RenderProcessBridge renderProcess();
    }

    private final Owner mOwner;
    private final ClientPrompts mPrompts;
    private boolean mFiltersRepushed;
    @Nullable
    private String mLastTitle;
    // Last progress value forwarded to the WebChromeClient this navigation
    // (terminal-100 completion below needs to know whether 100 went out).
    // Starts at 100 (fresh-WebView contract, mirrors bridge state) so a
    // finish with zero Gecko ticks still completes exactly once.
    private int mLastProgress = 100;

    ClientFanOut(@NonNull Owner owner) {
        mOwner = owner;
        mPrompts = new ClientPrompts(owner);
    }

    @NonNull
    ClientPrompts prompts() {
        return mPrompts;
    }

    static int fileChooserRequestCode() {
        return FILE_CHOOSER_REQUEST;
    }

    // --- GeckoSessionBridge.Client ---

    @Override
    public void onUrlChanged(@NonNull String url) {
    }

    @Override
    public void onCanGoBackChanged(boolean canGoBack) {
    }

    @Override
    public void onCanGoForwardChanged(boolean canGoForward) {
    }

    @Override
    public boolean shouldOverrideUrlLoading(@NonNull String url) {
        WebViewClient client = mOwner.webViewClient();
        if (client == null) {
            return false;
        }
        try {
            return client.shouldOverrideUrlLoading(mOwner.webView(), url);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.shouldOverrideUrlLoading threw", t);
            return false;
        }
    }

    @Override
    public void onPageStarted(@NonNull String url) {
        // New navigation: title dedupe restarts (same title on a new
        // document still reports), and the progress baseline restarts
        // (bridge state resets alongside; event side resets here).
        mLastTitle = null;
        mLastProgress = 0;
        if (!mFiltersRepushed) {
            mFiltersRepushed = true;
            try {
                mOwner.repushResponseFilters();
            } catch (Throwable t) {
                android.util.Log.w(TAG, "repushResponseFilters threw", t);
            }
        }
        WebViewClient client = mOwner.webViewClient();
        if (client == null) {
            return;
        }
        try {
            client.onPageStarted(mOwner.webView(), url, null);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onPageStarted threw", t);
        }
    }

    @Override
    public void onPageFinished(boolean success) {
        // Terminal-100 completion (Chromium contract — bridge state already
        // holds 100 via GeckoSessionBridge, this is the event side): a
        // finished load reports onProgressChanged(100) BEFORE onPageFinished,
        // even when Gecko emitted no ticks for it (instant local loads).
        // Failure loads keep Gecko's partial values (no fabrication).
        // Rule JVM-locked via shouldFireTerminalProgress; order device-
        // locked (CTS progress suites + harness probes).
        if (shouldFireTerminalProgress(success, mLastProgress)) {
            mLastProgress = 100;
            WebChromeClient chrome = mOwner.webChromeClient();
            if (chrome != null) {
                try {
                    chrome.onProgressChanged(mOwner.webView(), 100);
                } catch (Throwable t) {
                    android.util.Log.w(TAG,
                            "WebChromeClient.onProgressChanged threw", t);
                }
            }
        }
        WebViewClient client = mOwner.webViewClient();
        String url = mOwner.ownerBridge().getUrl();
        if (client == null || url == null) {
            return;
        }
        try {
            if (success) {
                client.onPageFinished(mOwner.webView(), url);
            } else {
                // Chromium order on failed loads: onReceivedError FIRST,
                // then onPageFinished anyway. Without the finish, load
                // gates (CTS WebViewSyncLoader mLoaded, and any app
                // spinner) hang forever on every failed navigation (CTS
                // WebViewSslTest.testOnReceivedSslErrorCancel family).
                client.onReceivedError(mOwner.webView(), WebViewClient.ERROR_UNKNOWN,
                        "load failed", url);
                client.onPageFinished(mOwner.webView(), url);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onPageFinished threw", t);
        } finally {
            try {
                mOwner.fireVisualState();
            } catch (Throwable t) {
                android.util.Log.w(TAG, "fireVisualState threw", t);
            }
            if (success) {
                try {
                    mOwner.fetchFavicon();
                } catch (Throwable t) {
                    android.util.Log.d(TAG, "fetchFavicon threw", t);
                }
            }
        }
    }

    @Override
    public void onProgressChanged(int progress) {
        mLastProgress = progress;
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            chrome.onProgressChanged(mOwner.webView(), progress);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onProgressChanged threw", t);
        }
    }

    @Override
    public void onTitleChanged(@Nullable String title) {
        // Gecko reports the title on parse and again on commit (same value
        // twice for one load); Chromium reports real changes only. Forward
        // on change (CTS WebChromeClientTest title queues assert exact
        // sequences — a duplicate static title breaks them). Provider-layer
        // last-value bookkeeping (bridge stays stateless per AGENTS.md).
        if (title != null && title.equals(mLastTitle)) {
            return;
        }
        mLastTitle = title;
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            chrome.onReceivedTitle(mOwner.webView(), title);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onReceivedTitle threw", t);
        }
    }

    @Override
    public void onLoadError(int errorCode, int sslPrimaryError,
            @NonNull String description, @Nullable String failingUrl) {
        WebViewClient client = mOwner.webViewClient();
        if (client == null || failingUrl == null) {
            return;
        }
        // P1 SSL: cert failures go to onReceivedSslError (Chromium order:
        // onReceivedSslError INSTEAD of onReceivedError for these). The
        // SslError carries no certificate (Gecko does not surface one in the
        // error callback); the handler routes the app's decision — cancel is
        // the error page, proceed = temporary cert override + reload
        // (firefox-patches/0006, Firefox Add-Exception semantics).
        if (sslPrimaryError >= 0) {
            try {
                client.onReceivedSslError(mOwner.webView(),
                        new SinytraSslErrorHandler(
                                () -> proceedSslError(failingUrl),
                                () -> {
                                    // cancel: the load is already terminal;
                                    // the error page stands.
                                }),
                            new android.net.http.SslError(sslPrimaryError,
                                    (android.net.http.SslCertificate) null,
                                    failingUrl));
                return;
            } catch (Throwable t) {
                android.util.Log.w(TAG,
                        "WebViewClient.onReceivedSslError threw", t);
            }
        }
        try {
            client.onReceivedError(mOwner.webView(), errorCode, description,
                    failingUrl);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onReceivedError threw", t);
        }
    }

    // SslErrorHandler.proceed: Chromium semantics = continue the blocked load
    // despite the invalid certificate. The navigation is already terminal on
    // the Gecko side, so proceed = remember a temporary override for the
    // failed certificate (0006) and reload the URI. If the reload fails
    // again the app is consulted again (same as Chromium re-asking).
    private void proceedSslError(@NonNull String url) {
        android.webkit.WebView webView = mOwner.webView();
        if (webView == null) {
            return;
        }
        org.mozilla.geckoview.CertOverrideController.allowError(url)
                .accept(ok -> {
                    android.util.Log.d(TAG, "ssl proceed override=" + ok
                            + " url=" + url);
                    if (Boolean.TRUE.equals(ok)) {
                        // Reload through OUR provider bridge after the failed
                        // load fully settles (the docshell is mid-decision
                        // when proceed() fires; an immediate loadUri was
                        // observed to be swallowed by the error-page flow).
                        // A plain android.os.Handler, NOT View.post: the
                        // WebView can be detached (harness, background
                        // tabs), and a detached View queues the runnable
                        // into the attach-time RunQueue where it never runs.
                        new android.os.Handler(android.os.Looper
                                .getMainLooper()).postDelayed(() -> {
                            android.util.Log.d(TAG,
                                    "ssl proceed reloading " + url);
                            mOwner.ownerBridge().loadUrl(url);
                        }, 500);
                    }
                },
                e -> android.util.Log.w(TAG, "ssl proceed failed", e));
    }

    // Pure (JVM-testable): terminal-100 fires only for successful loads
    // whose last reported progress never reached 100.
    static boolean shouldFireTerminalProgress(boolean success,
            int lastProgress) {
        return success && lastProgress < 100;
    }

    // --- ContentBridge.Host ---

    @Override
    public void onDownloadStart(@NonNull String url, @Nullable String userAgent,
            @Nullable String contentDisposition, @NonNull String mimeType,
            long contentLength) {
        DownloadListener listener = mOwner.downloadListener();
        android.util.Log.d(TAG, "onDownloadStart url=" + url
                + " listener=" + (listener != null)
                + " owner=" + Integer.toHexString(mOwner.hashCode()));
        if (listener == null) {
            return;
        }
        try {
            listener.onDownloadStart(url, userAgent, contentDisposition, mimeType,
                    contentLength);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "DownloadListener.onDownloadStart threw", t);
        }
    }

    @Override
    public void onFullScreen(boolean fullScreen) {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            if (fullScreen) {
                chrome.onShowCustomView(mOwner.webView(), null);
            } else {
                chrome.onHideCustomView();
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient fullscreen threw", t);
        }
    }

    @Override
    public void onCloseWindow() {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            chrome.onCloseWindow(mOwner.webView());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onCloseWindow threw", t);
        }
    }

    @Override
    public void onFocusRequest() {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            chrome.onRequestFocus(mOwner.webView());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onRequestFocus threw", t);
        }
    }

    @Override
    public void onCrash() {
        WebViewClient client = mOwner.webViewClient();
        String url = mOwner.ownerBridge().getUrl();
        mOwner.renderProcess().onGeckoCrash(mOwner.webView(), client);
        if (client == null) {
            return;
        }
        try {
            client.onReceivedError(mOwner.webView(), WebViewClient.ERROR_UNKNOWN,
                    "renderer crashed", url != null ? url : "about:blank");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient crash report threw", t);
        }
    }

    // --- MessageBridge.Host ---

    @Override
    public void onConsoleMessage(@NonNull String level, @NonNull String message,
            int line) {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            android.webkit.ConsoleMessage.MessageLevel mapped;
            try {
                mapped = android.webkit.ConsoleMessage.MessageLevel
                        .valueOf(level);
            } catch (Throwable t) {
                mapped = android.webkit.ConsoleMessage.MessageLevel.LOG;
            }
            String url = mOwner.ownerBridge().getUrl();
            chrome.onConsoleMessage(new android.webkit.ConsoleMessage(message,
                    url != null ? url : "", line, mapped));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onConsoleMessage threw", t);
        }
    }

    @Override
    public void onMessage(@NonNull String portId, @NonNull String data,
            @Nullable String origin) {
    }

    // --- InterceptBridge.Host ---

    @Override
    @Nullable
    public WebResourceResponse shouldIntercept(
            @NonNull InterceptBridge.SinytraResourceRequest request) {
        WebViewClient client = mOwner.webViewClient();
        if (client == null) {
            return null;
        }
        try {
            WebResourceResponse response = client.shouldInterceptRequest(
                    mOwner.webView(), request);
            if (response != null) {
                // Consultation only: the caller decides (deny via
                // onInterceptDeny, or body substitution via 0001
                // ResponseBridge) — never assume deny here.
                android.util.Log.d(TAG, "intercept: app returned response for "
                        + request.getUrl());
            }
            return response;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.shouldInterceptRequest threw", t);
            return null;
        }
    }

    @Override
    public void onInterceptDeny(@NonNull String uri, boolean isRedirect,
            boolean hasUserGesture) {
        android.util.Log.i(TAG, "intercept: DENY " + uri + " redirect=" + isRedirect
                + " gesture=" + hasUserGesture);
    }

    @Override
    public boolean responseSurfaceOwns(@NonNull String uri) {
        return org.mozilla.geckowebview.session.InterceptBridge
                .matchesFilterPrefix(uri, mOwner.effectiveInterceptFilters());
    }

    @Override
    public boolean policyBlocksNavigation(@NonNull String uri,
            boolean isMainFrame, @Nullable String triggerUri) {
        try {
            String page = triggerUri != null && !triggerUri.isEmpty()
                    ? triggerUri
                    : mOwner.ownerBridge().getUrl();
            return LoadPolicy.blockNavigation(uri, isMainFrame, page,
                    mOwner.webSettingsState());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "policyBlocksNavigation threw", t);
            return false;
        }
    }

    // --- FindBridge.Host ---

    @Override
    public void onFindResult(int activeMatchOrdinal, int numberOfMatches,
            boolean done) {
    }
}
