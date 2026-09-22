package org.mozilla.geckowebview.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mozilla.geckoview.GeckoSessionSettings;

// Unit locks for GeckoWebSettings (API_MAPPING.md §5: pure translation
// state — holds values only, no live session). Defaults mirror the
// WebSettings/Chromium baseline the provider must not drift from;
// toSessionSettings locks the initOnly keys mapping (allowJavascript /
// viewportMode / userAgentMode) that only takes effect at session
// construction.
public final class GeckoWebSettingsTest {

    @Test
    public void defaults_matchWebSettingsBaseline() {
        GeckoWebSettings settings = new GeckoWebSettings();
        assertFalse(settings.getJavaScriptEnabled());
        assertFalse(settings.getLoadWithOverviewMode());
        assertTrue(settings.getUseWideViewPort());
        assertTrue(settings.supportZoom());
        assertFalse(settings.getBuiltInZoomControls());
        assertTrue(settings.getDisplayZoomControls());
        assertTrue(settings.getMediaPlaybackRequiresUserGesture());
        assertEquals(100, settings.getTextZoom());
        assertNull(settings.getUserAgentString());
        assertFalse(settings.getDesktopMode());
        assertEquals(-1, settings.getCacheMode());
        assertFalse(settings.getBlockNetworkLoads());
        assertFalse(settings.getBlockNetworkImage());
        assertTrue(settings.getLoadsImagesAutomatically());
    }

    @Test
    public void setters_roundTrip() {
        GeckoWebSettings settings = new GeckoWebSettings();
        settings.setJavaScriptEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setTextZoom(133);
        settings.setUserAgentString("SinytraUA/1.0");
        settings.setDesktopMode(true);
        settings.setCacheMode(2);
        settings.setBlockNetworkLoads(true);
        settings.setBlockNetworkImage(true);
        settings.setLoadsImagesAutomatically(false);

        assertTrue(settings.getJavaScriptEnabled());
        assertTrue(settings.getLoadWithOverviewMode());
        assertFalse(settings.getUseWideViewPort());
        assertFalse(settings.supportZoom());
        assertTrue(settings.getBuiltInZoomControls());
        assertFalse(settings.getDisplayZoomControls());
        assertFalse(settings.getMediaPlaybackRequiresUserGesture());
        assertEquals(133, settings.getTextZoom());
        assertEquals("SinytraUA/1.0", settings.getUserAgentString());
        assertTrue(settings.getDesktopMode());
        assertEquals(2, settings.getCacheMode());
        assertTrue(settings.getBlockNetworkLoads());
        assertTrue(settings.getBlockNetworkImage());
        assertFalse(settings.getLoadsImagesAutomatically());
    }

    @Test
    public void toSessionSettings_javascriptMapsToAllowJavascript()
            throws Exception {
        GeckoWebSettings off = new GeckoWebSettings();
        assertFalse(off.toSessionSettings().getAllowJavascript());

        GeckoWebSettings on = new GeckoWebSettings();
        on.setJavaScriptEnabled(true);
        assertTrue(on.toSessionSettings().getAllowJavascript());
    }

    @Test
    public void toSessionSettings_desktopModeMapsToUserAgentMode()
            throws Exception {
        GeckoWebSettings mobile = new GeckoWebSettings();
        assertEquals(GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
                mobile.toSessionSettings().getUserAgentMode());

        GeckoWebSettings desktop = new GeckoWebSettings();
        desktop.setDesktopMode(true);
        assertEquals(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP,
                desktop.toSessionSettings().getUserAgentMode());
    }

    @Test
    public void toSessionSettings_wideViewportMapsToMobileRendering()
            throws Exception {
        // Wide viewport = mobile rendering (the bridge's chosen mapping —
        // locked here so a change is deliberate, not accidental).
        GeckoWebSettings wide = new GeckoWebSettings();
        assertEquals(GeckoSessionSettings.VIEWPORT_MODE_MOBILE,
                wide.toSessionSettings().getViewportMode());

        GeckoWebSettings narrow = new GeckoWebSettings();
        narrow.setUseWideViewPort(false);
        assertEquals(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP,
                narrow.toSessionSettings().getViewportMode());
    }

    @Test
    public void toSessionSettings_repeatedBuildsAreIndependent()
            throws Exception {
        GeckoWebSettings settings = new GeckoWebSettings();
        settings.setJavaScriptEnabled(true);
        GeckoSessionSettings first = settings.toSessionSettings();
        settings.setJavaScriptEnabled(false);
        GeckoSessionSettings second = settings.toSessionSettings();
        assertTrue(first.getAllowJavascript());
        assertFalse(second.getAllowJavascript());
    }
}
