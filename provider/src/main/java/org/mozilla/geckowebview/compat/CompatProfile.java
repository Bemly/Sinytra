package org.mozilla.geckowebview.compat;

import android.content.Context;
import androidx.annotation.NonNull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import org.chromium.support_lib_boundary.ProfileBoundaryInterface;

// ProfileBoundaryInterface: single-profile provider. Name is fixed
// ("default"); per-profile CookieManager/WebStorage come from the
// framework singletons (multi-profile needs the P2 patch).
public final class CompatProfile implements InvocationHandler {
    @NonNull
    private final Context mAppContext;

    private CompatProfile(@NonNull Context context) {
        mAppContext = context.getApplicationContext();
    }

    @NonNull
    public static InvocationHandler create(@NonNull Context context) {
        return new CompatProfile(context);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "getName":
                return "default";
            case "getCookieManager":
                return android.webkit.CookieManager.getInstance();
            case "getWebStorage":
                return android.webkit.WebStorage.getInstance();
            case "getGeoLocationPermissions":
                return android.webkit.GeolocationPermissions.getInstance();
            case "getServiceWorkerController":
                return android.webkit.ServiceWorkerController.getInstance();
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "CompatProfile(default)";
            default:
                throw new UnsupportedOperationException(
                        "CompatProfile: " + method.getName());
        }
    }
}
