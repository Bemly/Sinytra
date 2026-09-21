package org.mozilla.geckowebview.compat;

import android.webkit.WebSettings;
import androidx.annotation.NonNull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import org.chromium.support_lib_boundary.WebkitToCompatConverterBoundaryInterface;

// WebkitToCompatConverterBoundaryInterface: convert the framework objects
// the compat layer hands us into boundary InvocationHandlers. Only the
// conversions with live Gecko backing are implemented; the rest throw
// UnsupportedOperationException so the compat layer degrades honestly
// instead of receiving dead objects.
public final class CompatConverter implements InvocationHandler {
    private CompatConverter() {}

    @NonNull
    public static InvocationHandler create() {
        return new CompatConverter();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "convertSettings":
                return CompatWebSettingsBoundary.create((WebSettings) args[0]);
            case "convertWebResourceRequest":
                return CompatWebResourceRequest.create(
                        (android.webkit.WebResourceRequest) args[0]);
            case "convertWebResourceError":
                return CompatSmallBoundaries.error(-1, "");
            case "convertWebMessagePort":
                return CompatSmallBoundaries.messagePort();
            case "convertCookieManager":
                return CompatSmallBoundaries.cookieManager();
            case "convertWebStorage":
                return CompatSmallBoundaries.webStorage();
            case "convertServiceWorkerSettings":
            case "convertSafeBrowsingResponse":
                throw new UnsupportedOperationException(
                        "CompatConverter." + method.getName() + ": P2 patch needed");
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "CompatConverter";
            default:
                throw new UnsupportedOperationException(
                        "CompatConverter: " + method.getName());
        }
    }
}
