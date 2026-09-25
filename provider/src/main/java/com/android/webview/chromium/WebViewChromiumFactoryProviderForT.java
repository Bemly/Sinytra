package com.android.webview.chromium;

import android.util.Log;
import android.webkit.WebViewDelegate;
import android.webkit.WebViewFactoryProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.mozilla.geckowebview.provider.GeckoWebViewFactoryProvider;

// Framework entry trampoline (AGENTS §5 trampoline rule; BOOTSTRAP §2.3).
//
// Why this class exists: Android 14's WebViewFactory hardcodes
//   Class.forName("com.android.webview.chromium.WebViewChromiumFactoryProviderForT")
//     .getMethod("create", WebViewDelegate.class)
// and reads no provider-declared class name. Without changing the framework
// (the only deployment route is root + AnyWebView + developer-options switch)
// this name is Sinytra's single entry point. It carries NO logic: it builds
// the real factory and adapts one method descriptor.
//
// Why a Proxy: GeckoWebViewFactoryProvider is compiled against
// framework-stubs, where createWebView is erased to (WebView, Object)Object
// because android.jar lacks the hidden WebView$PrivateAccess type. The
// device framework calls (WebView, WebView$PrivateAccess)WebViewProvider,
// which that class does not implement (AbstractMethodError at new WebView(),
// PoC-verified). The Proxy implements the REAL boot-classpath interface and
// forwards createWebView explicitly; every other method has only public
// types in its descriptor, so Method.invoke on the factory matches exactly.
public final class WebViewChromiumFactoryProviderForT {
    private static final String TAG = "Sinytra/provider";

    private WebViewChromiumFactoryProviderForT() {}

    public static WebViewFactoryProvider create(WebViewDelegate delegate) {
        GeckoWebViewFactoryProvider factory =
                (GeckoWebViewFactoryProvider) GeckoWebViewFactoryProvider.create(delegate);
        Log.i(TAG, "framework entry: trampoline -> GeckoWebViewFactoryProvider");
        return (WebViewFactoryProvider) Proxy.newProxyInstance(
                WebViewChromiumFactoryProviderForT.class.getClassLoader(),
                new Class<?>[] {WebViewFactoryProvider.class},
                (proxy, method, args) -> dispatch(factory, proxy, method, args));
    }

    private static Object dispatch(GeckoWebViewFactoryProvider factory, Object proxy,
            Method method, Object[] args) throws Throwable {
        Object[] callArgs = args != null ? args : new Object[0];
        switch (method.getName()) {
            case "createWebView":
                if (callArgs.length == 2) {
                    return factory.createWebView(
                            (android.webkit.WebView) callArgs[0], callArgs[1]);
                }
                break;
            case "equals":
                if (callArgs.length == 1) {
                    return proxy == callArgs[0];
                }
                break;
            case "hashCode":
                if (callArgs.length == 0) {
                    return System.identityHashCode(proxy);
                }
                break;
            case "toString":
                if (callArgs.length == 0) {
                    return "SinytraFactoryProxy";
                }
                break;
            default:
                break;
        }
        try {
            return method.invoke(factory, callArgs);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
