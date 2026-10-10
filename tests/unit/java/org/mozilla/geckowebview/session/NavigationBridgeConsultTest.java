package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.webkit.WebResourceRequest;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.lang.reflect.Field;
import org.junit.Test;
import org.mozilla.geckoview.GeckoSession;

// Unit locks for the shouldOverride consult overload (API-24+ contract):
// the app must be consulted via the WebResourceRequest overload — never the
// legacy string overload directly. The string overload's default is reached
// only through the Request overload's own forwarding, so legacy-only apps
// stay covered with a single consult (device-verified 2026-10-10:
// consulting the string overload fires WEBVIEW TEST's legacy take-over impl
// — reload + true for every https URL — and blacks the page, while the
// Request overload returns false for non-redirect main-frame loads).
//
// LoadRequest needs reflection (protected ctor; same pattern as
// InterceptBridgeTest). The DENY return value stays device-locked:
// GeckoResult's class init needs a live UI Looper.
public final class NavigationBridgeConsultTest {

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

    private static final class RecordingHost implements NavigationBridge.Host {
        WebResourceRequest lastRequest;
        boolean override;

        @Override
        public void onUrlChanged(@NonNull String url) {
        }

        @Override
        public void onCanGoBackChanged(boolean canGoBack) {
        }

        @Override
        public void onCanGoForwardChanged(boolean canGoForward) {
        }

        @Override
        public boolean shouldOverrideRequest(WebResourceRequest request) {
            lastRequest = request;
            return override;
        }

        @Override
        public void onRedirectLeg(@NonNull String uri, boolean isRedirect,
                @Nullable String triggerUri) {
        }

        @Override
        public void onLoadError(int errorCode, int sslPrimaryError,
                @NonNull String description, @Nullable String failingUrl) {
        }
    }

    @Test
    public void allow_consultsRequestOverloadWithMainFrameFacade()
            throws Exception {
        RecordingHost host = new RecordingHost();
        NavigationBridge bridge = new NavigationBridge(host);
        assertNull("allow must return null (ALLOW)",
                bridge.onLoadRequest(null,
                        loadRequest("https://bing.com/", false, false)));
        assertNotNull(host.lastRequest);
        // getUrl() (framework Uri.parse) is stubbed to null on the JVM
        // (same device-lock as InterceptBridgeTest) — the URI string reach
        // is covered by the device harness (shouldOverride url= log line).
        assertTrue(host.lastRequest.isForMainFrame());
        assertFalse(host.lastRequest.isRedirect());
        assertFalse(host.lastRequest.hasGesture());
        assertEquals("GET", host.lastRequest.getMethod());
        assertTrue(host.lastRequest.getRequestHeaders().isEmpty());
    }

    @Test
    public void redirectFlag_reachesAppFacade() throws Exception {
        RecordingHost host = new RecordingHost();
        NavigationBridge bridge = new NavigationBridge(host);
        assertNull(bridge.onLoadRequest(null,
                loadRequest("https://cn.bing.com/", true, false)));
        assertNotNull(host.lastRequest);
        assertTrue(host.lastRequest.isRedirect());
        assertTrue(host.lastRequest.isForMainFrame());
    }

    @Test
    public void gestureFlag_reachesAppFacade() throws Exception {
        RecordingHost host = new RecordingHost();
        NavigationBridge bridge = new NavigationBridge(host);
        assertNull(bridge.onLoadRequest(null,
                loadRequest("https://example.com/", false, true)));
        assertNotNull(host.lastRequest);
        assertTrue(host.lastRequest.hasGesture());
    }
}
