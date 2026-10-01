package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

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
}
