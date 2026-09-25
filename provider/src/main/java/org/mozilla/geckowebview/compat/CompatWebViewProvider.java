package org.mozilla.geckowebview.compat;

import android.webkit.WebView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.chromium.support_lib_boundary.VisualStateCallbackBoundaryInterface;
import org.chromium.support_lib_boundary.WebViewProviderBoundaryInterface;

// WebViewProviderBoundaryInterface over one WebView backend (CompatHost): the glue
// object androidx.webkit drives through WebViewProviderFactory. Only the
// claimed-feature paths are implemented; everything else throws
// UnsupportedOperationException so isFeatureSupported stays honest.
public final class CompatWebViewProvider implements InvocationHandler {
    @NonNull
    private final WebView mWebView;
    @NonNull
    private final CompatHost.WebViewBackend mProvider;

    private CompatWebViewProvider(@NonNull WebView webView,
            @NonNull CompatHost.WebViewBackend provider) {
        mWebView = webView;
        mProvider = provider;
    }

    @NonNull
    public static InvocationHandler create(@NonNull WebView webView,
            @NonNull CompatHost.WebViewBackend provider) {
        return new CompatWebViewProvider(webView, provider);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "insertVisualStateCallback": {
                long requestId = (Long) args[0];
                InvocationHandler callback = (InvocationHandler) args[1];
                mProvider.insertVisualStateCallback(requestId, callback);
                return null;
            }
            case "createWebMessageChannel":
                return createChannel();
            case "postMessageToMainFrame":
                mProvider.postCompatMessage(args[0], args[1]);
                return null;
            case "getWebViewClient":
                return mProvider.getWebViewClient();
            case "getWebChromeClient":
                return mProvider.getWebChromeClient();
            case "getWebViewRenderer":
                return CompatRenderProcess.create(mProvider.getWebViewRenderProcess());
            case "getWebViewRendererClient":
                return CompatRenderProcess.clientHandler(
                        mProvider.getWebViewRenderProcessClient());
            case "setWebViewRendererClient":
                mProvider.setCompatRendererClient((InvocationHandler) args[0]);
                return null;
            case "getProfile":
                return CompatProfile.create(mWebView.getContext());
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "CompatWebViewProvider";
            default:
                throw new UnsupportedOperationException(
                        "CompatWebViewProvider: " + method.getName());
        }
    }

    @Nullable
    private InvocationHandler[] createChannel() {
        org.mozilla.geckowebview.session.MessageBridge bridge =
                mProvider.messageBridge();
        org.mozilla.geckowebview.session.MessageBridge.Port[] ports =
                bridge.createChannel();
        InvocationHandler[] handlers = new InvocationHandler[ports.length];
        for (int i = 0; i < ports.length; i++) {
            handlers[i] = CompatSmallBoundaries.messagePort(bridge, ports[i]);
        }
        return handlers;
    }

    // Visual-state callback that fires once, on the next page-stop.
    public static final class CompatVisualStateCallback {
        private CompatVisualStateCallback() {}

        @NonNull
        public static WebView.VisualStateCallback wrap(long requestId,
                @NonNull InvocationHandler boundary) {
            AtomicReference<WebView.VisualStateCallback> self = new AtomicReference<>();
            WebView.VisualStateCallback callback = new WebView.VisualStateCallback() {
                @Override
                public void onComplete(long id) {
                    try {
                        boundary.invoke(null,
                                VisualStateCallbackBoundaryInterface.class.getMethod(
                                        "onComplete", long.class),
                                new Object[] {id});
                    } catch (Throwable t) {
                        android.util.Log.w("Sinytra/compat",
                                "visual state callback threw", t);
                    }
                }
            };
            self.set(callback);
            return callback;
        }
    }
}
