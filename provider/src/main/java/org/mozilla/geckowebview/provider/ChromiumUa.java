package org.mozilla.geckowebview.provider;

import androidx.annotation.NonNull;

// Default User-Agent (user拍板 2026-10-01: Chromium形 + wv).
//
// The engine is Gecko, but the WebView UA slot is a compat surface:
// CTS testUserAgentString_default pins the exact Chromium shape, and
// real-world mobile sites key content on the Chrome token. The Chrome
// version below is a compat token tracking the era (not our engine
// version) — update it when the pinned Chromium generation moves.
// Pure string building: unit-tested (ChromiumUaTest); Build fields are
// read at the call sites so the builder stays JVM-clean.
final class ChromiumUa {
    // Compat token, not an engine claim. Matches the on-device Chromium
    // generation at the time of pinning.
    private static final String CHROME_VERSION = "156.0.0.0";

    private ChromiumUa() {}

    @NonNull
    static String build(@NonNull String release, @NonNull String model,
            @NonNull String buildId) {
        String m = model.isEmpty() ? "Unknown" : model;
        String b = buildId.isEmpty() ? "Unknown" : buildId;
        return "Mozilla/5.0 (Linux; Android " + release + "; " + m
                + " Build/" + b + "; wv) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Version/4.0 Chrome/"
                + CHROME_VERSION + " Mobile Safari/537.36";
    }

    @NonNull
    static String forDevice() {
        return build(android.os.Build.VERSION.RELEASE != null
                        ? android.os.Build.VERSION.RELEASE : "",
                android.os.Build.MODEL != null ? android.os.Build.MODEL : "",
                android.os.Build.ID != null ? android.os.Build.ID : "");
    }
}
