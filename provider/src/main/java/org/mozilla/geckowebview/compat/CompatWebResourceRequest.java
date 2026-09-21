package org.mozilla.geckowebview.compat;

import androidx.annotation.NonNull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import org.chromium.support_lib_boundary.WebResourceRequestBoundaryInterface;

// WebResourceRequestBoundaryInterface over a live WebResourceRequest
// (InterceptBridge.SinytraResourceRequest or the framework object).
public final class CompatWebResourceRequest implements InvocationHandler {
    @NonNull
    private final android.webkit.WebResourceRequest mRequest;

    private CompatWebResourceRequest(
            @NonNull android.webkit.WebResourceRequest request) {
        mRequest = request;
    }

    @NonNull
    public static InvocationHandler create(
            @NonNull android.webkit.WebResourceRequest request) {
        return new CompatWebResourceRequest(request);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "getUrl":
                return mRequest.getUrl().toString();
            case "isForMainFrame":
                return mRequest.isForMainFrame();
            case "isRedirect":
                return mRequest.isRedirect();
            case "hasGesture":
                return mRequest.hasGesture();
            case "getMethod":
                return mRequest.getMethod();
            case "getRequestHeaders":
                return mRequest.getRequestHeaders();
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "CompatWebResourceRequest";
            default:
                throw new UnsupportedOperationException(
                        "CompatWebResourceRequest: " + method.getName());
        }
    }
}
