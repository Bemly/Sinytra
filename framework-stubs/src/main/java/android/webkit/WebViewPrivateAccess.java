// Compile-only stub for AOSP android14-release hidden WebView.PrivateAccess.
// android.jar ships WebView WITHOUT its hidden inner class PrivateAccess.
// Verified against device framework.jar dex dump (classes4.dex).
// See WebViewFactoryProvider.java header for stub policy.
package android.webkit;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

public class WebViewPrivateAccess {
    protected WebViewPrivateAccess() {}

    public void awakenScrollBars(int startDelay) {
        throw new RuntimeException("Stub!");
    }

    public void awakenScrollBars(int startDelay, boolean invalidate) {
        throw new RuntimeException("Stub!");
    }

    public float getHorizontalScrollFactor() {
        throw new RuntimeException("Stub!");
    }

    public int getHorizontalScrollbarHeight() {
        throw new RuntimeException("Stub!");
    }

    public float getVerticalScrollFactor() {
        throw new RuntimeException("Stub!");
    }

    public void onScrollChanged(int l, int t, int oldl, int oldt) {
        throw new RuntimeException("Stub!");
    }

    public boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY,
            int scrollRangeX, int scrollRangeY, int maxOverScrollX, int maxOverScrollY,
            boolean isTouchEvent) {
        throw new RuntimeException("Stub!");
    }

    public void setMeasuredDimension(int measuredWidth, int measuredHeight) {
        throw new RuntimeException("Stub!");
    }

    public void setScrollXRaw(int scrollX) {
        throw new RuntimeException("Stub!");
    }

    public void setScrollYRaw(int scrollY) {
        throw new RuntimeException("Stub!");
    }

    public void super_computeScroll() {
        throw new RuntimeException("Stub!");
    }

    public boolean super_dispatchKeyEvent(KeyEvent event) {
        throw new RuntimeException("Stub!");
    }

    public int super_getScrollBarStyle() {
        throw new RuntimeException("Stub!");
    }

    public WindowInsets super_onApplyWindowInsets(WindowInsets insets) {
        throw new RuntimeException("Stub!");
    }

    public void super_onDrawVerticalScrollBar(Canvas canvas, Drawable scrollBar, int l, int t,
            int r, int b) {
        throw new RuntimeException("Stub!");
    }

    public boolean super_onGenericMotionEvent(MotionEvent event) {
        throw new RuntimeException("Stub!");
    }

    public boolean super_onHoverEvent(MotionEvent event) {
        throw new RuntimeException("Stub!");
    }

    public boolean super_performAccessibilityAction(int action, Bundle arguments) {
        throw new RuntimeException("Stub!");
    }

    public boolean super_performLongClick() {
        throw new RuntimeException("Stub!");
    }

    public boolean super_requestFocus(int direction, Rect previouslyFocusedRect) {
        throw new RuntimeException("Stub!");
    }

    public void super_scrollTo(int scrollX, int scrollY) {
        throw new RuntimeException("Stub!");
    }

    public boolean super_setFrame(int left, int top, int right, int bottom) {
        throw new RuntimeException("Stub!");
    }

    public void super_setLayoutParams(ViewGroup.LayoutParams layoutParams) {
        throw new RuntimeException("Stub!");
    }

    public void super_startActivityForResult(Intent intent, int requestCode) {
        throw new RuntimeException("Stub!");
    }
}
