package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

// P2 navigation fan-out: main-frame loads go through NavigationBridge
// (shouldOverrideUrlLoading + error mapping); subframe loads go through
// InterceptBridge (allow/deny via app shouldInterceptRequest).
final class P2NavigationDelegate implements GeckoSession.NavigationDelegate {
    private final NavigationBridge mMain;
    private final InterceptBridge mIntercept;

    P2NavigationDelegate(@NonNull NavigationBridge main,
            @NonNull InterceptBridge intercept) {
        mMain = main;
        mIntercept = intercept;
    }

    @Nullable
    @Override
    public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession session,
            @NonNull LoadRequest request) {
        return mMain.onLoadRequest(session, request);
    }

    @Nullable
    @Override
    public GeckoResult<AllowOrDeny> onSubframeLoadRequest(@NonNull GeckoSession session,
            @NonNull LoadRequest request) {
        return mIntercept.onSubframeLoadRequest(session, request);
    }

    @Override
    public void onLocationChange(@NonNull GeckoSession session, @Nullable String url,
            @NonNull java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms,
            @NonNull Boolean hasUserGesture) {
        mMain.onLocationChange(session, url, perms, hasUserGesture);
    }

    @Override
    public void onCanGoBack(@NonNull GeckoSession session, boolean canGoBack) {
        mMain.onCanGoBack(session, canGoBack);
    }

    @Override
    public void onCanGoForward(@NonNull GeckoSession session, boolean canGoForward) {
        mMain.onCanGoForward(session, canGoForward);
    }

    @Nullable
    @Override
    public GeckoResult<String> onLoadError(@NonNull GeckoSession session,
            @NonNull String uri, @NonNull org.mozilla.geckoview.WebRequestError error) {
        return mMain.onLoadError(session, uri, error);
    }
}
