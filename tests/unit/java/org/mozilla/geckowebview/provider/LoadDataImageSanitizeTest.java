package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Unit locks for the loadData image gate (CTS WebSettingsTest image
// family): with loadsImagesAutomatically=false the provider strips <img>
// sources up front (data: subresources never consult the necko hook).
// The device side (title "0" on the CTS pages) is locked by those CTS
// tests on the switched device.
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
    public void stripsCtsShape_keepsScriptAndHandlers() {
        String out = LoadDataHandler.neutralizeBlockedImages(CTS_HTML);
        // No image source remains anywhere.
        assertTrue(!out.contains("data:image"));
        // Script + handlers that drive the title update survive verbatim.
        assertTrue(out.contains("function updateTitle()"));
        assertTrue(out.contains("onload='updateTitle()'"));
        assertTrue(out.contains("<img id='img' onload='updateTitle()'"));
    }

    @Test
    public void quotingAndCaseVariants() {
        assertEquals("<img>",
                LoadDataHandler.neutralizeBlockedImages("<img SRC=\"a.png\">"));
        assertEquals("<IMG >",
                LoadDataHandler.neutralizeBlockedImages("<IMG src = 'b.png' >"));
        assertEquals("<img >",
                LoadDataHandler.neutralizeBlockedImages("<img  src=c.png >"));
        assertEquals("<img alt=\"x\">",
                LoadDataHandler.neutralizeBlockedImages(
                        "<img alt=\"x\" src=\"y.png\">"));
    }

    @Test
    public void stripsSrcset_keepsSizes() {
        assertEquals("<img sizes=\"100vw\">",
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
}
