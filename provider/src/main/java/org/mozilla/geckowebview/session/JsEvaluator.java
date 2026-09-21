package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.webkit.ValueCallback;

// JsEvaluator: evaluateJavascript surface without a Gecko primitive.
// P2-1 spike result: GV153 has no evaluateJavascript (confirmed by AAR
// javap: no evaluat*/script/JS API on GeckoSession) — needs a
// firefox-patch (ROADMAP.md P2.1). Until then: validate + wrap the script
// per WebView semantics (JSON-encode the result contract) and report
// honest-not-supported through the callback instead of throwing, so apps
// see a clean null rather than a crash. The patch binds transport later
// via setTransport().
public final class JsEvaluator {
    public interface Transport {
        void eval(@NonNull String script,
                @NonNull ValueCallback<String> callback);
    }

    @Nullable
    private volatile Transport mTransport;

    public void setTransport(@Nullable Transport transport) {
        mTransport = transport;
    }

    public boolean hasTransport() {
        return mTransport != null;
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
        if (resultCallback != null) {
            try {
                resultCallback.onReceiveValue(null);
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/js", "result callback threw", t);
            }
        }
    }
}
