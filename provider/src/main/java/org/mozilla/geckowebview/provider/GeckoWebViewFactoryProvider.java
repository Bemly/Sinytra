package org.mozilla.geckowebview.provider;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.ServiceWorkerController;
import android.webkit.TokenBindingService;
import android.webkit.TracingController;
import android.webkit.WebIconDatabase;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;
import android.webkit.WebViewProvider;
import androidx.annotation.NonNull;
import org.mozilla.geckowebview.session.GeckoSessionBridge;
import org.mozilla.geckowebview.settings.GeckoWebSettings;
import org.mozilla.geckowebview.storage.GeckoCookieManager;
import org.mozilla.geckowebview.storage.GeckoWebStorage;
import org.mozilla.geckowebview.view.GeckoViewHost;

// Entry point the framework loads via reflection (BOOTSTRAP.md §2):
//   Class.forName("com.android.webview.chromium.WebViewChromiumFactoryProviderForT")
//   (PoC trampoline) → create(delegate) → this provider.
// Real AOSP signature: create(WebView, WebView.PrivateAccess); the stub erases
// the hidden inner-class parameter to Object (framework-stubs README in
// WebViewFactoryProvider.java). At runtime the framework passes the real
// PrivateAccess — retained as Object, used via reflection only if needed.
public final class GeckoWebViewFactoryProvider
        implements android.webkit.WebViewFactoryProvider {
    private static final String TAG = "Sinytra/provider";

    private final GeckoWebViewStatics mStatics = new GeckoWebViewStatics();
    private volatile GeckoCookieManager mCookieManager;
    private volatile GeckoWebStorage mWebStorage;

    public GeckoWebViewFactoryProvider() {}

    public static android.webkit.WebViewFactoryProvider create(Object delegate) {
        Log.i(TAG, "create() via trampoline/delegate=" + delegate);
        return new GeckoWebViewFactoryProvider();
    }

    @Override
    public Object createWebView(WebView webView, Object privateAccess) {
        return new GeckoWebViewProvider(webView, this);
    }

    @Override
    public Statics getStatics() {
        return mStatics;
    }

    @Override
    public GeolocationPermissions getGeolocationPermissions() {
        return GeolocationPermissions.getInstance();
    }

    @Override
    public CookieManager getCookieManager() {
        return CookieManager.getInstance();
    }

    @Override
    @SuppressWarnings("deprecation")
    public TokenBindingService getTokenBindingService() {
        return null;
    }

    @Override
    public TracingController getTracingController() {
        throw new UnsupportedOperationException("TracingController: P2");
    }

    @Override
    public ServiceWorkerController getServiceWorkerController() {
        throw new UnsupportedOperationException("ServiceWorkerController: P2");
    }

    @Override
    public WebIconDatabase getWebIconDatabase() {
        return WebIconDatabase.getInstance();
    }

    @Override
    public WebStorage getWebStorage() {
        return WebStorage.getInstance();
    }

    @Override
    public WebViewDatabase getWebViewDatabase(Context context) {
        return WebViewDatabase.getInstance(context);
    }

    @Override
    public ClassLoader getWebViewClassLoader() {
        return GeckoWebViewFactoryProvider.class.getClassLoader();
    }

    // Provider-owned Gecko facades (process-local, host-process singletons).
    @NonNull
    GeckoCookieManager cookies(@NonNull Context context) {
        GeckoCookieManager existing = mCookieManager;
        if (existing == null) {
            synchronized (this) {
                existing = mCookieManager;
                if (existing == null) {
                    existing = new GeckoCookieManager(context);
                    mCookieManager = existing;
                }
            }
        }
        return existing;
    }

    @NonNull
    GeckoWebStorage storage(@NonNull Context context) {
        GeckoWebStorage existing = mWebStorage;
        if (existing == null) {
            synchronized (this) {
                existing = mWebStorage;
                if (existing == null) {
                    existing = new GeckoWebStorage(context);
                    mWebStorage = existing;
                }
            }
        }
        return existing;
    }

    static final class GeckoWebViewStatics implements Statics {
        @Override
        public String findAddress(String addr) {
            return null;
        }

        @Override
        public String getDefaultUserAgent(Context context) {
            return System.getProperty("http.agent", "Mozilla/5.0 (Linux; Android 14)");
        }

        @Override
        public void freeMemoryForTests() {
        }

        @Override
        public void setWebContentsDebuggingEnabled(boolean enable) {
        }

        @Override
        public void clearClientCertPreferences(Runnable onCleared) {
            if (onCleared != null) {
                onCleared.run();
            }
        }

        @Override
        public void enableSlowWholeDocumentDraw() {
        }

        @Override
        public Uri[] parseFileChooserResult(int resultCode, android.content.Intent intent) {
            return null;
        }

        @Override
        public void initSafeBrowsing(Context context,
                android.webkit.ValueCallback<Boolean> callback) {
            if (callback != null) {
                callback.onReceiveValue(Boolean.FALSE);
            }
        }

        @Override
        public void setSafeBrowsingWhitelist(java.util.List<String> hosts,
                android.webkit.ValueCallback<Boolean> callback) {
            if (callback != null) {
                callback.onReceiveValue(Boolean.FALSE);
            }
        }

        @Override
        @NonNull
        public Uri getSafeBrowsingPrivacyPolicyUrl() {
            return Uri.EMPTY;
        }
    }
}
