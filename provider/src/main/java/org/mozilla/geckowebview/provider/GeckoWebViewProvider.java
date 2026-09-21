package org.mozilla.geckowebview.provider;

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
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
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
import java.util.List;
import java.util.Map;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckowebview.session.GeckoSessionBridge;
import org.mozilla.geckowebview.settings.GeckoWebSettings;

// WebViewProvider backend for one WebView instance (P0 subset).
// Navigation + read accessors are live (GeckoSessionBridge); everything else
// fails honest-and-loud (UnsupportedOperationException) so gaps surface in
// tests instead of silently misbehaving. Delegate exceptions never propagate:
// see onGeckoError path.
public final class GeckoWebViewProvider
        implements WebViewProvider, GeckoSessionBridge.Client {
    private static final String TAG = "Sinytra/session";

    private final WebView mWebView;
    private final GeckoWebViewFactoryProvider mFactory;
    private final GeckoSessionBridge mBridge;
    private final CompatWebSettings mSettings;
    private WebViewClient mWebViewClient;
    private WebChromeClient mWebChromeClient;
    private DownloadListener mDownloadListener;
    private volatile boolean mDestroyed;

    public GeckoWebViewProvider(@NonNull WebView webView,
            @NonNull GeckoWebViewFactoryProvider factory) {
        mWebView = webView;
        mFactory = factory;
        mBridge = new GeckoSessionBridge(this);
        mSettings = new CompatWebSettings(new GeckoWebSettings());
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
                    android.content.Intent data) {}
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
    @Override public SslCertificate getCertificate() { return null; }
    @Override public void setCertificate(SslCertificate certificate) {}
    @Override public void savePassword(String host, String username, String password) {}
    @Override public void setHttpAuthUsernamePassword(String host, String realm, String username,
            String password) {}
    @Override public String[] getHttpAuthUsernamePassword(String host, String realm) {
        return null;
    }
    @Override public void setNetworkAvailable(boolean networkUp) {}
    @Override public WebBackForwardList saveState(Bundle outState) {
        throw todo("saveState");
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
    @Override public boolean canGoBackOrForward(int steps) { return false; }
    @Override public void goBackOrForward(int steps) { throw todo("goBackOrForward"); }
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
        throw todo("createPrintDocumentAdapter");
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
    @Override public void clearCache(boolean includeDiskFiles) {}
    @Override public void clearFormData() {}
    @Override public void clearHistory() {}
    @Override public void clearSslPreferences() {}
    @Override public void setFindListener(WebView.FindListener listener) {}
    @Override public void findNext(boolean forward) {}
    @Override public int findAll(String find) { return 0; }
    @Override public void findAllAsync(String find) {}
    @Override public boolean showFindDialog(String text, boolean showIme) { return false; }
    @Override public void clearMatches() {}
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
}
