package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.webkit.WebSettings;
import org.junit.Test;
import org.mozilla.geckowebview.settings.GeckoWebSettings;

// Unit locks for network-policy enforcement (decoded from CTS
// WebSettingsTest images/blockNetworkLoads/mixed-mode/file families).
// The bridge wiring (who calls with which URL) is device-locked by
// those same CTS tests on the switched device.
public final class LoadPolicyTest {

    private static GeckoWebSettings defaults() {
        return new GeckoWebSettings();
    }

    @Test
    public void defaults_allowEverything() {
        GeckoWebSettings s = defaults();
        assertFalse(LoadPolicy.blockSubresource(3,
                "http://h/i.png", "http://h/p", s));
        assertFalse(LoadPolicy.blockNavigation("http://h/p", true,
                "https://h/q", s));
    }

    @Test
    public void blockNetworkLoads_blocksHttpEverywhere() {
        GeckoWebSettings s = defaults();
        s.setBlockNetworkLoads(true);
        assertTrue(LoadPolicy.blockSubresource(3,
                "http://h/i.png", "http://h/p", s));
        assertTrue(LoadPolicy.blockNavigation("http://h/p", true, null, s));
        assertTrue(LoadPolicy.blockNavigation("http://h/f", false,
                "http://h/p", s));
        // Non-network URLs never block here.
        assertFalse(LoadPolicy.blockSubresource(3,
                "data:image/png;base64,AA", "http://h/p", s));
    }

    @Test
    public void images_followImageFlags() {
        GeckoWebSettings s = defaults();
        s.setBlockNetworkImage(true);
        assertTrue(LoadPolicy.blockSubresource(3,
                "http://h/i.png", "http://h/p", s));
        // …but data: images always load (never queried; documents intent).
        assertFalse(LoadPolicy.blockSubresource(3,
                "data:image/gif;base64,R0lG", "http://h/p", s));

        GeckoWebSettings off = defaults();
        off.setLoadsImagesAutomatically(false);
        assertTrue(LoadPolicy.blockSubresource(
                LoadPolicy.TYPE_IMAGESET, "http://h/i.png", "http://h/p",
                off));
        // Non-image subresources are unaffected by image flags.
        assertFalse(LoadPolicy.blockSubresource(LoadPolicy.TYPE_SCRIPT,
                "http://h/a.js", "http://h/p", off));
    }

    @Test
    public void mixed_neverBlocksAll() {
        GeckoWebSettings s = defaults();
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        assertTrue(LoadPolicy.blockSubresource(LoadPolicy.TYPE_SCRIPT,
                "http://h/a.js", "https://h/p", s));
        assertTrue(LoadPolicy.blockSubresource(LoadPolicy.TYPE_IMAGE,
                "http://h/i.png", "https://h/p", s));
        assertTrue(LoadPolicy.blockNavigation("http://h/f", false,
                "https://h/p", s));
        // Same-scheme traffic is not mixed.
        assertFalse(LoadPolicy.blockSubresource(LoadPolicy.TYPE_SCRIPT,
                "https://h/a.js", "https://h/p", s));
    }

    @Test
    public void mixed_compatBlocksActiveAllowsDisplay() {
        GeckoWebSettings s = defaults();
        s.setMixedContentMode(
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        assertTrue(LoadPolicy.blockSubresource(LoadPolicy.TYPE_SCRIPT,
                "http://h/a.js", "https://h/p", s));
        assertFalse(LoadPolicy.blockSubresource(LoadPolicy.TYPE_IMAGE,
                "http://h/i.png", "https://h/p", s));
        assertFalse(LoadPolicy.blockSubresource(LoadPolicy.TYPE_MEDIA,
                "http://h/a.mp3", "https://h/p", s));
        assertTrue(LoadPolicy.blockNavigation("http://h/f", false,
                "https://h/p", s));
    }

    @Test
    public void mixed_alwaysAllows() {
        GeckoWebSettings s = defaults();
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        assertFalse(LoadPolicy.blockSubresource(LoadPolicy.TYPE_SCRIPT,
                "http://h/a.js", "https://h/p", s));
        assertFalse(LoadPolicy.blockNavigation("http://h/f", false,
                "https://h/p", s));
    }

    @Test
    public void file_followsFlagWithAssetExemption() {
        GeckoWebSettings s = defaults();
        // Disabled: filesystem blocked, android_asset exempt (CTS).
        assertTrue(LoadPolicy.blockNavigation("file:///sdcard/a.html", true,
                null, s));
        assertFalse(LoadPolicy.blockNavigation("file:///android_asset/a.html",
                true, null, s));
        assertTrue(LoadPolicy.blockSubresource(3, "file:///sdcard/i.png",
                "file:///sdcard/a.html", s));

        GeckoWebSettings on = defaults();
        on.setAllowFileAccess(true);
        assertFalse(LoadPolicy.blockNavigation("file:///sdcard/a.html", true,
                null, on));
    }

    @Test
    public void filePageCrossScheme_gatedByUniversalFlag() {
        GeckoWebSettings s = defaults();
        assertTrue(LoadPolicy.blockSubresource(11, "http://h/x",
                "file:///sdcard/a.html", s));
        GeckoWebSettings on = defaults();
        on.setAllowUniversalAccessFromFileURLs(true);
        assertFalse(LoadPolicy.blockSubresource(11, "http://h/x",
                "file:///sdcard/a.html", on));
    }
}
