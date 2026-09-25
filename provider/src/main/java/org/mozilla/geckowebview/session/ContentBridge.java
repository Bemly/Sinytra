package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebResponse;

// Translates GeckoSession.ContentDelegate download-relevant events into
// DownloadListener-shaped callbacks. Bridge only: no state cached here.
public class ContentBridge implements GeckoSession.ContentDelegate {
    public interface Host {
        void onDownloadStart(@NonNull String url, @Nullable String userAgent,
                @Nullable String contentDisposition, @NonNull String mimeType,
                long contentLength);
        void onTitleChanged(@Nullable String title);
        void onFullScreen(boolean fullScreen);
        void onCloseWindow();
        void onFocusRequest();
        void onCrash();
    }

    private final Host mHost;

    public ContentBridge(@NonNull Host host) {
        mHost = host;
    }

    @Override
    public void onTitleChange(@NonNull GeckoSession session, @Nullable String title) {
        try {
            mHost.onTitleChanged(title);
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/content", "Host.onTitleChanged threw", t);
        }
    }

    @Override
    public void onExternalResponse(@NonNull GeckoSession session,
            @NonNull WebResponse response) {
        android.util.Log.d("Sinytra/content",
                "onExternalResponse uri=" + response.uri);
        try {
            String url = response.uri != null ? response.uri : "";
            // The C++ StreamListener visits ALL response headers into the
            // bundle (and synthesizes a content-disposition entry with the
            // suggested filename), so the download contract fields are all
            // here. Chromium passes Content-Disposition through verbatim.
            String mimeType = headerValue(response.headers, "Content-Type");
            String disposition =
                    headerValue(response.headers, "Content-Disposition");
            mHost.onDownloadStart(url, null, disposition,
                    mimeType != null ? mimeType : "application/octet-stream", -1);
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/content", "Host.onDownloadStart threw", t);
        }
    }

    /**
     * Case-insensitive header lookup (header names arrive as the server sent
     * them). Visible for JVM locks. Null-safe: a null map yields null.
     */
    @Nullable
    static String headerValue(
            @Nullable java.util.Map<String, String> headers,
            @NonNull String name) {
        if (headers == null) {
            return null;
        }
        for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public void onFullScreen(@NonNull GeckoSession session, boolean fullScreen) {
        try {
            mHost.onFullScreen(fullScreen);
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/content", "Host.onFullScreen threw", t);
        }
    }

    @Override
    public void onCloseRequest(@NonNull GeckoSession session) {
        try {
            mHost.onCloseWindow();
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/content", "Host.onCloseWindow threw", t);
        }
    }

    @Override
    public void onFocusRequest(@NonNull GeckoSession session) {
        try {
            mHost.onFocusRequest();
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/content", "Host.onFocusRequest threw", t);
        }
    }

    @Override
    public void onCrash(@NonNull GeckoSession session) {
        try {
            mHost.onCrash();
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/content", "Host.onCrash threw", t);
        }
    }
}
