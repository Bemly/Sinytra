package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

// Unit locks for MessageBridge bookkeeping + honest local fallback (no
// transport bound): channel pairing, close, pendingOrigin, callback and
// host fan-out, postToMainFrame first-callback-only delivery.
//
// Not lockable on the JVM: onPortDeliver routing (the JsBridge dispatch
// entry is private by design) and the full page round-trip — both are
// device-verified by the P0GlueActivity boundaryPort probe (via=page).
public final class MessageBridgeTest {

    private static final class RecordingHost implements MessageBridge.Host {
        final List<String> ids = new ArrayList<>();
        final List<String> data = new ArrayList<>();
        final List<String> origins = new ArrayList<>();

        @Override
        public void onMessage(String portId, String d, String origin) {
            ids.add(portId);
            data.add(d);
            origins.add(origin);
        }
    }


    private static WebMessagePort.WebMessageCallback callback(
            Runnable action) {
        return new WebMessagePort.WebMessageCallback() {
            @Override
            public void onMessage(WebMessagePort port, WebMessage message) {
                action.run();
            }
        };
    }

    @Test
    public void createChannel_pairsTwoUniquePorts() {
        MessageBridge bridge = new MessageBridge(null);
        MessageBridge.Port[] pair = bridge.createChannel();
        assertEquals(2, pair.length);
        assertNotNull(pair[0].id);
        assertNotNull(pair[1].id);
        assertNotEquals(pair[0].id, pair[1].id);
        assertEquals(2, bridge.portCount());
    }

    @Test
    public void close_removesExactlyItsPort() {
        MessageBridge bridge = new MessageBridge(null);
        MessageBridge.Port[] pair = bridge.createChannel();
        bridge.close(pair[0]);
        assertEquals(1, bridge.portCount());
        bridge.close(pair[1]);
        assertEquals(0, bridge.portCount());
    }

    @Test
    public void close_clearsCallbackAndPendingOrigin() {
        MessageBridge bridge = new MessageBridge(null);
        MessageBridge.Port port = bridge.createChannel()[0];
        AtomicInteger delivered = new AtomicInteger();
        bridge.setCallback(port, callback(delivered::incrementAndGet));
        bridge.postMessage(port, "x", "https://example.org");
        assertEquals(1, delivered.get());
        assertEquals("https://example.org", bridge.pendingOrigin(port));
        bridge.close(port);
        assertNull(bridge.pendingOrigin(port));
    }

    @Test
    public void postMessage_withoutTransport_firesCallbackAndHost() {
        RecordingHost host = new RecordingHost();
        MessageBridge bridge = new MessageBridge(host);
        MessageBridge.Port port = bridge.createChannel()[0];
        final String[][] got = new String[1][];
        bridge.setCallback(port, callback(() -> got[0] = new String[] {"cb"}));
        bridge.postMessage(port, "hello", null);
        assertNotNull("callback must fire on local fallback", got[0]);
        assertEquals(1, host.ids.size());
        assertEquals(port.id, host.ids.get(0));
        assertEquals("hello", host.data.get(0));
        assertNull(host.origins.get(0));
    }

    @Test
    public void postMessage_withoutCallback_stillReachesHost() {
        RecordingHost host = new RecordingHost();
        MessageBridge bridge = new MessageBridge(host);
        MessageBridge.Port port = bridge.createChannel()[0];
        bridge.postMessage(port, "bare", null);
        assertEquals(1, host.ids.size());
        assertEquals("bare", host.data.get(0));
    }

    @Test
    public void postToMainFrame_deliversToFirstPortWithCallbackOnly() {
        RecordingHost host = new RecordingHost();
        MessageBridge bridge = new MessageBridge(host);
        MessageBridge.Port[] a = bridge.createChannel();
        MessageBridge.Port[] b = bridge.createChannel();
        List<String> fired = new ArrayList<>();
        bridge.setCallback(a[1], callback(() -> fired.add("a1")));
        bridge.setCallback(b[0], callback(() -> fired.add("b0")));
        bridge.postToMainFrame("frame", null);
        assertEquals("exactly one callback port gets the frame message",
                1, fired.size());
        assertEquals(1, host.ids.size());
        assertEquals("frame", host.data.get(0));
    }

    @Test
    public void setCallback_null_clearsCallback() {
        RecordingHost host = new RecordingHost();
        MessageBridge bridge = new MessageBridge(host);
        MessageBridge.Port port = bridge.createChannel()[0];
        AtomicInteger delivered = new AtomicInteger();
        bridge.setCallback(port, callback(delivered::incrementAndGet));
        bridge.setCallback(port, null);
        bridge.postMessage(port, "host-only", null);
        assertEquals(0, delivered.get());
        assertEquals(1, host.ids.size());
    }

    @Test
    public void callbackReceivesFrameworkWebMessage_withoutThrowing() {
        // deliverLocal constructs an android.webkit.WebMessage (mocked on
        // JVM): the callback contract is "receives a non-null message
        // object" — payload assertion stays on the device harness.
        MessageBridge bridge = new MessageBridge(null);
        MessageBridge.Port port = bridge.createChannel()[0];
        final boolean[] got = {false};
        bridge.setCallback(port, new WebMessagePort.WebMessageCallback() {
            @Override
            public void onMessage(WebMessagePort p, WebMessage m) {
                got[0] = m != null;
            }
        });
        bridge.postMessage(port, "payload", null);
        assertTrue(got[0]);
    }

    @Test
    public void closeIsIdempotent() {
        MessageBridge bridge = new MessageBridge(null);
        MessageBridge.Port[] pair = bridge.createChannel();
        bridge.close(pair[0]);
        bridge.close(pair[0]);
        assertEquals(1, bridge.portCount());
    }
}
