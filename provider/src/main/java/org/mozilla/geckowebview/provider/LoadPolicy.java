package org.mozilla.geckowebview.provider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckowebview.settings.GeckoWebSettings;

// WebSettings network-policy enforcement (CTS WebSettingsTest images /
// blockNetworkLoads / mixed-mode / file-access families). Pure string +
// flag logic over the 0012 request face (contentPolicyType + trigger
// URI): no Gecko calls, fully JVM-locked (LoadPolicyTest). Values mirror
// dom/base/nsIContentPolicy.idl; mixed-mode ints are WebSettings
// MIXED_CONTENT_*.
//
// Split of duties with the bridges: subresource verdicts feed the 0001
// query path (synthetic empty body, app never consulted — Chromium
// never calls shouldInterceptRequest for policy-blocked loads);
// navigation verdicts feed InterceptBridge.decide (DENY cancels).
final class LoadPolicy {
    // nsIContentPolicy content types we branch on.
    static final int TYPE_SCRIPT = 2;
    static final int TYPE_IMAGE = 3;
    static final int TYPE_SUBDOCUMENT = 7;
    static final int TYPE_FONT = 14;
    static final int TYPE_MEDIA = 15;
    static final int TYPE_IMAGESET = 21;

    private static final String ASSET_PREFIX = "file:///android_asset/";

    private LoadPolicy() {}

    private static String schemeOf(@Nullable String url) {
        if (url == null) {
            return "";
        }
        int end = url.indexOf("://");
        if (end < 0) {
            return "";
        }
        return url.substring(0, end).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isHttpScheme(@NonNull String scheme) {
        return "http".equals(scheme) || "https".equals(scheme);
    }

    /** Displayable mixed content (allowed under COMPATIBILITY_MODE). */
    static boolean isDisplayable(int contentPolicyType) {
        return contentPolicyType == TYPE_IMAGE
                || contentPolicyType == TYPE_IMAGESET
                || contentPolicyType == TYPE_MEDIA;
    }

    /**
     * Subresource verdict for the 0001 query path. True = serve the
     * synthetic empty body without consulting the app.
     */
    static boolean blockSubresource(int contentPolicyType,
            @Nullable String targetUrl, @Nullable String pageUrl,
            @NonNull GeckoWebSettings settings) {
        String targetScheme = schemeOf(targetUrl);
        boolean targetHttp = isHttpScheme(targetScheme);
        boolean targetFile = "file".equals(targetScheme);
        if (!targetHttp && !targetFile) {
            return false;
        }
        if (settings.getBlockNetworkLoads()) {
            return true;
        }
        String pageScheme = schemeOf(pageUrl);
        if (targetFile) {
            // android_asset/ is exempt (CTS: assets load with file
            // access disabled); filesystem file: follows the flag.
            if (targetUrl != null && targetUrl.startsWith(ASSET_PREFIX)) {
                return false;
            }
            return !settings.getAllowFileAccess();
        }
        // File-page cross-scheme fetches (CTS XHR/file-URL families).
        if ("file".equals(pageScheme)
                && !settings.getAllowUniversalAccessFromFileURLs()) {
            return true;
        }
        // Mixed content: http target on an https page. Applies to every
        // type (including images) before the image flags below.
        // Same-scheme https is never mixed, even under NEVER_ALLOW.
        if ("http".equals(targetScheme) && "https".equals(pageScheme)) {
            int mode = settings.getMixedContentMode();
            if (mode == android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW) {
                return true;
            }
            if (mode == android.webkit.WebSettings
                    .MIXED_CONTENT_COMPATIBILITY_MODE
                    && !isDisplayable(contentPolicyType)) {
                return true;
            }
        }
        if (contentPolicyType == TYPE_IMAGE
                || contentPolicyType == TYPE_IMAGESET) {
            return settings.getBlockNetworkImage()
                    || !settings.getLoadsImagesAutomatically();
        }
        return false;
    }

    /**
     * Navigation verdict for the LoadRequest path (main frame or
     * subframe). True = DENY the navigation.
     */
    static boolean blockNavigation(@Nullable String targetUrl,
            boolean isMainFrame, @Nullable String pageUrl,
            @NonNull GeckoWebSettings settings) {
        String targetScheme = schemeOf(targetUrl);
        boolean targetHttp = isHttpScheme(targetScheme);
        if (settings.getBlockNetworkLoads() && targetHttp) {
            return true;
        }
        if (!isMainFrame && "http".equals(schemeOf(targetUrl))
                && "https".equals(schemeOf(pageUrl))) {
            int mode = settings.getMixedContentMode();
            // Frames are active content: blocked under NEVER and COMPAT.
            return mode != android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW;
        }
        if ("file".equals(targetScheme) && !isMainFrame) {
            return !settings.getAllowFileAccess();
        }
        if ("file".equals(targetScheme) && isMainFrame
                && !settings.getAllowFileAccess()
                && (targetUrl == null || !targetUrl.startsWith(ASSET_PREFIX))) {
            return true;
        }
        return false;
    }
}
