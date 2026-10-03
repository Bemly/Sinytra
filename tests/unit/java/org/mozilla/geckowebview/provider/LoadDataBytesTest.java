package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
// Unit locks for the loadData byte shaping (pure-Java paths). The base64
// branch calls android.util.Base64.decode (a framework static the
// mockable jar pins to null) and is device-covered instead: CTS
// WebChromeClientTest.testOnConsoleMessage loads base64 data. The routing
// decision (one-shot intercept vs data: URI) needs a live session:
// PostMessageTest on the switched device covers origin identity.
public final class LoadDataBytesTest {

    @Test
    public void charset_encodes() throws Exception {
        assertArrayEquals("hi".getBytes("UTF-8"),
                LoadDataHandler.loadDataBytes("hi", "UTF-8"));
    }

    @Test
    public void nullEncoding_defaultsToUtf8() throws Exception {
        assertArrayEquals("hi".getBytes("UTF-8"),
                LoadDataHandler.loadDataBytes("hi", null));
    }

    @Test
    public void unknownEncoding_fallsBackWithoutThrowing() {
        byte[] bytes =
                LoadDataHandler.loadDataBytes("hi", "no-such-charset");
        assertEquals(2, bytes.length);
    }

    // CTS PostMessageTest baseUrl "http://www.example.com" arrives at
    // necko as "https://www.example.com/" (HSTS upgrade + root slash):
    // the one-shot key must survive both.
    @Test
    public void canonicalKey_foldsSchemeAndRootSlash() {
        assertEquals(LoadDataHandler.canonicalKey("http://www.example.com"),
                LoadDataHandler.canonicalKey("https://www.example.com/"));
    }

    @Test
    public void canonicalKey_lowercasesHostKeepsPath() {
        assertEquals("h.example.com/a?b=c", LoadDataHandler
                .canonicalKey("HTTP://H.EXAMPLE.COM/a?b=c"));
    }

    @Test
    public void canonicalKey_distinctPathsStayDistinct() {
        assertEquals(false, LoadDataHandler.canonicalKey("http://h/a").equals(
                LoadDataHandler.canonicalKey("http://h/b")));
    }

    @Test
    public void canonicalKey_foldsLoneTrailingSlash() {
        assertEquals(LoadDataHandler.canonicalKey("http://h/p"),
                LoadDataHandler.canonicalKey("http://h/p/"));
    }

    // CTS WebSettingsTest image family (data: title ""): the data: path
    // must hand RAW bytes to the byte[] overload (which base64s itself).
    // Percent-encoding here double-encodes the document into literal
    // "%3Chtml%3E..." text — no <title>, no scripts.
    @Test
    public void dataPath_passesRawBytesNotPercentEncoded() throws Exception {
        final byte[][] captured = new byte[1][];
        final String[] capturedMime = new String[1];
        LoadDataHandler.Host host = new LoadDataHandler.Host() {
            @Override
            public void navigateTo(String url) {
                throw new AssertionError("data: path must not navigate");
            }

            @Override
            public void loadDataUri(byte[] data, String mimeType) {
                captured[0] = data;
                capturedMime[0] = mimeType;
            }

            @Override
            public void addInterceptFilter(String prefix) {
                throw new AssertionError("data: path must not filter");
            }

            @Override
            public void logWarning(String message) {
            }

            @Override
            public boolean imagesAllowed() {
                return true;
            }
        };
        String html = "<html><head><title>T</title></head><body>hi</body></html>";
        new LoadDataHandler(host).loadDataWithBaseURL(
                null, html, "text/html", "UTF-8", null);
        assertArrayEquals(html.getBytes("UTF-8"), captured[0]);
        assertEquals("text/html", capturedMime[0]);
    }

    // imagesAllowed=false: the data: document handed to the session keeps
    // the source under a backup name (naturalHeight reads 0 on device;
    // loosening restores it in place).
    @Test
    public void dataPath_relocatesImgSourcesWhenImagesOff() throws Exception {
        final byte[][] captured = new byte[1][];
        LoadDataHandler.Host host = new LoadDataHandler.Host() {
            @Override
            public void navigateTo(String url) {
                throw new AssertionError("data: path must not navigate");
            }

            @Override
            public void loadDataUri(byte[] data, String mimeType) {
                captured[0] = data;
            }

            @Override
            public void addInterceptFilter(String prefix) {
                throw new AssertionError("data: path must not filter");
            }

            @Override
            public void logWarning(String message) {
            }

            @Override
            public boolean imagesAllowed() {
                return false;
            }
        };
        new LoadDataHandler(host).loadDataWithBaseURL(null,
                "<html><body><img src=\"http://h/i.png\"></body></html>",
                "text/html", "UTF-8", null);
        String served = new String(captured[0], "UTF-8");
        assertTrue(served.contains("data-sinytra-src=\"http://h/i.png\""));
        assertTrue(!served.contains(" src="));
    }
}
