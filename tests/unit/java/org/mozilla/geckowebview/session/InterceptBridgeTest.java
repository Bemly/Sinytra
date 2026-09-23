package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.webkit.WebResourceResponse;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.mozilla.geckoview.GeckoSession;

// Unit locks for InterceptBridge allow/deny semantics (P2-4): the app's
// shouldInterceptRequest answer drives Gecko ALLOW (null result = "not
// handled") or DENY (non-null response — the body-substitution patch is
// pending, so non-null is recorded + denied, never silently swallowed).
// Host exceptions degrade to allow: a throwing WebViewClient must never
// crash the session.
//
// LoadRequest needs reflection (protected ctor + final fields set only
// inside org.mozilla.geckoview); the JVM has no hidden-API guard.
// getUrl() (framework Uri) is stubbed on the JVM and stays device-locked.
//
// NOT JVM-lockable: the DENY return value. GeckoResult's constructor
// needs a live UI Looper (ThreadUtils.getUiHandler in its class init) —
// any GeckoResult instantiation dies on the JVM, so the whole deny path
// is device-locked (and until a deny probe exists in the harness, the
// DENY GeckoResult value is verified nowhere: recorded in STATUS.md).
public final class InterceptBridgeTest {

    private static GeckoSession.NavigationDelegate.LoadRequest loadRequest(
            String uri, boolean isRedirect, boolean hasUserGesture)
            throws Exception {
        java.lang.reflect.Constructor<
                GeckoSession.NavigationDelegate.LoadRequest> ctor =
                GeckoSession.NavigationDelegate.LoadRequest.class
                        .getDeclaredConstructor();
        ctor.setAccessible(true);
        GeckoSession.NavigationDelegate.LoadRequest request = ctor.newInstance();
        set(request, "uri", uri);
        set(request, "triggerUri", null);
        set(request, "target", 0);
        set(request, "isRedirect", isRedirect);
        set(request, "hasUserGesture", hasUserGesture);
        set(request, "isDirectNavigation", false);
        return request;
    }

