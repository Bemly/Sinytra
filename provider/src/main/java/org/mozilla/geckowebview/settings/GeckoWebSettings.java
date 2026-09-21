package org.mozilla.geckowebview.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoSessionSettings;

// WebSettings-shaped state holder for P0. Pure translation target: AOSP
// WebSettings ↔ GeckoSessionSettings (API_MAPPING.md §5). Holds values only;
// applying them to a live GeckoSession happens in GeckoWebViewProvider.
// No android.webkit.WebSettings inheritance yet — that arrives with the
// framework-stubs wiring when GeckoWebViewProvider implements WebViewProvider.
public final class GeckoWebSettings {
    private boolean mJavaScriptEnabled;
    private boolean mLoadWithOverviewMode;
    private boolean mUseWideViewPort = true;
    private boolean mSupportZoom = true;
    private boolean mBuiltInZoomControls;
    private boolean mDisplayZoomControls = true;
    private boolean mMediaPlaybackRequiresUserGesture = true;
    private int mTextZoom = 100;
    @Nullable
    private String mUserAgentString;
    private boolean mDesktopMode;
    private int mCacheMode = -1;
    private boolean mBlockNetworkLoads;
    private boolean mBlockNetworkImage;
    private boolean mLoadsImagesAutomatically = true;

    public boolean getJavaScriptEnabled() {
        return mJavaScriptEnabled;
    }

    public void setJavaScriptEnabled(boolean flag) {
        mJavaScriptEnabled = flag;
    }

    public boolean getLoadWithOverviewMode() {
        return mLoadWithOverviewMode;
    }

    public void setLoadWithOverviewMode(boolean overview) {
        mLoadWithOverviewMode = overview;
    }

    public boolean getUseWideViewPort() {
        return mUseWideViewPort;
    }

    public void setUseWideViewPort(boolean use) {
        mUseWideViewPort = use;
    }

    public boolean supportZoom() {
        return mSupportZoom;
    }

    public void setSupportZoom(boolean support) {
        mSupportZoom = support;
    }

    public boolean getBuiltInZoomControls() {
        return mBuiltInZoomControls;
    }

    public void setBuiltInZoomControls(boolean enabled) {
        mBuiltInZoomControls = enabled;
    }

    public boolean getDisplayZoomControls() {
        return mDisplayZoomControls;
    }

    public void setDisplayZoomControls(boolean enabled) {
        mDisplayZoomControls = enabled;
    }

    public boolean getMediaPlaybackRequiresUserGesture() {
        return mMediaPlaybackRequiresUserGesture;
    }

    public void setMediaPlaybackRequiresUserGesture(boolean require) {
        mMediaPlaybackRequiresUserGesture = require;
    }

    public int getTextZoom() {
        return mTextZoom;
    }

    public void setTextZoom(int textZoom) {
        mTextZoom = textZoom;
    }

    @Nullable
    public String getUserAgentString() {
        return mUserAgentString;
    }

    public void setUserAgentString(@Nullable String ua) {
        mUserAgentString = ua;
    }

    public boolean getDesktopMode() {
        return mDesktopMode;
    }

    public void setDesktopMode(boolean desktop) {
        mDesktopMode = desktop;
    }

    public int getCacheMode() {
        return mCacheMode;
    }

    public void setCacheMode(int mode) {
        mCacheMode = mode;
    }

    public boolean getBlockNetworkLoads() {
        return mBlockNetworkLoads;
    }

    public void setBlockNetworkLoads(boolean flag) {
        mBlockNetworkLoads = flag;
    }

    public boolean getBlockNetworkImage() {
        return mBlockNetworkImage;
    }

    public void setBlockNetworkImage(boolean flag) {
        mBlockNetworkImage = flag;
    }

    public boolean getLoadsImagesAutomatically() {
        return mLoadsImagesAutomatically;
    }

    public void setLoadsImagesAutomatically(boolean flag) {
        mLoadsImagesAutomatically = flag;
    }

    // Build GeckoSessionSettings for a NEW session. userAgentMode/viewportMode/
    // allowJavascript are initOnly keys — they only take effect at construction.
    @NonNull
    public GeckoSessionSettings toSessionSettings() {
        GeckoSessionSettings.Builder builder = new GeckoSessionSettings.Builder()
                .allowJavascript(mJavaScriptEnabled)
                .viewportMode(mUseWideViewPort
                        ? GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                        : GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                .userAgentMode(mDesktopMode
                        ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                        : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
        return builder.build();
    }
}
