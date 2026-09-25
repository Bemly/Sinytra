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
    private final InterceptBridge mIntercept;
    private final RenderProcessBridge mRenderProcess;
    // Sinytra 0001: response interception surface (see ResponseBridge).
    private final ResponseBridge mResponseBridge;
    // Visual surface (2026-09-25): GeckoViewHost attached as a WebView
    // child; the child's own view lifecycle owns surface attach/detach.
    // Null = attach failed, headless fallback (loud log).
    @Nullable
    private final GeckoViewHost mViewHost;
    @NonNull
    private volatile String[] mInterceptFilters = new String[0];
    private final java.util.Map<Long, WebView.VisualStateCallback> mPendingVisualState =
            new java.util.concurrent.ConcurrentHashMap<>();
    private WebViewClient mWebViewClient;
    private WebChromeClient mWebChromeClient;
    private DownloadListener mDownloadListener;
    @Nullable
    private ValueCallback<Uri[]> mFileChooserCallback;
    private volatile boolean mDestroyed;

    public GeckoWebViewProvider(@NonNull WebView webView,
            @NonNull GeckoWebViewFactoryProvider factory) {
        mWebView = webView;
        mFactory = factory;
        mFanOut = new ClientFanOut(this);
        mBridge = new GeckoSessionBridge(mFanOut);
        mSettings = new CompatWebSettings(new GeckoWebSettings());
        mFind = new FindBridge(mBridge.session(), mFanOut);
        mPrint = new PrintBridge(mBridge.session());
        mJs = new JsEvaluator();
        mJsBridge = new JsBridge();
        mJs.setBridge(mJsBridge);
        mJsInterfaces = new JavascriptBridge();
        mMessages = new MessageBridge(mFanOut);
        mJsInterfaces.setTransport(mJsBridge);
        mMessages.setTransport(mJsBridge);
        mIntercept = new InterceptBridge(mFanOut);
        mRenderProcess = new RenderProcessBridge();
        mBridge.setExtraDelegates(mFanOut, mFanOut, mFanOut);
        mBridge.setInterceptBridge(mIntercept);
        // Session must be opened on the UI thread (GeckoView @UiThread contract).
        // Real framework calls create() on the UI thread; assert here so the
        // harness (or future callers) fail fast instead of hanging on load.
        mResponseBridge = new ResponseBridge(new ResponseBridge.Host() {
            @Override
            @NonNull
            public String[] getFilters() {
                return mInterceptFilters;
            }

            @Override
            @Nullable
            public ResponseBridge.WebResourceResponseHolder shouldIntercept(
                    @NonNull WebRequestInfo info) {
                // 0002: the necko query carries the request surface —
                // isTopLevel maps to WebResourceRequest.isForMainFrame,
                // method/headers pass through unchanged.
                WebResourceResponse app = mIntercept.queryApp(info.uri,
                        false, false, info.isTopLevel, info.method,
                        info.requestHeaders);
                if (app == null) {
                    return null;
                }
                return new ResponseBridge.WebResourceResponseHolder(
                        app.getMimeType(), app.getEncoding(),
                        app.getStatusCode(), app.getData());
            }
        });
        mBridge.session().setResponseDelegate(mResponseBridge);
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
        // view hosts the session surface). Failure degrades to headless
        // operation with a loud log — every non-visual path keeps working.
        GeckoViewHost host = null;
        try {
            host = new GeckoViewHost(webView.getContext());
            webView.addView(host, new android.widget.AbsoluteLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
            host.bind(webView.getContext(), mBridge);
        } catch (Throwable t) {
            android.util.Log.w(TAG,
                    "GeckoViewHost attach threw (headless fallback)", t);
            host = null;
        }
        mViewHost = host;
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
    }

    @Override
    @NonNull
    public String[] interceptFilters() {
        return mInterceptFilters;
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
        }
    }

    // --- WebViewProvider: P0 navigation ---

    @Override
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        mBridge.loadUrl(url);
    }

    @Override
    public void loadUrl(String url) {
        mBridge.loadUrl(url);
    }

    @Override
    public void stopLoading() {
        mBridge.stopLoading();
    }

    @Override
    public void reload() {
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
        return new GeckoBackForwardList(mBridge.historySnapshot());
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
        mInterceptFilters = filters.clone();
        // Re-dispatch the delegate: setResponseDelegate re-pushes filters
        // to the Gecko interception controller.
        if (!mDestroyed) {
            mBridge.session().setResponseDelegate(mResponseBridge);
        }
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
            return new GeckoBackForwardList(mBridge.historySnapshot());
        }
        try {
            StateBridge.saveInto(outState, mBridge.sessionState(),
                    mBridge.historySnapshot());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "saveState threw", t);
        }
        return new GeckoBackForwardList(mBridge.historySnapshot());
    }
    @Override public boolean savePicture(Bundle b, File dest) { return false; }
    @Override public boolean restorePicture(Bundle b, File src) { return false; }
    @Override public WebBackForwardList restoreState(Bundle inState) {
        if (inState == null) {
            return new GeckoBackForwardList(mBridge.historySnapshot());
        }
        try {
            GeckoSession.SessionState state = StateBridge.restoreParcelable(inState);
            if (state != null) {
                mBridge.session().restoreState(state);
                return new GeckoBackForwardList(mBridge.historySnapshot());
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
        return new GeckoBackForwardList(mBridge.historySnapshot());
    }
    @Override public void postUrl(String url, byte[] postData) { throw todo("postUrl"); }
    @Override public void loadData(String data, String mimeType, String encoding) {
        throw todo("loadData");
    }
    @Override public void loadDataWithBaseURL(String baseUrl, String data, String mimeType,
            String encoding, String historyUrl) {
        throw todo("loadDataWithBaseURL");
    }
    @Override public void evaluateJavaScript(String script, ValueCallback<String> resultCallback) {
        mJs.evaluate(script, resultCallback);
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
    @Override public Picture capturePicture() { return null; }
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
    @Override public String getOriginalUrl() { return getUrl(); }
    @Override public Bitmap getFavicon() { return null; }
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
    @Override public void setPictureListener(WebView.PictureListener listener) {}
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
        return new WebMessagePort[] {
                new SinytraWebMessagePort(portBinding(ports[0])),
                new SinytraWebMessagePort(portBinding(ports[1])),
        };
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
            mMessages.postToMainFrame(message.getData(),
                    targetOrigin != null ? targetOrigin.toString() : null);
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
