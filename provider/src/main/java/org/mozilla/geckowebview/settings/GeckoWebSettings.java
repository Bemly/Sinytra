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
    // Everything below is stored verbatim for WebSettings parity (CTS
    // WebSettingsTest asserts set-then-get round-trips). No Gecko mapping
    // exists for these keys — they are facade state only.
    // LayoutAlgorithm ordinals (deprecated, frozen): 0 NORMAL, 1
    // SINGLE_COLUMN, 2 NARROW_COLUMNS. Chromium default is NARROW_COLUMNS.
    private int mLayoutAlgorithm = 2;
    private String mStandardFontFamily = "sans-serif";
    private String mFixedFontFamily = "monospace";
    private String mSansSerifFontFamily = "sans-serif";
    private String mSerifFontFamily = "serif";
    private String mCursiveFontFamily = "cursive";
    private String mFantasyFontFamily = "fantasy";
    private int mMinimumFontSize = 8;
    private int mMinimumLogicalFontSize = 8;
    private int mDefaultFontSize = 16;
    private int mDefaultFixedFontSize = 13;
    private String mDefaultTextEncodingName = "Latin-1";
    private int mMixedContentMode = 1;
    private boolean mSafeBrowsingEnabled = true;
    private int mDisabledActionModeMenuItems;
    private boolean mOffscreenPreRaster;
    private boolean mJavaScriptCanOpenWindowsAutomatically;
    private boolean mAllowFileAccess;
    private boolean mAllowContentAccess = true;
    private boolean mAllowUniversalAccessFromFileURLs;
    private boolean mAllowFileAccessFromFileURLs;
    private boolean mDomStorageEnabled;
    private boolean mDatabaseEnabled;
    private boolean mSupportMultipleWindows;

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

    // CTS WebSettingsTest.testAccessMinimumFontSize pins the range:
    // set(100) reads back 72, set(-10) reads back 1. Clamp all four font
    // sizes to [1, 72] (Chromium caps at an arbitrary limit; the sibling
    // default-size tests only need >0 / <max / exact-small-value).
    private static int clampFontSize(int size) {
        return Math.max(1, Math.min(72, size));
    }

    public int getLayoutAlgorithm() {
        return mLayoutAlgorithm;
    }

    public void setLayoutAlgorithm(int ordinal) {
        mLayoutAlgorithm = ordinal;
    }

    @NonNull
    public String getStandardFontFamily() {
        return mStandardFontFamily;
    }

    public void setStandardFontFamily(@Nullable String font) {
        if (font != null) {
            mStandardFontFamily = font;
        }
    }

    @NonNull
    public String getFixedFontFamily() {
        return mFixedFontFamily;
    }

    public void setFixedFontFamily(@Nullable String font) {
        if (font != null) {
            mFixedFontFamily = font;
        }
    }

    @NonNull
    public String getSansSerifFontFamily() {
        return mSansSerifFontFamily;
    }

    public void setSansSerifFontFamily(@Nullable String font) {
        if (font != null) {
            mSansSerifFontFamily = font;
        }
    }

    @NonNull
    public String getSerifFontFamily() {
        return mSerifFontFamily;
    }

    public void setSerifFontFamily(@Nullable String font) {
        if (font != null) {
            mSerifFontFamily = font;
        }
    }

    @NonNull
    public String getCursiveFontFamily() {
        return mCursiveFontFamily;
    }

    public void setCursiveFontFamily(@Nullable String font) {
        if (font != null) {
            mCursiveFontFamily = font;
        }
    }

    @NonNull
    public String getFantasyFontFamily() {
        return mFantasyFontFamily;
    }

    public void setFantasyFontFamily(@Nullable String font) {
        if (font != null) {
            mFantasyFontFamily = font;
        }
    }

    public int getMinimumFontSize() {
        return mMinimumFontSize;
    }

    public void setMinimumFontSize(int size) {
        mMinimumFontSize = clampFontSize(size);
    }

    public int getMinimumLogicalFontSize() {
        return mMinimumLogicalFontSize;
    }

    public void setMinimumLogicalFontSize(int size) {
        mMinimumLogicalFontSize = clampFontSize(size);
    }

    public int getDefaultFontSize() {
        return mDefaultFontSize;
    }

    public void setDefaultFontSize(int size) {
        mDefaultFontSize = clampFontSize(size);
    }

    public int getDefaultFixedFontSize() {
        return mDefaultFixedFontSize;
    }

    public void setDefaultFixedFontSize(int size) {
        mDefaultFixedFontSize = clampFontSize(size);
    }

    @NonNull
    public String getDefaultTextEncodingName() {
        return mDefaultTextEncodingName;
    }

    public void setDefaultTextEncodingName(@Nullable String encoding) {
        if (encoding != null) {
            mDefaultTextEncodingName = encoding;
        }
    }

    public int getMixedContentMode() {
        return mMixedContentMode;
    }

    public void setMixedContentMode(int mode) {
        mMixedContentMode = mode;
    }

    public boolean getSafeBrowsingEnabled() {
        return mSafeBrowsingEnabled;
    }

    public void setSafeBrowsingEnabled(boolean enabled) {
        mSafeBrowsingEnabled = enabled;
    }

    public int getDisabledActionModeMenuItems() {
        return mDisabledActionModeMenuItems;
    }

    public void setDisabledActionModeMenuItems(int menuItems) {
        mDisabledActionModeMenuItems = menuItems;
    }

    public boolean getOffscreenPreRaster() {
        return mOffscreenPreRaster;
    }

    public void setOffscreenPreRaster(boolean enabled) {
        mOffscreenPreRaster = enabled;
    }

    public boolean getJavaScriptCanOpenWindowsAutomatically() {
        return mJavaScriptCanOpenWindowsAutomatically;
    }

    public void setJavaScriptCanOpenWindowsAutomatically(boolean flag) {
        mJavaScriptCanOpenWindowsAutomatically = flag;
    }

    public boolean getAllowFileAccess() {
        return mAllowFileAccess;
    }

    public void setAllowFileAccess(boolean allow) {
        mAllowFileAccess = allow;
    }

    public boolean getAllowContentAccess() {
        return mAllowContentAccess;
    }

    public void setAllowContentAccess(boolean allow) {
        mAllowContentAccess = allow;
    }

    public boolean getAllowUniversalAccessFromFileURLs() {
        return mAllowUniversalAccessFromFileURLs;
    }

    public void setAllowUniversalAccessFromFileURLs(boolean flag) {
        mAllowUniversalAccessFromFileURLs = flag;
    }

    public boolean getAllowFileAccessFromFileURLs() {
        return mAllowFileAccessFromFileURLs;
    }

    public void setAllowFileAccessFromFileURLs(boolean flag) {
        mAllowFileAccessFromFileURLs = flag;
    }

    public boolean getDomStorageEnabled() {
        return mDomStorageEnabled;
    }

    public void setDomStorageEnabled(boolean flag) {
        mDomStorageEnabled = flag;
    }

    public boolean getDatabaseEnabled() {
        return mDatabaseEnabled;
    }

    public void setDatabaseEnabled(boolean flag) {
        mDatabaseEnabled = flag;
    }

    public boolean getSupportMultipleWindows() {
        return mSupportMultipleWindows;
    }

    public void setSupportMultipleWindows(boolean support) {
        mSupportMultipleWindows = support;
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
