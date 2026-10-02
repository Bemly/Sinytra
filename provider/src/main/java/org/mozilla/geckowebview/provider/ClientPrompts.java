package org.mozilla.geckowebview.provider;

import android.content.Context;
import android.net.Uri;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckowebview.session.PermissionBridge;
import org.mozilla.geckowebview.session.PromptBridge;

// Permission + prompt app fan-out (geolocation/device permissions, file
// chooser, HTTP auth, JS dialogs), split out of ClientFanOut (AGENTS.md
// §7 file-size rule). Owns no session state: translates bridge Host calls
// onto the app clients via the shared Owner. Tag stays Sinytra/session
// (log continuity with the code's previous home).
final class ClientPrompts implements PermissionBridge.Host, PromptBridge.Host {
    private static final String TAG = "Sinytra/session";

    @NonNull
    private final ClientFanOut.Owner mOwner;

    ClientPrompts(@NonNull ClientFanOut.Owner owner) {
        mOwner = owner;
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
}
