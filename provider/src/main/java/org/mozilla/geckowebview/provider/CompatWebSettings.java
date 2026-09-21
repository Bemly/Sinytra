package org.mozilla.geckowebview.provider;

import android.webkit.WebSettings;
import androidx.annotation.Nullable;
import org.mozilla.geckowebview.settings.GeckoWebSettings;

// android.webkit.WebSettings facade over GeckoWebSettings state (P0).
// Pure delegation: no independent behavior. initOnly keys (JS/UA/viewport)
// apply at session construction via GeckoWebSettings.toSessionSettings().
final class CompatWebSettings extends WebSettings {
    private final GeckoWebSettings mDelegate;

    CompatWebSettings(GeckoWebSettings delegate) {
        mDelegate = delegate;
    }

    GeckoWebSettings gecko() {
        return mDelegate;
    }

    @Override public void setSupportZoom(boolean support) {
        mDelegate.setSupportZoom(support);
    }

    @Override public boolean supportZoom() {
        return mDelegate.supportZoom();
    }

    @Override public void setMediaPlaybackRequiresUserGesture(boolean require) {
        mDelegate.setMediaPlaybackRequiresUserGesture(require);
    }

    @Override public boolean getMediaPlaybackRequiresUserGesture() {
        return mDelegate.getMediaPlaybackRequiresUserGesture();
    }

    @Override public void setBuiltInZoomControls(boolean enabled) {
        mDelegate.setBuiltInZoomControls(enabled);
    }

    @Override public boolean getBuiltInZoomControls() {
        return mDelegate.getBuiltInZoomControls();
    }

    @Override public void setDisplayZoomControls(boolean enabled) {
        mDelegate.setDisplayZoomControls(enabled);
    }

    @Override public boolean getDisplayZoomControls() {
        return mDelegate.getDisplayZoomControls();
    }

    @Override public void setAllowFileAccess(boolean allow) {
    }

    @Override public boolean getAllowFileAccess() {
        return false;
    }

    @Override public void setAllowContentAccess(boolean allow) {
    }

    @Override public boolean getAllowContentAccess() {
        return false;
    }

    @Override public void setLoadWithOverviewMode(boolean overview) {
        mDelegate.setLoadWithOverviewMode(overview);
    }

    @Override public boolean getLoadWithOverviewMode() {
        return mDelegate.getLoadWithOverviewMode();
    }

    @Override public void setTextZoom(int textZoom) {
        mDelegate.setTextZoom(textZoom);
    }

    @Override public int getTextZoom() {
        return mDelegate.getTextZoom();
    }

    @Override public void setUseWideViewPort(boolean use) {
        mDelegate.setUseWideViewPort(use);
    }

    @Override public boolean getUseWideViewPort() {
        return mDelegate.getUseWideViewPort();
    }

    @Override public void setSupportMultipleWindows(boolean support) {
    }

    @Override public boolean supportMultipleWindows() {
        return false;
    }

    @Override public void setLayoutAlgorithm(LayoutAlgorithm l) {
    }

    @Override public LayoutAlgorithm getLayoutAlgorithm() {
        return LayoutAlgorithm.NORMAL;
    }

    @Override public void setStandardFontFamily(String font) {
    }

    @Override public String getStandardFontFamily() {
        return "sans-serif";
    }

    @Override public void setFixedFontFamily(String font) {
    }

    @Override public String getFixedFontFamily() {
        return "monospace";
    }

    @Override public void setSansSerifFontFamily(String font) {
    }

    @Override public String getSansSerifFontFamily() {
        return "sans-serif";
    }

    @Override public void setSerifFontFamily(String font) {
    }

    @Override public String getSerifFontFamily() {
        return "serif";
    }

    @Override public void setCursiveFontFamily(String font) {
    }

    @Override public String getCursiveFontFamily() {
        return "cursive";
    }

    @Override public void setFantasyFontFamily(String font) {
    }

    @Override public String getFantasyFontFamily() {
        return "fantasy";
    }

    @Override public void setMinimumFontSize(int size) {
    }

    @Override public int getMinimumFontSize() {
        return 8;
    }

    @Override public void setMinimumLogicalFontSize(int size) {
    }

    @Override public int getMinimumLogicalFontSize() {
        return 8;
    }

    @Override public void setDefaultFontSize(int size) {
    }

    @Override public int getDefaultFontSize() {
        return 16;
    }

    @Override public void setDefaultFixedFontSize(int size) {
    }

