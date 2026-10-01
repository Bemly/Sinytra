package org.mozilla.geckowebview.provider;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.webkit.WebChromeClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.mozilla.geckowebview.storage.GeckoWebIconDatabase;

// Page favicon fetching (CTS WebChromeClientTest.testOnReceivedIcon +
// WebHistoryItemTest icon wait). No GeckoView primitive exists, so the
// provider fetches itself (AGENTS.md §4: allowed self-owned capability):
// after a successful page finish, eval for a link[rel~=icon] URL else
// fall back to <origin>/favicon.ico, download off-UI, decode, store in
// the shared GeckoWebIconDatabase and deliver to the chrome client.
// Failures are silent (no icon is a valid state, never an error).
final class IconFetcher {
    interface Host {
        void evaluate(@NonNull String script,
                @NonNull android.webkit.ValueCallback<String> callback);

        @Nullable
        String pageUrl();

        @Nullable
        WebChromeClient chromeClient();

        @NonNull
        android.webkit.WebView webView();

        @NonNull
        GeckoWebIconDatabase iconDatabase();
    }

    private static final String TAG = "Sinytra/icon";
    private static final ExecutorService FETCH = Executors.newCachedThreadPool();
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_BYTES = 1 << 20;

    // Page-world query: absolute icon URL or "" (no string ops on the
    // Java side — JSON-quoted result included).
    private static final String ICON_QUERY =
            "(function(){try{var l=document.querySelector"
                    + "('link[rel~=icon]');if(l&&l.href){return l.href;}"
                    + "return '';}catch(e){return '';}})()";

    @NonNull
    private final Host mHost;

    IconFetcher(@NonNull Host host) {
        mHost = host;
    }

    void onPageFinished() {
        String page = mHost.pageUrl();
        if (page == null || page.isEmpty() || page.startsWith("about:")
                || page.startsWith("data:")) {
            return;
        }
        try {
            mHost.evaluate(ICON_QUERY, raw -> {
                String link = unjson(raw);
                String target = (link != null && !link.isEmpty())
                        ? link
                        : defaultIconUrl(page);
                if (target == null) {
                    return;
                }
                fetch(target, page);
            });
        } catch (Throwable t) {
            android.util.Log.d(TAG, "icon query not issued", t);
        }
    }

    private void fetch(@NonNull String iconUrl, @NonNull String pageUrl) {
        FETCH.execute(() -> {
            Bitmap bitmap = download(iconUrl);
            if (bitmap == null) {
                return;
            }
            try {
                mHost.iconDatabase().putIcon(pageUrl, bitmap);
            } catch (Throwable t) {
                android.util.Log.d(TAG, "icon store threw", t);
            }
            try {
                WebChromeClient chrome = mHost.chromeClient();
                if (chrome != null) {
                    chrome.onReceivedIcon(mHost.webView(), bitmap);
                }
            } catch (Throwable t) {
                android.util.Log.w(TAG, "onReceivedIcon threw", t);
            }
        });
    }

    @Nullable
    private static String defaultIconUrl(@NonNull String pageUrl) {
        try {
            URL url = new URL(pageUrl);
            String protocol = url.getProtocol();
            if (!"http".equalsIgnoreCase(protocol)
                    && !"https".equalsIgnoreCase(protocol)) {
                return null;
            }
            int port = url.getPort();
            return protocol + "://" + url.getHost()
                    + (port < 0 ? "" : ":" + port) + "/favicon.ico";
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Bitmap download(@NonNull String iconUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(iconUrl)
                    .openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return null;
            }
            String contentType = connection.getContentType();
            if (contentType != null
                    && contentType.startsWith("text/html")) {
                return null;
            }
            try (InputStream in = connection.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_BYTES) {
                        return null;
                    }
                    out.write(buffer, 0, read);
                }
                byte[] bytes = out.toByteArray();
                if (bytes.length == 0) {
                    return null;
                }
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private static String unjson(@Nullable String value) {
        if (value != null && value.length() >= 2
                && value.startsWith("\"") && value.endsWith("\"")) {
            try {
                return value.substring(1, value.length() - 1)
                        .replace("\\\"", "\"").replace("\\\\", "\\");
            } catch (Throwable t) {
                return null;
            }
        }
        return value;
    }
}
