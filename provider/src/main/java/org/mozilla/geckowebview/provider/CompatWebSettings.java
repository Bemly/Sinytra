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
        mDelegate.setAllowFileAccess(allow);
    }

    @Override public boolean getAllowFileAccess() {
        return mDelegate.getAllowFileAccess();
    }

    @Override public void setAllowContentAccess(boolean allow) {
        mDelegate.setAllowContentAccess(allow);
    }

    @Override public boolean getAllowContentAccess() {
        return mDelegate.getAllowContentAccess();
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
        mDelegate.setSupportMultipleWindows(support);
    }

    @Override public boolean supportMultipleWindows() {
        return mDelegate.getSupportMultipleWindows();
    }

    @Override public void setLayoutAlgorithm(LayoutAlgorithm l) {
        mDelegate.setLayoutAlgorithm(l != null ? l.ordinal() : 0);
    }

    @Override public LayoutAlgorithm getLayoutAlgorithm() {
        LayoutAlgorithm[] values = LayoutAlgorithm.values();
        int ordinal = mDelegate.getLayoutAlgorithm();
        if (ordinal < 0 || ordinal >= values.length) {
            return LayoutAlgorithm.NORMAL;
        }
        return values[ordinal];
    }

    @Override public void setStandardFontFamily(String font) {
        mDelegate.setStandardFontFamily(font);
    }

    @Override public String getStandardFontFamily() {
        return mDelegate.getStandardFontFamily();
    }

    @Override public void setFixedFontFamily(String font) {
        mDelegate.setFixedFontFamily(font);
    }

    @Override public String getFixedFontFamily() {
        return mDelegate.getFixedFontFamily();
    }

    @Override public void setSansSerifFontFamily(String font) {
        mDelegate.setSansSerifFontFamily(font);
    }

    @Override public String getSansSerifFontFamily() {
        return mDelegate.getSansSerifFontFamily();
    }

    @Override public void setSerifFontFamily(String font) {
        mDelegate.setSerifFontFamily(font);
    }

    @Override public String getSerifFontFamily() {
        return mDelegate.getSerifFontFamily();
    }

    @Override public void setCursiveFontFamily(String font) {
        mDelegate.setCursiveFontFamily(font);
    }

    @Override public String getCursiveFontFamily() {
        return mDelegate.getCursiveFontFamily();
    }

    @Override public void setFantasyFontFamily(String font) {
        mDelegate.setFantasyFontFamily(font);
    }

    @Override public String getFantasyFontFamily() {
        return mDelegate.getFantasyFontFamily();
    }

    @Override public void setMinimumFontSize(int size) {
        mDelegate.setMinimumFontSize(size);
    }

    @Override public int getMinimumFontSize() {
        return mDelegate.getMinimumFontSize();
    }

    @Override public void setMinimumLogicalFontSize(int size) {
        mDelegate.setMinimumLogicalFontSize(size);
    }

    @Override public int getMinimumLogicalFontSize() {
        return mDelegate.getMinimumLogicalFontSize();
    }

    @Override public void setDefaultFontSize(int size) {
        mDelegate.setDefaultFontSize(size);
    }

    @Override public int getDefaultFontSize() {
        return mDelegate.getDefaultFontSize();
    }

    @Override public void setDefaultFixedFontSize(int size) {
        mDelegate.setDefaultFixedFontSize(size);
    }

    @Override public int getDefaultFixedFontSize() {
        return mDelegate.getDefaultFixedFontSize();
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
        mDelegate.setDatabaseEnabled(flag);
    }

    @Override public void setDomStorageEnabled(boolean flag) {
        mDelegate.setDomStorageEnabled(flag);
    }

    @Override public boolean getDomStorageEnabled() {
        return mDelegate.getDomStorageEnabled();
    }

    @Override public boolean getDatabaseEnabled() {
        return mDelegate.getDatabaseEnabled();
    }

    @Override public void setGeolocationEnabled(boolean flag) {
    }

    @Override public void setJavaScriptCanOpenWindowsAutomatically(boolean flag) {
        mDelegate.setJavaScriptCanOpenWindowsAutomatically(flag);
    }

    @Override public boolean getJavaScriptCanOpenWindowsAutomatically() {
        return mDelegate.getJavaScriptCanOpenWindowsAutomatically();
    }

    @Override public void setDefaultTextEncodingName(String encoding) {
        mDelegate.setDefaultTextEncodingName(encoding);
    }

    @Override public String getDefaultTextEncodingName() {
        return mDelegate.getDefaultTextEncodingName();
    }

    @Override public void setUserAgentString(@Nullable String ua) {
        // CTS testAccessUserAgentString: setting null is a no-op, setting
        // "" sticks verbatim. Never store null (get must stay non-null).
        if (ua != null) {
            mDelegate.setUserAgentString(ua);
        }
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

    // The delegate stores exactly the int the app set (framework WebSettings
    // already intdef-checks callers) and defaults to LOAD_DEFAULT (-1) —
    // in-range by construction, but not provable across the delegation.
    @android.annotation.SuppressLint("WrongConstant")
    @Override public int getCacheMode() {
        return mDelegate.getCacheMode();
    }

    @Override public void setMixedContentMode(int mode) {
        mDelegate.setMixedContentMode(mode);
    }

    @Override public int getMixedContentMode() {
        return mDelegate.getMixedContentMode();
    }

    @Override public void setOffscreenPreRaster(boolean enabled) {
        mDelegate.setOffscreenPreRaster(enabled);
    }

    @Override public boolean getOffscreenPreRaster() {
        return mDelegate.getOffscreenPreRaster();
    }

    // Safe Browsing has no Gecko backend in P1 (flag parity only, like
    // Chromium's toggle surface). Default true matches Chromium.
    @Override public void setSafeBrowsingEnabled(boolean enabled) {
        mDelegate.setSafeBrowsingEnabled(enabled);
    }

    @Override public boolean getSafeBrowsingEnabled() {
        return mDelegate.getSafeBrowsingEnabled();
    }

    @Override public void setDisabledActionModeMenuItems(int menuItems) {
        mDelegate.setDisabledActionModeMenuItems(menuItems);
    }

    // The delegate stores exactly the int the app set (same shape as the
    // getCacheMode suppression above: in-range by the setter contract,
    // not provable across the delegation).
    @android.annotation.SuppressLint("WrongConstant")
    @Override public int getDisabledActionModeMenuItems() {
        return mDelegate.getDisabledActionModeMenuItems();
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
        mDelegate.setAllowUniversalAccessFromFileURLs(flag);
    }

    @Override public boolean getAllowUniversalAccessFromFileURLs() {
        return mDelegate.getAllowUniversalAccessFromFileURLs();
    }

    @Override public void setAllowFileAccessFromFileURLs(boolean flag) {
        mDelegate.setAllowFileAccessFromFileURLs(flag);
    }

    @Override public boolean getAllowFileAccessFromFileURLs() {
        return mDelegate.getAllowFileAccessFromFileURLs();
    }

    // Deprecated plugin API: absent from android.jar (stripped) but still
    // abstract on the API-34 device framework — without these declarations
    // any call dies with AbstractMethodError (CTS
    // WebSettingsTest.testAccessPluginsEnabled). Plugins are long dead:
    // always false, setter is a no-op. No @Override (android.jar has no
    // such member to override against).
    public boolean getPluginsEnabled() {
        return false;
    }

    public void setPluginsEnabled(boolean flag) {
    }
}
