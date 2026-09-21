// PoC trampoline: Android 14 WebViewFactory hardcodes
// Class.forName("com.android.webview.chromium.WebViewChromiumFactoryProviderForT")
// + getMethod("create", WebViewDelegate.class), with NO metadata route
// (verified against android14-release WebViewFactory.java).
// BOOTSTRAP.md §2.2: this trampoline is allowed on the PoC branch ONLY —
// never merge to main. Stable route is metadata self-declaration +
// aosp-patches framework change (manifest already declares
// android.webkit.WebViewFactoryClass for that future).
//
// Why Proxy: our GeckoWebViewFactoryProvider was compiled against
// framework-stubs where createWebView is erased to
// (WebView, Object)Object — android.jar has no WebView$PrivateAccess
// inner class, and no source stub can declare it without shadowing the
// real public WebView. The device framework requires exactly
// (WebView, WebView$PrivateAccess)WebViewProvider, so direct impl throws
// AbstractMethodError at new WebView() (FrameworkEntryActivity verified).
// The Proxy implements the REAL framework interface at runtime (same
// binary name, boot classloader) and forwards createWebView to
// real.webViewProvider(webView) — bypassing the descriptor mismatch.
// All other methods dispatch via Method.invoke on the real impl; their
// descriptors use only public types so they already match.
package com.android.webview.chromium;

import android.webkit.WebView;
import android.webkit.WebViewDelegate;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class WebViewChromiumFactoryProviderForT {
    private WebViewChromiumFactoryProviderForT() {}

    private static final ThreadLocal<Object> REENTRANT = new ThreadLocal<>();

    public static android.webkit.WebViewFactoryProvider create(WebViewDelegate delegate) {
        org.mozilla.geckowebview.provider.GeckoWebViewFactoryProvider real =
                (org.mozilla.geckowebview.provider.GeckoWebViewFactoryProvider)
                        org.mozilla.geckowebview.provider.GeckoWebViewFactoryProvider
                                .create(delegate);
        ClassLoader cl =
                WebViewChromiumFactoryProviderForT.class.getClassLoader();
        Class<?> iface;
        try {
            iface = Class.forName("android.webkit.WebViewFactoryProvider", false, cl);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return (android.webkit.WebViewFactoryProvider) Proxy.newProxyInstance(cl,
                new Class<?>[] {iface},
                (proxy, method, args) -> {
                    // Cycle breaker: impl.getWebStorage() calls
                    // WebStorage.getInstance() → WebViewFactory.getProvider()
                    // → Proxy.getWebStorage() → method.invoke(impl) → impl
                    // calls getInstance() again → ... StackOverflowError
                    // (device log: Proxy↔getInstance↔frameworkInstance loop).
                    // The impl CANNOT break it (it must call getInstance()
                    // to honor STATUS §1a). So break it HERE: when the flag
                    // is set (we are inside method.invoke already), answer
                    // getWebStorage/getGeolocationPermissions directly from
                    // the real impl WITHOUT re-entering method.invoke —
                    // read its already-constructed singleton fields via
                    // package-private accessors. No recursion: direct field
                    // read, no getInstance() call.
                    if (REENTRANT.get() != null) {
                        String name = method.getName();
                        if ("getWebStorage".equals(name)) {
                            return real.storageDirect();
                        }
                        if ("getGeolocationPermissions".equals(name)) {
                            return real.geoDirect();
                        }
                    }
                    if ("createWebView".equals(method.getName())) {
                        WebView webView = (WebView) args[0];
                        // Retain PrivateAccess for the ViewDelegate: the
                        // delegate needs privateAccess.super_setLayoutParams
                        // (the ONLY legal params-write channel — direct
                        // reflection is hiddenapi-blocked, re-add recurses).
                        // super_* methods are TEST-API (SDK,TEST-API) =
                        // allowed for targetSdk<=34 callers on user builds,
                        // and our provider IS such a caller in the app
                        // process. Retain + hand to the provider instance.
                        Object privateAccess = args.length > 1 ? args[1] : null;
                        org.mozilla.geckowebview.provider.GeckoWebViewProvider
                                provider = real.webViewProvider(webView);
                        if (privateAccess != null) {
                            provider.retainPrivateAccess(privateAccess);
                            // Fix the Activity-measure NPE at creation time
                            // (Chromium does the same: real params before
                            // addView/measure): push default MATCH_PARENT
                            // params through the legal super_ channel NOW,
                            // while we hold the real PrivateAccess.
                            try {
                                java.lang.reflect.Method superSet =
                                        privateAccess.getClass().getMethod(
                                                "super_setLayoutParams",
                                                android.view.ViewGroup.LayoutParams.class);
                                superSet.invoke(privateAccess,
                                        new android.view.ViewGroup.LayoutParams(
                                                android.view.ViewGroup.LayoutParams
                                                        .MATCH_PARENT,
                                                android.view.ViewGroup.LayoutParams
                                                        .MATCH_PARENT));
                            } catch (Throwable t) {
                                android.util.Log.w("Sinytra/provider",
                                        "super_setLayoutParams threw", t);
                            }
                        }
                        return provider;
                    }
                    Object[] callArgs = args != null ? args : new Object[0];
                    REENTRANT.set(Boolean.TRUE);
                    try {
                        return method.invoke(real, callArgs);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException) {
                            throw (RuntimeException) cause;
                        }
                        if (cause instanceof Error) {
                            throw (Error) cause;
                        }
                        throw new RuntimeException(cause);
                    } finally {
                        REENTRANT.remove();
                    }
                });
    }
}
