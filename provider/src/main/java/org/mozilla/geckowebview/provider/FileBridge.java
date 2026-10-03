package org.mozilla.geckowebview.provider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckowebview.settings.GeckoWebSettings;

// file:// documents the Gecko child cannot load itself, served from the
// provider (host-process UID). Two disjoint cases (CTS WebSettingsTest
// file family, STATUS §1x):
//
// - file:///android_asset/* is an Android virtual path with no meaning to
//   necko (literal /android_asset/... ENOENTs silently: onLoadRequest then
//   no PageStart/PageStop/error, history stays blank). Served natively by
//   firefox-patches/0020 (android_asset -> host-APK jar mapping, keeps
//   file:// identity for iframe-href/XHR asserts). NEVER synthesized here.
// - Real-filesystem file:// (e.g. the app's own files dir): the tab child
//   runs as the provider UID and cannot read 0700 app-private paths, so the
//   provider (same UID as the app) reads the bytes and loads them as a
//   data: document. Only when LoadPolicy would allow the navigation
//   (allowFileAccess, mirroring blockNavigation); otherwise fall through
//   to the normal path (LoadRequest DENY handles it, existing behavior).
//   Identity becomes data: (history records the data: URI, same known edge
//   as LoadDataHandler) — the CTS asserts on this path are content asserts
//   (page title text), so this is sufficient. Relative subresources resolve
//   against the data: base and stay broken (documented limitation;
//   datadir-style pages have none).
//
// Pure policy + java.io (no Gecko calls): unit-tested (FileBridgeTest);
// the byte read is device-covered.
final class FileBridge {
    static final String FILE_SCHEME = "file://";
    static final String ANDROID_ASSET_PATH = "/android_asset/";
    // Safety cap: file documents are expected tiny (HTML); anything bigger
    // falls through to the normal path rather than ballooning a data: URI.
    static final long MAX_BYTES = 2L * 1024 * 1024;

    private FileBridge() {}

    /** True for file:///android_asset/* (0020 owns these, never synthesize). */
    static boolean isAndroidAssetUrl(@Nullable String url) {
        if (url == null || !url.regionMatches(true, 0, FILE_SCHEME, 0, 7)) {
            return false;
        }
        String path = stripScheme(url);
        return path != null && path.startsWith(ANDROID_ASSET_PATH);
    }

    /**
     * True when the provider should serve this navigation itself: a
     * real-filesystem file:// URL the policy allows. Android-asset URLs
     * always return false (0020 serves them with file:// identity).
     */
    static boolean shouldServeLocalFile(@Nullable String url,
            @NonNull GeckoWebSettings settings) {
        if (url == null || !url.regionMatches(true, 0, FILE_SCHEME, 0, 7)) {
            return false;
        }
        if (isAndroidAssetUrl(url)) {
            return false;
        }
        return settings.getAllowFileAccess();
    }

    /** Filesystem path for a file:// URL, or null when malformed. */
    @Nullable
    static String filePathOf(@Nullable String url) {
        String path = stripScheme(url);
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        try {
            // %20 etc. must decode; '+' is kept literal (file paths, not
            // query strings) by protecting it through the decode.
            return java.net.URLDecoder.decode(
                    path.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** Minimal extension -> MIME map (file documents in practice are HTML). */
    @NonNull
    static String mimeForPath(@Nullable String path) {
        if (path != null) {
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                return "text/html";
            }
            if (lower.endsWith(".txt")) {
                return "text/plain";
            }
            if (lower.endsWith(".css")) {
                return "text/css";
            }
            if (lower.endsWith(".js")) {
                return "text/javascript";
            }
            if (lower.endsWith(".png")) {
                return "image/png";
            }
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return "image/jpeg";
            }
            if (lower.endsWith(".gif")) {
                return "image/gif";
            }
            if (lower.endsWith(".svg")) {
                return "image/svg+xml";
            }
        }
        return "text/html";
    }

    /**
     * Reads the file, or null when missing/unreadable/too big (caller falls
     * through to the normal load so error surfacing stays unchanged).
     */
    @Nullable
    static byte[] readLocalFile(@Nullable String url) {
        String path = filePathOf(url);
        if (path == null) {
            return null;
        }
        java.io.File file = new java.io.File(path);
        try {
            if (!file.isFile() || file.length() > MAX_BYTES) {
                return null;
            }
            byte[] data = new byte[(int) file.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) {
                        return null;
                    }
                    off += n;
                }
            } finally {
                in.close();
            }
            return data;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    @Nullable
    private static String stripScheme(@Nullable String url) {
        if (url == null || !url.regionMatches(true, 0, FILE_SCHEME, 0, 7)) {
            return null;
        }
        String rest = url.substring(7);
        // file://localhost/path == file:///path (Chromium parity).
        if (rest.regionMatches(true, 0, "localhost", 0, 9)) {
            rest = rest.substring(9);
        }
        return rest;
    }
}
