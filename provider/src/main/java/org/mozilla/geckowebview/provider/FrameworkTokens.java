package org.mozilla.geckowebview.provider;

import android.util.Log;
import android.webkit.HttpAuthHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Reflective framework-token factories: HttpAuthHandler/JsResult/
// JsPromptResult all have package-private ctors invisible to provider code
// at compile time; runtime reflection reaches them (same technique as
// P0Glue's PrivateAccess construction). Instances are only tokens passed
// into app callbacks; Gecko-side decisions are made separately from app
// return values. Split out of GeckoWebViewProvider (file-size rule).
//
// NOTE (device-verified 2026-09-30, framework.jar dex dump):
// Landroid/webkit/HttpAuthHandler is PUBLIC with a PUBLIC ctor and
// PUBLIC cancel()/proceed()/useHttpAuthUsernamePassword() — the stub's
// "package-private ctor" record was android.jar stripping (@SystemApi).
// Direct construction works; reflection below is belt-and-braces.
final class FrameworkTokens {
    private static final String TAG = "Sinytra/provider";

    private FrameworkTokens() {}

    // android.webkit.HttpAuthHandler token wired to a live AuthPrompt:
    // proceed()/cancel() complete the Gecko prompt (confirm/dismiss);
    // useHttpAuthUsernamePassword() reports whether stored credentials
    // were already consumed (Chromium parity: true = WebViewDatabase hit).
    // The Decision posts confirm/dismiss to the UI thread (confirm is
    // @UiThread) AND completes the delegate GeckoResult the caller holds.
    @Nullable
    static HttpAuthHandler newAuthHandler(
            @NonNull android.webkit.SinytraHttpAuthHandler.Decision decision,
            boolean usedStoredCredentials) {
        try {
            return new android.webkit.SinytraHttpAuthHandler(decision,
                    usedStoredCredentials);
        } catch (Throwable t) {
            Log.w(TAG, "HttpAuthHandler construction failed", t);
            return null;
        }
    }

    @Nullable
    static JsResult newJsResult() {
        try {
            java.lang.reflect.Constructor<JsResult> ctor =
                    JsResult.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            Log.w(TAG, "JsResult reflection failed", t);
            return null;
        }
    }

    @Nullable
    static JsPromptResult newJsPromptResult() {
        try {
            java.lang.reflect.Constructor<JsPromptResult> ctor =
                    JsPromptResult.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            Log.w(TAG, "JsPromptResult reflection failed", t);
            return null;
        }
    }
}
