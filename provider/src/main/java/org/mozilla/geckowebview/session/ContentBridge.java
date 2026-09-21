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
        try {
            String url = response.uri != null ? response.uri : "";
            String mimeType = response.headers != null
                    ? response.headers.get("Content-Type") : null;
            mHost.onDownloadStart(url, null, null,
                    mimeType != null ? mimeType : "application/octet-stream", -1);
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/content", "Host.onDownloadStart threw", t);
        }
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
