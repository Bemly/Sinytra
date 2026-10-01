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

    // Tokens are built through the real @SystemApi ctors
    // JsResult(ResultReceiver)/JsPromptResult(ResultReceiver) — there is
    // NO no-arg ctor at runtime (android14-release source), so bare
    // getDeclaredConstructor() would always fail and no dialog would ever
    // reach the app. The receiver is a no-op: answers are read back
    // synchronously via jsResultValue/jsPromptString below.
    @Nullable
    static JsResult newJsResult() {
        try {
            Class<?> receiverClass =
                    Class.forName("android.webkit.JsResult$ResultReceiver");
            Object receiver = java.lang.reflect.Proxy.newProxyInstance(
                    FrameworkTokens.class.getClassLoader(),
                    new Class<?>[] {receiverClass},
                    (proxy, method, args) -> null);
            java.lang.reflect.Constructor<JsResult> ctor =
                    JsResult.class.getDeclaredConstructor(receiverClass);
            ctor.setAccessible(true);
            return ctor.newInstance(receiver);
        } catch (Throwable t) {
            Log.w(TAG, "JsResult reflection failed", t);
            return null;
        }
    }

    @Nullable
    static JsPromptResult newJsPromptResult() {
        try {
            Class<?> receiverClass =
                    Class.forName("android.webkit.JsResult$ResultReceiver");
            Object receiver = java.lang.reflect.Proxy.newProxyInstance(
                    FrameworkTokens.class.getClassLoader(),
                    new Class<?>[] {receiverClass},
                    (proxy, method, args) -> null);
            java.lang.reflect.Constructor<JsPromptResult> ctor =
                    JsPromptResult.class.getDeclaredConstructor(receiverClass);
            ctor.setAccessible(true);
            return ctor.newInstance(receiver);
        } catch (Throwable t) {
            Log.w(TAG, "JsPromptResult reflection failed", t);
            return null;
        }
    }

    // Read back the app's synchronous answer from the token we handed out.
    // getResult()/getStringResult() are public @SystemApi on the API-34
    // device framework (absent from android.jar) — plain getMethod, no
    // hidden-API violation. CTS clients (and typical apps) answer
    // synchronously inside the callback; async answers land after we
    // return and are not observed (documented WebView-semantic gap:
    // Chromium would wait on the UI thread, which we must not block).
    // On the JVM (mockable android.jar) the methods don't exist — callers
    // must treat the fallback as the device-unverified path.
    static boolean jsResultValue(@Nullable JsResult result) {
        if (result == null) {
            return false;
        }
        try {
            Object value = JsResult.class.getMethod("getResult").invoke(result);
            return Boolean.TRUE.equals(value);
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    static String jsPromptString(@Nullable JsPromptResult result) {
        if (result == null) {
            return null;
        }
        try {
            Object value = JsPromptResult.class.getMethod("getStringResult")
                    .invoke(result);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
