package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebRequestInfo;
import org.mozilla.geckoview.WebResponse;

// ResponseBridge: maps the app's shouldInterceptRequest answer onto the
// GeckoView ResponseDelegate (Sinytra firefox-patches/0001) so a non-null
// app response becomes a synthesized body with URL identity preserved —
// instead of the P2-4 deny fallback. Runs on whatever thread the Gecko
// query resolves on; the app's InputStream is drained eagerly (the C++
// side caps at 16MB and refuses larger bodies).
//
// The filter list is dynamic: the provider re-calls
// GeckoSession.setResponseDelegate whenever the app-visible filter set
// changes, which re-pushes the prefixes to the Gecko interception
// controller.
public final class ResponseBridge implements GeckoSession.ResponseDelegate {
    public interface Host {
        @NonNull
        String[] getFilters();

        @Nullable
        WebResourceResponseHolder shouldIntercept(
                @NonNull WebRequestInfo info);
    }

    /** App answer carrier (mirrors android.webkit.WebResourceResponse). */
    public static final class WebResourceResponseHolder {
        @Nullable
        public final String mimeType;
        @Nullable
        public final String encoding;
        public final int statusCode;
        @Nullable
        public final java.io.InputStream body;

        public WebResourceResponseHolder(@Nullable String mimeType,
                @Nullable String encoding, int statusCode,
                @Nullable java.io.InputStream body) {
            this.mimeType = mimeType;
            this.encoding = encoding;
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private static final int DRAIN_LIMIT = 16 * 1024 * 1024;

    @NonNull
    private final Host mHost;

    public ResponseBridge(@NonNull Host host) {
        mHost = host;
    }

    @Override
    @NonNull
    public String[] getUriFilters() {
        return mHost.getFilters();
    }

    @Override
    @Nullable
    public GeckoResult<WebResponse> onRequestResponse(
            @NonNull GeckoSession session,
            @NonNull WebRequestInfo info) {
        final WebResourceResponseHolder app =
                mHost.shouldIntercept(info);
        if (app == null || app.body == null) {
            // Not handled: the C++ controller resets the interception and
            // the load proceeds normally.
            return null;
        }

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[16384];
        try {
            int read;
            while ((read = app.body.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > DRAIN_LIMIT) {
                    android.util.Log.w("Sinytra/response",
                            "App response body exceeds 16MB cap; refusing");
                    try {
                        app.body.close();
                    } catch (Throwable ignored) {
                    }
                    return null;
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/response", "Body drain failed", t);
            return null;
        } finally {
            try {
                app.body.close();
            } catch (Throwable ignored) {
            }
        }

        String contentType = app.mimeType;
        if (contentType != null && app.encoding != null) {
            contentType = contentType + "; charset=" + app.encoding;
        }

        final WebResponse.Builder builder =
                new WebResponse.Builder(info.uri).statusCode(app.statusCode);
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        builder.body(new java.io.ByteArrayInputStream(out.toByteArray()));
        return GeckoResult.fromValue(builder.build());
    }
}
