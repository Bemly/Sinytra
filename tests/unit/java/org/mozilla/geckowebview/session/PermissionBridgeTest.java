package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.mozilla.geckoview.GeckoSession;

// Unit locks for the P1 permission auto-decision table (Chromium-parity):
// surfaces with no WebView prompt API are decided silently — storage /
// autoplay / EME grant like Chromium WebView does; notifications / XR /
// tracking / storage-access / local-* deny because the embedder surface
// does not exist. Only geolocation needs the app (null = prompt path).
// Assertions use the named constants so a wrong mapping fails here
// instead of shipping as a magic number.
public final class PermissionBridgeTest {

    @Test
    public void geolocation_needsTheApp() {
        assertNull(PermissionBridge.autoDecision(
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION));
    }

    @Test
    public void storageAutoplayAndEme_grantSilently() {
        assertEquals(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW,
                (int) PermissionBridge.autoDecision(
                        GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE));
        assertEquals(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW,
                (int) PermissionBridge.autoDecision(
                        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE));
        assertEquals(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW,
                (int) PermissionBridge.autoDecision(
                        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE));
        assertEquals(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW,
                (int) PermissionBridge.autoDecision(
                        GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS));
    }

    @Test
    public void surfacedWithoutWebViewPromptApi_denySilently() {
        assertEquals(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
                (int) PermissionBridge.autoDecision(
                        GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION));
        assertEquals(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
                (int) PermissionBridge.autoDecision(
                        GeckoSession.PermissionDelegate.PERMISSION_XR));
        assertEquals(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
                (int) PermissionBridge.autoDecision(
                        GeckoSession.PermissionDelegate.PERMISSION_TRACKING));
        assertEquals(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
                (int) PermissionBridge.autoDecision(
                        GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS));
        assertEquals(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
                (int) PermissionBridge.autoDecision(9999));
    }
}
