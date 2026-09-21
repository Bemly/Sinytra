package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

// WebMessagePort/postWebMessage channel over Gecko WebExtension ports.
// P2-3 spike result: GeckoSession has NO JS-eval primitive and NO message
// channel primitive in GV153 public API — both need a firefox-patch
// (ARCHITECTURE.md §6.4). Until that patch lands, this class owns the
// channel bookkeeping (ports, pending messages, default target origin)
// so the provider surface is stable and testable; actual transport binds
// when the patch provides an eval/message primitive.
public final class MessageBridge {
    public interface Host {
        void onMessage(@NonNull String portId, @NonNull String data,
                @Nullable String origin);
    }

    public static final class Port {
        @NonNull
        public final String id;
        @Nullable
        public volatile WebExtension.Port geckoPort;
        @Nullable
        public volatile android.webkit.WebMessagePort.WebMessageCallback callback;

        Port(@NonNull String id) {
            this.id = id;
        }
    }

    private final AtomicInteger mNextId = new AtomicInteger(1);
    private final Map<String, Port> mPorts = new ConcurrentHashMap<>();
    private final Map<String, String> mPending = new ConcurrentHashMap<>();
    @Nullable
    private final Host mHost;

    public MessageBridge(@Nullable Host host) {
        mHost = host;
    }

    @NonNull
    public Port[] createChannel() {
        Port a = new Port("sinytra-port-" + mNextId.getAndIncrement());
        Port b = new Port("sinytra-port-" + mNextId.getAndIncrement());
        mPorts.put(a.id, a);
        mPorts.put(b.id, b);
        return new Port[] {a, b};
    }

    public void setCallback(@NonNull Port port,
            @Nullable android.webkit.WebMessagePort.WebMessageCallback callback) {
        port.callback = callback;
    }

    public void postMessage(@NonNull Port port, @NonNull String data,
            @Nullable String targetOrigin) {
        if (targetOrigin != null) {
            mPending.put(port.id, targetOrigin);
        }
        WebExtension.Port gecko = port.geckoPort;
        if (gecko != null) {
            try {
                org.json.JSONObject message = new org.json.JSONObject();
                message.put("data", data);
                if (targetOrigin != null) {
                    message.put("targetOrigin", targetOrigin);
                }
                gecko.postMessage(message);
                return;
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/message", "gecko postMessage threw", t);
            }
        }
        deliverLocal(port, data, targetOrigin);
    }

    public void postToMainFrame(@NonNull String data, @Nullable String targetOrigin) {
        for (Port port : mPorts.values()) {
            if (port.callback != null) {
                deliverLocal(port, data, targetOrigin);
                return;
            }
        }
        mPending.put("__main__", targetOrigin != null ? targetOrigin : "");
    }

    public void close(@NonNull Port port) {
        mPorts.remove(port.id);
        mPending.remove(port.id);
        WebExtension.Port gecko = port.geckoPort;
        port.geckoPort = null;
        port.callback = null;
        if (gecko != null) {
            try {
                gecko.disconnect();
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/message", "gecko disconnect threw", t);
            }
        }
    }

    @Nullable
    public String pendingOrigin(@NonNull Port port) {
        return mPending.get(port.id);
    }

    public int portCount() {
        return mPorts.size();
    }

    private void deliverLocal(@NonNull Port port, @NonNull String data,
            @Nullable String origin) {
        try {
            android.webkit.WebMessagePort.WebMessageCallback callback = port.callback;
            if (callback != null) {
                callback.onMessage(null, new android.webkit.WebMessage(data));
            }
            if (mHost != null) {
                mHost.onMessage(port.id, data, origin);
            }
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/message", "deliver threw", t);
        }
    }

    @SuppressWarnings("unused")
    private void bindSession(@NonNull GeckoSession session) {
    }
}
