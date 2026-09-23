package org.mozilla.geckowebview.provider;

import android.content.Context;
import android.net.Uri;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckowebview.session.ContentBridge;
import org.mozilla.geckowebview.session.InterceptBridge;
import org.mozilla.geckowebview.session.MessageBridge;
import org.mozilla.geckowebview.session.PermissionBridge;
import org.mozilla.geckowebview.session.PromptBridge;

// Client callback fan-out: Gecko bridge hosts -> WebViewClient/WebChromeClient.
// Split out of GeckoWebViewProvider (AGENTS.md §7 file-size rule).
final class ClientFanOut
        implements org.mozilla.geckowebview.session.GeckoSessionBridge.Client,
        PermissionBridge.Host, PromptBridge.Host, ContentBridge.Host,
        org.mozilla.geckowebview.session.FindBridge.Host,
        MessageBridge.Host, InterceptBridge.Host {
    private static final String TAG = "Sinytra/session";
    private static final int FILE_CHOOSER_REQUEST = 0x5EED;

    interface Owner {
        @NonNull
        WebView webView();

        @NonNull
        GeckoWebViewFactoryProvider factory();

        @NonNull
        org.mozilla.geckowebview.session.GeckoSessionBridge ownerBridge();

        @Nullable
        WebViewClient webViewClient();

        @Nullable
        WebChromeClient webChromeClient();

        @Nullable
        DownloadListener downloadListener();

        void setFileChooserCallback(@Nullable ValueCallback<Uri[]> callback);

        @Nullable
        ValueCallback<Uri[]> fileChooserCallback();

        void fireVisualState();

        /** Current 0001 response-surface filters (never null; may be empty). */
        @NonNull
        String[] interceptFilters();

        @NonNull
        org.mozilla.geckowebview.session.RenderProcessBridge renderProcess();
    }

    private final Owner mOwner;

    ClientFanOut(@NonNull Owner owner) {
        mOwner = owner;
    }

    static int fileChooserRequestCode() {
        return FILE_CHOOSER_REQUEST;
    }

    // --- GeckoSessionBridge.Client ---

    @Override
    public void onUrlChanged(@NonNull String url) {
    }

    @Override
    public void onCanGoBackChanged(boolean canGoBack) {
    }

    @Override
    public void onCanGoForwardChanged(boolean canGoForward) {
    }

    @Override
    public boolean shouldOverrideUrlLoading(@NonNull String url) {
        WebViewClient client = mOwner.webViewClient();
        if (client == null) {
            return false;
        }
        try {
            return client.shouldOverrideUrlLoading(mOwner.webView(), url);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.shouldOverrideUrlLoading threw", t);
            return false;
        }
    }

    @Override
    public void onPageStarted(@NonNull String url) {
        WebViewClient client = mOwner.webViewClient();
        if (client == null) {
            return;
        }
        try {
            client.onPageStarted(mOwner.webView(), url, null);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onPageStarted threw", t);
        }
    }

    @Override
    public void onPageFinished(boolean success) {
        WebViewClient client = mOwner.webViewClient();
        String url = mOwner.ownerBridge().getUrl();
        if (client == null || url == null) {
            return;
        }
        try {
            if (success) {
                client.onPageFinished(mOwner.webView(), url);
            } else {
                client.onReceivedError(mOwner.webView(), WebViewClient.ERROR_UNKNOWN,
                        "load failed", url);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onPageFinished threw", t);
        } finally {
            try {
                mOwner.fireVisualState();
            } catch (Throwable t) {
                android.util.Log.w(TAG, "fireVisualState threw", t);
            }
        }
    }

    @Override
    public void onProgressChanged(int progress) {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            chrome.onProgressChanged(mOwner.webView(), progress);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onProgressChanged threw", t);
        }
    }

    @Override
    public void onTitleChanged(@Nullable String title) {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            chrome.onReceivedTitle(mOwner.webView(), title);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onReceivedTitle threw", t);
        }
    }

    @Override
    public void onLoadError(int errorCode, @NonNull String description,
            @Nullable String failingUrl) {
        WebViewClient client = mOwner.webViewClient();
        if (client == null || failingUrl == null) {
            return;
        }
        try {
            client.onReceivedError(mOwner.webView(), errorCode, description,
                    failingUrl);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onReceivedError threw", t);
        }
    }

    // --- PermissionBridge.Host ---

    @Override
    public void onGeolocationPrompt(@NonNull String origin) {
        WebChromeClient chrome = mOwner.webChromeClient();
        GeolocationPermissions.Callback callback =
                new GeolocationPermissions.Callback() {
                    @Override
                    public void invoke(String o, boolean allow, boolean retain) {
                    }
                };
        if (chrome == null) {
            callback.invoke(origin, false, false);
            return;
        }
        try {
            chrome.onGeolocationPermissionsShowPrompt(origin, callback);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "onGeolocationPermissionsShowPrompt threw", t);
            callback.invoke(origin, false, false);
        }
    }

    @Override
    public void onPermissionRequest(@NonNull String origin, int geckoPermission) {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            chrome.onPermissionRequest(
                    new ProviderAdapters.SinytraPermissionRequest(origin));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onPermissionRequest threw", t);
        }
    }

    @Override
    public void onAndroidPermissionsRequest(@NonNull String[] permissions,
            @NonNull GeckoSession.PermissionDelegate.Callback callback) {
        try {
            callback.reject();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "permission callback reject threw", t);
        }
    }

    @Override
    public void onMediaRequest(@NonNull String uri,
            @NonNull GeckoSession.PermissionDelegate.MediaSource[] video,
            @NonNull GeckoSession.PermissionDelegate.MediaSource[] audio,
            @NonNull GeckoSession.PermissionDelegate.MediaCallback callback) {
        try {
            callback.reject();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "media callback reject threw", t);
        }
    }

    // --- PromptBridge.Host ---

    @Override
    public void onFileChooserRequest(
            @NonNull GeckoSession.PromptDelegate.FilePrompt prompt) {
        WebChromeClient chrome = mOwner.webChromeClient();
        ValueCallback<Uri[]> callback = new ValueCallback<Uri[]>() {
            @Override
            public void onReceiveValue(Uri[] value) {
                try {
                    if (value != null && value.length > 0) {
                        Context context = mOwner.webView().getContext();
                        if (prompt.type
                                == GeckoSession.PromptDelegate.FilePrompt.Type.SINGLE) {
                            prompt.confirm(context, value[0]);
                        } else {
                            prompt.confirm(context, value);
                        }
                    } else {
                        prompt.dismiss();
                    }
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "file prompt confirm threw", t);
                } finally {
                    mOwner.setFileChooserCallback(null);
                }
            }
        };
        mOwner.setFileChooserCallback(callback);
        if (chrome == null) {
            callback.onReceiveValue(null);
            return;
        }
        try {
            boolean handled = chrome.onShowFileChooser(mOwner.webView(), callback,
                    new ProviderAdapters.SinytraFileChooserParams(prompt));
            if (!handled) {
                callback.onReceiveValue(null);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onShowFileChooser threw", t);
            callback.onReceiveValue(null);
        }
    }

    @Override
    public void onHttpAuthRequest(
            @NonNull GeckoSession.PromptDelegate.AuthPrompt prompt) {
        String uri = prompt.authOptions != null && prompt.authOptions.uri != null
                ? prompt.authOptions.uri : "";
        String host = hostOf(uri);
        String realm = prompt.message != null ? prompt.message : "";
        String[] stored = null;
        try {
            stored = mOwner.factory().webViewDatabase(mOwner.webView().getContext())
                    .getHttpAuthUsernamePassword(host, realm);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "webViewDatabase get threw", t);
        }
        if (stored != null && stored.length == 2) {
            try {
                prompt.confirm(stored[0] != null ? stored[0] : "",
                        stored[1] != null ? stored[1] : "");
                return;
            } catch (Throwable t) {
                android.util.Log.w(TAG, "auth confirm stored threw", t);
            }
        }
        WebViewClient client = mOwner.webViewClient();
        if (client == null) {
            try {
                prompt.dismiss();
            } catch (Throwable t) {
                android.util.Log.w(TAG, "auth prompt dismiss threw", t);
            }
            return;
        }
        HttpAuthHandler handler = FrameworkTokens.newAuthHandler();
        if (handler == null) {
            try {
                prompt.dismiss();
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            client.onReceivedHttpAuthRequest(mOwner.webView(), handler, uri, realm);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onReceivedHttpAuthRequest threw", t);
            try {
                prompt.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String hostOf(@NonNull String uri) {
        try {
            return Uri.parse(uri).getHost() != null ? Uri.parse(uri).getHost() : uri;
        } catch (Throwable t) {
            return uri;
        }
    }

    @Override
    public void onJsAlert(@NonNull String title, @NonNull String message) {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        JsResult result = FrameworkTokens.newJsResult();
        if (result == null) {
            return;
        }
        try {
            chrome.onJsAlert(mOwner.webView(), mOwner.ownerBridge().getUrl(), message,
                    result);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onJsAlert threw", t);
        }
    }

    @Override
    public boolean onJsConfirm(@NonNull String title, @NonNull String message) {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return false;
        }
        JsResult result = FrameworkTokens.newJsResult();
        if (result == null) {
            return false;
        }
        try {
            chrome.onJsConfirm(mOwner.webView(), mOwner.ownerBridge().getUrl(), message,
                    result);
            return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onJsConfirm threw", t);
            return false;
        }
    }

    @Override
    @Nullable
    public String onJsPrompt(@NonNull String title, @NonNull String message,
            @Nullable String defaultValue) {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return null;
        }
        JsPromptResult result = FrameworkTokens.newJsPromptResult();
        if (result == null) {
            return null;
        }
        try {
            boolean handled = chrome.onJsPrompt(mOwner.webView(),
                    mOwner.ownerBridge().getUrl(), message,
                    defaultValue != null ? defaultValue : "", result);
            return handled ? "" : null;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onJsPrompt threw", t);
            return null;
        }
    }

    // --- ContentBridge.Host ---

    @Override
    public void onDownloadStart(@NonNull String url, @Nullable String userAgent,
            @Nullable String contentDisposition, @NonNull String mimeType,
            long contentLength) {
        DownloadListener listener = mOwner.downloadListener();
        if (listener == null) {
            return;
        }
        try {
            listener.onDownloadStart(url, userAgent, contentDisposition, mimeType,
                    contentLength);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "DownloadListener.onDownloadStart threw", t);
        }
    }

    @Override
    public void onFullScreen(boolean fullScreen) {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            if (fullScreen) {
                chrome.onShowCustomView(mOwner.webView(), null);
            } else {
                chrome.onHideCustomView();
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient fullscreen threw", t);
        }
    }

    @Override
    public void onCloseWindow() {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            chrome.onCloseWindow(mOwner.webView());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onCloseWindow threw", t);
        }
    }

    @Override
    public void onFocusRequest() {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            chrome.onRequestFocus(mOwner.webView());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onRequestFocus threw", t);
        }
    }

    @Override
    public void onCrash() {
        WebViewClient client = mOwner.webViewClient();
        String url = mOwner.ownerBridge().getUrl();
        mOwner.renderProcess().onGeckoCrash(mOwner.webView(), client);
        if (client == null) {
            return;
        }
        try {
            client.onReceivedError(mOwner.webView(), WebViewClient.ERROR_UNKNOWN,
                    "renderer crashed", url != null ? url : "about:blank");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient crash report threw", t);
        }
    }

    // --- MessageBridge.Host ---

    @Override
    public void onMessage(@NonNull String portId, @NonNull String data,
            @Nullable String origin) {
    }

    // --- InterceptBridge.Host ---

    @Override
    @Nullable
    public WebResourceResponse shouldIntercept(
            @NonNull InterceptBridge.SinytraResourceRequest request) {
        WebViewClient client = mOwner.webViewClient();
        if (client == null) {
            return null;
        }
        try {
            WebResourceResponse response = client.shouldInterceptRequest(
                    mOwner.webView(), request);
            if (response != null) {
                // Consultation only: the caller decides (deny via
                // onInterceptDeny, or body substitution via 0001
                // ResponseBridge) — never assume deny here.
                android.util.Log.d(TAG, "intercept: app returned response for "
                        + request.getUrl());
            }
            return response;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.shouldInterceptRequest threw", t);
            return null;
        }
    }

    @Override
    public void onInterceptDeny(@NonNull String uri, boolean isRedirect,
            boolean hasUserGesture) {
        android.util.Log.i(TAG, "intercept: DENY " + uri + " redirect=" + isRedirect
                + " gesture=" + hasUserGesture);
    }

    @Override
    public boolean responseSurfaceOwns(@NonNull String uri) {
        return org.mozilla.geckowebview.session.InterceptBridge
                .matchesFilterPrefix(uri, mOwner.interceptFilters());
    }

    // --- FindBridge.Host ---

    @Override
    public void onFindResult(int activeMatchOrdinal, int numberOfMatches,
            boolean done) {
    }
}
