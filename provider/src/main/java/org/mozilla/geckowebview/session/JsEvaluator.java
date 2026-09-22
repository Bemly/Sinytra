package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.webkit.ValueCallback;

// JsEvaluator: evaluateJavascript surface over the JsBridge transport.
// P2-1 result: GV153 has no GeckoSession eval primitive (AAR javap
// confirmed) — transport is the built-in WebExtension (JsBridge, public
// API: ensureBuiltIn + native messaging; no firefox-patch). Until the
// bridge reports ready, evaluate answers honest-null (P2-1 harness
// `jsEval null=true` preserved) instead of throwing.
// Result contract (Chromium parity): JSON-encoded value string;
// undefined/functions/DOM -> "null"; errors/timeout -> null callback.
public final class JsEvaluator {
    public interface Transport {
        void eval(@NonNull String script,
                @NonNull ValueCallback<String> callback);
    }

    @Nullable
    private volatile Transport mTransport;
    @Nullable
    private volatile JsBridge mBridge;

    public void setTransport(@Nullable Transport transport) {
        mTransport = transport;
    }

    // Bind the WebExtension transport. Replaces setTransport for the
    // production path; the Transport hook stays for tests.
    public void setBridge(@Nullable JsBridge bridge) {
        mBridge = bridge;
    }

    public boolean hasTransport() {
        return mTransport != null || (mBridge != null && mBridge.isReady());
    }

    public void evaluate(@Nullable String script,
            @Nullable ValueCallback<String> resultCallback) {
        if (script == null) {
            if (resultCallback != null) {
                resultCallback.onReceiveValue(null);
            }
            return;
        }
        Transport transport = mTransport;
        if (transport != null) {
            try {
                transport.eval(script, resultCallback != null ? resultCallback
                        : value -> {
                        });
                return;
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/js", "transport eval threw", t);
            }
        }
        JsBridge bridge = mBridge;
        if (bridge != null && bridge.isReady()) {
            try {
                bridge.evaluate(script, value -> {
                    if (resultCallback == null) {
                        return;
                    }
                    try {
                        resultCallback.onReceiveValue(value);
                    } catch (Throwable t) {
                        android.util.Log.w("Sinytra/js",
                                "result callback threw", t);
                    }
                });
                return;
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/js", "bridge eval threw", t);
            }
        }
        if (resultCallback != null) {
            try {
                resultCallback.onReceiveValue(null);
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/js", "result callback threw", t);
            }
        }
    }
}
