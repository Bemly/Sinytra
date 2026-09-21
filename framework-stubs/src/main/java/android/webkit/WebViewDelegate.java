// Compile-only stub for AOSP android14-release hidden WebViewDelegate.
// Verified against device framework.jar dex dump (classes4.dex).
// See WebViewFactoryProvider.java header for stub policy.
package android.webkit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

public final class WebViewDelegate {
    WebViewDelegate() {}

    public interface OnTraceEnabledChangeListener {
        void onTraceEnabledChange(boolean enabled);
    }

    public void setOnTraceEnabledChangeListener(OnTraceEnabledChangeListener listener) {
        throw new RuntimeException("Stub!");
    }

    public boolean isTraceTagEnabled() {
        throw new RuntimeException("Stub!");
    }

    @Deprecated
    public boolean canInvokeDrawGlFunctor(View containerView) {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    public void invokeDrawGlFunctor(View containerView, long nativeDrawGLFunctor,
            boolean waitForCompletion) {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    public void callDrawGlFunction(Canvas canvas, long nativeDrawGLFunctor) {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    public void callDrawGlFunction(@NonNull Canvas canvas, long nativeDrawGLFunctor,
            @Nullable Runnable releasedRunnable) {
        throw new UnsupportedOperationException();
    }

    public void drawWebViewFunctor(@NonNull Canvas canvas, int functor) {
        throw new RuntimeException("Stub!");
    }

    @Deprecated
    public void detachDrawGlFunctor(View containerView, long nativeDrawGLFunctor) {
        throw new RuntimeException("Stub!");
    }

    public int getPackageId(android.content.res.Resources resources, String packageName) {
        throw new RuntimeException("Stub!");
    }

    public Application getApplication() {
        throw new RuntimeException("Stub!");
    }

    public String getErrorString(Context context, int errorCode) {
        throw new RuntimeException("Stub!");
    }

    public void addWebViewAssetPath(Context context) {
        throw new RuntimeException("Stub!");
    }

    public boolean isMultiProcessEnabled() {
        throw new RuntimeException("Stub!");
    }

    public String getDataDirectorySuffix() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public WebViewFactory.StartupTimestamps getStartupTimestamps() {
        throw new RuntimeException("Stub!");
    }
}
