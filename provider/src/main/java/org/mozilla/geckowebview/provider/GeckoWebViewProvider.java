package org.mozilla.geckowebview.provider;

import org.mozilla.geckowebview.runtime.GeckoRuntimeHolder;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Picture;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Bundle;
import android.os.Message;
import android.print.PrintDocumentAdapter;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ClientCertRequest;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.SinytraWebMessagePort;
import org.mozilla.geckowebview.storage.GeckoWebIconDatabase;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewProvider;
import android.webkit.WebViewRenderProcess;
import android.webkit.WebViewRenderProcessClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.File;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebRequestInfo;
import org.mozilla.geckowebview.session.ContentBridge;
import org.mozilla.geckowebview.session.ErrorBridge;
import org.mozilla.geckowebview.session.FindBridge;
import org.mozilla.geckowebview.session.GeckoSessionBridge;
import org.mozilla.geckowebview.session.PermissionBridge;
import org.mozilla.geckowebview.session.PrintBridge;
import org.mozilla.geckowebview.session.PromptBridge;
import org.mozilla.geckowebview.session.StateBridge;
import org.mozilla.geckowebview.session.InterceptBridge;
import org.mozilla.geckowebview.session.JavascriptBridge;
import org.mozilla.geckowebview.session.JsBridge;
import org.mozilla.geckowebview.session.JsEvaluator;
import org.mozilla.geckowebview.session.MessageBridge;
import org.mozilla.geckowebview.session.ResponseBridge;
import org.mozilla.geckowebview.session.RenderProcessBridge;
import org.mozilla.geckowebview.view.GeckoViewHost;
import org.mozilla.geckowebview.settings.GeckoWebSettings;

