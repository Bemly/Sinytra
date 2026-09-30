package android.webkit;

import android.net.Network;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Sinytra: android.webkit.PacProcessor implementation over the JsBridge
// eval transport (same-package subclass; the framework type is PUBLIC
// INTERFACE ABSTRACT with PUBLIC STATIC createInstance/getInstance, so the
// provider cannot implement the interface directly — the framework calls
// getProvider().createPacProcessor(), and that return must extend the
// framework PacProcessor; same pattern as SinytraSslErrorHandler /
// SinytraWebMessagePort).
//
// Why self-written (AGENTS.md §4): Gecko has no PAC primitive; the script
// is executed in the page's JS context via the provider's evaluator and
// FindProxyForURL(url, host) is called per query. Network binding is an
// honest no-op (Gecko manages its own sockets; getNetwork stays null).
//
// Chromium-parity notes: createInstance returns a NEW object each call
// (CTS asserts distinct instances); release() marks dead (further calls
// are no-ops returning null/false); malformed scripts fail setProxyScript
// (returns false) rather than throwing.
public final class SinytraPacProcessor implements PacProcessor {
    public interface Evaluator {
        void eval(@NonNull String script,
                @NonNull android.webkit.ValueCallback<String> callback);
    }

    private static final long EVAL_TIMEOUT_MS = 15000L;

    @NonNull
    private final Evaluator mEvaluator;
    @Nullable
    private volatile String mScript;
    @Nullable
    private volatile Network mNetwork;
    private volatile boolean mReleased;

    public SinytraPacProcessor(@NonNull Evaluator evaluator) {
        mEvaluator = evaluator;
    }

    @Override
    public String findProxyForUrl(String url) {
        String script = mScript;
        if (mReleased || script == null || url == null) {
            return null;
        }
        String host = "";
        try {
            Uri uri = Uri.parse(url);
            host = uri.getHost() != null ? uri.getHost() : "";
        } catch (Throwable ignored) {
        }
        String escapedUrl = url.replace("\\", "\\\\").replace("'", "\\'");
        String escapedHost = host.replace("\\", "\\\\").replace("'", "\\'");
        String probe = "(function(){try{return String(FindProxyForURL('"
                + escapedUrl + "','" + escapedHost + "'));}catch(e){return '__sinytra_pac_error__';}})()";
        try {
            String result = evalSync(script + "\n" + probe);
            if (result == null || result.isEmpty()
                    || "__sinytra_pac_error__".equals(result)) {
                return null;
            }
            return result;
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/pac", "findProxyForUrl threw", t);
            return null;
        }
    }

    // Synchronous eval over the async JsBridge transport: the PAC contract
    // is synchronous (Chromium blocks the caller too). Bounded wait; the
    // JsBridge callback lands on the transport thread, not the caller, so
    // no deadlock even when the caller is the UI thread.
    @Nullable
    private String evalSync(@NonNull String script) {
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> value =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            mEvaluator.eval(script, v -> {
                value.set(v);
                done.countDown();
            });
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/pac", "eval dispatch threw", t);
            return null;
        }
        try {
            done.await(EVAL_TIMEOUT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return decodeJsString(value.get());
    }

    // JsBridge returns JSON-encoded values ("PROXY ..." arrives quoted);
    // strip one layer of JSON string quoting when present.
    @Nullable
    private static String decodeJsString(@Nullable String quoted) {
        if (quoted == null || quoted.length() < 2
                || !quoted.startsWith("\"") || !quoted.endsWith("\"")) {
            return quoted;
        }
        try {
            org.json.JSONArray arr = new org.json.JSONArray("[" + quoted + "]");
            Object parsed = arr.opt(0);
            return parsed instanceof String ? (String) parsed : quoted;
        } catch (Throwable t) {
            return quoted;
        }
    }

    @Override
    public Network getNetwork() {
        return mNetwork;
    }

    @Override
    public void release() {
        mReleased = true;
        mScript = null;
        mNetwork = null;
    }

    @Override
    public void setNetwork(Network network) {
        if (!mReleased) {
            mNetwork = network;
        }
    }

    @Override
    public boolean setProxyScript(String script) {
        if (mReleased || script == null || script.isEmpty()) {
            return false;
        }
        try {
            String check = evalSync(
                    script + "\n(typeof FindProxyForURL === 'function')");
            if (!"true".equals(check)) {
                return false;
            }
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/pac", "setProxyScript threw", t);
            return false;
        }
        mScript = script;
        return true;
    }
}
