package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebRequestInfo;
import org.mozilla.geckoview.WebResponse;

// ResponseBridge: maps the app's shouldInterceptRequest answer onto the
// GeckoView ResponseDelegate (Sinytra firefox-patches/0001) so a non-null
// app response becomes a synthesized body with URL identity preserved —
// instead of the P2-4 deny fallback. Runs on whatever thread the Gecko
// query resolves on; since 0003 the app's InputStream passes through
// unchanged — Gecko reads it incrementally over JNI (the query runs in
// the app process, so the stream crosses a JNI boundary, not a process
// one; the retired 16MB base64 cap was solving a non-problem).
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

        // 0003: stream the app body through unchanged — Gecko reads it
        // incrementally over JNI (GeckoViewInputStream via the
        // GeckoViewResponseStreams registry), so no drain and no size cap
        // (Chromium streams shouldInterceptRequest bodies the same way).
        // Close semantics stay with the Gecko-side wrapper.
        String contentType = app.mimeType;
        if (contentType != null && app.encoding != null) {
            contentType = contentType + "; charset=" + app.encoding;
        }

        final WebResponse.Builder builder =
                new WebResponse.Builder(info.uri).statusCode(app.statusCode);
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        builder.body(app.body);
        return GeckoResult.fromValue(builder.build());
    }
}
