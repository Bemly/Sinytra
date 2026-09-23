package org.mozilla.geckowebview.session;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

// InterceptBridge: shouldInterceptRequest alignment surface.
// P2-4 spike result: GeckoView GV153 NavigationDelegate has onLoadRequest
// (ALLOW/DENY only) and onSubframeLoadRequest — NO response-substitution
// primitive (no WebResponse return, no request headers/method access beyond
// LoadRequest.uri/triggerUri/isRedirect/hasUserGesture). Full interception
// (return custom body/status/headers per request) needs a firefox-patch.
// Until then this bridge maps what exists: main-frame + subframe allow/deny
// driven by the app's shouldInterceptRequest, with the deny decision
// recorded for the order-alignment log (P2-6).
public final class InterceptBridge {
    public interface Host {
        @Nullable
        WebResourceResponse shouldIntercept(
                @NonNull SinytraResourceRequest request);

        void onInterceptDeny(@NonNull String uri, boolean isRedirect,
                boolean hasUserGesture);

        /**
         * True when the 0001 necko response surface owns this URI
         * (filters registered + prefix hit): the LoadRequest-level DENY
         * approximation must stand down — the interception controller
         * answers the load with the app-provided body, and denying here
         * would kill the load before the channel is ever claimed
         * (firefox-patches/0002 design §4 Group A).
         */
        boolean responseSurfaceOwns(@NonNull String uri);
    }

    private final Host mHost;

    public InterceptBridge(@NonNull Host host) {
        mHost = host;
    }

    @Nullable
    public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onLoadRequest(
            @NonNull GeckoSession session,
            @NonNull GeckoSession.NavigationDelegate.LoadRequest request) {
        return decide(request.uri, request.isRedirect, request.hasUserGesture,
                true);
    }

    @Nullable
    public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onSubframeLoadRequest(
            @NonNull GeckoSession session,
            @NonNull GeckoSession.NavigationDelegate.LoadRequest request) {
        return decide(request.uri, request.isRedirect, request.hasUserGesture,
                false);
    }

    @Nullable
    private GeckoResult<org.mozilla.geckoview.AllowOrDeny> decide(
            @Nullable String uri, boolean isRedirect, boolean hasUserGesture,
            boolean isMainFrame) {
        if (uri == null) {
            return null;
        }
        if (mHost.responseSurfaceOwns(uri)) {
            // 0001/0002: the necko controller answers this URI with the
            // app body; the P2-4 deny approximation must not fire (it
            // would cancel the load before the channel is claimed).
            android.util.Log.d("Sinytra/intercept",
                    "response surface owns " + uri + "; standing down");
            return null;
        }
        WebResourceResponse appResponse = queryApp(uri, isRedirect,
                hasUserGesture, isMainFrame);
        if (appResponse != null) {
            // P2 patch will substitute this body; today only DENY is
            // expressible, so record + deny (loud, never silently wrong).
            try {
                mHost.onInterceptDeny(uri, isRedirect, hasUserGesture);
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/intercept",
                        "Host.onInterceptDeny threw", t);
            }
            return GeckoResult.fromValue(org.mozilla.geckoview.AllowOrDeny.DENY);
        }
        return null;
    }

    /**
     * Raw app consultation without the DENY bookkeeping: used by
     * ResponseBridge (patch 0001) so a non-null app answer can become a
     * synthesized body instead of a deny. Returns the app's answer or null
     * (allow / not handled / host threw).
     */
    @Nullable
    public WebResourceResponse queryApp(@NonNull String uri,
            boolean isRedirect, boolean hasUserGesture, boolean isMainFrame) {
        // LoadRequest-shaped consult (deny path): GeckoView LoadRequest
        // carries no method/headers, so the WebView-approximation surface
        // keeps the GET/empty defaults.
        return queryApp(uri, isRedirect, hasUserGesture, isMainFrame,
                "GET", new String[0]);
    }

    /**
     * Raw app consultation without the DENY bookkeeping, with the 0002
     * request surface: {@code headerPairs} are "name:value" strings as
     * shipped by the Gecko query (split on the FIRST colon — values may
     * contain ':').
     */
    @Nullable
    public WebResourceResponse queryApp(@NonNull String uri,
            boolean isRedirect, boolean hasUserGesture, boolean isMainFrame,
            @NonNull String method, @NonNull String[] headerPairs) {
        try {
            return mHost.shouldIntercept(
                    new SinytraResourceRequest(uri, isMainFrame, isRedirect,
                            hasUserGesture, method, headerPairs));
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/intercept",
                    "Host.shouldIntercept threw", t);
            return null;
        }
    }

    /**
     * True when {@code uri} starts with any registered prefix filter —
     * the same prefix semantics as the C++ controller's filter table
     * (firefox-patches/0001). Pure function: shared by the provider's
     * Host impl and JVM tests.
     */
    public static boolean matchesFilterPrefix(@NonNull String uri,
            @NonNull String[] filters) {
        for (String filter : filters) {
            if (filter != null && !filter.isEmpty()
                    && uri.startsWith(filter)) {
                return true;
            }
        }
        return false;
    }

    public static final class SinytraResourceRequest implements WebResourceRequest {
        private final String mUri;
        private final boolean mMainFrame;
        private final boolean mRedirect;
        private final boolean mGesture;
        // 0002: request surface from the Gecko query (method + flattened
        // "name:value" header pairs). The LoadRequest-shaped deny path
        // keeps the GET/empty defaults.
        private final String mMethod;
        private final String[] mHeaderPairs;

        SinytraResourceRequest(String uri, boolean mainFrame, boolean redirect,
                boolean gesture) {
            this(uri, mainFrame, redirect, gesture, "GET", new String[0]);
        }

        SinytraResourceRequest(String uri, boolean mainFrame, boolean redirect,
                boolean gesture, String method, String[] headerPairs) {
            mUri = uri;
            mMainFrame = mainFrame;
            mRedirect = redirect;
            mGesture = gesture;
            mMethod = method;
            mHeaderPairs = headerPairs;
        }

        @Override
        public Uri getUrl() {
            try {
                return Uri.parse(mUri);
            } catch (Throwable t) {
                return Uri.EMPTY;
            }
        }

        @Override
        public boolean isForMainFrame() {
            return mMainFrame;
        }

        @Override
        public boolean isRedirect() {
            return mRedirect;
        }

        @Override
        public boolean hasGesture() {
            return mGesture;
        }

        @Override
        public String getMethod() {
            return mMethod;
        }

        @Override
        public Map<String, String> getRequestHeaders() {
            if (mHeaderPairs.length == 0) {
                return Collections.emptyMap();
            }
            // Split on the FIRST colon: values may contain ':'.
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            for (String pair : mHeaderPairs) {
                if (pair == null) {
                    continue;
                }
                int sep = pair.indexOf(':');
                if (sep <= 0) {
                    continue;
                }
                headers.put(pair.substring(0, sep), pair.substring(sep + 1));
            }
            return headers;
        }
    }
}
