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
    // Real AOSP android14 signature:
    //   WebViewProvider createWebView(WebView webView, WebView.PrivateAccess privateAccess)
    // PrivateAccess is a hidden inner class of android.webkit.WebView that
    // android.jar does NOT ship (javac resolves inner classes through the
    // outer class, so no source stub can declare it without shadowing the
    // real public WebView). The device framework DOES have it — verified in
    // the on-device framework.jar: Landroid/webkit/WebView$PrivateAccess,
    // PUBLIC, ctor (WebView)V, ~24 super_* passthrough methods.
    // Interim PoC rule (verified by FrameworkEntryActivity): javac/erasure
    // means the RUNTIME descriptor is what matters, not the compile-time
    // type name. So this stub erases the parameter to Object — the compiled
    // method is createWebView(WebView, Object)Object — and the IMPL adds a
    // bridge overload createWebView(WebView, WebView$PrivateAccess-type)
    // once the real PrivateAccess bytecode is available as compileOnly.
    // Until then the framework's exact-descriptor lookup
    // (WebView,WebView$PrivateAccess)WebViewProvider will NOT match this
    // impl → AbstractMethodError at new WebView(). That is EXPECTED until
    // the PrivateAccess stub lands; P0Glue (manual factory path) is
    // unaffected. See STATUS §5.
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
