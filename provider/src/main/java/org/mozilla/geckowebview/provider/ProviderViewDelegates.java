package org.mozilla.geckowebview.provider;

import android.content.Intent;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebViewProvider;
import androidx.annotation.NonNull;

// No-op default View/Scroll delegates for GeckoWebViewProvider (file-size
// rule split, mirrors ClientFanOut/ProviderAdapters). The real view
// behavior comes from the framework WebView plus the GeckoViewHost child,
// so every hook here is a harmless default; the only live branch is
// onActivityResult (file chooser result routing into the pending callback).
final class ProviderViewDelegates {
    private static final String TAG = "Sinytra/session";

    private ProviderViewDelegates() {}

    static @NonNull WebViewProvider.ViewDelegate viewDelegate(
            @NonNull GeckoWebViewProvider provider) {
        return new WebViewProvider.ViewDelegate() {
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
                    Intent data) {
                if (requestCode == ClientFanOut.fileChooserRequestCode()) {
                    ValueCallback<Uri[]> callback = provider.fileChooserCallback();
                    provider.setFileChooserCallback(null);
                    if (callback == null) {
                        return;
                    }
                    try {
                        Uri[] results = WebChromeClient.FileChooserParams.parseResult(
                                resultCode, data);
                        callback.onReceiveValue(results);
                    } catch (Throwable t) {
                        Log.w(TAG, "file chooser parse threw", t);
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

    static @NonNull WebViewProvider.ScrollDelegate scrollDelegate() {
        return new WebViewProvider.ScrollDelegate() {
            @Override public int computeHorizontalScrollRange() { return 0; }
            @Override public int computeHorizontalScrollOffset() { return 0; }
            @Override public int computeVerticalScrollRange() { return 0; }
            @Override public int computeVerticalScrollOffset() { return 0; }
            @Override public int computeVerticalScrollExtent() { return 0; }
            @Override public void computeScroll() {}
        };
    }
}
