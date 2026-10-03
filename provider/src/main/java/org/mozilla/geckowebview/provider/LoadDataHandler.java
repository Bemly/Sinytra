package org.mozilla.geckowebview.provider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// loadData/loadDataWithBaseURL semantics, split out of
// GeckoWebViewProvider (AGENTS.md §7 file-size rule). No framework
// supertype: the unit-test sourceSet cannot see framework-stubs, so this
// helper stays directly testable (LoadDataBytesTest).
//
// Two paths (see the method javadoc): an origin-faithful one-shot
// intercept for http(s) targets, and the legacy data: URI otherwise.
final class LoadDataHandler {
    interface Host {
        void navigateTo(@NonNull String url);

        void loadDataUri(byte[] data, @NonNull String mimeType);

        void addInterceptFilter(@NonNull String prefix);

        void logWarning(@NonNull String message);

        /** WebSettings.getLoadsImagesAutomatically (CTS image family). */
        boolean imagesAllowed();
    }

    static final class OneShotBody {
        final String mimeType;
        final byte[] bytes;

        OneShotBody(String mimeType, byte[] bytes) {
            this.mimeType = mimeType;
            this.bytes = bytes;
        }
    }

    @NonNull
    private final Host mHost;
    // Exact-URL → body, consumed by the first necko query. Internal only.
    @NonNull
    private final Map<String, OneShotBody> mOneShotBodies =
            new ConcurrentHashMap<>();

    LoadDataHandler(@NonNull Host host) {
        mHost = host;
    }

    @Nullable
    OneShotBody consume(@NonNull String url) {
        // Idempotent serve (NOT remove-on-read): the necko layer may
        // query the same document URL more than once (favicon, retry,
        // speculative re-query). Removing on first read lets a duplicate
        // query fall through to network and overwrite the synthesized
        // document with the live site (CTS PostMessage family showed the
        // title never arriving). Repeat serves are harmless (same static
        // body); a newer loadData overwrites the entry. Known edge: a
        // later plain loadUrl of the same URL keeps serving the stale
        // body until overwritten — rare in practice, noted.
        return mOneShotBodies.get(canonicalKey(url));
    }

    void discard(@NonNull String url) {
        mOneShotBodies.remove(canonicalKey(url));
    }

    // Canonical one-shot key: scheme-insensitive, host lowercased, empty
    // path treated as "/". Gecko normalizes the navigated URL before the
    // necko query (root gains a trailing slash) and may HSTS-upgrade
    // http→https without consulting the intercept layer for the http
    // form at all (CTS PostMessageTest: baseUrl "http://www.example.com"
    // arrives as "https://www.example.com/"). An exact-string key misses
    // both and the load falls through to the live network — the test
    // page never exists and every downstream assertion times out. The
    // scheme fold is safe here: one-shot bodies are short-lived internal
    // entries, and serving the same bytes post-upgrade keeps the
    // document (and its base-URL resolution) intact. Honest edge: getUrl
    // reports the upgraded https URL while the app passed http.
    @NonNull
    static String canonicalKey(@NonNull String url) {
        String rest = url;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        int slash = rest.indexOf('/');
        String host = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "/";
        return host.toLowerCase(java.util.Locale.ROOT) + path;
    }

