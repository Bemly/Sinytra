package org.mozilla.geckowebview.compat;

import android.webkit.WebSettings;
import androidx.annotation.NonNull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import org.chromium.support_lib_boundary.WebSettingsBoundaryInterface;

// WebSettingsBoundaryInterface over the live android.webkit.WebSettings:
// every getter/setter delegates to the framework settings object owned by
// the provider (CompatWebSettings), so compat settings stay in sync.
public final class CompatWebSettingsBoundary implements InvocationHandler {
    @NonNull
    private final WebSettings mSettings;

    private CompatWebSettingsBoundary(@NonNull WebSettings settings) {
        mSettings = settings;
    }

    @NonNull
    public static InvocationHandler create(@NonNull WebSettings settings) {
        return new CompatWebSettingsBoundary(settings);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "setOffscreenPreRaster":
                mSettings.setOffscreenPreRaster((Boolean) args[0]);
                return null;
            case "getOffscreenPreRaster":
                return mSettings.getOffscreenPreRaster();
            case "setSafeBrowsingEnabled":
                mSettings.setSafeBrowsingEnabled((Boolean) args[0]);
                return null;
            case "getSafeBrowsingEnabled":
                return mSettings.getSafeBrowsingEnabled();
            case "setDisabledActionModeMenuItems":
                mSettings.setDisabledActionModeMenuItems((Integer) args[0]);
                return null;
            case "getDisabledActionModeMenuItems":
                return mSettings.getDisabledActionModeMenuItems();
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "CompatWebSettingsBoundary";
            default:
                throw new UnsupportedOperationException(
                        "CompatWebSettingsBoundary: " + method.getName());
        }
    }
}
