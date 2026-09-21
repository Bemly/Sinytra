package org.mozilla.geckowebview.storage;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.StorageController;

// CookieManager-shaped facade for P0. GeckoView has NO cookie jar API — cookies
// live in Necko inside Gecko. Full cookie read/write needs a Gecko patch (P2,
// firefox-patches/). Until then this facade owns process-local policy flags and
// reports cookie ops as unsupported-but-honest: set/get return safe defaults
// instead of crashing the host app (AGENTS.md §7 error handling).
// Why not StorageController: it clears site data (incl. cookies) but exposes
// no per-cookie get/set — confirmed against GV 153 sources (no CookieManager class).
public final class GeckoCookieManager {
    private volatile boolean mAcceptCookie = true;
    private final Map<String, Boolean> mThirdPartyPolicy = new ConcurrentHashMap<>();
    private final Context mAppContext;

    public GeckoCookieManager(@NonNull Context context) {
        mAppContext = context.getApplicationContext();
    }

    public void setAcceptCookie(boolean accept) {
        mAcceptCookie = accept;
    }

    public boolean acceptCookie() {
        return mAcceptCookie;
    }

    public void setAcceptThirdPartyCookies(@Nullable Object webview, boolean accept) {
        mThirdPartyPolicy.put("default", accept);
    }

    public boolean acceptThirdPartyCookies(@Nullable Object webview) {
        Boolean value = mThirdPartyPolicy.get("default");
        return value != null ? value : false;
    }

    public void setCookie(@NonNull String url, @NonNull String value) {
        setCookie(url, value, null);
    }

    public void setCookie(@NonNull String url, @NonNull String value,
            @Nullable android.webkit.ValueCallback<Boolean> callback) {
        // P2: route through a Gecko cookie API once the firefox-patch lands.
        if (callback != null) {
            callback.onReceiveValue(Boolean.FALSE);
        }
    }

    @NonNull
    public String getCookie(@NonNull String url) {
        // P2: read from Necko cookie jar once the firefox-patch lands.
        return "";
    }

    public void removeSessionCookies(@Nullable android.webkit.ValueCallback<Boolean> callback) {
        clearByFlags(StorageController.ClearFlags.COOKIES, callback);
    }

    public void removeAllCookies(@Nullable android.webkit.ValueCallback<Boolean> callback) {
        clearByFlags(StorageController.ClearFlags.COOKIES, callback);
    }

    public boolean hasCookies() {
        return false;
    }

    public void flush() {
    }

    private void clearByFlags(long flags,
            @Nullable android.webkit.ValueCallback<Boolean> callback) {
        try {
            GeckoResult<Void> result = org.mozilla.geckowebview.provider.GeckoRuntimeHolder
                    .get(mAppContext).getStorageController().clearData(flags);
            result.accept(v -> {
                if (callback != null) {
                    callback.onReceiveValue(Boolean.TRUE);
                }
            }, e -> {
                if (callback != null) {
                    callback.onReceiveValue(Boolean.FALSE);
                }
            });
        } catch (Throwable t) {
            if (callback != null) {
                callback.onReceiveValue(Boolean.FALSE);
            }
        }
    }
}
