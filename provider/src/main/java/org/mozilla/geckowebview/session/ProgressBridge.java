package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import org.mozilla.geckoview.GeckoSession;

// Translates GeckoSession.ProgressDelegate into WebViewClient/WebChromeClient-shaped
// callbacks. Bridge only: no state cached here (ARCHITECTURE.md §3, AGENTS.md §2).
public class ProgressBridge implements GeckoSession.ProgressDelegate {
    public interface Host {
        void onPageStarted(@NonNull String url);
        void onPageFinished(boolean success);
        void onProgressChanged(int progress);
        void onSecurityChanged(@NonNull SecurityInformation securityInfo);
    }

    private final Host mHost;

    public ProgressBridge(@NonNull Host host) {
        mHost = host;
    }

    @Override
    public void onPageStart(@NonNull GeckoSession session, @NonNull String url) {
        mHost.onPageStarted(url);
    }

    @Override
    public void onPageStop(@NonNull GeckoSession session, boolean success) {
        mHost.onPageFinished(success);
    }

    @Override
    public void onProgressChange(@NonNull GeckoSession session, int progress) {
        mHost.onProgressChanged(progress);
    }

    @Override
    public void onSecurityChange(@NonNull GeckoSession session,
            @NonNull SecurityInformation securityInfo) {
        mHost.onSecurityChanged(securityInfo);
    }
}
