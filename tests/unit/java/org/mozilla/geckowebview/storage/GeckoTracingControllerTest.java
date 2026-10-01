package org.mozilla.geckowebview.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.webkit.TracingConfig;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

// Unit locks for the TracingController validation contract (decoded from
// CTS TracingControllerTest — mirrors Chromium, which throws instead of
// arming). No device needed: pure argument checks, no Gecko involvement.
// The stop()-delivers-chunks path stays device-locked (needs the CTS
// receiver + executor thread assertions).
public final class GeckoTracingControllerTest {

    @Test
    public void start_nullConfig_throws() {
        try {
            new GeckoTracingController().start(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    // NOTE: start() with a real config is device-covered (CTS
    // TracingControllerTest drives every path). The mockable android.jar
    // builds null configs (Builder.build() returns default null), so the
    // shape rules below lock validateCategories() directly — pure list
    // logic, no framework involved.
    @Test
    public void categories_commaJoined_throws() {
        try {
            GeckoTracingController.validateCategories(
                    Arrays.asList("android_webview, blink"));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void categories_loneExclusion_throws() {
        try {
            GeckoTracingController.validateCategories(
                    Arrays.asList("android_webview", "-blink"));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void categories_exclusionWithBase_allowed() {
        GeckoTracingController.validateCategories(
                Arrays.asList("blink", "-blink"));
        GeckoTracingController.validateCategories(
                Collections.singletonList("android_webview"));
        GeckoTracingController.validateCategories(
                Collections.<String>emptyList());
    }

    @Test
    public void stopWithoutStart_returnsFalse() {
        assertFalse(new GeckoTracingController()
                .stop(new java.io.ByteArrayOutputStream(), Runnable::run));
    }

    @Test
    public void settings_roundTrip() {
        GeckoServiceWorkerController controller =
                new GeckoServiceWorkerController();
        android.webkit.ServiceWorkerWebSettings settings =
                controller.getServiceWorkerWebSettings();
        assertEquals(android.webkit.WebSettings.LOAD_DEFAULT,
                settings.getCacheMode());
        settings.setCacheMode(android.webkit.WebSettings.LOAD_NORMAL);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(true);
        settings.setBlockNetworkLoads(true);
        assertEquals(android.webkit.WebSettings.LOAD_NORMAL,
                settings.getCacheMode());
        assertFalse(settings.getAllowContentAccess());
        assertTrue(settings.getAllowFileAccess());
        assertTrue(settings.getBlockNetworkLoads());
        // Same singleton across calls (Chromium parity).
        assertTrue(settings == controller.getServiceWorkerWebSettings());
    }
}
