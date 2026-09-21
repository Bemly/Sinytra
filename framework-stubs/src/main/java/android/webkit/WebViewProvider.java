// Compile-only stub for AOSP android14-release hidden WebViewProvider.
// Verified against device framework.jar dex dump (classes4.dex).
// See WebViewFactoryProvider.java header for stub policy.
package android.webkit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.print.PrintDocumentAdapter;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.content.res.Configuration;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.textclassifier.TextClassifier;
import android.view.translation.TranslationCapability;
import android.view.translation.ViewTranslationRequest;
import android.view.translation.ViewTranslationResponse;
import java.io.BufferedWriter;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public interface WebViewProvider {
    void init(Map<String, Object> javaScriptInterfaces, boolean privateBrowsing);
    void setHorizontalScrollbarOverlay(boolean overlay);
    void setVerticalScrollbarOverlay(boolean overlay);
    boolean overlayHorizontalScrollbar();
    boolean overlayVerticalScrollbar();
    int getVisibleTitleHeight();
    SslCertificate getCertificate();
    void setCertificate(SslCertificate certificate);
    void savePassword(String host, String username, String password);
    void setHttpAuthUsernamePassword(String host, String realm, String username, String password);
    String[] getHttpAuthUsernamePassword(String host, String realm);
    void destroy();
    void setNetworkAvailable(boolean networkUp);
    WebBackForwardList saveState(Bundle outState);
    boolean savePicture(Bundle b, File dest);
    boolean restorePicture(Bundle b, File src);
    WebBackForwardList restoreState(Bundle inState);
    void loadUrl(String url, Map<String, String> additionalHttpHeaders);
    void loadUrl(String url);
    void postUrl(String url, byte[] postData);
    void loadData(String data, String mimeType, String encoding);
    void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding,
            String historyUrl);
    void evaluateJavaScript(String script, ValueCallback<String> resultCallback);
    void saveWebArchive(String filename);
    void saveWebArchive(String basename, boolean autoname, ValueCallback<String> callback);
    void stopLoading();
    void reload();
    boolean canGoBack();
    void goBack();
    boolean canGoForward();
    void goForward();
    boolean canGoBackOrForward(int steps);
    void goBackOrForward(int steps);
    boolean isPrivateBrowsingEnabled();
    boolean pageUp(boolean top);
    boolean pageDown(boolean bottom);
    void insertVisualStateCallback(long requestId, WebView.VisualStateCallback callback);
    void clearView();
    Picture capturePicture();
    PrintDocumentAdapter createPrintDocumentAdapter(String documentName);
    float getScale();
    void setInitialScale(int scaleInPercent);
    void invokeZoomPicker();
    WebView.HitTestResult getHitTestResult();
    void requestFocusNodeHref(Message hrefMsg);
    void requestImageRef(Message msg);
    String getUrl();
    String getOriginalUrl();
    String getTitle();
    Bitmap getFavicon();
    String getTouchIconUrl();
    int getProgress();
    int getContentHeight();
    int getContentWidth();
    void pauseTimers();
    void resumeTimers();
    void onPause();
    void onResume();
    boolean isPaused();
    void freeMemory();
    void clearCache(boolean includeDiskFiles);
    void clearFormData();
    void clearHistory();
    void clearSslPreferences();
    WebBackForwardList copyBackForwardList();
    void setFindListener(WebView.FindListener listener);
    void findNext(boolean forward);
    int findAll(String find);
    void findAllAsync(String find);
    boolean showFindDialog(String text, boolean showIme);
    void clearMatches();
    void documentHasImages(Message response);
    void setWebViewClient(WebViewClient client);
    WebViewClient getWebViewClient();
    @Nullable WebViewRenderProcess getWebViewRenderProcess();
    void setWebViewRenderProcessClient(@Nullable Executor executor,
            @Nullable WebViewRenderProcessClient client);
    @Nullable WebViewRenderProcessClient getWebViewRenderProcessClient();
    void setDownloadListener(DownloadListener listener);
    void setWebChromeClient(WebChromeClient client);
    WebChromeClient getWebChromeClient();
    void setPictureListener(WebView.PictureListener listener);
    void addJavascriptInterface(Object obj, String interfaceName);
    void removeJavascriptInterface(String interfaceName);
    WebMessagePort[] createWebMessageChannel();
    void postMessageToMainFrame(WebMessage message, Uri targetOrigin);
    WebSettings getSettings();
    void setMapTrackballToArrowKeys(boolean setMap);
    void flingScroll(int vx, int vy);
    View getZoomControls();
    boolean canZoomIn();
    boolean canZoomOut();
    boolean zoomBy(float zoomFactor);
    boolean zoomIn();
    boolean zoomOut();
    void dumpViewHierarchyWithProperties(BufferedWriter out, int level);
    View findHierarchyView(String className, int hashCode);
    void setRendererPriorityPolicy(int rendererRequestedPriority, boolean waivedWhenNotVisible);
    int getRendererRequestedPriority();
    boolean getRendererPriorityWaivedWhenNotVisible();

    @SuppressWarnings("unused")
    default void setTextClassifier(@Nullable TextClassifier textClassifier) {}

    @NonNull
    default TextClassifier getTextClassifier() {
        return TextClassifier.NO_OP;
    }

    ViewDelegate getViewDelegate();
    ScrollDelegate getScrollDelegate();
    void notifyFindDialogDismissed();

    interface ViewDelegate {
        boolean shouldDelayChildPressedState();
        void onProvideVirtualStructure(android.view.ViewStructure structure);

        default void onProvideAutofillVirtualStructure(
                @SuppressWarnings("unused") android.view.ViewStructure structure,
                @SuppressWarnings("unused") int flags) {}

        default void autofill(@SuppressWarnings("unused") SparseArray<AutofillValue> values) {}

        default boolean isVisibleToUserForAutofill(@SuppressWarnings("unused") int virtualId) {
            return true;
        }

        default void onProvideContentCaptureStructure(
                @NonNull @SuppressWarnings("unused") android.view.ViewStructure structure,
                @SuppressWarnings("unused") int flags) {}

        @SuppressWarnings("NullableCollection")
        default void onCreateVirtualViewTranslationRequests(
                @NonNull @SuppressWarnings("unused") long[] virtualIds,
                @NonNull @SuppressWarnings("unused") int[] supportedFormats,
                @NonNull @SuppressWarnings("unused") Consumer<ViewTranslationRequest>
                        requestsCollector) {}

        default void onVirtualViewTranslationResponses(
                @NonNull @SuppressWarnings("unused")
                        LongSparseArray<ViewTranslationResponse> response) {}

        default void dispatchCreateViewTranslationRequest(
                @NonNull @SuppressWarnings("unused") Map<AutofillId, long[]> viewIds,
                @NonNull @SuppressWarnings("unused") int[] supportedFormats,
                @SuppressWarnings("unused") TranslationCapability capability,
                @NonNull @SuppressWarnings("unused") List<ViewTranslationRequest> requests) {}

        AccessibilityNodeProvider getAccessibilityNodeProvider();
        void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info);
        void onInitializeAccessibilityEvent(AccessibilityEvent event);
        boolean performAccessibilityAction(int action, Bundle arguments);
        void setOverScrollMode(int mode);
        void setScrollBarStyle(int style);
        void onDrawVerticalScrollBar(Canvas canvas, Drawable scrollBar, int l, int t, int r,
                int b);
        void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY);
        void onWindowVisibilityChanged(int visibility);
        void onDraw(Canvas canvas);
        void setLayoutParams(LayoutParams layoutParams);
        boolean performLongClick();
        void onConfigurationChanged(Configuration newConfig);
        InputConnection onCreateInputConnection(EditorInfo outAttrs);
        boolean onDragEvent(DragEvent event);
        boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event);
        boolean onKeyDown(int keyCode, KeyEvent event);
        boolean onKeyUp(int keyCode, KeyEvent event);
        void onAttachedToWindow();
        void onDetachedFromWindow();

        default void onMovedToDisplay(int displayId, Configuration config) {}

        void onVisibilityChanged(View changedView, int visibility);
        void onWindowFocusChanged(boolean hasWindowFocus);
        void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect);
        boolean setFrame(int left, int top, int right, int bottom);
        void onSizeChanged(int w, int h, int ow, int oh);
        void onScrollChanged(int l, int t, int oldl, int oldt);
        boolean dispatchKeyEvent(KeyEvent event);
        boolean onTouchEvent(MotionEvent ev);
        boolean onHoverEvent(MotionEvent event);
        boolean onGenericMotionEvent(MotionEvent event);
        boolean onTrackballEvent(MotionEvent ev);
        boolean requestFocus(int direction, Rect previouslyFocusedRect);
        void onMeasure(int widthMeasureSpec, int heightMeasureSpec);
        boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate);
        void setBackgroundColor(int color);
        void setLayerType(int layerType, Paint paint);
        void preDispatchDraw(Canvas canvas);
        void onStartTemporaryDetach();
        void onFinishTemporaryDetach();
        void onActivityResult(int requestCode, int resultCode, Intent data);
        Handler getHandler(Handler originalHandler);
        View findFocus(View originalFocusedView);

        @SuppressWarnings("unused")
        default boolean onCheckIsTextEditor() {
            return false;
        }

        @SuppressWarnings("unused")
        @Nullable
        default WindowInsets onApplyWindowInsets(@Nullable WindowInsets insets) {
            return null;
        }
    }

    interface ScrollDelegate {
        int computeHorizontalScrollRange();
        int computeHorizontalScrollOffset();
        int computeVerticalScrollRange();
        int computeVerticalScrollOffset();
        int computeVerticalScrollExtent();
        void computeScroll();
    }
}
