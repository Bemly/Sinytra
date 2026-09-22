package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;
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
}
