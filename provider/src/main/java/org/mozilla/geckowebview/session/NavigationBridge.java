package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebRequestError;

// Translates GeckoSession.NavigationDelegate into WebViewClient-shaped callbacks.
// Bridge only: no state cached here (ARCHITECTURE.md §3, AGENTS.md §2).
public class NavigationBridge implements GeckoSession.NavigationDelegate {
    public interface Host {
        void onUrlChanged(@NonNull String url);
        void onCanGoBackChanged(boolean canGoBack);
        void onCanGoForwardChanged(boolean canGoForward);
        // Maps to WebViewClient.shouldOverrideUrlLoading: true = host handles it, DENY the load.
        boolean shouldOverrideUrlLoading(@NonNull String url);
        void onLoadError(int errorCode, @NonNull String description,
                @Nullable String failingUrl);
    }

    private final Host mHost;

    public NavigationBridge(@NonNull Host host) {
        mHost = host;
    }

    @Nullable
    @Override
    public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession session,
            @NonNull LoadRequest request) {
        if (request.uri != null && mHost.shouldOverrideUrlLoading(request.uri)) {
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        return null;
    }

    @Override
    public void onLocationChange(@NonNull GeckoSession session, @Nullable String url,
            @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> perms,
            @NonNull Boolean hasUserGesture) {
        if (url != null) {
            mHost.onUrlChanged(url);
        }
    }

    @Override
    public void onCanGoBack(@NonNull GeckoSession session, boolean canGoBack) {
        mHost.onCanGoBackChanged(canGoBack);
    }

    @Override
    public void onCanGoForward(@NonNull GeckoSession session, boolean canGoForward) {
        mHost.onCanGoForwardChanged(canGoForward);
    }

    @Nullable
    @Override
    public GeckoResult<String> onLoadError(@NonNull GeckoSession session,
            @NonNull String uri, @NonNull WebRequestError error) {
        try {
            mHost.onLoadError(ErrorBridge.toWebViewErrorCode(error.code),
                    ErrorBridge.describe(error.code), uri);
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/navigation", "Host.onLoadError threw", t);
        }
        return null;
    }
}