    void loadDataWithBaseURL(@Nullable String baseUrl, @Nullable String data,
            @Nullable String mimeType, @Nullable String encoding,
            @Nullable String historyUrl) {
        String body = data != null ? data : "";
        String type = mimeType != null && !mimeType.isEmpty()
                ? mimeType : "text/html";
        String target = (historyUrl != null && !historyUrl.isEmpty())
                ? historyUrl : baseUrl;
        if (target != null && isHttpUrl(target)) {
            if (historyUrl != null && !historyUrl.isEmpty()
                    && baseUrl != null && !baseUrl.isEmpty()
                    && !historyUrl.equals(baseUrl)) {
                mHost.logWarning("loadDataWithBaseURL: subresources resolve "
                        + "against historyUrl; Chromium uses baseUrl "
                        + "(honest gap)");
            }
            mOneShotBodies.put(canonicalKey(target),
                    new OneShotBody(type, loadDataBytes(body, encoding)));
            mHost.addInterceptFilter(target);
            mHost.navigateTo(target);
            return;
        }
        // Legacy data: URI path (no necko surface for non-http targets).
        // baseUrl still resolves relative URLs via an injected <base>.
        String html = body;
        // Deterministic image gate for loadData documents: data:/file:
        // subresources never consult the 0001 necko hook (nsDataChannel/
        // nsFileChannel have no interception point — 0020's conclusion),
        // and the docshell flag (0024) races content-process lifecycle, so
        // a renderer-dependent block is not observable here. The document
        // bytes are ours, so strip image sources up front when the app
        // disabled images: <img> without src never loads (naturalHeight 0)
        // while body onload still fires the title update (CTS image family:
        // "0"). Only <img> tags are touched — <script src> and page text
        // pass through verbatim. JS-injected images after load are NOT
        // covered (documented gap; no CTS coverage).
        boolean htmlType = type.startsWith("text/html");
        if (!mHost.imagesAllowed() && htmlType) {
            html = neutralizeBlockedImages(body);
        }
        if (baseUrl != null && !baseUrl.isEmpty()
                && type.startsWith("text/html")) {
            html = "<base href=\"" + baseUrl.replace("\"", "%22") + "\">"
                    + html;
        }
        if ("base64".equalsIgnoreCase(encoding)) {
            byte[] decoded = loadDataBytes(body, "base64");
            if (!mHost.imagesAllowed() && htmlType) {
                try {
                    String decodedHtml =
                            new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                    decoded = neutralizeBlockedImages(decodedHtml).getBytes(
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (Throwable t) {
                    mHost.logWarning("loadDataWithBaseURL: image sanitize "
                            + "skipped undecodable base64 body");
                }
            }
            mHost.loadDataUri(decoded, type);
        } else {
            String charset =
                    encoding != null && !encoding.isEmpty() ? encoding : "UTF-8";
            byte[] raw;
            try {
                raw = html.getBytes(charset);
            } catch (java.io.UnsupportedEncodingException e) {
                mHost.logWarning("loadDataWithBaseURL: unknown encoding "
                        + encoding + ", falling back to UTF-8");
                raw = loadDataBytes(html, "UTF-8");
            }
            // Raw bytes, NOT percent-encoded: the byte[] overload builds
            // data:<mime>;base64,<BASE64(bytes)> itself, and base64 bodies
            // are decoded verbatim (percent-escapes are not processed in
            // the base64 form). Percent-encoding here double-encodes the
            // document into literal "%3Chtml%3E..." text — no <title>,
            // no scripts (CTS WebSettingsTest image family: title "").
            mHost.loadDataUri(raw, type);
        }
        if (historyUrl != null && !historyUrl.isEmpty()) {
            mHost.logWarning("loadDataWithBaseURL: historyUrl " + historyUrl
                    + " has no Gecko primitive on the data: path; history "
                    + "records the data: URI");
        }
    }

    static boolean isHttpUrl(@NonNull String url) {
        return url.regionMatches(true, 0, "http://", 0, 7)
                || url.regionMatches(true, 0, "https://", 0, 8);
    }

    // Matches an <img ...> open tag (ASCII case-insensitive; tags never
    // contain a nested '<' or '>' so the negated class cannot run away).
    private static final java.util.regex.Pattern IMG_TAG =
            java.util.regex.Pattern.compile("<img\\b[^<>]*>",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    // Matches one src/srcset assignment inside a tag (double-quoted,
    // single-quoted, or bare value; leading whitespace included so removal
    // leaves no double spaces).
    private static final java.util.regex.Pattern IMG_SOURCE =
            java.util.regex.Pattern.compile(
                    "\\s+(?:src|srcset)\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s\"'`=<>]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Strips src/srcset from <img> tags so the images never load
     * (naturalHeight reads 0) while inline scripts and body onload keep
     * running. Pure string surgery for the loadData path (see caller);
     * null-safe (null in, null out).
     */
    @Nullable
    static String neutralizeBlockedImages(@Nullable String html) {
        if (html == null) {
            return null;
        }
        java.util.regex.Matcher tags = IMG_TAG.matcher(html);
        StringBuffer out = new StringBuffer(html.length());
        while (tags.find()) {
            String tag = tags.group();
            String stripped = IMG_SOURCE.matcher(tag).replaceAll("");
            tags.appendReplacement(out,
                    java.util.regex.Matcher.quoteReplacement(stripped));
        }
        tags.appendTail(out);
        return out.toString();
    }

    @NonNull
    static byte[] loadDataBytes(@NonNull String body,
            @Nullable String encoding) {
        if ("base64".equalsIgnoreCase(encoding)) {
            try {
                return android.util.Base64.decode(body,
                        android.util.Base64.DEFAULT);
            } catch (Throwable t) {
                // Fall through to raw bytes below.
            }
        }
        String charset = encoding != null && !encoding.isEmpty()
                ? encoding : "UTF-8";
        try {
            return body.getBytes(charset);
        } catch (java.io.UnsupportedEncodingException e) {
            try {
                return body.getBytes("UTF-8");
            } catch (java.io.UnsupportedEncodingException impossible) {
                return body.getBytes();
            }
        }
    }
}
