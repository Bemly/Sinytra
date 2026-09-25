package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

// JVM locks for the download-contract header extraction (the jar round-trip
// and onExternalResponse dispatch are locked by the device download probe).
public class ContentBridgeTest {

    @Test
    public void headerValue_isCaseInsensitive() {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-disposition", "attachment; filename=\"a.bin\"");
        headers.put("CONTENT-TYPE", "application/octet-stream");
        assertEquals("attachment; filename=\"a.bin\"",
                ContentBridge.headerValue(headers, "Content-Disposition"));
        assertEquals("application/octet-stream",
                ContentBridge.headerValue(headers, "Content-Type"));
    }

    @Test
    public void headerValue_nullMapAndMissingNames() {
        assertNull(ContentBridge.headerValue(null, "Content-Type"));
        assertNull(ContentBridge.headerValue(new HashMap<>(), "Content-Type"));
    }
}