// WebViewProvider backend for one WebView instance (P0 subset).
// Navigation + read accessors are live (GeckoSessionBridge); everything else
// fails honest-and-loud (UnsupportedOperationException) so gaps surface in
// tests instead of silently misbehaving. Delegate exceptions never propagate:
// see onGeckoError path. Client fan-out lives in ClientFanOut (file-size rule).
public final class GeckoWebViewProvider
        implements WebViewProvider, ClientFanOut.Owner,
        org.mozilla.geckowebview.compat.CompatHost.WebViewBackend {
    private static final String TAG = "Sinytra/session";

    private final WebView mWebView;
    private final GeckoWebViewFactoryProvider mFactory;
    private final GeckoSessionBridge mBridge;
    private final ClientFanOut mFanOut;
    private final CompatWebSettings mSettings;
    private final FindBridge mFind;
    private final PrintBridge mPrint;
    private final JsEvaluator mJs;
    private final JsBridge mJsBridge;
    private final JavascriptBridge mJsInterfaces;
    private final MessageBridge mMessages;
    // Framework port -> bridge port (app-to-page MessagePort transfer;
    // weak: closed ports must not pin the bridge).
    private final java.util.Map<android.webkit.WebMessagePort, MessageBridge.Port>
            mPortRoutes = new java.util.WeakHashMap<>();
    private final org.mozilla.geckowebview.session.ConsoleBridge mConsole;
    private final InterceptBridge mIntercept;
    private final Interception mInterception;
    private final ProviderFavicon mFavicon;
    private final RenderProcessBridge mRenderProcess;
    // Visual surface (2026-09-25): GeckoViewHost attached as a WebView
    // child; the child's own view lifecycle owns surface attach/detach.
    // Null = attach failed, headless fallback (loud log).
    @Nullable
    private final GeckoViewHost mViewHost;
    private final java.util.Map<Long, WebView.VisualStateCallback> mPendingVisualState =
            new java.util.concurrent.ConcurrentHashMap<>();
    private WebViewClient mWebViewClient;
    private WebChromeClient mWebChromeClient;
    private DownloadListener mDownloadListener;
    // Deprecated PictureListener (Chromium fires onNewPicture on every
    // invalidation; CTS WebViewSyncLoader gates load completion on it).
    // Gecko has no picture-invalidation signal, so synthesize: fire once per
    // successful page finish on the UI thread. Honest gap: mid-load
    // invalidations never fire, only the post-load synthetic one.
    @Nullable
    private volatile WebView.PictureListener mPictureListener;
    @Nullable
    private ValueCallback<Uri[]> mFileChooserCallback;
    private volatile boolean mDestroyed;
    // The framework's WebView.PrivateAccess (super_* channel); absent only
    // when a caller constructs the provider without one.
    @NonNull
    private final FrameworkPrivateAccess mPrivateAccess;

    public GeckoWebViewProvider(@NonNull WebView webView,
            @NonNull GeckoWebViewFactoryProvider factory,
            @Nullable Object privateAccess) {
        mWebView = webView;
        // Debug-gated host-main watchdog (CTS triage; release builds skip).
        // Never breaks construction: install failures stay silent.
        try {
            MainWatchdog.installIfDebuggable(
                    webView.getContext().getApplicationContext());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "watchdog install threw", t);
        }
        mPrivateAccess = new FrameworkPrivateAccess(privateAccess);
        mFactory = factory;
        mFanOut = new ClientFanOut(this);
        mBridge = new GeckoSessionBridge(mFanOut);
        mSettings = new CompatWebSettings(new GeckoWebSettings());
        // Image-policy flips take effect without an app reload (CTS
        // LoadsImagesAutomatically_/BlockNetworkImage families poll for the
        // re-render): when the app loosens image loading on a live page,
        // re-issue the current navigation internally.
        mSettings.setMutationListener(() -> {
            try {
                String url = mBridge.getUrl();
                if (url == null || url.startsWith("about:")) {
                    return;
                }
                org.mozilla.geckowebview.settings.GeckoWebSettings state =
                        mSettings.gecko();
                if (state.getLoadsImagesAutomatically()
                        || !state.getBlockNetworkImage()) {
                    mBridge.reload();
                }
            } catch (Throwable t) {
                android.util.Log.w(TAG, "images policy reload threw", t);
            }
        });
        mFind = new FindBridge(mBridge.session(), mFanOut);
        mPrint = new PrintBridge(mBridge.session());
        mJs = new JsEvaluator();
        mJsBridge = new JsBridge();
        mJs.setBridge(mJsBridge);
        mJsInterfaces = new JavascriptBridge();
        mMessages = new MessageBridge(mFanOut);
        mConsole = new org.mozilla.geckowebview.session.ConsoleBridge(mFanOut);
        mJsInterfaces.setTransport(mJsBridge);
        mMessages.setTransport(mJsBridge);
        mConsole.setTransport(mJsBridge);
        mIntercept = new InterceptBridge(mFanOut);
        mInterception = new Interception(new Interception.Host() {
            @Override
            @NonNull
            public GeckoSessionBridge bridge() {
                return mBridge;
            }

            @Override
            @NonNull
            public GeckoWebSettings settings() {
                return mSettings.gecko();
            }

            @Override
            public boolean destroyed() {
                return mDestroyed;
            }
        }, mIntercept);
        mRenderProcess = new RenderProcessBridge();
        mFavicon = new ProviderFavicon(new ProviderFavicon.Host() {
            @Override
            @NonNull
            public WebView webView() {
                return mWebView;
            }

            @Override
            @Nullable
            public WebChromeClient chromeClient() {
                return mWebChromeClient;
            }

            @Override
            @NonNull
            public GeckoWebViewFactoryProvider factory() {
                return mFactory;
            }

            @Override
            @NonNull
            public JsEvaluator js() {
                return mJs;
            }

            @Override
            @Nullable
            public String pageUrl() {
                return mBridge.getUrl();
            }
        });
        mBridge.setExtraDelegates(mFanOut, mFanOut, mFanOut);
        mBridge.setInterceptBridge(mIntercept);
        // Session must be opened on the UI thread (GeckoView @UiThread contract).
        // Real framework calls create() on the UI thread; assert here so the
        // harness (or future callers) fail fast instead of hanging on load.
        // Liveness gate for filter retries (set before the first push):
        // the JS transport bring-up rides the same parent-JS bring-up as
        // the filter module, so a retry that waits for it can never push
        // into a not-yet-listening dispatcher. isReady() is field reads
        // only, safe to wire before bind().
        mInterception.setTransportReady(mJsBridge::isReady);
        // Initial filter dispatch goes through Interception so the
        // gated retries cover the onInit race from the very first
        // push (a bare setResponseDelegate here would be fire-and-forget
        // with no recovery until the first PageStart).
        mInterception.repushFilters();
        mBridge.session().open(GeckoRuntimeHolder.get(
                webView.getContext().getApplicationContext()));
        // Bind the JS extension transport (built-in WebExtension, public API;
        // install is async — eval answers honest-null until ready).
        try {
            mJsBridge.bind(
                    GeckoRuntimeHolder.get(
                            webView.getContext().getApplicationContext()),
                    mBridge.session());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "JsBridge.bind threw", t);
        }
        // Visual surface: attach a GeckoViewHost as a child of the WebView
        // (the framework WebView is an AbsoluteLayout, so the child params
        // must be AbsoluteLayout.LayoutParams — its onLayout casts them).
        // The child's own view lifecycle owns surface attach/detach/freeze;
        // the provider never manages pixels itself (ARCHITECTURE §3: the
        // view hosts the session surface). Real-path timing: the framework
        // constructs the provider from inside WebView's View.<init>
        // (setOverScrollMode -> ensureProviderCreated), where the WebView's
        // own ViewGroup state (mChildren) is not initialized yet — addView
        // NPEs there (framework-entry probe, 2026-09-26). bind() (setSession)
        // is safe at construction time; only addView defers, retried from
        // ViewDelegate.onAttachedToWindow.
        GeckoViewHost host = null;
        try {
            host = new GeckoViewHost(webView.getContext());
            host.bind(webView.getContext(), mBridge);
        } catch (Throwable t) {
            android.util.Log.w(TAG,
                    "GeckoViewHost bind threw (headless fallback)", t);
        }
        mViewHost = host;
        attachViewHost();
    }

    // Adds the GeckoViewHost as the WebView's child. Idempotent: no-op once
    // the child has a parent, or when the host fell back to headless (never
    // bound). Called from the constructor and, when the ctor-time addView hit
    // the WebView's own View.<init>, retried from the framework's
    // ViewDelegate.onAttachedToWindow (first moment the WebView ViewGroup is
    // fully constructed).
    void attachViewHost() {
        if (mViewHost == null || mViewHost.getParent() != null) {
            return;
        }
        try {
            mWebView.addView(mViewHost,
                    new android.widget.AbsoluteLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        } catch (Throwable t) {
            android.util.Log.w(TAG,
                    "GeckoViewHost addView failed; deferred to onAttachedToWindow", t);
        }
    }

    // Test seam: JsBridge bound to this provider's session.
    @NonNull
    JsBridge jsBridge() {
        return mJsBridge;
    }

    @NonNull
    GeckoSessionBridge bridge() {
        return mBridge;
    }

    @NonNull
    FrameworkPrivateAccess privateAccess() {
        return mPrivateAccess;
    }

    // --- ClientFanOut.Owner ---

    @Override
    @NonNull
    public WebView webView() {
        return mWebView;
    }

    @Override
    @NonNull
    public GeckoWebViewFactoryProvider factory() {
        return mFactory;
    }

    @Override
    @NonNull
    public GeckoSessionBridge ownerBridge() {
        return mBridge;
    }

    @Override
    @Nullable
    public WebViewClient webViewClient() {
        return mWebViewClient;
    }

    @Override
    @Nullable
    public WebChromeClient webChromeClient() {
        return mWebChromeClient;
    }

    @Override
    @Nullable
    public DownloadListener downloadListener() {
        return mDownloadListener;
    }

    @Override
    public void setFileChooserCallback(@Nullable ValueCallback<Uri[]> callback) {
        mFileChooserCallback = callback;
    }

    @Override
    @Nullable
    public ValueCallback<Uri[]> fileChooserCallback() {
        return mFileChooserCallback;
    }

    @Override
    public void fireVisualState() {
        firePendingVisualState();
        firePictureListener();
    }

    // Synthetic onNewPicture: the deprecated PictureListener has no Gecko
    // source signal; fire it once per successful page finish so CTS-style
    // waiters (WebViewSyncLoader gates completion on mNewPicture) unblock.
    // Always posted to the UI thread — the delegate contract runs there.
    private void firePictureListener() {
        WebView.PictureListener listener = mPictureListener;
        if (listener == null) {
            return;
        }
        android.os.Handler main =
                new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> {
            try {
                listener.onNewPicture(mWebView, capturePicture());
            } catch (Throwable t) {
                android.util.Log.w(TAG, "PictureListener.onNewPicture threw", t);
            }
        });
    }

    @Override
    @NonNull
    public String[] interceptFilters() {
        return mInterception.appFilters();
    }

    @Override
    public void repushResponseFilters() {
        mInterception.repushFilters();
    }

    @Override
    @NonNull
    public org.mozilla.geckowebview.settings.GeckoWebSettings webSettingsState() {
        return mSettings.gecko();
    }

    @Override
    public void fetchFavicon() {
        mFavicon.onPageFinished();
    }

    // Effective filters pushed to Gecko + consulted by the LoadRequest
    // stand-down: app filters plus the universal "http" prefix (matches
    // http:// and https://, never data:/about:/file:). Full interception
    // retires the P2-4 DENY approximation — an app answer now always
    // renders (Chromium parity: shouldInterceptRequest has no deny).
    // Without this, any page served by the app's shouldInterceptRequest
    // (every CTS test server page) DENYs to about:blank.
    @NonNull
    public String[] effectiveInterceptFilters() {
        return mInterception.effectiveFilters();
    }

    @Override
    @NonNull
    public RenderProcessBridge renderProcess() {
        return mRenderProcess;
    }

    // --- WebViewProvider: lifecycle ---

    @Override
    public void init(Map<String, Object> javaScriptInterfaces, boolean privateBrowsing) {
    }

    @Override
    public void destroy() {
        mDestroyed = true;
        mFactory.unregisterWebViewProvider(this);
        long startMs = android.os.SystemClock.uptimeMillis();
        try {
            if (mViewHost != null) {
                // GeckoViewHost.release() closes the bridge/session, which
                // unbinds the view (GV setSession is @NonNull — see host).
                mViewHost.release();
            } else {
                mBridge.close();
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "bridge.close threw", t);
        } finally {
            android.util.Log.d(TAG, "destroy took "
                    + (android.os.SystemClock.uptimeMillis() - startMs)
                    + "ms");
        }
    }

    // --- WebViewProvider: P0 navigation ---

    // Push facade state onto the live session before every navigation
    // (all mapped keys are non-initOnly in GV158). Set-then-load is the
    // CTS norm; without this, toggles like setJavaScriptEnabled never
    // reached the session (only construction state applied).
    private void pushSettings() {
        try {
            org.mozilla.geckowebview.settings.GeckoWebSettings state =
                    mSettings.gecko();
            // Resolved UA is never null: custom verbatim, else the
            // Chromium-shaped default — so the wire UA equals the
            // settings default (CTS testAccessUserAgentString echoes it).
            mBridge.applyWebSettings(state.getJavaScriptEnabled(),
                    mSettings.resolvedUserAgentString(),
                    state.getDesktopMode(), state.getUseWideViewPort());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "pushSettings threw", t);
        }
    }

    @Override
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        pushSettings();
        mBridge.loadUrl(url);
    }

    @Override
    public void loadUrl(String url) {
        pushSettings();
        mBridge.loadUrl(url);
    }

    @Override
    public void stopLoading() {
        mBridge.stopLoading();
    }

    @Override
    public void reload() {
        pushSettings();
        mBridge.reload();
    }

    @Override
    public boolean canGoBack() {
        return mBridge.canGoBack();
    }

    @Override
    public void goBack() {
        mBridge.goBack();
    }

    @Override
    public boolean canGoForward() {
        return mBridge.canGoForward();
    }

    @Override
    public void goForward() {
        mBridge.goForward();
    }

    @Override
    public String getUrl() {
        return mBridge.getUrl();
    }

    @Override
    public String getTitle() {
        return mBridge.getTitle();
    }

    @Override
    public int getProgress() {
        return mBridge.getProgress();
    }

    @Override
    public WebBackForwardList copyBackForwardList() {
        return new GeckoBackForwardList(mBridge.historySnapshot(),
                !mBridge.hasExplicitAboutLoad(), mFavicon.lookup());
    }


    public void flushHistory() {
        mBridge.flushHistory();
    }

    public void dumpHistorySources(@NonNull String where) {
        mBridge.dumpHistorySources(where);
    }

    public int messagePortCount() {
        return mMessages.portCount();
    }

    @Override
    @NonNull
    public MessageBridge messageBridge() {
        return mMessages;
    }

    /**
     * Sinytra 0001: URI prefixes the app's shouldInterceptRequest can
     * answer; matching loads are synthesized from the app-provided body.
     * Re-settable at any time (re-pushes the filter list to Gecko).
     */
    public void setInterceptFilters(@NonNull String[] filters) {
        mInterception.setFilters(filters);
    }

    public int jsInterfaceCount() {
        return mJsInterfaces.interfaceCount();
    }

    // --- WebViewProvider: clients/settings ---

    @Override
    public void setWebViewClient(WebViewClient client) {
        mWebViewClient = client;
    }

    @Override
    public WebViewClient getWebViewClient() {
        return mWebViewClient;
    }

    @Override
    public void setWebChromeClient(WebChromeClient client) {
        mWebChromeClient = client;
    }

    @Override
    public WebChromeClient getWebChromeClient() {
        return mWebChromeClient;
    }

    @Override
    public void setDownloadListener(DownloadListener listener) {
        Log.d(TAG, "setDownloadListener " + (listener != null)
                + " this=" + Integer.toHexString(hashCode()));
        mDownloadListener = listener;
    }

    @Override
    public WebSettings getSettings() {
        return mSettings;
    }

    // --- WebViewProvider: view/scroll delegates ---
    // No-op defaults split to ProviderViewDelegates (file-size rule); the
    // only live branch there is file chooser onActivityResult routing.

    @Override
    public ViewDelegate getViewDelegate() {
        return ProviderViewDelegates.viewDelegate(this);
    }

    @Override
    public ScrollDelegate getScrollDelegate() {
        return ProviderViewDelegates.scrollDelegate();
    }

    // --- WebViewProvider: the rest is P1/P2 (fail loud, never silently wrong) ---

    private static UnsupportedOperationException todo(String name) {
        return new UnsupportedOperationException(name + ": P1/P2");
    }

    @Override public void setHorizontalScrollbarOverlay(boolean overlay) {}
    @Override public void setVerticalScrollbarOverlay(boolean overlay) {}
    @Override public boolean overlayHorizontalScrollbar() { return false; }
    @Override public boolean overlayVerticalScrollbar() { return false; }
    @Override public int getVisibleTitleHeight() { return 0; }
    @Override public SslCertificate getCertificate() {
        return mBridge.certificate();
    }
    @Override public void setCertificate(SslCertificate certificate) {}
    @Override public void savePassword(String host, String username, String password) {}
    @Override public void setHttpAuthUsernamePassword(String host, String realm, String username,
            String password) {
        try {
            mFactory.webViewDatabase(mWebView.getContext())
                    .setHttpAuthUsernamePassword(host, realm, username, password);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "setHttpAuthUsernamePassword threw", t);
        }
    }
    @Override public String[] getHttpAuthUsernamePassword(String host, String realm) {
        try {
            return mFactory.webViewDatabase(mWebView.getContext())
                    .getHttpAuthUsernamePassword(host, realm);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "getHttpAuthUsernamePassword threw", t);
            return null;
        }
    }
    @Override public void setNetworkAvailable(boolean networkUp) {}
    @Override public WebBackForwardList saveState(Bundle outState) {
        if (outState == null) {
            return new GeckoBackForwardList(mBridge.historySnapshot(),
                !mBridge.hasExplicitAboutLoad(), mFavicon.lookup());
        }
        try {
            StateBridge.saveInto(outState, mBridge.sessionState(),
                    mBridge.historySnapshot());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "saveState threw", t);
        }
        return new GeckoBackForwardList(mBridge.historySnapshot(),
                !mBridge.hasExplicitAboutLoad(), mFavicon.lookup());
    }
    @Override public boolean savePicture(Bundle b, File dest) { return false; }
    @Override public boolean restorePicture(Bundle b, File src) { return false; }
    @Override public WebBackForwardList restoreState(Bundle inState) {
        if (inState == null) {
            return new GeckoBackForwardList(mBridge.historySnapshot(),
                !mBridge.hasExplicitAboutLoad(), mFavicon.lookup());
        }
        try {
            GeckoSession.SessionState state = StateBridge.restoreParcelable(inState);
            if (state != null) {
                mBridge.session().restoreState(state);
                return new GeckoBackForwardList(mBridge.historySnapshot(),
                !mBridge.hasExplicitAboutLoad(), mFavicon.lookup());
            }
            List<String> urls = StateBridge.restoreUrls(inState);
            int index = StateBridge.restoreIndex(inState);
            if (!urls.isEmpty() && index >= 0 && index < urls.size()) {
                String target = urls.get(index);
                if (target != null && !target.isEmpty()) {
                    mBridge.loadUrl(target);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "restoreState threw", t);
        }
        return new GeckoBackForwardList(mBridge.historySnapshot(),
                !mBridge.hasExplicitAboutLoad(), mFavicon.lookup());
    }
    @Override public void postUrl(String url, byte[] postData) { throw todo("postUrl"); }
    // Chromium semantics: data is loaded as-is; baseUrl only resolves
    // relative URLs inside it; historyUrl (when non-null) is shown in the
    // address bar / history INSTEAD of the data: URL.
    //
    // Origin-faithful path (http(s) target): register a one-shot body for
    // the target URL and navigate there for real, so the document origin
    // is the base/history URL — not an opaque data: origin. This is what
    // makes origin-checked APIs (postWebMessage targetOrigin, cookies,
    // CORS, SecureContext) behave: the previous data:-URI mapping broke
    // all of them (CTS PostMessageTest family). Filter registration makes
    // the necko surface own the URL (0001 query serves the one-shot, the
    // LoadRequest DENY stands down); subresource loads under the target
    // still consult the app normally.
    //
    // Honest gaps: with both historyUrl and baseUrl set, subresources
    // resolve against historyUrl (Chromium uses baseUrl) — logged.
    // Non-http(s) targets keep the legacy data: URI (no necko surface).
    // encoding is Chromium's legacy charset label ("base64" or text charset);
    // unknown encodings fall back to UTF-8 (raw bytes for the one-shot
    // path, percent-encoding for the data: path), never throw.
    @Override public void loadData(String data, String mimeType, String encoding) {
        loadDataWithBaseURL(null, data, mimeType, encoding, null);
    }
    @Override public void loadDataWithBaseURL(String baseUrl, String data, String mimeType,
            String encoding, String historyUrl) {
        pushSettings();
        try {
            mInterception.loadDataWithBaseURL(baseUrl, data, mimeType,
                    encoding, historyUrl);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "loadDataWithBaseURL threw", t);
        }
    }

    @Override public void evaluateJavaScript(String script, ValueCallback<String> resultCallback) {
        mJs.evaluate(script, resultCallback);
    }

    // Test seam + PAC evaluator entry: raw JsBridge eval surface.
    void evaluateJavascriptBridge(@NonNull String script,
            @NonNull ValueCallback<String> callback) {
        mJs.evaluate(script, callback);
    }
    @Override public void saveWebArchive(String filename) { throw todo("saveWebArchive"); }
    @Override public void saveWebArchive(String basename, boolean autoname,
            ValueCallback<String> callback) {
        throw todo("saveWebArchive");
    }
    @Override public boolean canGoBackOrForward(int steps) {
        return mBridge.canGoBackOrForward(steps);
    }
    @Override public void goBackOrForward(int steps) { mBridge.goBackOrForward(steps); }
    @Override public boolean isPrivateBrowsingEnabled() { return false; }
    @Override public boolean pageUp(boolean top) { return false; }
    @Override public boolean pageDown(boolean bottom) { return false; }
    @Override public void insertVisualStateCallback(long requestId,
            WebView.VisualStateCallback callback) {
        if (callback == null) {
            return;
        }
        mPendingVisualState.put(requestId, callback);
    }

    void firePendingVisualState() {
        if (mPendingVisualState.isEmpty()) {
            return;
        }
        java.util.Map<Long, WebView.VisualStateCallback> pending =
                new java.util.HashMap<>(mPendingVisualState);
        mPendingVisualState.clear();
        for (java.util.Map.Entry<Long, WebView.VisualStateCallback> entry
                : pending.entrySet()) {
            try {
                entry.getValue().onComplete(entry.getKey());
            } catch (Throwable t) {
                android.util.Log.w(TAG, "VisualStateCallback threw", t);
            }
        }
    }

    // Compat entry points (called by CompatWebViewProvider boundary).

    @Override
    public void insertVisualStateCallback(long requestId,
            @NonNull java.lang.reflect.InvocationHandler boundary) {
        insertVisualStateCallback(requestId,
                org.mozilla.geckowebview.compat.CompatWebViewProvider
                        .CompatVisualStateCallback.wrap(requestId, boundary));
    }

    @Override
    public void postCompatMessage(@Nullable Object messageHandler,
            @Nullable Object targetOrigin) {
        String data = null;
        if (messageHandler != null) {
            try {
                java.lang.reflect.Method getData =
                        messageHandler.getClass().getMethod("getData");
                Object value = getData.invoke(messageHandler);
                data = value != null ? value.toString() : null;
            } catch (Throwable t) {
                android.util.Log.w(TAG, "compat message getData threw", t);
            }
        }
        if (data == null) {
            return;
        }
        String origin = targetOrigin != null ? targetOrigin.toString() : null;
        try {
            mMessages.postToMainFrame(data, origin);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "postCompatMessage threw", t);
        }
    }

    @Override
    public void setCompatRendererClient(
            @Nullable java.lang.reflect.InvocationHandler boundary) {
        if (boundary == null) {
            mRenderProcess.setClient(null, null);
            return;
        }
        // WebViewRenderProcessClient is API 29+; below Q there is no
        // framework renderer client to forward to, so the boundary client
        // is honestly dropped.
        if (android.os.Build.VERSION.SDK_INT < 29) {
            android.util.Log.w(TAG,
                    "setCompatRendererClient: renderer client is API 29+, "
                            + "not available on this device");
            return;
        }
        mRenderProcess.setClient(null,
                org.mozilla.geckowebview.compat.CompatRenderProcess.wrapClient(
                        boundary, null));
    }
    @Override public void clearView() {}
    // Deprecated picture capture (Chromium returns the last-composited
    // picture). Gecko has no such surface here — synthesize an empty Picture
    // so registered PictureListeners still get a non-null argument.
    @Override public Picture capturePicture() { return new Picture(); }
    @Override public PrintDocumentAdapter createPrintDocumentAdapter(String documentName) {
        try {
            return mPrint.createAdapter(mWebView.getContext(), documentName);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "createPrintDocumentAdapter threw", t);
            throw todo("createPrintDocumentAdapter");
        }
    }
    @Override public float getScale() { return 1.0f; }
    @Override public void setInitialScale(int scaleInPercent) {}
    @Override public void invokeZoomPicker() {}
    @Override public WebView.HitTestResult getHitTestResult() { return null; }
    @Override public void requestFocusNodeHref(Message hrefMsg) {}
    @Override public void requestImageRef(Message msg) {}
    @Override public String getOriginalUrl() { return mBridge.getOriginalUrl(); }
    @Override public Bitmap getFavicon() { return mFavicon.getFavicon(); }
    @Override public String getTouchIconUrl() { return null; }
    @Override public int getContentHeight() { return 0; }
    @Override public int getContentWidth() { return 0; }
    @Override public void pauseTimers() {}
    @Override public void resumeTimers() {}

    // App-invoked pause/resume map to the Gecko session active state
    // (the documented background/foreground primitive; ARCHITECTURE §2).
    @Override
    public void onPause() {
        try {
            if (mBridge.session().isOpen()) {
                mBridge.session().setActive(false);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "onPause setActive threw", t);
        }
    }

    @Override
    public void onResume() {
        try {
            if (mBridge.session().isOpen()) {
                mBridge.session().setActive(true);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "onResume setActive threw", t);
        }
    }

    @Override public boolean isPaused() { return false; }
    @Override public void freeMemory() {}
    @Override public void clearCache(boolean includeDiskFiles) {
        if (!includeDiskFiles) {
            return;
        }
        try {
            org.mozilla.geckoview.StorageController controller =
                    GeckoRuntimeHolder.get(mWebView.getContext())
                            .getStorageController();
            controller.clearData(
                    org.mozilla.geckoview.StorageController.ClearFlags.ALL_CACHES);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "clearCache threw", t);
        }
    }
    @Override public void clearFormData() {
        try {
            mFactory.webViewDatabase(mWebView.getContext()).clearFormData();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "clearFormData threw", t);
        }
    }
    @Override public void clearHistory() {
        mBridge.clearHistory();
    }
    @Override public void clearSslPreferences() {}
    @Override public void setFindListener(WebView.FindListener listener) {
        mFind.setFindListener(listener);
    }
    @Override public void findNext(boolean forward) {
        mFind.findNext(forward);
    }
    @Override public int findAll(String find) { return 0; }
    @Override public void findAllAsync(String find) {
        if (find == null) {
            return;
        }
        mFind.findAllAsync(find);
    }
    @Override public boolean showFindDialog(String text, boolean showIme) { return false; }
    @Override public void clearMatches() {
        mFind.clearMatches();
    }
    @Override public void documentHasImages(Message response) {}
    @Override public WebViewRenderProcess getWebViewRenderProcess() {
        return mRenderProcess.process();
    }
    @Override public void setWebViewRenderProcessClient(
            java.util.concurrent.Executor executor, WebViewRenderProcessClient client) {
        mRenderProcess.setClient(executor, client);
    }
    @Override public WebViewRenderProcessClient getWebViewRenderProcessClient() {
        return mRenderProcess.getClient();
    }
    @Override public void setPictureListener(WebView.PictureListener listener) {
        mPictureListener = listener;
    }
    @Override public void addJavascriptInterface(Object obj, String interfaceName) {
        try {
            mJsInterfaces.addInterface(obj, interfaceName);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "addJavascriptInterface threw", t);
            throw new IllegalArgumentException(interfaceName, t);
        }
    }
    @Override public void removeJavascriptInterface(String interfaceName) {
        try {
            mJsInterfaces.removeInterface(interfaceName);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "removeJavascriptInterface threw", t);
        }
    }
    @Override public WebMessagePort[] createWebMessageChannel() {
        // Framework-typed ports: android.webkit.SinytraWebMessagePort
        // (same-package subclass over the @SystemApi framework ctor — see
        // its header; resolves the old AOSP-patch-vs-hook decision point
        // with neither). Routes into MessageBridge; the page transport is
        // shared with the boundary face (LiveMessagePort). Port transfer
        // (getPorts) stays an honest gap.
        MessageBridge.Port[] ports = mMessages.createChannel();
        android.webkit.WebMessagePort[] fw = new WebMessagePort[] {
                new SinytraWebMessagePort(portBinding(ports[0])),
                new SinytraWebMessagePort(portBinding(ports[1])),
        };
        synchronized (mPortRoutes) {
            mPortRoutes.put(fw[0], ports[0]);
            mPortRoutes.put(fw[1], ports[1]);
        }
        return fw;
    }

    @NonNull
    private SinytraWebMessagePort.Binding portBinding(
            @NonNull MessageBridge.Port port) {
        return new SinytraWebMessagePort.Binding() {
            @Override
            public void post(@NonNull String data) {
                mMessages.postMessage(port, data, null);
            }

            @Override
            public void close() {
                mMessages.close(port);
            }

            @Override
            public void setCallback(
                    @Nullable WebMessagePort.WebMessageCallback callback) {
                mMessages.setCallback(port, callback);
            }
        };
    }
    @Override public void postMessageToMainFrame(WebMessage message, Uri targetOrigin) {
        if (message == null) {
            return;
        }
        try {
            // App-to-page MessagePort transfer (CTS PostMessageTest
            // testMessageChannel family): framework ports map back to
            // bridge ports; unknown/closed ports are skipped (honest).
            // Page-created transfer stays unsupported (see header).
            java.util.List<MessageBridge.Port> transferred =
                    new java.util.ArrayList<>();
            WebMessagePort[] fwPorts = message.getPorts();
            if (fwPorts != null) {
                synchronized (mPortRoutes) {
                    for (WebMessagePort fw : fwPorts) {
                        MessageBridge.Port routed = mPortRoutes.get(fw);
                        if (routed != null) {
                            transferred.add(routed);
                        }
                    }
                }
            }
            mMessages.postToMainFrame(
                    message.getData() != null ? message.getData() : "",
                    targetOrigin != null ? targetOrigin.toString() : null,
                    transferred);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "postMessageToMainFrame threw", t);
        }
    }
    @Override public void setMapTrackballToArrowKeys(boolean setMap) {}
    @Override public void flingScroll(int vx, int vy) {}
    @Override public View getZoomControls() { return null; }
    @Override public boolean canZoomIn() { return false; }
    @Override public boolean canZoomOut() { return false; }
    @Override public boolean zoomBy(float zoomFactor) { return false; }
    @Override public boolean zoomIn() { return false; }
    @Override public boolean zoomOut() { return false; }
    @Override public void dumpViewHierarchyWithProperties(BufferedWriter out, int level) {}
    @Override public View findHierarchyView(String className, int hashCode) { return null; }
    @Override public void setRendererPriorityPolicy(int rendererRequestedPriority,
            boolean waivedWhenNotVisible) {}
    @Override public int getRendererRequestedPriority() { return 0; }
    @Override public boolean getRendererPriorityWaivedWhenNotVisible() { return false; }
    @Override public void notifyFindDialogDismissed() {}
}
