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
// - Page-created ports (page.postMessage with transfer, or a port the
//   page itself news up) cannot be entangled: the transport has no
//   transferable-port primitive at the Gecko level (needs MessagePort
//   IPDL plumbing — a P2 firefox-patch). App-to-page transfer IS
//   supported (shim-side stub ports, see handlePort); page-to-app
//   transfer and getPorts stay unimplemented.
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
    // Entangled pairs (createChannel links both ids) + per-port queues:
    // a page round-trip answer routes to the PAIR (Chromium entanglement),
    // queued while the pair has no callback (Chromium queues port
    // messages; setCallback drains in order). The no-transport local
    // fallback below intentionally keeps same-port delivery (unit-locked
    // honest behavior when no page exists at all).
    private final Map<String, String> mPairs = new ConcurrentHashMap<>();
    private final Map<String, java.util.List<Queued>> mQueues =
            new ConcurrentHashMap<>();
    // Java ports handed to the page (postMessageToMainFrame transfer):
    // the Java object is neutered from then on — posts on it, and posts
    // on its pair, route to the page-side stub, never to a Java callback.
    private final java.util.Set<String> mTransferredOut =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final int MAX_QUEUE = 50;

    private static final class Queued {
        @NonNull
        final String data;
        @Nullable
        final String origin;

        Queued(@NonNull String data, @Nullable String origin) {
            this.data = data;
            this.origin = origin;
        }
    }
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
        mPairs.put(a.id, b.id);
        mPairs.put(b.id, a.id);
        return new Port[] {a, b};
    }

    public void setCallback(@NonNull Port port,
            @Nullable android.webkit.WebMessagePort.WebMessageCallback callback) {
        port.callback = callback;
        if (callback != null) {
            drainQueue(port);
        }
    }

    public void postMessage(@NonNull Port port, @NonNull String data,
            @Nullable String targetOrigin) {
        if (targetOrigin != null) {
            mPending.put(port.id, targetOrigin);
        }
        JsBridge bridge = mTransport;
        if (bridge != null && bridge.isReady()) {
            // The pair was handed to the page: deliver to the page-side
            // stub. Posting on a transferred-out (neutered) port itself
            // is a silent no-op (Chromium parity).
            String pair = mPairs.get(port.id);
            if (pair != null && mTransferredOut.contains(pair)) {
                bridge.postToPage(port.id, data, targetOrigin,
                        java.util.Collections.emptyList(), pair);
            } else if (!mTransferredOut.contains(port.id)) {
                bridge.postToPage(port.id, data, targetOrigin);
            }
            return;
        }
        if (!mTransferredOut.contains(port.id)) {
            deliverLocal(port, data, targetOrigin);
        }
    }

    public void postToMainFrame(@NonNull String data, @Nullable String targetOrigin) {
        postToMainFrame(data, targetOrigin,
                java.util.Collections.emptyList());
    }

    public void postToMainFrame(@NonNull String data,
            @Nullable String targetOrigin,
            @NonNull java.util.List<Port> transferred) {
        JsBridge bridge = mTransport;
        if (bridge != null && bridge.isReady()) {
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (Port port : transferred) {
                if (port != null && mPorts.containsKey(port.id)) {
                    ids.add(port.id);
                    // Transfer neuters the Java object (Chromium parity):
                    // from here the page-side stub owns this end.
                    mTransferredOut.add(port.id);
                }
            }
            bridge.postToPage("__main__", data, targetOrigin, ids);
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
        mQueues.remove(port.id);
        mTransferredOut.remove(port.id);
        String pair = mPairs.remove(port.id);
        if (pair != null) {
            mPairs.remove(pair);
        }
        port.callback = null;
    }

    @Nullable
    public String pendingOrigin(@NonNull Port port) {
        return mPending.get(port.id);
    }

    public int portCount() {
        return mPorts.size();
    }

    // Page round-trip answer (shim port-deliver event): route to the
    // ENTANGLED pair (Chromium), queued while the pair has no callback.
    // Direct-post acks land here with the sender id and park on the pair
    // (never loop back to the sender); page-stub posts carry the Java
    // port id whose pair owns the callback. Host fan-out preserved.
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
        String pairId = mPairs.get(portId);
        Port target = pairId != null ? mPorts.get(pairId) : null;
        if (target != null && target.callback != null) {
            deliverLocal(target, data, origin);
            return;
        }
        if (target != null) {
            java.util.List<Queued> queue = mQueues.get(pairId);
            if (queue == null) {
                queue = new java.util.ArrayList<>();
                mQueues.put(pairId, queue);
            }
            queue.add(new Queued(data, origin));
            while (queue.size() > MAX_QUEUE) {
                queue.remove(0);
            }
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

    private void drainQueue(@NonNull Port port) {
        java.util.List<Queued> queue = mQueues.remove(port.id);
        if (queue == null) {
            return;
        }
        for (Queued queued : queue) {
            deliverLocal(port, queued.data, queued.origin);
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
