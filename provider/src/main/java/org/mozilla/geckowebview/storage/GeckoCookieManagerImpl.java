package org.mozilla.geckowebview.storage;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// android.webkit.CookieManager implementation over GeckoCookieManager state.
// Per-cookie get/set stays honest-default until the P2 Necko patch lands
// (API_MAPPING.md §7); policy flags + clear paths are live.
public final class GeckoCookieManagerImpl extends CookieManager {
    private final GeckoCookieManager mDelegate;

    public GeckoCookieManagerImpl(@NonNull Context context) {
        mDelegate = new GeckoCookieManager(context);
    }

    GeckoCookieManagerImpl(@NonNull GeckoCookieManager delegate) {
        mDelegate = delegate;
    }

    @Override
    public void setAcceptCookie(boolean accept) {
        mDelegate.setAcceptCookie(accept);
    }

    @Override
    public boolean acceptCookie() {
        return mDelegate.acceptCookie();
    }

    @Override
    public void setAcceptThirdPartyCookies(WebView webview, boolean accept) {
        mDelegate.setAcceptThirdPartyCookies(webview, accept);
    }

    @Override
    public boolean acceptThirdPartyCookies(WebView webview) {
        return mDelegate.acceptThirdPartyCookies(webview);
    }

    @Override
    public void setCookie(String url, String value) {
        if (url == null || value == null) {
            return;
        }
        mDelegate.setCookie(url, value);
    }

    @Override
    public void setCookie(String url, String value,
            @Nullable ValueCallback<Boolean> callback) {
        if (url == null || value == null) {
            if (callback != null) {
                callback.onReceiveValue(Boolean.FALSE);
            }
            return;
        }
        mDelegate.setCookie(url, value, callback);
    }

    @Override
    public String getCookie(String url) {
        if (url == null) {
            return "";
        }
        return mDelegate.getCookie(url);
    }

    // NOTE: getCookie(String, boolean), hasCookies(boolean),
    // allowFileSchemeCookiesImpl, setAcceptFileSchemeCookiesImpl and
    // getCookie(WebAddress) exist only on the device framework (hidden
    // @SystemApi), NOT in android.jar 34 compile stubs — confirmed by javap
    // above. They are NOT overridden here (javac would reject @Override);
    // at runtime the base-class concrete impls run instead:
    // getCookie(uri)→getCookie(uri.toString()) and static file-scheme
    // policy. If a real AbstractMethodError ever surfaces on these paths,
    // add framework-stubs entries, not @Overrides here.

    @Override
    @Deprecated
    public void removeSessionCookie() {
        mDelegate.removeSessionCookies(null);
    }

    @Override
    public void removeSessionCookies(@Nullable ValueCallback<Boolean> callback) {
        mDelegate.removeSessionCookies(callback);
    }

    @Override
    @Deprecated
    public void removeAllCookie() {
        mDelegate.removeAllCookies(null);
    }

    @Override
    public void removeAllCookies(@Nullable ValueCallback<Boolean> callback) {
        mDelegate.removeAllCookies(callback);
    }

    @Override
    public boolean hasCookies() {
        return mDelegate.hasCookies();
    }

    @Override
    @Deprecated
    public void removeExpiredCookie() {
    }

    @Override
    public void flush() {
        mDelegate.flush();
    }
}
