package org.mozilla.geckowebview.provider;

import android.util.Log;
import android.webkit.HttpAuthHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import androidx.annotation.Nullable;

// Reflective framework-token factories: HttpAuthHandler/JsResult/
// JsPromptResult all have package-private ctors invisible to provider code
// at compile time; runtime reflection reaches them (same technique as
// P0Glue's PrivateAccess construction). Instances are only tokens passed
// into app callbacks; Gecko-side decisions are made separately from app
// return values. Split out of GeckoWebViewProvider (file-size rule).
final class FrameworkTokens {
    private static final String TAG = "Sinytra/provider";

    private FrameworkTokens() {}

    // android.webkit.HttpAuthHandler token: stored-credential auto-fill
    // already handled the unattended case; the app's interactive
    // proceed()/cancel() on this token is best-effort in P1/P2.
    @Nullable
    static HttpAuthHandler newAuthHandler() {
        try {
            java.lang.reflect.Constructor<HttpAuthHandler> ctor =
                    HttpAuthHandler.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            Log.w(TAG, "HttpAuthHandler reflection failed", t);
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

    // android.webkit.SslErrorHandler token for onReceivedSslError. The load
    // is already terminal on the Gecko side by the time the app sees the
    // callback: cancel() is the effective default (matches Chromium when
    // the handler is untouched). proceed() would require a Gecko cert-
    // override primitive (nsICertOverrideService) — P2 patch candidate;
    // until then proceed() is best-effort like the auth-handler token.
    @Nullable
    static android.webkit.SslErrorHandler newSslErrorHandler() {
        try {
            java.lang.reflect.Constructor<android.webkit.SslErrorHandler> ctor =
                    android.webkit.SslErrorHandler.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            Log.w(TAG, "SslErrorHandler reflection failed", t);
            return null;
        }
    }
}
