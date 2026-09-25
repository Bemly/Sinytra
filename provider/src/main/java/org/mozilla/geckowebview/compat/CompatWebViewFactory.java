package org.mozilla.geckowebview.compat;

import androidx.annotation.NonNull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.chromium.support_lib_boundary.WebViewProviderFactoryBoundaryInterface;

// WebViewProviderFactoryBoundaryInterface over the provider factory: the
// object androidx.webkit's WebViewGlueCommunicator fetches through
// SupportLibReflectionUtil.createWebViewProviderFactory(). Only claimed
// features are reachable; every other method throws honest errors.
// getWebViewBuilder/ProfileStore/ProxyController/DropDataProvider are
// unclaimed (P2 patch / future work) and throw on use.
public final class CompatWebViewFactory implements InvocationHandler {
    @NonNull
    private final CompatHost.Factory mFactory;
    @NonNull
    private final InvocationHandler mStatics;
    @NonNull
    private final InvocationHandler mConverter;
    @NonNull
    private final InvocationHandler mServiceWorker;
    @NonNull
    private final InvocationHandler mTracing;
    private final String[] mFeatures;

    private CompatWebViewFactory(@NonNull CompatHost.Factory factory) {
        mFactory = factory;
        mStatics = CompatStatics.create(factory.getStatics());
        mConverter = CompatConverter.create();
        mServiceWorker = CompatServiceWorkerController.create(
                factory.serviceWorkerController());
        mTracing = CompatTracingController.create(factory.tracingController());
        mFeatures = SupportedFeatures.all();
    }

    @NonNull
    public static InvocationHandler create(@NonNull CompatHost.Factory factory) {
        return new CompatWebViewFactory(factory);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "createWebView":
                return CompatWebViewProvider.create((android.webkit.WebView) args[0],
                        mFactory.webViewBackend((android.webkit.WebView) args[0]));
            case "getWebkitToCompatConverter":
                return mConverter;
            case "getStatics":
                return mStatics;
            case "getSupportedFeatures":
                return mFeatures.clone();
            case "getServiceWorkerController":
                return mServiceWorker;
            case "getTracingController":
                return mTracing;
            case "getWebViewBuilder":
            case "buildWebContent":
            case "getProxyController":
            case "getDropDataProvider":
            case "getProfileStore":
                throw new UnsupportedOperationException(
                        "CompatWebViewFactory." + method.getName() + ": unclaimed");
            case "startUpWebView":
                return startUp(args);
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "CompatWebViewFactory";
            default:
                throw new UnsupportedOperationException(
                        "CompatWebViewFactory: " + method.getName());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @NonNull
    private Object startUp(Object[] args) {
        if (args != null && args.length == 2 && args[0] instanceof Consumer
                && args[1] instanceof Consumer) {
            Consumer<Consumer<BiConsumer<Integer, Object>>> onSuccess =
                    (Consumer<Consumer<BiConsumer<Integer, Object>>>) args[1];
            onSuccess.accept(results -> {
            });
            return null;
        }
        return null;
    }
}
