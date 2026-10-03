package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Unit locks for the loadData image gate (CTS WebSettingsTest image
// family): with loadsImagesAutomatically=false the provider relocates
// <img> sources to data-sinytra-* up front (never a loading attribute,
// so naturalHeight reads 0) and loosening restores them in place with
// zero navigation. The device side (title "0" then "48"/"1" on the CTS
// pages) is locked by those CTS tests on the switched device.
public final class LoadDataImageSanitizeTest {

    // Exact CTS DATA_URL_IMAGE_HTML shape (whitespace-normalized by hand).
    private static final String CTS_HTML =
            "<html><head><script>function updateTitle(){"
                    + "document.title=document.getElementById('img').naturalHeight;}"
                    + "</script></head><body onload='updateTitle()'>"
                    + "<img id='img' onload='updateTitle()' src='data:image/png;base64,AA'>"
                    + "</body></html>";

    @Test
    public void null_passthrough() {
        assertNull(LoadDataHandler.neutralizeBlockedImages(null));
    }

    @Test
    public void relocatesCtsShape_keepsScriptAndHandlers() {
        String out = LoadDataHandler.neutralizeBlockedImages(CTS_HTML);
        // The source survives verbatim under the backup name (recoverable
        // in place on loosening).
        assertTrue(out.contains("data-sinytra-src='data:image/png;base64,AA'"));
        // No live loading attribute remains.
        assertTrue(!out.contains(" src="));
        // Script + handlers that drive the title update survive verbatim.
        assertTrue(out.contains("function updateTitle()"));
        assertTrue(out.contains("onload='updateTitle()'"));
        assertTrue(out.contains("<img id='img' onload='updateTitle()'"));
    }

    @Test
    public void quotingAndCaseVariants() {
        // The backup name keeps the source spelling (attribute lookup is
        // ASCII case-insensitive, so restore finds it either way).
        assertEquals("<img data-sinytra-SRC=\"a.png\">",
                LoadDataHandler.neutralizeBlockedImages("<img SRC=\"a.png\">"));
        assertEquals("<IMG data-sinytra-src = 'b.png' >",
                LoadDataHandler.neutralizeBlockedImages("<IMG src = 'b.png' >"));
        assertEquals("<img  data-sinytra-src=c.png >",
                LoadDataHandler.neutralizeBlockedImages("<img  src=c.png >"));
        assertEquals("<img alt=\"x\" data-sinytra-src=\"y.png\">",
                LoadDataHandler.neutralizeBlockedImages(
                        "<img alt=\"x\" src=\"y.png\">"));
    }

    @Test
    public void relocatesSrcset_keepsSizes() {
        assertEquals("<img sizes=\"100vw\" data-sinytra-srcset=\"a.png 1x, b.png 2x\">",
                LoadDataHandler.neutralizeBlockedImages(
                        "<img sizes=\"100vw\" srcset=\"a.png 1x, b.png 2x\">"));
    }

    @Test
    public void leavesNonImgTagsAlone() {
        String html = "<script src=\"app.js\"></script>"
                + "<p>src='not-a-tag'</p>"
                + "<image src=\"svg.png\">";
        assertEquals(html, LoadDataHandler.neutralizeBlockedImages(html));
    }

    @Test
    public void imgWithoutSrc_unchanged() {
        String html = "<img id='img'>";
        assertEquals(html, LoadDataHandler.neutralizeBlockedImages(html));
    }

    @Test
    public void idempotent_noDoublePrefix() {
        // A body served twice keeps a single backup prefix (the relocated
        // name is dash-prefixed, never whitespace-prefixed).
        String once = LoadDataHandler.neutralizeBlockedImages(
                "<img src=\"a.png\">");
        assertEquals(once, LoadDataHandler.neutralizeBlockedImages(once));
    }
}
