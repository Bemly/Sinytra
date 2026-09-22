package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.webkit.JavascriptInterface;
import org.junit.Test;

// Unit locks for JavascriptBridge reflection + bookkeeping (no transport
// bound — the bridge stays usable per its documented contract):
// only @JavascriptInterface methods are exposed (by-arity keys plus a
// simple-name alias), add/remove bookkeeping works without a transport,
// and invoke() resolves by arity then simple name and honestly throws
// NoSuchMethodException on a miss. Page->Java dispatch (onIfaceCall) is
// driven end-to-end by the device harness.
public final class JavascriptBridgeTest {

    @SuppressWarnings("unused")
    public static class Server {
        @JavascriptInterface
        public String echo(String s) {
            return s;
        }

        // Overload resolved by arity.
        @JavascriptInterface
        public String echo(String a, String b) {
            return a + b;
        }

        @JavascriptInterface
        public int ping() {
            return 7;
        }

        // NOT annotated: must never be exposed.
        public String secret() {
            return "leaked";
        }
    }

    @Test
    public void inspect_exposesOnlyAnnotatedMethods_byArityAndSimpleKey() {
        JavascriptBridge inspected = JavascriptBridge.inspect(new Server());
        assertTrue(inspected.methods.containsKey("echo/1"));
        assertTrue(inspected.methods.containsKey("echo/2"));
        assertTrue("simple-name alias must exist for resolution",
                inspected.methods.containsKey("echo"));
        assertTrue(inspected.methods.containsKey("ping/0"));
        assertTrue(inspected.methods.containsKey("ping"));
        for (String key : inspected.methods.keySet()) {
            assertTrue("unannotated method must not be exposed: " + key,
                    !key.startsWith("secret"));
        }
    }

    @Test
    public void inspect_overloadsResolveByArity() throws Exception {
        JavascriptBridge inspected = JavascriptBridge.inspect(new Server());
        assertEquals("a", inspected.invoke("echo", new Object[] {"a"}));
        assertEquals("ab", inspected.invoke("echo", new Object[] {"a", "b"}));
        assertEquals(7, inspected.invoke("ping", null));
    }

    @Test
    public void invoke_unknownMethod_throwsHonest() throws Exception {
        JavascriptBridge inspected = JavascriptBridge.inspect(new Server());
        try {
            inspected.invoke("nope", null);
            fail("expected NoSuchMethodException");
        } catch (NoSuchMethodException expected) {
            // honest miss
        }
    }

    @Test
    public void addRemoveInterface_bookkeepingWithoutTransport()
            throws Exception {
        JavascriptBridge bridge = new JavascriptBridge();
        bridge.addInterface(new Server(), "Srv");
        assertEquals(1, bridge.interfaceCount());
        assertNotNull(bridge.lookup("Srv"));
        assertEquals("a", bridge.lookup("Srv").invoke("echo", new Object[] {"a"}));
        bridge.removeInterface("Srv");
        assertEquals(0, bridge.interfaceCount());
        assertNull(bridge.lookup("Srv"));
    }

    @Test
    public void addInterface_duplicateName_replaces() {
        JavascriptBridge bridge = new JavascriptBridge();
        bridge.addInterface(new Server(), "Srv");
        Server replacement = new Server();
        bridge.addInterface(replacement, "Srv");
        assertEquals(1, bridge.interfaceCount());
        assertTrue(bridge.lookup("Srv").target == replacement);
    }

    @Test
    public void addInterface_requiresObjectAndName() {
        JavascriptBridge bridge = new JavascriptBridge();
        try {
            bridge.addInterface(new Server(), "");
            fail("expected IllegalArgumentException for empty name");
        } catch (IllegalArgumentException expected) {
            // honest guard
        }
    }

    @Test
    public void removeInterface_unknownName_isNoop() {
        JavascriptBridge bridge = new JavascriptBridge();
        bridge.removeInterface("never-added");
        assertEquals(0, bridge.interfaceCount());
    }
}
