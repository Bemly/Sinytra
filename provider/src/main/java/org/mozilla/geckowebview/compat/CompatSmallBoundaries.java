package org.mozilla.geckowebview.compat;

import android.webkit.WebMessagePort;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

    // WebMessagePortBoundaryInterface over a live MessageBridge.Port: the
    // provider-side port is fully functional (post/close/callback all route
    // through MessageBridge + JsBridge transport). The boundary handler is
    // a thin proxy, not a stub.
    @NonNull
    public static InvocationHandler messagePort(
            @NonNull org.mozilla.geckowebview.session.MessageBridge bridge,
            @NonNull org.mozilla.geckowebview.session.MessageBridge.Port port) {
        return new LiveMessagePort(bridge, port);
    }

    // Legacy no-arg form kept for the converter path (no live port at
    // conversion time): records close, throws honest errors otherwise.
    // convertWebMessagePort(Object) hits this path — a bare framework
    // port object with no bridge binding cannot be made live, so the
    // converter stays a stub while createWebMessageChannel (above) is live.
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

    // Live boundary port: postMessage forwards into MessageBridge (and
    // from there into the JsBridge page transport); close closes the
    // bridge port; setWebMessageCallback registers a bridge callback
    // that unwraps the boundary WebMessage (getData via reflection-free
    // boundary call) and re-fires the boundary onMessage handler.
    private static final class LiveMessagePort implements InvocationHandler {
        @NonNull
        private final org.mozilla.geckowebview.session.MessageBridge mBridge;
        @NonNull
        private final org.mozilla.geckowebview.session.MessageBridge.Port mPort;
        private volatile boolean mClosed;

        LiveMessagePort(
                @NonNull org.mozilla.geckowebview.session.MessageBridge bridge,
                @NonNull org.mozilla.geckowebview.session.MessageBridge.Port port) {
            mBridge = bridge;
            mPort = port;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "postMessage": {
                    if (mClosed) {
                        return null;
                    }
                    String data = boundaryMessageData(args[0]);
                    if (data != null) {
                        try {
                            mBridge.postMessage(mPort, data, null);
                        } catch (Throwable t) {
                            android.util.Log.w("Sinytra/compat",
                                    "LiveMessagePort post threw", t);
                        }
                    }
                    return null;
                }
                case "close":
                    mClosed = true;
                    try {
                        mBridge.close(mPort);
                    } catch (Throwable t) {
                        android.util.Log.w("Sinytra/compat",
                                "LiveMessagePort close threw", t);
                    }
                    return null;
                case "setWebMessageCallback": {
                    final InvocationHandler callback =
                            args != null && args.length > 0
                                    && args[0] instanceof InvocationHandler
                                    ? (InvocationHandler) args[0] : null;
                    android.os.Handler handler =
                            args != null && args.length > 1
                                    && args[1] instanceof android.os.Handler
                                    ? (android.os.Handler) args[1] : null;
                    if (callback == null) {
                        try {
                            mBridge.setCallback(mPort, null);
                        } catch (Throwable ignored) {
                        }
                        return null;
                    }
                    final android.os.Handler target = handler;
                    android.webkit.WebMessagePort.WebMessageCallback fw =
                            new WebMessagePort.WebMessageCallback() {
                                @Override
                                public void onMessage(
                                        WebMessagePort port,
                                        android.webkit.WebMessage message) {
                                    Runnable fire = () -> {
                                        try {
                                            InvocationHandler msgHandler =
                                                    boundaryMessage(message);
                                            InvocationHandler portHandler =
                                                    LiveMessagePort.this;
                                            callback.invoke(null,
                                                    boundaryOnMessage(),
                                                    new Object[] {
                                                        portHandler, msgHandler});
                                        } catch (Throwable t) {
                                            android.util.Log.w("Sinytra/compat",
                                                    "LiveMessagePort callback threw",
                                                    t);
                                        }
                                    };
                                    if (target != null) {
                                        try {
                                            if (!target.post(fire)) {
                                                fire.run();
                                            }
                                        } catch (Throwable t) {
                                            android.util.Log.w("Sinytra/compat",
                                                    "LiveMessagePort post threw",
                                                    t);
                                        }
                                    } else {
                                        fire.run();
                                    }
                                }
                            };
                    try {
                        mBridge.setCallback(mPort, fw);
                    } catch (Throwable t) {
                        android.util.Log.w("Sinytra/compat",
                                "LiveMessagePort setCallback threw", t);
                    }
                    return null;
                }
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "CompatLiveWebMessagePort";
                default:
                    throw new UnsupportedOperationException(
                            "CompatLiveWebMessagePort: " + method.getName());
            }
        }

        @Nullable
        private static String boundaryMessageData(@Nullable Object message) {
            if (message == null) {
                return null;
            }
            try {
                Method getData = null;
                for (Method candidate : message.getClass().getMethods()) {
                    if (candidate.getName().equals("getData")
                            && candidate.getParameterTypes().length == 0) {
                        getData = candidate;
                        break;
                    }
                }
                // Boundary messages cross as InvocationHandlers: invoke the
                // WebMessageBoundaryInterface.getData method reflectively.
                if (getData != null) {
                    Object value = getData.invoke(message);
                    return value != null ? value.toString() : null;
                }
                if (message instanceof InvocationHandler) {
                    Method m = org.chromium.support_lib_boundary
                            .WebMessageBoundaryInterface.class
                            .getMethod("getData");
                    Object value = ((InvocationHandler) message)
                            .invoke(null, m, null);
                    return value != null ? value.toString() : null;
                }
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/compat",
                        "LiveMessagePort getData threw", t);
            }
            return null;
        }

        @NonNull
        private static InvocationHandler boundaryMessage(
                @NonNull android.webkit.WebMessage message) {
            final String data = message.getData();
            return new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method,
                        Object[] args) {
                    switch (method.getName()) {
                        case "getData":
                            return data;
                        case "getMessagePayload":
                            return null;
                        case "getPorts":
                            return new InvocationHandler[0];
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "CompatLiveWebMessage";
                        default:
                            throw new UnsupportedOperationException(
                                    "CompatLiveWebMessage: "
                                            + method.getName());
                    }
                }
            };
        }

        @NonNull
        private static Method boundaryOnMessage() throws Exception {
            return org.chromium.support_lib_boundary
                    .WebMessageCallbackBoundaryInterface.class
                    .getMethod("onMessage", InvocationHandler.class,
                            InvocationHandler.class);
        }
    }

    private static final class StorageStub implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            throw new UnsupportedOperationException("CompatWebStorage: unclaimed");
        }
    }
}
