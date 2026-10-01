package org.mozilla.geckowebview.provider;

import androidx.annotation.NonNull;
import java.util.Arrays;

// 0001 response-surface filter table, split out of GeckoWebViewProvider
// (AGENTS.md §7 file-size rule). Holds the app-set prefixes; the
// effective set always carries the universal "http" prefix (matches
// http:// and https://, never data:/about:/file:) so every network
// channel consults the app — full interception retires the P2-4 DENY
// approximation (an app answer always renders, Chromium parity).
// Pure state: unit-testable without a session (InterceptFilterTableTest).
final class InterceptFilterTable {
    static final String UNIVERSAL_PREFIX = "http";

    @NonNull
    private volatile String[] mAppFilters = new String[0];

    void set(@NonNull String[] filters) {
        mAppFilters = filters.clone();
    }

    void add(@NonNull String prefix) {
        String[] current = mAppFilters;
        for (String filter : current) {
            if (prefix.equals(filter)) {
                return;
            }
        }
        String[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = prefix;
        mAppFilters = next;
    }

    @NonNull
    String[] appFilters() {
        return mAppFilters;
    }

    @NonNull
    String[] effective() {
        String[] app = mAppFilters;
        for (String filter : app) {
            if (UNIVERSAL_PREFIX.equals(filter)) {
                return app;
            }
        }
        String[] next = Arrays.copyOf(app, app.length + 1);
        next[app.length] = UNIVERSAL_PREFIX;
        return next;
    }
}