    private static void set(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class RecordingHost implements InterceptBridge.Host {
        InterceptBridge.SinytraResourceRequest lastRequest;
        final List<String> denies = new ArrayList<>();
        boolean owns;

        @Override
        public WebResourceResponse shouldIntercept(
                InterceptBridge.SinytraResourceRequest request) {
            lastRequest = request;
            return null;
        }

        @Override
        public void onInterceptDeny(String uri, boolean isRedirect,
                boolean hasUserGesture) {
            denies.add(uri + "|" + isRedirect + "|" + hasUserGesture);
        }

        @Override
        public boolean responseSurfaceOwns(String uri) {
            return owns;
        }
    }

    @Test
    public void nullAppResponse_meansAllow() throws Exception {
        RecordingHost host = new RecordingHost();
        InterceptBridge bridge = new InterceptBridge(host);
        assertNull("null response must stay ALLOW (not handled)",
                bridge.onLoadRequest(null,
                        loadRequest("https://example.com/next", false, true)));
        assertNotNull(host.lastRequest);
        assertTrue(host.lastRequest.isForMainFrame());
        assertTrue(host.lastRequest.hasGesture());
        assertTrue(host.denies.isEmpty());
    }

    @Test
    public void subframeLoadRequest_reportsMainFrameFalse() throws Exception {
        RecordingHost host = new RecordingHost();
        InterceptBridge bridge = new InterceptBridge(host);
        bridge.onSubframeLoadRequest(null,
                loadRequest("https://cdn.example/pixel.png", false, false));
        assertNotNull(host.lastRequest);
        assertEquals(false, host.lastRequest.isForMainFrame());
    }

    @Test
    public void hostThrowing_degradesToAllow() throws Exception {
        InterceptBridge bridge = new InterceptBridge(
                new InterceptBridge.Host() {
                    @Override
                    public WebResourceResponse shouldIntercept(
                            InterceptBridge.SinytraResourceRequest request) {
                        throw new IllegalStateException("app client bug");
                    }

                    @Override
                    public void onInterceptDeny(String uri, boolean isRedirect,
                            boolean hasUserGesture) {
                    }

                    @Override
                    public boolean responseSurfaceOwns(String uri) {
                        return false;
                    }
                });
        assertNull("a throwing host must degrade to allow, never crash",
                bridge.onLoadRequest(null,
                        loadRequest("https://example.com/", false, false)));
    }

    @Test
    public void nullUri_shortCircuitsToAllow() throws Exception {
        RecordingHost host = new RecordingHost();
        InterceptBridge bridge = new InterceptBridge(host);
        assertNull(bridge.onLoadRequest(null, loadRequest(null, false, false)));
        assertNull("no request object must reach the app for a null uri",
                host.lastRequest);
    }

    @Test
    public void requestSurface_methodAndHeadersAreHonest() throws Exception {
        RecordingHost host = new RecordingHost();
        InterceptBridge bridge = new InterceptBridge(host);
        bridge.onLoadRequest(null,
                loadRequest("https://example.com/", false, false));
        assertNotNull(host.lastRequest);
        assertEquals("GET", host.lastRequest.getMethod());
        assertTrue(host.lastRequest.getRequestHeaders().isEmpty());
    }

    // --- 0002 Group A: response-surface stand-down ---

    @Test
    public void responseSurfaceOwns_standsDownBeforeConsult()
            throws Exception {
        RecordingHost host = new RecordingHost();
        host.owns = true;
        InterceptBridge bridge = new InterceptBridge(host);
        assertNull("a filter-owned URI must stand down to allow",
                bridge.onSubframeLoadRequest(null,
                        loadRequest("https://body.example/pixel.png", false,
                                false)));
        assertNull("the app must not be consulted for owned URIs",
                host.lastRequest);
        assertTrue("no deny bookkeeping for owned URIs",
                host.denies.isEmpty());
    }

    @Test
    public void responseSurfaceNotOwning_consultsAppNormally()
            throws Exception {
        RecordingHost host = new RecordingHost();
        host.owns = false;
        InterceptBridge bridge = new InterceptBridge(host);
        bridge.onSubframeLoadRequest(null,
                loadRequest("https://example.org/next", false, false));
        assertNotNull("a non-owned URI keeps the P2-4 consult path",
                host.lastRequest);
    }

    @Test
    public void filterPrefixMatching_mirrorsCppTable() {
        String[] filters = {"https://body.example/", "https://cdn.other/x"};
        assertTrue(InterceptBridge.matchesFilterPrefix(
                "https://body.example/pixel.png", filters));
        assertTrue(InterceptBridge.matchesFilterPrefix(
                "https://body.example/", filters));
        // Literal spec prefix, exactly like the C++ StringBeginsWith.
        // The trailing "/" in a filter anchors the host boundary:
        // "body.example.evil.com" does NOT start with "body.example/".
        // Filters registered WITHOUT the trailing slash would leak to
        // sibling hosts — the provider contract requires them; Java and
        // C++ must change semantics in the same commit if ever tightened.
        assertFalse(InterceptBridge.matchesFilterPrefix(
                "https://body.example.evil.com/", filters));
        assertFalse(InterceptBridge.matchesFilterPrefix(
                "https://evil.body.example/", filters));
        assertFalse(InterceptBridge.matchesFilterPrefix(
                "https://example.org/", filters));
        assertFalse(InterceptBridge.matchesFilterPrefix(
                "https://body.example/", new String[0]));
        assertFalse(InterceptBridge.matchesFilterPrefix(
                "https://body.example/", new String[] {null, ""}));
    }

    // --- 0002 Group B: request-surface fidelity ---

    @Test
    public void queryApp_requestSurfacePassthrough() throws Exception {
        RecordingHost host = new RecordingHost();
        InterceptBridge bridge = new InterceptBridge(host);
        bridge.queryApp("https://body.example/sub-target", false, false,
                false, "POST",
                new String[] {"Content-Type:application/json",
                              "X-Custom:a:b"});
        assertNotNull(host.lastRequest);
        assertEquals("POST", host.lastRequest.getMethod());
        assertEquals("application/json",
                host.lastRequest.getRequestHeaders().get("Content-Type"));
        assertEquals("values keep inner colons (split on FIRST colon)",
                "a:b", host.lastRequest.getRequestHeaders().get("X-Custom"));
    }

    @Test
    public void queryApp_defaultOverloadKeepsGetEmpty() throws Exception {
        RecordingHost host = new RecordingHost();
        InterceptBridge bridge = new InterceptBridge(host);
        bridge.queryApp("https://example.org/", false, false, true);
        assertNotNull(host.lastRequest);
        assertEquals("LoadRequest-shaped consult stays GET/empty",
                "GET", host.lastRequest.getMethod());
        assertTrue(host.lastRequest.getRequestHeaders().isEmpty());
    }

    @Test
    public void requestHeaders_malformedPairsSkipped() throws Exception {
        RecordingHost host = new RecordingHost();
        InterceptBridge bridge = new InterceptBridge(host);
        bridge.queryApp("https://example.org/", false, false, false, "GET",
                new String[] {"nocolon", ":emptyname", "OK:1"});
        java.util.Map<String, String> headers =
                host.lastRequest.getRequestHeaders();
        assertEquals(1, headers.size());
        assertEquals("1", headers.get("OK"));
    }
}
