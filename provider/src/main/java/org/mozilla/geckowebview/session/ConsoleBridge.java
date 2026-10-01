package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.json.JSONObject;

// Page console.* forwarding (CTS WebChromeClientTest.testOnConsoleMessage).
// The page-shim hooks console methods and emits sinytra-event "console"
// {level, message, line}; content.js ferries it as a native event and this
// bridge fans it out to the Host. No Gecko patch: same transport as eval
// (AGENTS.md §4 sinytra-js exception). Levels follow
// android.webkit.ConsoleMessage.MessageLevel.
public final class ConsoleBridge {
    public interface Host {
        void onConsoleMessage(@NonNull String level, @NonNull String message,
                int line);
    }

    @Nullable
    private final Host mHost;
    @Nullable
    private volatile JsBridge mTransport;

    public ConsoleBridge(@Nullable Host host) {
        mHost = host;
    }

    public void setTransport(@Nullable JsBridge bridge) {
        mTransport = bridge;
        if (bridge != null) {
            bridge.addPageEventListener(event -> {
                if ("console".equals(event.optString("name", ""))) {
                    onConsoleEvent(event.optJSONObject("payload"));
                }
            });
        }
    }

    // Shim level string → framework MessageLevel name. Package-visible for
    // JVM locks; unknown levels degrade to LOG (never drop a message).
    static String messageLevelFor(@Nullable String level) {
        if ("warn".equals(level) || "warning".equals(level)) {
            return "WARNING";
        }
        if ("error".equals(level)) {
            return "ERROR";
        }
        if ("debug".equals(level)) {
            return "DEBUG";
        }
        return "LOG";
    }

    private void onConsoleEvent(@Nullable JSONObject payload) {
        Host host = mHost;
        if (host == null || payload == null) {
            return;
        }
        try {
            host.onConsoleMessage(
                    messageLevelFor(payload.optString("level", "")),
                    payload.optString("message", ""),
                    payload.optInt("line", 0));
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/console",
                    "Host.onConsoleMessage threw", t);
        }
    }
}
