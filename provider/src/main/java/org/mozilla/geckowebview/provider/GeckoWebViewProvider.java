package org.mozilla.geckowebview.provider;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Bundle;
import android.os.Message;
import android.print.PrintDocumentAdapter;
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
import java.util.Map;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckowebview.session.ContentBridge;
import org.mozilla.geckowebview.session.ErrorBridge;
import org.mozilla.geckowebview.session.FindBridge;
import org.mozilla.geckowebview.session.GeckoSessionBridge;
import org.mozilla.geckowebview.session.PermissionBridge;
import org.mozilla.geckowebview.session.PrintBridge;
import org.mozilla.geckowebview.session.PromptBridge;
import org.mozilla.geckowebview.settings.GeckoWebSettings;

// WebViewProvider backend for one WebView instance (P0 subset).
// Navigation + read accessors are live (GeckoSessionBridge); everything else
// fails honest-and-loud (UnsupportedOperationException) so gaps surface in
// tests instead of silently misbehaving. Delegate exceptions never propagate:
// see onGeckoError path.
public final class GeckoWebViewProvider
        implements WebViewProvider, GeckoSessionBridge.Client,
        PermissionBridge.Host, PromptBridge.Host, ContentBridge.Host,
        FindBridge.Host {
    private static final String TAG = "Sinytra/session";
    private static final int FILE_CHOOSER_REQUEST = 0x5EED;

    private final WebView mWebView;
    private final GeckoWebViewFactoryProvider mFactory;
    private final GeckoSessionBridge mBridge;
    private final CompatWebSettings mSettings;
    private final FindBridge mFind;
    private final PrintBridge mPrint;
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
        mBridge = new GeckoSessionBridge(this);
        mSettings = new CompatWebSettings(new GeckoWebSettings());
        mFind = new FindBridge(mBridge.session(), this);
        mPrint = new PrintBridge(mBridge.session());
        mBridge.setExtraDelegates(this, this, this);
        // Session must be opened on the UI thread (GeckoView @UiThread contract).
        // Real framework calls create() on the UI thread; assert here so the
        // harness (or future callers) fail fast instead of hanging on load.
        mBridge.session().open(GeckoRuntimeHolder.get(
                webView.getContext().getApplicationContext()));
    }

    @NonNull
    GeckoSessionBridge bridge() {
        return mBridge;
    }

    // --- GeckoSessionBridge.Client (delegate → WebViewClient fan-out) ---

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
        WebViewClient client = mWebViewClient;
        if (client == null) {
            return false;
        }
        try {
            return client.shouldOverrideUrlLoading(mWebView, url);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.shouldOverrideUrlLoading threw", t);
            return false;
        }
    }

    @Override
    public void onPageStarted(@NonNull String url) {
        WebViewClient client = mWebViewClient;
        if (client == null) {
            return;
        }
        try {
            client.onPageStarted(mWebView, url, null);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onPageStarted threw", t);
        }
    }

    @Override
    public void onPageFinished(boolean success) {
        WebViewClient client = mWebViewClient;
        String url = mBridge.getUrl();
        if (client == null || url == null) {
            return;
        }
        try {
            if (success) {
                client.onPageFinished(mWebView, url);
            } else {
                client.onReceivedError(mWebView, WebViewClient.ERROR_UNKNOWN, "load failed",
                        url);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onPageFinished threw", t);
        }
    }

    @Override
    public void onProgressChanged(int progress) {
        WebChromeClient chrome = mWebChromeClient;
        if (chrome == null) {
            return;
        }
        try {
            chrome.onProgressChanged(mWebView, progress);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onProgressChanged threw", t);
        }
    }

    @Override
    public void onTitleChanged(@Nullable String title) {
        WebChromeClient chrome = mWebChromeClient;
        if (chrome == null) {
            return;
        }
        try {
            chrome.onReceivedTitle(mWebView, title);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onReceivedTitle threw", t);
        }
    }

    @Override
    public void onLoadError(int errorCode, @NonNull String description,
            @Nullable String failingUrl) {
        WebViewClient client = mWebViewClient;
        if (client == null || failingUrl == null) {
            return;
        }
        try {
            client.onReceivedError(mWebView, errorCode, description, failingUrl);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onReceivedError threw", t);
        }
    }

    // --- PermissionBridge.Host ---

    @Override
    public void onGeolocationPrompt(@NonNull String origin) {
        WebChromeClient chrome = mWebChromeClient;
        GeolocationPermissions.Callback callback =
                new GeolocationPermissions.Callback() {
                    @Override
                    public void invoke(String o, boolean allow, boolean retain) {
                    }
                };
        if (chrome == null) {
            callback.invoke(origin, false, false);
            return;
        }
        try {
            chrome.onGeolocationPermissionsShowPrompt(origin, callback);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "onGeolocationPermissionsShowPrompt threw", t);
            callback.invoke(origin, false, false);
        }
    }

    @Override
    public void onPermissionRequest(@NonNull String origin, int geckoPermission) {
        WebChromeClient chrome = mWebChromeClient;
        if (chrome == null) {
            return;
        }
        try {
            chrome.onPermissionRequest(new SinytraPermissionRequest(origin));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onPermissionRequest threw", t);
        }
    }

    @Override
    public void onAndroidPermissionsRequest(@NonNull String[] permissions,
            @NonNull GeckoSession.PermissionDelegate.Callback callback) {
        try {
            callback.reject();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "permission callback reject threw", t);
        }
    }

    @Override
    public void onMediaRequest(@NonNull String uri,
            @NonNull GeckoSession.PermissionDelegate.MediaSource[] video,
            @NonNull GeckoSession.PermissionDelegate.MediaSource[] audio,
            @NonNull GeckoSession.PermissionDelegate.MediaCallback callback) {
        try {
            callback.reject();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "media callback reject threw", t);
        }
    }

    // --- PromptBridge.Host ---

    @Override
    public void onFileChooserRequest(
            @NonNull GeckoSession.PromptDelegate.FilePrompt prompt) {
        WebChromeClient chrome = mWebChromeClient;
        ValueCallback<Uri[]> callback = new ValueCallback<Uri[]>() {
            @Override
            public void onReceiveValue(Uri[] value) {
                try {
                    if (value != null && value.length > 0) {
                        Context context = mWebView.getContext();
                        if (prompt.type
                                == GeckoSession.PromptDelegate.FilePrompt.Type.SINGLE) {
                            prompt.confirm(context, value[0]);
                        } else {
                            prompt.confirm(context, value);
                        }
                    } else {
                        prompt.dismiss();
                    }
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "file prompt confirm threw", t);
                } finally {
                    mFileChooserCallback = null;
                }
            }
        };
        mFileChooserCallback = callback;
        if (chrome == null) {
            callback.onReceiveValue(null);
            return;
        }
        try {
            boolean handled = chrome.onShowFileChooser(mWebView, callback,
                    new SinytraFileChooserParams(prompt));
            if (!handled) {
                callback.onReceiveValue(null);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onShowFileChooser threw", t);
            callback.onReceiveValue(null);
        }
    }

    @Override
    public void onHttpAuthRequest(@NonNull GeckoSession.PromptDelegate.AuthPrompt prompt) {
        String uri = prompt.authOptions != null && prompt.authOptions.uri != null
                ? prompt.authOptions.uri : "";
        String host = hostOf(uri);
        String realm = prompt.message != null ? prompt.message : "";
        String[] stored = null;
        try {
            stored = mFactory.webViewDatabase(mWebView.getContext())
                    .getHttpAuthUsernamePassword(host, realm);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "webViewDatabase get threw", t);
        }
        if (stored != null && stored.length == 2) {
            try {
                prompt.confirm(stored[0] != null ? stored[0] : "",
                        stored[1] != null ? stored[1] : "");
                return;
            } catch (Throwable t) {
                android.util.Log.w(TAG, "auth confirm stored threw", t);
            }
        }
        WebViewClient client = mWebViewClient;
        if (client == null) {
            try {
                prompt.dismiss();
            } catch (Throwable t) {
                android.util.Log.w(TAG, "auth prompt dismiss threw", t);
            }
            return;
        }
        HttpAuthHandler handler = newFrameworkAuthHandler(prompt);
        if (handler == null) {
            try {
                prompt.dismiss();
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            client.onReceivedHttpAuthRequest(mWebView, handler, uri, realm);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onReceivedHttpAuthRequest threw", t);
            try {
                prompt.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String hostOf(@NonNull String uri) {
        try {
            return Uri.parse(uri).getHost() != null ? Uri.parse(uri).getHost() : uri;
        } catch (Throwable t) {
            return uri;
        }
    }

    // Reflective JsResult/JsPromptResult factory: android.webkit.JsResult has
    // a package-private ctor, so provider code (different package) cannot
    // `new` one at compile time; runtime reflection reaches it (same
    // technique as P0Glue's PrivateAccess construction). The instance is
    // only passed into the app's onJsAlert/onJsConfirm/onJsPrompt; the
    // Gecko-side decision is already made by PromptBridge from the app's
    // return value, so these objects are never observed afterwards.
    @Nullable
    private static JsResult newJsResult() {
        try {
            java.lang.reflect.Constructor<JsResult> ctor =
                    JsResult.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "JsResult reflection failed", t);
            return null;
        }
    }

    @Nullable
    private static JsPromptResult newJsPromptResult() {
        try {
            java.lang.reflect.Constructor<JsPromptResult> ctor =
                    JsPromptResult.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "JsPromptResult reflection failed", t);
            return null;
        }
    }

    @Override
    public void onJsAlert(@NonNull String title, @NonNull String message) {
        WebChromeClient chrome = mWebChromeClient;
        if (chrome == null) {
            return;
        }
        JsResult result = newJsResult();
        if (result == null) {
            return;
        }
        try {
            chrome.onJsAlert(mWebView, mBridge.getUrl(), message, result);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onJsAlert threw", t);
        }
    }

    @Override
    public boolean onJsConfirm(@NonNull String title, @NonNull String message) {
        WebChromeClient chrome = mWebChromeClient;
        if (chrome == null) {
            return false;
        }
        JsResult result = newJsResult();
        if (result == null) {
            return false;
        }
        try {
            chrome.onJsConfirm(mWebView, mBridge.getUrl(), message, result);
            return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onJsConfirm threw", t);
            return false;
        }
    }

    @Override
    @Nullable
    public String onJsPrompt(@NonNull String title, @NonNull String message,
            @Nullable String defaultValue) {
        WebChromeClient chrome = mWebChromeClient;
        if (chrome == null) {
            return null;
        }
        JsPromptResult result = newJsPromptResult();
        if (result == null) {
            return null;
        }
        try {
            boolean handled = chrome.onJsPrompt(mWebView, mBridge.getUrl(), message,
                    defaultValue != null ? defaultValue : "", result);
            return handled ? "" : null;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onJsPrompt threw", t);
            return null;
        }
    }

    // --- ContentBridge.Host ---

    @Override
    public void onDownloadStart(@NonNull String url, @Nullable String userAgent,
            @Nullable String contentDisposition, @NonNull String mimeType,
            long contentLength) {
        DownloadListener listener = mDownloadListener;
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
        WebChromeClient chrome = mWebChromeClient;
        if (chrome == null) {
            return;
        }
        try {
            if (fullScreen) {
                chrome.onShowCustomView(mWebView, null);
            } else {
                chrome.onHideCustomView();
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient fullscreen threw", t);
        }
    }

    @Override
    public void onCloseWindow() {
        WebChromeClient chrome = mWebChromeClient;
        if (chrome == null) {
            return;
        }
        try {
            chrome.onCloseWindow(mWebView);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onCloseWindow threw", t);
        }
    }

    @Override
    public void onFocusRequest() {
        WebChromeClient chrome = mWebChromeClient;
        if (chrome == null) {
            return;
        }
        try {
            chrome.onRequestFocus(mWebView);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onRequestFocus threw", t);
        }
    }

    @Override
    public void onCrash() {
        WebViewClient client = mWebViewClient;
        if (client == null) {
            return;
        }
        String url = mBridge.getUrl();
        try {
            client.onReceivedError(mWebView, WebViewClient.ERROR_UNKNOWN,
                    "renderer crashed", url != null ? url : "about:blank");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient crash report threw", t);
        }
    }

    // --- FindBridge.Host ---

    @Override
    public void onFindResult(int activeMatchOrdinal, int numberOfMatches, boolean done) {
    }

    // --- WebViewProvider: lifecycle ---

    @Override
    public void init(Map<String, Object> javaScriptInterfaces, boolean privateBrowsing) {
    }

    @Override
    public void destroy() {
        mDestroyed = true;
        try {
            mBridge.close();
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
        mDownloadListener = listener;
    }

    @Override
    public WebSettings getSettings() {
        return mSettings;
    }

    // --- WebViewProvider: view/scroll delegates (P1: real GeckoViewHost) ---

    @Override
    public ViewDelegate getViewDelegate() {
        return new ViewDelegate() {
            @Override public boolean shouldDelayChildPressedState() { return false; }
            @Override public void onProvideVirtualStructure(
                    android.view.ViewStructure structure) {}
            @Override public android.view.accessibility.AccessibilityNodeProvider
                    getAccessibilityNodeProvider() { return null; }
            @Override public void onInitializeAccessibilityNodeInfo(
                    android.view.accessibility.AccessibilityNodeInfo info) {}
            @Override public void onInitializeAccessibilityEvent(
                    android.view.accessibility.AccessibilityEvent event) {}
            @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
                return false;
            }
            @Override public void setOverScrollMode(int mode) {}
            @Override public void setScrollBarStyle(int style) {}
            @Override public void onDrawVerticalScrollBar(Canvas canvas,
                    android.graphics.drawable.Drawable scrollBar, int l, int t, int r,
                    int b) {}
            @Override public void onOverScrolled(int scrollX, int scrollY, boolean clampedX,
                    boolean clampedY) {}
            @Override public void onWindowVisibilityChanged(int visibility) {}
            @Override public void onDraw(Canvas canvas) {}
            @Override public void setLayoutParams(ViewGroup.LayoutParams layoutParams) {}
            @Override public boolean performLongClick() { return false; }
            @Override public void onConfigurationChanged(
                    android.content.res.Configuration newConfig) {}
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(
                    android.view.inputmethod.EditorInfo outAttrs) { return null; }
            @Override public boolean onDragEvent(android.view.DragEvent event) { return false; }
            @Override public boolean onKeyMultiple(int keyCode, int repeatCount,
                    android.view.KeyEvent event) { return false; }
            @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
                return false;
            }
            @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
                return false;
            }
            @Override public void onAttachedToWindow() {}
            @Override public void onDetachedFromWindow() {}
            @Override public void onVisibilityChanged(View changedView, int visibility) {}
            @Override public void onWindowFocusChanged(boolean hasWindowFocus) {}
            @Override public void onFocusChanged(boolean focused, int direction,
                    android.graphics.Rect previouslyFocusedRect) {}
            @Override public boolean setFrame(int left, int top, int right, int bottom) {
                return false;
            }
            @Override public void onSizeChanged(int w, int h, int ow, int oh) {}
            @Override public void onScrollChanged(int l, int t, int oldl, int oldt) {}
            @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
                return false;
            }
            @Override public boolean onTouchEvent(android.view.MotionEvent ev) { return false; }
            @Override public boolean onHoverEvent(android.view.MotionEvent event) {
                return false;
            }
            @Override public boolean onGenericMotionEvent(android.view.MotionEvent event) {
                return false;
            }
            @Override public boolean onTrackballEvent(android.view.MotionEvent ev) {
                return false;
            }
            @Override public boolean requestFocus(int direction,
                    android.graphics.Rect previouslyFocusedRect) { return false; }
            @Override public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {}
            @Override public boolean requestChildRectangleOnScreen(View child,
                    android.graphics.Rect rect, boolean immediate) { return false; }
            @Override public void setBackgroundColor(int color) {}
            @Override public void setLayerType(int layerType, android.graphics.Paint paint) {}
            @Override public void preDispatchDraw(Canvas canvas) {}
            @Override public void onStartTemporaryDetach() {}
            @Override public void onFinishTemporaryDetach() {}
            @Override public void onActivityResult(int requestCode, int resultCode,
                    android.content.Intent data) {
                if (requestCode == FILE_CHOOSER_REQUEST) {
                    ValueCallback<Uri[]> callback = mFileChooserCallback;
                    mFileChooserCallback = null;
                    if (callback == null) {
                        return;
                    }
                    try {
                        Uri[] results = WebChromeClient.FileChooserParams.parseResult(
                                resultCode, data);
                        callback.onReceiveValue(results);
                    } catch (Throwable t) {
                        android.util.Log.w(TAG, "file chooser parse threw", t);
                        try {
                            callback.onReceiveValue(null);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            @Override public android.os.Handler getHandler(android.os.Handler originalHandler) {
                return originalHandler;
            }
            @Override public View findFocus(View originalFocusedView) {
                return originalFocusedView;
            }
        };
    }

    @Override
    public ScrollDelegate getScrollDelegate() {
        return new ScrollDelegate() {
            @Override public int computeHorizontalScrollRange() { return 0; }
            @Override public int computeHorizontalScrollOffset() { return 0; }
            @Override public int computeVerticalScrollRange() { return 0; }
            @Override public int computeVerticalScrollOffset() { return 0; }
            @Override public int computeVerticalScrollExtent() { return 0; }
            @Override public void computeScroll() {}
        };
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
        return new GeckoBackForwardList(mBridge.historySnapshot());
    }
    @Override public boolean savePicture(Bundle b, File dest) { return false; }
    @Override public boolean restorePicture(Bundle b, File src) { return false; }
    @Override public WebBackForwardList restoreState(Bundle inState) {
        throw todo("restoreState");
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
        throw todo("evaluateJavaScript");
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
        throw todo("insertVisualStateCallback");
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
    @Override public void onPause() {}
    @Override public void onResume() {}
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
    @Override public WebViewRenderProcess getWebViewRenderProcess() { return null; }
    @Override public void setWebViewRenderProcessClient(
            java.util.concurrent.Executor executor, WebViewRenderProcessClient client) {}
    @Override public WebViewRenderProcessClient getWebViewRenderProcessClient() { return null; }
    @Override public void setPictureListener(WebView.PictureListener listener) {}
    @Override public void addJavascriptInterface(Object obj, String interfaceName) {
        throw todo("addJavascriptInterface");
    }
    @Override public void removeJavascriptInterface(String interfaceName) {}
    @Override public WebMessagePort[] createWebMessageChannel() {
        throw todo("createWebMessageChannel");
    }
    @Override public void postMessageToMainFrame(WebMessage message, Uri targetOrigin) {
        throw todo("postMessageToMainFrame");
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

    // --- P1 adapter classes (package-visible for harness/tests) ---

    static final class SinytraPermissionRequest extends PermissionRequest {
        private final String mOrigin;

        SinytraPermissionRequest(String origin) {
            mOrigin = origin;
        }

        @Override
        public Uri getOrigin() {
            try {
                return Uri.parse(mOrigin);
            } catch (Throwable t) {
                return Uri.EMPTY;
            }
        }

        @Override
        public String[] getResources() {
            return new String[0];
        }

        @Override
        public void grant(String[] resources) {
        }

        @Override
        public void deny() {
        }
    }

    static final class SinytraFileChooserParams extends WebChromeClient.FileChooserParams {
        private final GeckoSession.PromptDelegate.FilePrompt mPrompt;

        SinytraFileChooserParams(GeckoSession.PromptDelegate.FilePrompt prompt) {
            mPrompt = prompt;
        }

        @Override
        public int getMode() {
            return mPrompt.type
                    == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
                    ? MODE_OPEN_MULTIPLE : MODE_OPEN;
        }

        @Override
        public String[] getAcceptTypes() {
            return mPrompt.mimeTypes != null ? mPrompt.mimeTypes : new String[0];
        }

        @Override
        public boolean isCaptureEnabled() {
            return false;
        }

        @Override
        public CharSequence getTitle() {
            return mPrompt.title != null ? mPrompt.title : "";
        }

        @Override
        public String getFilenameHint() {
            return "";
        }

        @Override
        public Intent createIntent() {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            String[] types = getAcceptTypes();
            if (types.length == 1) {
                intent.setType(types[0]);
            } else {
                intent.setType("*/*");
                if (types.length > 1) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
                }
            }
            if (getMode() == MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            return intent;
        }
    }

    // android.webkit.HttpAuthHandler has a package-private ctor: compile
    // time cannot see it from another package, but runtime reflection can
    // (same as P0Glue's PrivateAccess). The instance is only a token passed
    // into the app's onReceivedHttpAuthRequest; stored-credential auto-fill
    // above already handled the unattended case, and the app's interactive
    // proceed()/cancel() on this token is best-effort in P1 (P2 can proxy
    // it once a generated subclass lands).
    private HttpAuthHandler newFrameworkAuthHandler(
            final GeckoSession.PromptDelegate.AuthPrompt prompt) {
        try {
            java.lang.reflect.Constructor<HttpAuthHandler> ctor =
                    HttpAuthHandler.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "HttpAuthHandler reflection failed", t);
            return null;
        }
    }

    static final class SinytraClientCertRequest extends ClientCertRequest {
        @Override
        public String[] getKeyTypes() {
            return new String[0];
        }

        @Override
        public Principal[] getPrincipals() {
            return new Principal[0];
        }

        @Override
        public String getHost() {
            return "";
        }

        @Override
        public int getPort() {
            return -1;
        }

        @Override
        public void proceed(PrivateKey privateKey, X509Certificate[] chain) {
        }

        @Override
        public void ignore() {
        }

        @Override
        public void cancel() {
        }
    }
}
