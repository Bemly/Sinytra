package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

// Main/subframe split fan-out: main-frame loads go through NavigationBridge
// (shouldOverrideUrlLoading + error mapping); subframe loads go through
// InterceptBridge (allow/deny via app shouldInterceptRequest). Settings
// policy (blockNetworkLoads, mixed frames, file access) gates BOTH paths
// first — Chromium never consults the app for policy-blocked loads, and
// the main-frame path historically skipped the check entirely (CTS
// testBlockNetworkLoads loaded despite the flag).
final class SplitNavigationDelegate implements GeckoSession.NavigationDelegate {
    private final NavigationBridge mMain;
    private final InterceptBridge mIntercept;
    // Runs after a main-frame policy DENY (may be null: plain DENY then).
    // The bridge synthesizes the failure completion (progress 100 +
    // onReceivedError + onPageFinished) because Gecko fires nothing after
    // a LoadRequest DENY and load-gates would hang forever otherwise.
    @Nullable
    private final Runnable mOnPolicyBlocked;

    SplitNavigationDelegate(@NonNull NavigationBridge main,
            @NonNull InterceptBridge intercept) {
        this(main, intercept, null);
    }

    SplitNavigationDelegate(@NonNull NavigationBridge main,
            @NonNull InterceptBridge intercept,
            @Nullable Runnable onPolicyBlocked) {
        mMain = main;
        mIntercept = intercept;
        mOnPolicyBlocked = onPolicyBlocked;
    }

    @Nullable
    @Override
    public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession session,
            @NonNull LoadRequest request) {
        android.util.Log.d("Sinytra/navigation",
                "onLoadRequest " + request.uri);
        if (request.uri != null && mIntercept.policyBlocksNavigation(
                request.uri, true, request.triggerUri)) {
            android.util.Log.i("Sinytra/navigation",
                    "policy blocks main-frame " + request.uri);
            if (mOnPolicyBlocked != null) {
                try {
                    mOnPolicyBlocked.run();
                } catch (Throwable t) {
                    android.util.Log.w("Sinytra/navigation",
                            "onPolicyBlocked threw", t);
                }
            }
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        return mMain.onLoadRequest(session, request);
    }

    @Nullable
    @Override
    public GeckoResult<AllowOrDeny> onSubframeLoadRequest(@NonNull GeckoSession session,
            @NonNull LoadRequest request) {
        android.util.Log.d("Sinytra/navigation",
                "onSubframeLoadRequest " + request.uri);
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
