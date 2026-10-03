package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mozilla.geckowebview.settings.GeckoWebSettings;

// Unit locks for the file:// serving policy (STATUS §1x file family).
// Byte reads touch the device filesystem: device-covered (AllowFileAccess).
public final class FileBridgeTest {

    private static GeckoWebSettings settings(boolean fileAccess) {
        GeckoWebSettings s = new GeckoWebSettings();
        s.setAllowFileAccess(fileAccess);
        return s;
    }

    @Test
    public void nonFileUrlsNeverServed() {
        assertFalse(FileBridge.shouldServeLocalFile(
                "https://example.com/", settings(true)));
        assertFalse(FileBridge.shouldServeLocalFile(
                "data:text/html,hi", settings(true)));
        assertFalse(FileBridge.shouldServeLocalFile(null, settings(true)));
    }

    @Test
    public void androidAssetNeverSynthesized() {
        // 0020 owns file:///android_asset/ (file:// identity for
        // iframe-href/XHR asserts); the provider must not shadow it.
        assertFalse(FileBridge.shouldServeLocalFile(
                "file:///android_asset/webkit/test_hello_world.html",
                settings(true)));
        assertTrue(FileBridge.isAndroidAssetUrl(
                "file:///android_asset/webkit/test_hello_world.html"));
        assertFalse(FileBridge.isAndroidAssetUrl(
                "file:///data/user/0/a/files/datadir.html"));
    }

    @Test
    public void realFileHonorsAllowFileAccess() {
        String url = "file:///data/user/0/a/files/datadir.html";
        assertTrue(FileBridge.shouldServeLocalFile(url, settings(true)));
        assertFalse(FileBridge.shouldServeLocalFile(url, settings(false)));
    }

    @Test
    public void fileLocalhostEqualsTripleSlash() {
        assertTrue(FileBridge.shouldServeLocalFile(
                "file://localhost/data/f.html", settings(true)));
        assertEquals("/data/f.html",
                FileBridge.filePathOf("file://localhost/data/f.html"));
    }

    @Test
    public void percentEscapesDecode() {
        assertEquals("/a b/c.html",
                FileBridge.filePathOf("file:///a%20b/c.html"));
    }

    @Test
    public void malformedUrlsRejected() {
        assertEquals(null, FileBridge.filePathOf(null));
        assertEquals(null, FileBridge.filePathOf("file://host/only.txt"));
        assertEquals(null, FileBridge.filePathOf("file://"));
    }

    @Test
    public void mimeByExtension() {
        assertEquals("text/html",
                FileBridge.mimeForPath("/d/datadir.html"));
        assertEquals("image/png", FileBridge.mimeForPath("/a/tomato.png"));
        assertEquals("text/plain", FileBridge.mimeForPath("/a/note.txt"));
    }
}
