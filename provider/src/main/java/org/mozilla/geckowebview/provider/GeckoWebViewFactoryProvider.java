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
import androidx.annotation.Nullable;
import org.mozilla.geckowebview.session.GeckoSessionBridge;
import org.mozilla.geckowebview.settings.GeckoWebSettings;
import org.mozilla.geckowebview.storage.GeckoCookieManager;
import org.mozilla.geckowebview.storage.GeckoCookieManagerImpl;
import org.mozilla.geckowebview.storage.GeckoGeolocationStore;
import org.mozilla.geckowebview.storage.GeckoServiceWorkerController;
import org.mozilla.geckowebview.storage.GeckoTracingController;
import org.mozilla.geckowebview.storage.GeckoWebIconDatabase;
import org.mozilla.geckowebview.storage.GeckoWebStorage;
import org.mozilla.geckowebview.storage.GeckoWebStorageFacade;
import org.mozilla.geckowebview.storage.GeckoWebViewDatabaseImpl;
import org.mozilla.geckowebview.view.GeckoViewHost;

// Entry point the framework loads via reflection (BOOTSTRAP.md §2):
//   Class.forName("com.android.webview.chromium.WebViewChromiumFactoryProviderForT")
//   (PoC trampoline) → create(delegate) → this provider.
// Real AOSP signature: create(WebView, WebView.PrivateAccess); the stub erases
// the hidden inner-class parameter to Object (framework-stubs README in
// WebViewFactoryProvider.java). At runtime the framework passes the real
// PrivateAccess — retained as Object, used via reflection only if needed.
public final class GeckoWebViewFactoryProvider
        implements android.webkit.WebViewFactoryProvider,
        org.mozilla.geckowebview.compat.CompatHost.Factory {
    private static final String TAG = "Sinytra/provider";

    private final GeckoWebViewStatics mStatics = new GeckoWebViewStatics();
    private volatile GeckoCookieManager mCookieManager;
    private volatile GeckoCookieManagerImpl mCookieManagerImpl;
    private volatile GeckoWebStorage mWebStorage;
    private volatile GeckoWebStorageFacade mStorageFacade;
    private volatile GeckoWebIconDatabase mIconDatabase;
    private volatile GeckoWebViewDatabaseImpl mWebViewDatabase;
    private volatile GeckoGeolocationStore mGeoStore;
    // API-28 framework singletons (ServiceWorkerController/TracingController
    // classes did not exist below API 28): created lazily behind an SDK
    // guard. Eager fields here would NoClassDefFoundError the whole factory
    // on API 26/27 hosts the moment the class is constructed.
    @Nullable
    private volatile GeckoServiceWorkerController mServiceWorkerController;
    @Nullable
    private volatile GeckoTracingController mTracingController;
    // One provider per WebView for its lifetime (Chromium glue keeps the same
    // WebView→WebViewChromium map): androidx.webkit's boundary factory resolves
    // the EXISTING provider for an already-created WebView, and a phantom
    // second provider here would open a dead second GeckoSession whose glue
    // features (message channels etc.) silently dead-end. Weak keys so
    // destroy()d WebViews do not pin their owners.
    private final java.util.Map<WebView, GeckoWebViewProvider> mWebViews =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public GeckoWebViewFactoryProvider() {}

    public static android.webkit.WebViewFactoryProvider create(Object delegate) {
        Log.i(TAG, "create() via trampoline/delegate=" + delegate);
        return new GeckoWebViewFactoryProvider();
    }

    @Override
    public Object createWebView(WebView webView, Object privateAccess) {
        GeckoWebViewProvider provider = new GeckoWebViewProvider(webView, this);
        mWebViews.put(webView, provider);
        return provider;
    }

    @NonNull
    public GeckoWebViewProvider webViewProvider(@NonNull WebView webView) {
        GeckoWebViewProvider provider = mWebViews.get(webView);
        if (provider == null) {
            throw new IllegalStateException(
                    "webViewProvider: no provider registered for this "
                            + "WebView (not created by this factory, or "
                            + "already destroyed)");
        }
        return provider;
    }

    @Override
    @NonNull
    public org.mozilla.geckowebview.compat.CompatHost.WebViewBackend webViewBackend(
            @NonNull WebView webView) {
        return webViewProvider(webView);
    }

    void unregisterWebViewProvider(@NonNull GeckoWebViewProvider provider) {
        synchronized (mWebViews) {
            mWebViews.values().removeIf(p -> p == provider);
        }
    }

    @Override
    public Statics getStatics() {
        return mStatics;
    }

    @Override
    public GeolocationPermissions getGeolocationPermissions() {
        GeolocationPermissions framework = GeckoGeolocationStore.frameworkInstance();
        return framework != null ? framework
                : GeolocationPermissions.getInstance();
    }

    @Override
    public CookieManager getCookieManager() {
        throw new IllegalStateException(
                "getCookieManager(): framework must pass a Context "
                        + "(use cookieManager(context))");
    }

    @Override
    @SuppressWarnings("deprecation")
    public TokenBindingService getTokenBindingService() {
        return null;
    }

    @Override
    public TracingController getTracingController() {
        return tracingController();
    }

    @Override
    @Nullable
    public org.mozilla.geckowebview.storage.GeckoTracingController tracingController() {
        // API 28+: the framework class does not exist below Q; hosts that
        // old never call these accessors (the interface method itself is
        // API 28), so honest null instead of a class-load crash.
        if (android.os.Build.VERSION.SDK_INT < 28) {
            return null;
        }
        GeckoTracingController controller = mTracingController;
        if (controller == null) {
            synchronized (this) {
                controller = mTracingController;
                if (controller == null) {
                    controller = new GeckoTracingController();
                    mTracingController = controller;
                }
            }
        }
        return controller;
    }

    @Override
    public ServiceWorkerController getServiceWorkerController() {
        return serviceWorkerController();
    }

    @Override
    @Nullable
    public org.mozilla.geckowebview.storage.GeckoServiceWorkerController
            serviceWorkerController() {
        if (android.os.Build.VERSION.SDK_INT < 28) {
            return null;
        }
        GeckoServiceWorkerController controller = mServiceWorkerController;
        if (controller == null) {
            synchronized (this) {
                controller = mServiceWorkerController;
                if (controller == null) {
                    controller = new GeckoServiceWorkerController();
                    mServiceWorkerController = controller;
                }
            }
        }
        return controller;
    }

    @Override
    public WebIconDatabase getWebIconDatabase() {
        throw new IllegalStateException(
                "getWebIconDatabase(): framework must pass a Context (use icons(context))");
    }

    @Override
    public WebStorage getWebStorage() {
        android.webkit.WebStorage framework = GeckoWebStorageFacade.frameworkInstance();
        return framework != null ? framework : WebStorage.getInstance();
    }

    @Override
    public WebViewDatabase getWebViewDatabase(Context context) {
        return webViewDatabase(context);
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

    // android.webkit singleton implementations (returned to the framework).
    // The framework may call these before any WebView exists, so each takes
    // the harness/activity context explicitly — no "before provider init"
    // failure mode. All instances are host-process singletons.
    @NonNull
    public CookieManager cookieManager(@NonNull Context context) {
        GeckoCookieManagerImpl existing = mCookieManagerImpl;
        if (existing == null) {
            synchronized (this) {
                existing = mCookieManagerImpl;
                if (existing == null) {
                    existing = new GeckoCookieManagerImpl(context);
                    mCookieManagerImpl = existing;
                }
            }
        }
        return existing;
    }

    @NonNull
    public GeckoWebStorageFacade storageFacade(@NonNull Context context) {
        GeckoWebStorageFacade existing = mStorageFacade;
        if (existing == null) {
            synchronized (this) {
                existing = mStorageFacade;
                if (existing == null) {
                    existing = new GeckoWebStorageFacade(context);
                    mStorageFacade = existing;
                }
            }
        }
        return existing;
    }

    @NonNull
    public WebIconDatabase icons(@NonNull Context context) {
        GeckoWebIconDatabase existing = mIconDatabase;
        if (existing == null) {
            synchronized (this) {
                existing = mIconDatabase;
                if (existing == null) {
                    existing = new GeckoWebIconDatabase(context);
                    mIconDatabase = existing;
                }
            }
        }
        return existing;
    }

    @NonNull
    public WebViewDatabase webViewDatabase(@NonNull Context context) {
        GeckoWebViewDatabaseImpl existing = mWebViewDatabase;
        if (existing == null) {
            synchronized (this) {
                existing = mWebViewDatabase;
                if (existing == null) {
                    existing = new GeckoWebViewDatabaseImpl(context);
                    mWebViewDatabase = existing;
                }
            }
        }
        return existing;
    }

    @NonNull
    public GeckoGeolocationStore geoStore(@NonNull Context context) {
        GeckoGeolocationStore existing = mGeoStore;
        if (existing == null) {
            synchronized (this) {
                existing = mGeoStore;
                if (existing == null) {
                    existing = new GeckoGeolocationStore(context);
                    mGeoStore = existing;
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
