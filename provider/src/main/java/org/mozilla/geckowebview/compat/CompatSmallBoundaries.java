package org.mozilla.geckowebview.compat;

import androidx.annotation.NonNull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import org.chromium.support_lib_boundary.WebMessagePortBoundaryInterface;
import org.chromium.support_lib_boundary.WebResourceErrorBoundaryInterface;
import org.chromium.support_lib_boundary.WebViewCookieManagerBoundaryInterface;

// Small boundary adapters with live backing where it exists, honest throw
// where it does not. Each adapter is a concrete InvocationHandler (NOT a
// Proxy: Proxy.newProxyInstance returns an impl of the boundary interface,
// which cannot cast to InvocationHandler). Split to keep file sizes small.
public final class CompatSmallBoundaries {
    private CompatSmallBoundaries() {}

    // WebResourceErrorBoundaryInterface over an int code + description.
    @NonNull
    public static InvocationHandler error(int code, @NonNull CharSequence description) {
        return new ErrorStub(code, description);
    }

    // WebViewCookieManagerBoundaryInterface: GET_COOKIE_INFO is unclaimed
    // (per-cookie read needs the P2 Necko patch), but the converter must
    // still return a handler so feature probing degrades honestly.
    @NonNull
    public static InvocationHandler cookieManager() {
        return new CookieStub();
    }

    // WebMessagePortBoundaryInterface: ports need the P2 concrete subclass;
    // the stub records close and throws honest errors until then.
    @NonNull
    public static InvocationHandler messagePort() {
        return new MessagePortStub();
    }

    // No dedicated WebStorage boundary type exists; return a stub handler
    // that throws honest errors on any use.
    @NonNull
    public static InvocationHandler webStorage() {
        return new StorageStub();
    }

    private static final class ErrorStub implements InvocationHandler {
        private final int mCode;
        @NonNull
        private final CharSequence mDescription;

        ErrorStub(int code, @NonNull CharSequence description) {
            mCode = code;
            mDescription = description;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getErrorCode":
                    return mCode;
                case "getDescription":
                    return mDescription;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "CompatWebResourceError";
                default:
                    throw new UnsupportedOperationException(
                            "CompatWebResourceError: " + method.getName());
            }
        }
    }

    private static final class CookieStub implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(method.getName())) {
                return "CompatCookieManager";
            }
            throw new UnsupportedOperationException(
                    "CompatCookieManager: GET_COOKIE_INFO unclaimed");
        }
    }

    private static final class MessagePortStub implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "close":
                    return null;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "CompatWebMessagePort";
                default:
                    throw new UnsupportedOperationException(
                            "CompatWebMessagePort: P2 patch needed");
            }
        }
    }

    private static final class StorageStub implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            throw new UnsupportedOperationException("CompatWebStorage: unclaimed");
        }
    }
}