    @Override public int getDefaultFixedFontSize() {
        return 13;
    }

    @Override public void setLoadsImagesAutomatically(boolean flag) {
        mDelegate.setLoadsImagesAutomatically(flag);
    }

    @Override public boolean getLoadsImagesAutomatically() {
        return mDelegate.getLoadsImagesAutomatically();
    }

    @Override public void setBlockNetworkImage(boolean flag) {
        mDelegate.setBlockNetworkImage(flag);
    }

    @Override public boolean getBlockNetworkImage() {
        return mDelegate.getBlockNetworkImage();
    }

    @Override public void setBlockNetworkLoads(boolean flag) {
        mDelegate.setBlockNetworkLoads(flag);
    }

    @Override public boolean getBlockNetworkLoads() {
        return mDelegate.getBlockNetworkLoads();
    }

    @Override public void setJavaScriptEnabled(boolean flag) {
        mDelegate.setJavaScriptEnabled(flag);
    }

    @Override public boolean getJavaScriptEnabled() {
        return mDelegate.getJavaScriptEnabled();
    }

    @Override public void setDatabaseEnabled(boolean flag) {
    }

    @Override public void setDomStorageEnabled(boolean flag) {
    }

    @Override public boolean getDomStorageEnabled() {
        return false;
    }

    @Override public boolean getDatabaseEnabled() {
        return false;
    }

    @Override public void setGeolocationEnabled(boolean flag) {
    }

    @Override public void setJavaScriptCanOpenWindowsAutomatically(boolean flag) {
    }

    @Override public boolean getJavaScriptCanOpenWindowsAutomatically() {
        return false;
    }

    @Override public void setDefaultTextEncodingName(String encoding) {
    }

    @Override public String getDefaultTextEncodingName() {
        return "UTF-8";
    }

    @Override public void setUserAgentString(@Nullable String ua) {
        mDelegate.setUserAgentString(ua);
    }

    @Override public String getUserAgentString() {
        String ua = mDelegate.getUserAgentString();
        return ua != null ? ua : "";
    }

    @Override public void setNeedInitialFocus(boolean flag) {
    }

    @Override public void setCacheMode(int mode) {
        mDelegate.setCacheMode(mode);
    }

    @Override public int getCacheMode() {
        return mDelegate.getCacheMode();
    }

    @Override public void setMixedContentMode(int mode) {
    }

    @Override public int getMixedContentMode() {
        return 1;
    }

    @Override public void setOffscreenPreRaster(boolean enabled) {
    }

    @Override public boolean getOffscreenPreRaster() {
        return false;
    }

    @Override public void setSafeBrowsingEnabled(boolean enabled) {
    }

    @Override public boolean getSafeBrowsingEnabled() {
        return false;
    }

    @Override public void setDisabledActionModeMenuItems(int menuItems) {
    }

    @Override public int getDisabledActionModeMenuItems() {
        return 0;
    }

    @Override public void setEnableSmoothTransition(boolean enable) {
    }

    @Override public boolean enableSmoothTransition() {
        return false;
    }

    @Override public void setSaveFormData(boolean save) {
    }

    @Override public boolean getSaveFormData() {
        return false;
    }

    @Override public void setSavePassword(boolean save) {
    }

    @Override public boolean getSavePassword() {
        return false;
    }

    @Override public void setLightTouchEnabled(boolean enabled) {
    }

    @Override public boolean getLightTouchEnabled() {
        return false;
    }

    @Override public void setPluginState(PluginState state) {
    }

    @Override public PluginState getPluginState() {
        return PluginState.OFF;
    }

    @Override public void setRenderPriority(RenderPriority priority) {
    }

    @Override public void setGeolocationDatabasePath(String databasePath) {
    }

    @Override public void setDefaultZoom(ZoomDensity zoom) {
    }

    @Override public ZoomDensity getDefaultZoom() {
        return ZoomDensity.MEDIUM;
    }

    @Override public void setDatabasePath(String databasePath) {
    }

    @Override public String getDatabasePath() {
        return "";
    }

    @Override public void setAllowUniversalAccessFromFileURLs(boolean flag) {
    }

    @Override public boolean getAllowUniversalAccessFromFileURLs() {
        return false;
    }

    @Override public void setAllowFileAccessFromFileURLs(boolean flag) {
    }

    @Override public boolean getAllowFileAccessFromFileURLs() {
        return false;
    }
}
