package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// WebMessagePort/postWebMessage channel over the JsBridge transport.
// P2-3 result: GV153 exposes NO message-channel primitive on GeckoSession
// (AAR javap confirmed) — channel traffic rides the built-in WebExtension
// (ARCHITECTURE.md section 6.4): postToPage delivers the payload into page
// context, where the shim dispatches it as a window MessageEvent; page
// replies arrive as JsBridge PageEvents named "port-deliver" and fan out
// to the WebMessageCallback registered on the receiving port.
//
// Port typing, two surfaces over the same bridge ports:
// - Framework-typed (android.webkit.WebMessagePort): live through the
//   same-package subclass android.webkit.SinytraWebMessagePort (the
//   framework ctor is public @SystemApi; the old "package-private" note
//   read the android.jar stub, where @SystemApi is stripped).
//   createWebMessageChannel returns real ports (harness fwPort probe).
// - Boundary-typed (androidx.webkit clients): FULLY functional through
//   CompatSmallBoundaries.LiveMessagePort (post/close/callback all live,
//   verified by boundary round-trip).
//
// Honest gaps (never silently wrong):
// - MessagePorts are logical endpoints, not transferable: the ENTANGLED
//   pair in createWebMessageChannel shares nothing (the JsBridge
//   transport has no transferable-port primitive — a transport gap, not
//   a subclass gap); setCallback/postMessage on a port work end-to-end
//   (page MessageEvent <-> callback) via both surfaces, transfer between
//   frames does not.
// - postToMainFrame fans out to every open port with a callback (Chromium
//   delivers to the page; without frame addressing this is the closest
//   honest mapping). Target-origin filtering is recorded, not enforced —
//   enforcement needs the P2 patch's frame addressing.
public final class MessageBridge {
    public interface Host {
        void onMessage(@NonNull String portId, @NonNull String data,
                @Nullable String origin);
    }

    public static final class Port {
        @NonNull
        public final String id;
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
    @Nullable
    private volatile JsBridge mTransport;

    public MessageBridge(@Nullable Host host) {
        mHost = host;
    }

    // Bind the WebExtension transport. Page replies ("port-deliver"
    // events) fan out to port callbacks here.
    public void setTransport(@Nullable JsBridge bridge) {
        mTransport = bridge;
        if (bridge != null) {
            bridge.addPageEventListener(event -> {
                if ("port-deliver".equals(event.optString("name", ""))) {
                    onPortDeliver(event.optJSONObject("payload"));
                }
            });
        }
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
        JsBridge bridge = mTransport;
        if (bridge != null && bridge.isReady()) {
            bridge.postToPage(port.id, data, targetOrigin);
            return;
        }
        deliverLocal(port, data, targetOrigin);
    }

    public void postToMainFrame(@NonNull String data, @Nullable String targetOrigin) {
        JsBridge bridge = mTransport;
        if (bridge != null && bridge.isReady()) {
            bridge.postToPage("__main__", data, targetOrigin);
        }
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
        port.callback = null;
    }

    @Nullable
    public String pendingOrigin(@NonNull Port port) {
        return mPending.get(port.id);
    }

    public int portCount() {
        return mPorts.size();
    }

    // Page replied on a port (shim re-broadcast it as port-deliver):
    // deliver to the matching port's callback + Host fan-out.
    private void onPortDeliver(@Nullable org.json.JSONObject payload) {
        if (payload == null) {
            return;
        }
        String portId = payload.optString("port", null);
        String data = payload.optString("data", null);
        String origin = payload.optString("origin", null);
        if (portId == null || data == null) {
            return;
        }
        Port port = mPorts.get(portId);
        if (port != null) {
            deliverLocal(port, data, origin);
        } else if (mHost != null) {
            try {
                mHost.onMessage(portId, data, origin);
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/message", "host onMessage threw", t);
            }
        }
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
}
