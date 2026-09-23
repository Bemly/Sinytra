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
        try {
            return mHost.shouldIntercept(
                    new SinytraResourceRequest(uri, isMainFrame, isRedirect,
                            hasUserGesture));
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/intercept",
                    "Host.shouldIntercept threw", t);
            return null;
        }
    }

    public static final class SinytraResourceRequest implements WebResourceRequest {
        private final String mUri;
        private final boolean mMainFrame;
        private final boolean mRedirect;
        private final boolean mGesture;

        SinytraResourceRequest(String uri, boolean mainFrame, boolean redirect,
                boolean gesture) {
            mUri = uri;
            mMainFrame = mainFrame;
            mRedirect = redirect;
            mGesture = gesture;
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
            return "GET";
        }

        @Override
        public Map<String, String> getRequestHeaders() {
            return Collections.emptyMap();
        }
    }
}
