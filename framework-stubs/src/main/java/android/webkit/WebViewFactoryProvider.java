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
    // NOTE: real signature is createWebView(WebView, WebView.PrivateAccess).
    // android.jar strips the hidden inner class, so stubs use WebViewPrivateAccess
    // (same method name + arity; javac-level placeholder only — runtime linking
    // uses the real framework classes in the host process).
    WebViewProvider createWebView(WebView webView, WebViewPrivateAccess privateAccess);
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
