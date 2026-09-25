package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.webkit.SinytraWebMessagePort;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;

import org.junit.Test;

// Locks for the framework-typed WebMessagePort (android.webkit.
// SinytraWebMessagePort): Chromium-parity closed-port/callback-once
// semantics, port-identity delivery (onMessage receives the port itself,
// not null), and never-propagating callback exceptions. Page round-trip
// stays a device harness lock (fwPort probe — transport needs a live
// Gecko session).
public class SinytraWebMessagePortTest {

    private static final class RecordingBinding
            implements SinytraWebMessagePort.Binding {
        String posted;
        boolean closed;
        WebMessagePort.WebMessageCallback callback;

        @Override
        public void post(String data) {
            posted = data;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void setCallback(WebMessagePort.WebMessageCallback callback) {
            this.callback = callback;
        }
    }

    @Test
    public void postMessage_routesDataIntoBinding() {
        RecordingBinding binding = new RecordingBinding();
        SinytraWebMessagePort port = new SinytraWebMessagePort(binding);
        port.postMessage(new WebMessage("hello"));
        // Mockable-jar limit: WebMessage.getData() is stubbed to null, so
        // the route lands with the null-safe "" fallback. The lock here is
        // the ROUTE + closed-port not touched; data content is device-
        // locked end-to-end by the harness fwPort probe.
        assertEquals("", binding.posted);
        assertFalse(binding.closed);
    }

    @Test
    public void close_routesOnce_andIsIdempotent() {
        RecordingBinding binding = new RecordingBinding();
        SinytraWebMessagePort port = new SinytraWebMessagePort(binding);
        port.close();
        port.close();
        assertTrue(binding.closed);
    }

    @Test
    public void postMessage_afterClose_throwsIllegalState() {
        SinytraWebMessagePort port =
                new SinytraWebMessagePort(new RecordingBinding());
        port.close();
        try {
            port.postMessage(new WebMessage("late"));
            fail("postMessage after close must throw");
        } catch (IllegalStateException expected) {
            // Chromium parity
        }
    }

    @Test
    public void setWebMessageCallback_afterClose_throwsIllegalState() {
        SinytraWebMessagePort port =
                new SinytraWebMessagePort(new RecordingBinding());
        port.close();
        try {
            port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() { });
            fail("setWebMessageCallback after close must throw");
        } catch (IllegalStateException expected) {
            // Chromium parity
        }
    }

    @Test
    public void secondNonNullSetCallback_throwsIllegalState() {
        SinytraWebMessagePort port =
                new SinytraWebMessagePort(new RecordingBinding());
        port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() { });
        try {
            port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() { });
            fail("second setWebMessageCallback must throw");
        } catch (IllegalStateException expected) {
            // Chromium parity
        }
    }

    @Test
    public void onMessage_receivesThePortItself_notNull() {
        RecordingBinding binding = new RecordingBinding();
        SinytraWebMessagePort port = new SinytraWebMessagePort(binding);
        final WebMessagePort[] gotPort = new WebMessagePort[1];
        final String[] gotData = new String[1];
        port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
            @Override
            public void onMessage(WebMessagePort p, WebMessage message) {
                gotPort[0] = p;
                gotData[0] = message.getData();
            }
        });
        // MessageBridge delivers with a null port surface; the wrapper
        // must substitute the framework port. Data content stays a device
        // lock (mockable WebMessage.getData() is stubbed to null).
        binding.callback.onMessage(null, new WebMessage("payload"));
        assertSame(port, gotPort[0]);
    }

    @Test
    public void nullCallback_clearsBindingCallback_andAllowsReset() {
        RecordingBinding binding = new RecordingBinding();
        SinytraWebMessagePort port = new SinytraWebMessagePort(binding);
        // The binding receives the WRAPPER (by design — it substitutes the
        // port identity), so only null/non-null transitions are lockable.
        port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() { });
        assertTrue(binding.callback != null);
        port.setWebMessageCallback(null);
        assertNull(binding.callback);
        port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() { });
        assertTrue(binding.callback != null);
    }

    @Test
    public void callbackThrow_neverPropagates() {
        RecordingBinding binding = new RecordingBinding();
        SinytraWebMessagePort port = new SinytraWebMessagePort(binding);
        port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
            @Override
            public void onMessage(WebMessagePort p, WebMessage message) {
                throw new RuntimeException("app bug");
            }
        });
        // App callback exceptions are delegate exceptions: degrade + log,
        // never propagate into the transport.
        binding.callback.onMessage(null, new WebMessage("x"));
    }
}
