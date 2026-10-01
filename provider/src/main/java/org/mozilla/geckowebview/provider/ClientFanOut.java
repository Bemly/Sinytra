package org.mozilla.geckowebview.provider;

import android.content.Context;
import android.net.Uri;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SinytraSslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoResult;
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
        MessageBridge.Host, InterceptBridge.Host,
        org.mozilla.geckowebview.session.ConsoleBridge.Host {
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

        /** Live WebSettings state for policy enforcement. */
        @NonNull
        org.mozilla.geckowebview.settings.GeckoWebSettings webSettingsState();

        /** Re-push 0001 filters: recovers a startup-raced push (see Interception). */
        void repushResponseFilters();

        /** Kick a best-effort favicon fetch for the current page. */
        void fetchFavicon();
        /** App filters plus the universal prefix (what Gecko actually gets). */
        @NonNull
        String[] effectiveInterceptFilters();

        @NonNull
        org.mozilla.geckowebview.session.RenderProcessBridge renderProcess();
    }

    private final Owner mOwner;
    private boolean mFiltersRepushed;

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
        if (!mFiltersRepushed) {
            mFiltersRepushed = true;
            try {
                mOwner.repushResponseFilters();
            } catch (Throwable t) {
                android.util.Log.w(TAG, "repushResponseFilters threw", t);
            }
        }
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
                // Chromium order on failed loads: onReceivedError FIRST,
                // then onPageFinished anyway. Without the finish, load
                // gates (CTS WebViewSyncLoader mLoaded, and any app
                // spinner) hang forever on every failed navigation (CTS
                // WebViewSslTest.testOnReceivedSslErrorCancel family).
                client.onReceivedError(mOwner.webView(), WebViewClient.ERROR_UNKNOWN,
                        "load failed", url);
                client.onPageFinished(mOwner.webView(), url);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onPageFinished threw", t);
        } finally {
            try {
                mOwner.fireVisualState();
            } catch (Throwable t) {
                android.util.Log.w(TAG, "fireVisualState threw", t);
            }
            if (success) {
                try {
                    mOwner.fetchFavicon();
                } catch (Throwable t) {
                    android.util.Log.d(TAG, "fetchFavicon threw", t);
                }
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
    public void onLoadError(int errorCode, int sslPrimaryError,
            @NonNull String description, @Nullable String failingUrl) {
        WebViewClient client = mOwner.webViewClient();
        if (client == null || failingUrl == null) {
            return;
        }
        // P1 SSL: cert failures go to onReceivedSslError (Chromium order:
        // onReceivedSslError INSTEAD of onReceivedError for these). The
        // SslError carries no certificate (Gecko does not surface one in the
        // error callback); the handler routes the app's decision — cancel is
        // the error page, proceed = temporary cert override + reload
        // (firefox-patches/0006, Firefox Add-Exception semantics).
        if (sslPrimaryError >= 0) {
            try {
                client.onReceivedSslError(mOwner.webView(),
                        new SinytraSslErrorHandler(
                                () -> proceedSslError(failingUrl),
                                () -> {
                                    // cancel: the load is already terminal;
                                    // the error page stands.
                                }),
                            new android.net.http.SslError(sslPrimaryError,
                                    (android.net.http.SslCertificate) null,
                                    failingUrl));
                return;
            } catch (Throwable t) {
                android.util.Log.w(TAG,
                        "WebViewClient.onReceivedSslError threw", t);
            }
        }
        try {
            client.onReceivedError(mOwner.webView(), errorCode, description,
                    failingUrl);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onReceivedError threw", t);
        }
    }

    // SslErrorHandler.proceed: Chromium semantics = continue the blocked load
    // despite the invalid certificate. The navigation is already terminal on
    // the Gecko side, so proceed = remember a temporary override for the
    // failed certificate (0006) and reload the URI. If the reload fails
    // again the app is consulted again (same as Chromium re-asking).
    private void proceedSslError(@NonNull String url) {
        android.webkit.WebView webView = mOwner.webView();
        if (webView == null) {
            return;
        }
        org.mozilla.geckoview.CertOverrideController.allowError(url)
                .accept(ok -> {
                    android.util.Log.d(TAG, "ssl proceed override=" + ok
                            + " url=" + url);
                    if (Boolean.TRUE.equals(ok)) {
                        // Reload through OUR provider bridge after the failed
                        // load fully settles (the docshell is mid-decision
                        // when proceed() fires; an immediate loadUri was
                        // observed to be swallowed by the error-page flow).
                        // A plain android.os.Handler, NOT View.post: the
                        // WebView can be detached (harness, background
                        // tabs), and a detached View queues the runnable
                        // into the attach-time RunQueue where it never runs.
                        new android.os.Handler(android.os.Looper
                                .getMainLooper()).postDelayed(() -> {
                            android.util.Log.d(TAG,
                                    "ssl proceed reloading " + url);
                            mOwner.ownerBridge().loadUrl(url);
                        }, 500);
                    }
                },
                e -> android.util.Log.w(TAG, "ssl proceed failed", e));
    }

    // --- PermissionBridge.Host ---

    // Chromium parity: geolocation is a secure-context API. Insecure
    // origins are denied WITHOUT prompting (the app client is never
    // consulted) — prompting there then honoring "allow" leaks position
    // to http. Secure = https scheme, or loopback host (Secure Contexts
    // §3.1 trustworthy loopback, so the CTS http://localhost harness
    // keeps prompting). Visible for JVM locks; pure string parsing so it
    // stays off android.net.Uri (mockable-jar hostile).
    static boolean isSecureOriginForGeolocation(@Nullable String origin) {
        if (origin == null) {
            return false;
        }
        int schemeEnd = origin.indexOf("://");
        if (schemeEnd < 0) {
            return false;
        }
        String scheme = origin.substring(0, schemeEnd);
        if ("https".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)) {
            return true;
        }
        String rest = origin.substring(schemeEnd + 3);
        String host;
        if (rest.startsWith("[")) {
            // Bracketed IPv6 literal: colons inside are not port separators.
            int close = rest.indexOf(']');
            host = close < 0 ? "" : rest.substring(0, close + 1);
        } else {
            int hostEnd = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '/' || c == '?' || c == '#' || c == ':') {
                    hostEnd = i;
                    break;
                }
            }
            host = rest.substring(0, hostEnd);
        }
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host);
    }

    @Override
    public void onGeolocationPrompt(@NonNull String origin,
            @NonNull PermissionBridge.Decision decision) {
        if (!isSecureOriginForGeolocation(origin)) {
            android.util.Log.w(TAG,
                    "geolocation denied without prompt: insecure origin "
                            + origin);
            decision.deny();
            return;
        }
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            decision.deny();
            return;
        }
        GeolocationPermissions.Callback callback =
                new GeolocationPermissions.Callback() {
                    @Override
                    public void invoke(String o, boolean allow, boolean retain) {
                        if (allow) {
                            decision.allow();
                        } else {
                            decision.deny();
                        }
                        // retain (remember this origin) has no Gecko
                        // persistence primitive in P1 — accepted, logged.
                        if (retain) {
                            android.util.Log.d(TAG,
                                    "geolocation retain=true not persisted");
                        }
                    }
                };
        try {
            chrome.onGeolocationPermissionsShowPrompt(origin, callback);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "onGeolocationPermissionsShowPrompt threw", t);
            decision.deny();
        }
    }

    @Override
    public void onPermissionRequest(@NonNull String origin, int geckoPermission,
            @NonNull PermissionBridge.Decision decision) {
        // No WebView prompt surface exists for content permissions other
        // than geolocation — Chromium never routes these through
        // onPermissionRequest (that API is for device capture, which the
        // media path below owns). Deny loudly rather than fabricate a
        // prompt the app cannot answer truthfully.
        android.util.Log.w(TAG, "no WebView surface for content permission "
                + geckoPermission + " on " + origin + " — denying");
        decision.deny();
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
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            try {
                callback.reject();
            } catch (Throwable t) {
                android.util.Log.w(TAG, "media callback reject threw", t);
            }
            return;
        }
        try {
            chrome.onPermissionRequest(
                    new ProviderAdapters.SinytraMediaPermissionRequest(uri,
                            video, audio, callback));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onPermissionRequest threw", t);
            try {
                callback.reject();
            } catch (Throwable ignored) {
            }
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
            @NonNull GeckoSession.PromptDelegate.AuthPrompt prompt,
            @NonNull GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pending) {
        String uri = prompt.authOptions != null && prompt.authOptions.uri != null
                ? prompt.authOptions.uri : "";
        String host = hostOf(uri);
        // Gecko composes the message as "{uri} is requesting your username
        // and password. The site says: "{realm}"" — the framework contract
        // (and CTS HttpAuthHandlerTest) wants the bare realm. Parse it out;
        // anything unparseable falls back to the full message.
        String realm = realmOf(prompt.message);
        // The app's proceed()/cancel() on the handler must complete the
        // delegate GeckoResult (PromptBridge holds it open). The Decision
        // posts prompt.confirm/dismiss to the UI thread AND completes
        // pending with the same answer (GeckoResult tolerates exactly one
        // completion; the handler guards duplicates first).
        android.webkit.SinytraHttpAuthHandler.Decision decision =
                new android.webkit.SinytraHttpAuthHandler.Decision() {
                    @Override
                    public void proceed(@NonNull String username,
                            @NonNull String password) {
                        // Chromium stores proceed() credentials for the
                        // protection space (later challenges report
                        // useHttpAuthUsernamePassword()=true).
                        try {
                            mOwner.factory().webViewDatabase(mOwner.webView()
                                    .getContext()).setHttpAuthUsernamePassword(
                                            host, realm, username, password);
                        } catch (Throwable t) {
                            android.util.Log.w(TAG,
                                    "webViewDatabase set threw", t);
                        }
                        mainPostAuth(pending, prompt, username, password,
                                false);
                    }

                    @Override
                    public void cancel() {
                        mainPostAuth(pending, prompt, "", "", true);
                    }
                };
        String[] stored = null;
        try {
            stored = mOwner.factory().webViewDatabase(mOwner.webView().getContext())
                    .getHttpAuthUsernamePassword(host, realm);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "webViewDatabase get threw", t);
        }
        HttpAuthHandler handler =
                FrameworkTokens.newAuthHandler(decision, stored != null
                        && stored.length == 2);
        if (handler == null) {
            try {
                pending.complete(prompt.dismiss());
            } catch (Throwable ignored) {
            }
            return;
        }
        // Chromium parity: stored credentials do NOT bypass the app — the
        // handler reports useHttpAuthUsernamePassword()=true and the app
        // decides (typically: query getHttpAuthUsernamePassword + proceed).
        // Only without an app client do stored credentials auto-answer.
        WebViewClient client = mOwner.webViewClient();
        if (client == null) {
            if (stored != null && stored.length == 2) {
                final String user = stored[0] != null ? stored[0] : "";
                final String pass = stored[1] != null ? stored[1] : "";
                try {
                    pending.complete(prompt.confirm(user, pass));
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "auth confirm stored threw", t);
                    try {
                        pending.complete(prompt.dismiss());
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            try {
                pending.complete(prompt.dismiss());
            } catch (Throwable t) {
                android.util.Log.w(TAG, "auth prompt dismiss threw", t);
            }
            return;
        }
        try {
            // Framework contract: host is the bare host (not the full
            // URI) and realm is the bare protection-space name.
            client.onReceivedHttpAuthRequest(mOwner.webView(), handler, host,
                    realm);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebViewClient.onReceivedHttpAuthRequest threw", t);
            try {
                pending.complete(prompt.dismiss());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void mainPostAuth(
            @NonNull GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pending,
            @NonNull GeckoSession.PromptDelegate.AuthPrompt prompt,
            @NonNull String username, @NonNull String password,
            boolean dismiss) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                if (dismiss) {
                    pending.complete(prompt.dismiss());
                } else {
                    pending.complete(prompt.confirm(username, password));
                }
            } catch (Throwable t) {
                android.util.Log.w(TAG, "auth pending complete threw", t);
            }
        });
    }

    private static String hostOf(@NonNull String uri) {
        try {
            return Uri.parse(uri).getHost() != null ? Uri.parse(uri).getHost() : uri;
        } catch (Throwable t) {
            return uri;
        }
    }

    static String realmOf(@Nullable String message) {
        if (message == null) {
            return "";
        }
        // Gecko quotes the realm with ASCII or curly quotes depending on
        // platform string formatting (CTS failure text shows U+201C/U+201D).
        String realm = realmBetween(message, "The site says: \"", "\"");
        if (realm != null) {
            return realm;
        }
        String curly = realmBetween(message, "The site says: \u201C", "\u201D");
        return curly != null ? curly : message;
    }

    @Nullable
    private static String realmBetween(@NonNull String message,
            @NonNull String open, @NonNull String close) {
        int start = message.indexOf(open);
        if (start < 0) {
            return null;
        }
        int end = message.lastIndexOf(close);
        if (end <= start + open.length()) {
            return null;
        }
        return message.substring(start + open.length(), end);
    }

    @Override
    public void onJsAlert(@NonNull String title, @NonNull String message) {
        WebChromeClient chrome = mOwner.webChromeClient();
        JsResult result = FrameworkTokens.newJsResult();
        if (result == null) {
            return;
        }
        if (chrome == null) {
            // No client: framework default handling confirms (a dangling
            // alert would wedge page JS forever).
            try {
                result.confirm();
            } catch (Throwable ignored) {
            }
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
        JsResult result = FrameworkTokens.newJsResult();
        if (result == null) {
            return false;
        }
        if (chrome == null) {
            // No client: framework default is cancel.
            return false;
        }
        try {
            chrome.onJsConfirm(mOwner.webView(), mOwner.ownerBridge().getUrl(), message,
                    result);
            // The token is ours; read back the app's synchronous answer
            // (getResult is public @SystemApi on device). Blind-true here
            // used to report OK even when the app cancelled (CTS
            // WebChromeClientTest timeout family).
            return FrameworkTokens.jsResultValue(result);
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
        JsPromptResult result = FrameworkTokens.newJsPromptResult();
        if (result == null) {
            return null;
        }
        if (chrome == null) {
            return null;
        }
        try {
            chrome.onJsPrompt(mOwner.webView(),
                    mOwner.ownerBridge().getUrl(), message,
                    defaultValue != null ? defaultValue : "", result);
            // Read back the confirmed string (was: hardcoded "" — the page
            // always received empty input).
            return FrameworkTokens.jsPromptString(result);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onJsPrompt threw", t);
            return null;
        }
    }

    @Override
    public boolean onJsBeforeUnload(@NonNull String url, @NonNull String message) {
        WebChromeClient chrome = mOwner.webChromeClient();
        JsResult result = FrameworkTokens.newJsResult();
        if (result == null) {
            return true;
        }
        if (chrome == null) {
            // No client: proceed without dialog (Chromium default).
            try {
                result.confirm();
            } catch (Throwable ignored) {
            }
            return true;
        }
        try {
            chrome.onJsBeforeUnload(mOwner.webView(), url, message, result);
            return FrameworkTokens.jsResultValue(result);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onJsBeforeUnload threw", t);
            return true;
        }
    }

    // --- ContentBridge.Host ---

    @Override
    public void onDownloadStart(@NonNull String url, @Nullable String userAgent,
            @Nullable String contentDisposition, @NonNull String mimeType,
            long contentLength) {
        DownloadListener listener = mOwner.downloadListener();
        android.util.Log.d(TAG, "onDownloadStart url=" + url
                + " listener=" + (listener != null)
                + " owner=" + Integer.toHexString(mOwner.hashCode()));
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
    public void onConsoleMessage(@NonNull String level, @NonNull String message,
            int line) {
        WebChromeClient chrome = mOwner.webChromeClient();
        if (chrome == null) {
            return;
        }
        try {
            android.webkit.ConsoleMessage.MessageLevel mapped;
            try {
                mapped = android.webkit.ConsoleMessage.MessageLevel
                        .valueOf(level);
            } catch (Throwable t) {
                mapped = android.webkit.ConsoleMessage.MessageLevel.LOG;
            }
            String url = mOwner.ownerBridge().getUrl();
            chrome.onConsoleMessage(new android.webkit.ConsoleMessage(message,
                    url != null ? url : "", line, mapped));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "WebChromeClient.onConsoleMessage threw", t);
        }
    }

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
                .matchesFilterPrefix(uri, mOwner.effectiveInterceptFilters());
    }

    @Override
    public boolean policyBlocksNavigation(@NonNull String uri,
            boolean isMainFrame, @Nullable String triggerUri) {
        try {
            String page = triggerUri != null && !triggerUri.isEmpty()
                    ? triggerUri
                    : mOwner.ownerBridge().getUrl();
            return LoadPolicy.blockNavigation(uri, isMainFrame, page,
                    mOwner.webSettingsState());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "policyBlocksNavigation threw", t);
            return false;
        }
    }

    // --- FindBridge.Host ---

    @Override
    public void onFindResult(int activeMatchOrdinal, int numberOfMatches,
            boolean done) {
    }
}
