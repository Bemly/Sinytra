// Compile-only stubs for AOSP android14-release hidden webkit APIs.
// Source of truth: device framework.jar (API 34) method dump + AOSP android14-release
// WebViewFactoryProvider.java / WebViewProvider.java / WebViewDelegate.java.
// These stubs exist ONLY so the provider module compiles against hidden APIs.
// They are NEVER packaged: provider depends on this module as compileOnly, and at
// runtime the real framework classes (loaded in the host process) are used.
package android.webkit;

import androidx.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.net.Uri;
import android.webkit.ValueCallback;
import java.util.List;

public interface WebViewFactoryProvider {
    interface Statics {
        String findAddress(String addr);
        String getDefaultUserAgent(Context context);
        void freeMemoryForTests();
        void setWebContentsDebuggingEnabled(boolean enable);
        void clearClientCertPreferences(Runnable onCleared);
        void enableSlowWholeDocumentDraw();
        Uri[] parseFileChooserResult(int resultCode, Intent intent);
        void initSafeBrowsing(Context context, ValueCallback<Boolean> callback);
        void setSafeBrowsingWhitelist(List<String> hosts, ValueCallback<Boolean> callback);
        @NonNull Uri getSafeBrowsingPrivacyPolicyUrl();
    }

    Statics getStatics();
    // Real AOSP signature: createWebView(WebView, WebView.PrivateAccess) where
    // PrivateAccess is a hidden inner class of android.webkit.WebView.
    // android.jar ships WebView WITHOUT that inner class, and javac resolves
    // inner classes through the OUTER class — so no stub jar can supply it
    // without also shadowing WebView itself (which we must NOT do: WebView is
    // public API and must resolve to android.jar at compile time and to the
    // real framework at runtime). Therefore the stub erases the parameter to
    // Object. At runtime the real framework passes the real PrivateAccess;
    // our implementation casts/reflects as needed. See docs/BOOTSTRAP.md.
    Object createWebView(WebView webView, Object privateAccess);
    GeolocationPermissions getGeolocationPermissions();
    CookieManager getCookieManager();
    @SuppressWarnings("deprecation")
    TokenBindingService getTokenBindingService();
    TracingController getTracingController();
    ServiceWorkerController getServiceWorkerController();
    WebIconDatabase getWebIconDatabase();
    WebStorage getWebStorage();
    WebViewDatabase getWebViewDatabase(Context context);

    @NonNull
    default PacProcessor getPacProcessor() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @NonNull
    default PacProcessor createPacProcessor() {
        throw new UnsupportedOperationException("Not implemented");
    }

    ClassLoader getWebViewClassLoader();
}
