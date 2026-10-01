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
        // CTS WebSettingsTest baseline: NARROW_COLUMNS default (deprecated
        // but frozen), Latin-1 encoding, safe browsing on, file access off.
        assertEquals(2, settings.getLayoutAlgorithm());
        assertEquals("sans-serif", settings.getStandardFontFamily());
        assertEquals("monospace", settings.getFixedFontFamily());
        assertEquals("sans-serif", settings.getSansSerifFontFamily());
        assertEquals("serif", settings.getSerifFontFamily());
        assertEquals("cursive", settings.getCursiveFontFamily());
        assertEquals("fantasy", settings.getFantasyFontFamily());
        assertEquals(8, settings.getMinimumFontSize());
        assertEquals(8, settings.getMinimumLogicalFontSize());
        assertEquals(16, settings.getDefaultFontSize());
        assertEquals(13, settings.getDefaultFixedFontSize());
        assertEquals("Latin-1", settings.getDefaultTextEncodingName());
        assertEquals(1, settings.getMixedContentMode());
        assertTrue(settings.getSafeBrowsingEnabled());
        assertEquals(0, settings.getDisabledActionModeMenuItems());
        assertFalse(settings.getOffscreenPreRaster());
        assertFalse(settings.getJavaScriptCanOpenWindowsAutomatically());
        assertFalse(settings.getAllowFileAccess());
        assertTrue(settings.getAllowContentAccess());
        assertFalse(settings.getAllowUniversalAccessFromFileURLs());
        assertFalse(settings.getAllowFileAccessFromFileURLs());
        assertFalse(settings.getDomStorageEnabled());
        assertFalse(settings.getDatabaseEnabled());
        assertFalse(settings.getSupportMultipleWindows());
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
    public void setters_verbatimRoundTrip() {
        GeckoWebSettings settings = new GeckoWebSettings();
        settings.setLayoutAlgorithm(0);
        settings.setStandardFontFamily("Times");
        settings.setFixedFontFamily("Courier");
        settings.setSansSerifFontFamily("Verdana");
        settings.setSerifFontFamily("Times");
        settings.setCursiveFontFamily("Apple Chancery");
        settings.setFantasyFontFamily("Papyrus");
        settings.setMinimumFontSize(10);
        settings.setMinimumLogicalFontSize(10);
        settings.setDefaultFontSize(10);
        settings.setDefaultFixedFontSize(10);
        settings.setDefaultTextEncodingName("iso-8859-1");
        settings.setMixedContentMode(0);
        settings.setSafeBrowsingEnabled(false);
        settings.setDisabledActionModeMenuItems(7);
        settings.setOffscreenPreRaster(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportMultipleWindows(true);

        assertEquals(0, settings.getLayoutAlgorithm());
        assertEquals("Times", settings.getStandardFontFamily());
        assertEquals("Courier", settings.getFixedFontFamily());
        assertEquals("Verdana", settings.getSansSerifFontFamily());
        assertEquals("Times", settings.getSerifFontFamily());
        assertEquals("Apple Chancery", settings.getCursiveFontFamily());
        assertEquals("Papyrus", settings.getFantasyFontFamily());
        assertEquals(10, settings.getMinimumFontSize());
        assertEquals(10, settings.getMinimumLogicalFontSize());
        assertEquals(10, settings.getDefaultFontSize());
        assertEquals(10, settings.getDefaultFixedFontSize());
        assertEquals("iso-8859-1", settings.getDefaultTextEncodingName());
        assertEquals(0, settings.getMixedContentMode());
        assertFalse(settings.getSafeBrowsingEnabled());
        assertEquals(7, settings.getDisabledActionModeMenuItems());
        assertTrue(settings.getOffscreenPreRaster());
        assertTrue(settings.getJavaScriptCanOpenWindowsAutomatically());
        assertTrue(settings.getAllowFileAccess());
        assertFalse(settings.getAllowContentAccess());
        assertTrue(settings.getAllowUniversalAccessFromFileURLs());
        assertTrue(settings.getAllowFileAccessFromFileURLs());
        assertTrue(settings.getDomStorageEnabled());
        assertTrue(settings.getDatabaseEnabled());
        assertTrue(settings.getSupportMultipleWindows());
    }

    @Test
    public void fontSizes_clampedToOneThroughSeventyTwo() {
        // CTS pins: set(100) reads 72, set(-10) reads 1 (minimum size).
        GeckoWebSettings settings = new GeckoWebSettings();
        settings.setMinimumFontSize(100);
        assertEquals(72, settings.getMinimumFontSize());
        settings.setMinimumFontSize(-10);
        assertEquals(1, settings.getMinimumFontSize());
        settings.setDefaultFixedFontSize(1000);
        assertTrue(settings.getDefaultFixedFontSize() > 13);
        settings.setDefaultFixedFontSize(-10);
        assertEquals(1, settings.getDefaultFixedFontSize());
        settings.setDefaultFontSize(-10);
        assertEquals(1, settings.getDefaultFontSize());
        settings.setMinimumLogicalFontSize(-10);
        assertEquals(1, settings.getMinimumLogicalFontSize());
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
