package org.mozilla.geckowebview.compat;

import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewFactoryProvider;
import android.webkit.WebViewRenderProcess;
import android.webkit.WebViewRenderProcessClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import org.mozilla.geckowebview.session.MessageBridge;
import org.mozilla.geckowebview.storage.GeckoServiceWorkerController;
import org.mozilla.geckowebview.storage.GeckoTracingController;

// What the androidx.webkit boundary glue needs from the provider layer.
// AGENTS §2: compat/ depends only on session/ + storage/, never on
// provider/ — so compat declares the contract and provider/ implements it
// (dependency inversion); compat never names a provider class.
public final class CompatHost {
    private CompatHost() {}

    /** Implemented by the WebViewFactoryProvider. */
    public interface Factory {
        @NonNull
        WebViewFactoryProvider.Statics getStatics();

        /** API 28+; null below. */
        @Nullable
        GeckoServiceWorkerController serviceWorkerController();

        /** API 28+; null below. */
        @Nullable
        GeckoTracingController tracingController();

        /**
         * Live backend of a WebView this factory created. Throws
         * IllegalStateException when none is registered (never builds a
         * second backend for an existing WebView).
         */
        @NonNull
        WebViewBackend webViewBackend(@NonNull WebView webView);
    }

    /** Implemented by the per-WebView provider. */
    public interface WebViewBackend {
        void insertVisualStateCallback(long requestId, @NonNull InvocationHandler boundary);

        void postCompatMessage(@Nullable Object messageHandler, @Nullable Object targetOrigin);

        @Nullable
        WebViewClient getWebViewClient();

        @Nullable
        WebChromeClient getWebChromeClient();

        /** API 29+ type; callers gate on SDK_INT. */
        @Nullable
        WebViewRenderProcess getWebViewRenderProcess();

        /** API 29+ type; callers gate on SDK_INT. */
        @Nullable
        WebViewRenderProcessClient getWebViewRenderProcessClient();

        void setCompatRendererClient(@Nullable InvocationHandler boundary);

        @NonNull
        MessageBridge messageBridge();
    }
}
