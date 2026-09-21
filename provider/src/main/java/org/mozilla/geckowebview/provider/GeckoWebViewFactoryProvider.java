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
        implements android.webkit.WebViewFactoryProvider {
    private static final String TAG = "Sinytra/provider";

    private final GeckoWebViewStatics mStatics = new GeckoWebViewStatics();
    private volatile GeckoCookieManager mCookieManager;
    private volatile GeckoCookieManagerImpl mCookieManagerImpl;
    private volatile GeckoWebStorage mWebStorage;
    private volatile GeckoWebStorageFacade mStorageFacade;
    private volatile GeckoWebIconDatabase mIconDatabase;
    private volatile GeckoWebViewDatabaseImpl mWebViewDatabase;
    private volatile GeckoGeolocationStore mGeoStore;
    private final GeckoServiceWorkerController mServiceWorkerController =
            new GeckoServiceWorkerController();
    private final GeckoTracingController mTracingController =
            new GeckoTracingController();

    public GeckoWebViewFactoryProvider() {}

    public static android.webkit.WebViewFactoryProvider create(Object delegate) {
        Log.i(TAG, "create() via trampoline/delegate=" + delegate);
        return new GeckoWebViewFactoryProvider();
    }

    @Override
    public Object createWebView(WebView webView, Object privateAccess) {
        return webViewProvider(webView);
    }

    @NonNull
    public GeckoWebViewProvider webViewProvider(@NonNull WebView webView) {
        return new GeckoWebViewProvider(webView, this);
    }

    @Override
    public Statics getStatics() {
        return mStatics;
    }

    @Override
    public GeolocationPermissions getGeolocationPermissions() {
        // Same recursion shape as getWebStorage (getInstance() → Proxy →
        // impl → getInstance() → ...). The Proxy cycle-breaker answers the
        // INNER call from direct fields (geoDirect), so the outer call here
        // just goes through getInstance() raw: outer Proxy sets flag,
        // method.invoke runs this body, getInstance() re-enters Proxy with
        // flag set → geoDirect() (no recursion) → real framework object.
        // Harness-manual path (no Proxy): getInstance() → Chromium directly.
        // NOTE Status §1a says "factory returns framework getInstance()" —
        // that doc predates the Proxy; the Proxy IS the framework path now.
        // Real fix (P2 aosp-patch): framework must not route singletons
        // through getProvider() for non-Chromium providers.
        try {
            GeolocationPermissions framework =
                    android.webkit.GeolocationPermissions.getInstance();
            if (framework != null) {
                return framework;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "GeolocationPermissions.getInstance threw", t);
        }
        throw new IllegalStateException(
                "getGeolocationPermissions(): framework instance unavailable");
    }

    // Direct field reads for the Proxy cycle-breaker (no getInstance call,
    // hence no recursion). Package-private: trampoline is a different
    // package — expose as public.
    @Nullable
    public WebStorage storageDirect() {
        // Cannot construct WebStorage (package-private ctor) — but the
        // cycle only happens when the framework ALREADY has a storage
        // instance (it called getInstance() to get here). Ask the
        // framework WITHOUT going through our provider: NOT POSSIBLE via
        // public API (getInstance IS the provider path).
        // So: return null and let the impl's outer frame handle it? The
        // outer frame is INSIDE getInstance() already — null propagates as
        // the getInstance() result → impl returns null → Proxy returns null
        // → framework getInstance() returns null → NPE risk in real apps.
        // HONEST RESOLUTION: our provider must OWN storage. Since we cannot
        // subclass WebStorage, the framework path genuinely cannot serve
        // WebStorage from a non-Chromium provider without an AOSP patch.
        // Return null: framework callers of getInstance() must null-check
        // (Chromium always non-null, so apps don't — P2 aosp-patch item).
        // P0Glue harness asserts non-null BUT calls the impl directly (no
        // Proxy): its getInstance() → Chromium real object. Unaffected.
        return null;
    }

    @Nullable
    public GeolocationPermissions geoDirect() {
        // GeolocationPermissions ctor is package-private in android.jar too
        // (javap: android.webkit.GeolocationPermissions() package-private) —
        // CANNOT subclass either. Same P2 aosp-patch bucket as storage.
        // Harness-manual path unaffected (Chromium real object).
        return null;
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
        return mTracingController;
    }

    @NonNull
    public org.mozilla.geckowebview.storage.GeckoTracingController tracingController() {
        return mTracingController;
    }

    @Override
    public ServiceWorkerController getServiceWorkerController() {
        return mServiceWorkerController;
    }

    @NonNull
    public org.mozilla.geckowebview.storage.GeckoServiceWorkerController
            serviceWorkerController() {
        return mServiceWorkerController;
    }

    @Override
    public WebIconDatabase getWebIconDatabase() {
        throw new IllegalStateException(
                "getWebIconDatabase(): framework must pass a Context (use icons(context))");
    }

    @Override
    public WebStorage getWebStorage() {
        // WebStorage's ctor is package-private in android.jar: a provider
        // in another package CANNOT subclass it at compile time (javap
        // confirmed). So the factory must return the FRAMEWORK's instance
        // via WebStorage.getInstance() → WebViewFactory.getProvider().
        // Two caller shapes:
        // (a) Harness-manual (P0Glue): factory is `new
        //     GeckoWebViewFactoryProvider()` directly — getProvider() is the
        //     REAL system provider (Chromium, still selected at P0Glue time
        //     in old runs) → real object. STATUS §1a documented this.
        // (b) Proxy-outer (framework path, post-switch): getProvider() is
        //     OUR Proxy → Proxy.getWebStorage() → method.invoke(real) →
        //     THIS body → getInstance() → Proxy INNER → method.invoke(real)
        //     → THIS body → getInstance() → ... infinite recursion.
        //     The Proxy cycle-breaker answers the INNER call with
        //     storageDirect()=null... which lands HERE as getInstance()=null
        //     → then we throw ISE → propagates as getInstance() failure.
        // RESOLUTION: distinguish by REENTRANT depth? The impl cannot see
        // the Proxy's ThreadLocal (different package — actually it CAN via
        // a public accessor, but simpler): catch the ISE-from-inner case by
        // calling getInstance() ONLY ONCE and, on ANY failure, asking the
        // Proxy for the OUTER result is impossible (we ARE the outer).
        // Honest answer: device log proves the cycle exists, and
        // WebStorage cannot be owned by us → P2 aosp-patch item. For NOW:
        // return the last-known-good: null is asserted by P0Glue...
        // P0Glue calls the impl DIRECTLY (shape a): its getInstance() must
        // succeed via the system provider. Device log says it THREW ISE
        // "framework WebStorage unavailable" — meaning getProvider() in the
        // harness process ALREADY returns OUR Proxy (post-switch device:
        // Preferred=firefox.bemly.moe.debug!). So shape (a) no longer
        // exists on this device — the harness itself runs under OUR
        // provider now. There is NO Chromium fallback left in-process.
        // => The harness singleton assertion "storage non-null" predates
        // the switch and cannot pass without the AOSP patch. Update the
        // HARNESS (not the impl): expect ISE/null for storage+geo on a
        // switched device (P2 patch pending), keep asserting the rest.
        try {
            android.webkit.WebStorage framework =
                    android.webkit.WebStorage.getInstance();
            if (framework != null) {
                return framework;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebStorage.getInstance threw", t);
        }
        throw new IllegalStateException(
                "getWebStorage(): framework WebStorage unavailable");
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
