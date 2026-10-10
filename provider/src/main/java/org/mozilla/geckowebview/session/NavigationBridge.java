package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.webkit.WebResourceRequest;
import java.util.List;
import org.mozilla.geckowebview.runtime.SinytraLog;
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
        // API-24+ contract: the framework consults the Request overload;
        // its default implementation forwards to the legacy string overload,
        // so legacy-only apps are covered without a second consult. Consulting
        // the string overload DIRECTLY is wrong: it fires legacy take-over
        // impls that return true for every https URL (device-verified
        // 2026-10-10, WEBVIEW TEST: string impl reloads via r0 + true,
        // Request impl returns false for non-redirect main-frame loads).
        // Method/headers are GET/empty here: LoadRequest carries no
        // request-body surface (0002 info lives on the necko consult path) —
        // apps branching on them in shouldOverride are a documented gap.
        boolean shouldOverrideRequest(@NonNull WebResourceRequest request);

        // Fired when the host takes over a URL (shouldOverrideUrlLoading true →
        // LoadRequest DENY). The load produces no PageStart by design — commit
        // guards must disarm here, or they would re-issue a URL the app took
        // over. Default no-op so existing Hosts are unaffected.
        default void onAppOverrideDeny(@NonNull String url) {
        }
        // Post-commit redirect leg (main frame only): a redirect LoadRequest
        // for a new document after a commit. The bridge may arm a bounded
        // resume watch for the target — a redirect target whose commit never
        // arrives otherwise stalls silently forever (device-verified S3:
        // cross-origin switch drops session listeners, fission class).
        void onRedirectLeg(@NonNull String uri, boolean isRedirect,
                @Nullable String triggerUri);
        // sslPrimaryError: SslError primary constant when the failure was an
        // SSL cert error (drives onReceivedSslError), -1 otherwise.
        void onLoadError(int errorCode, int sslPrimaryError, @NonNull String description,
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
        if (request.uri != null && mHost.shouldOverrideRequest(
                new InterceptBridge.SinytraResourceRequest(request.uri,
                        true, request.isRedirect, request.hasUserGesture))) {
            SinytraLog.d("Sinytra/navigation",
                    "app override DENY " + request.uri);
            try {
                mHost.onAppOverrideDeny(request.uri);
            } catch (Throwable t) {
                SinytraLog.w("Sinytra/navigation",
                        "Host.onAppOverrideDeny threw", t);
            }
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        if (request.uri != null) {
            try {
                mHost.onRedirectLeg(request.uri, request.isRedirect,
                        request.triggerUri);
            } catch (Throwable t) {
                SinytraLog.w("Sinytra/navigation",
                        "Host.onRedirectLeg threw", t);
            }
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
        SinytraLog.d("Sinytra/navigation", "loadError gecko=" + error.code
                + " webview=" + ErrorBridge.toWebViewErrorCode(error.code)
                + " url=" + uri);
        try {
            mHost.onLoadError(ErrorBridge.toWebViewErrorCode(error.code),
                    ErrorBridge.toSslPrimaryError(error.code),
                    ErrorBridge.describe(error.code), uri);
        } catch (Throwable t) {
            SinytraLog.w("Sinytra/navigation", "Host.onLoadError threw", t);
        }
        return null;
    }
}
