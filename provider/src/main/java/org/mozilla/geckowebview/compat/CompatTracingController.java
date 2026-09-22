package org.mozilla.geckowebview.compat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.Executor;
import org.chromium.support_lib_boundary.TracingControllerBoundaryInterface;

// TracingControllerBoundaryInterface over GeckoTracingController.
// Boundary start(int, Collection<String>, int): mode/predefined-categories
// are accepted and ignored (Gecko tracing has no category set); the
// provider impl records tracing state honestly.
public final class CompatTracingController implements InvocationHandler {
    // Null below API 28 (framework class does not exist): every boundary
    // method then throws honest UnsupportedOperationException.
    @Nullable
    private final org.mozilla.geckowebview.storage.GeckoTracingController mController;

    private CompatTracingController(
            @Nullable org.mozilla.geckowebview.storage.GeckoTracingController
                    controller) {
        mController = controller;
    }

    @NonNull
    public static InvocationHandler create(
            @Nullable org.mozilla.geckowebview.storage.GeckoTracingController
                    controller) {
        return new CompatTracingController(controller);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "isTracing":
                if (android.os.Build.VERSION.SDK_INT < 28 || mController == null) {
                    throw new UnsupportedOperationException(
                            "CompatTracingController: framework TracingController "
                                    + "is API 28+");
                }
                return mController.isTracing();
            case "start":
                // TracingConfig (and framework tracing generally) is API 28+;
                // below Q there is no legal config to hand the provider impl,
                // so refuse honestly instead of fabricating one.
                if (android.os.Build.VERSION.SDK_INT < 28 || mController == null) {
                    throw new UnsupportedOperationException(
                            "CompatTracingController: framework TracingController "
                                    + "is API 28+");
                }
                mController.start(new android.webkit.TracingConfig.Builder().build());
                return null;
            case "stop":
                if (android.os.Build.VERSION.SDK_INT < 28 || mController == null) {
                    throw new UnsupportedOperationException(
                            "CompatTracingController: framework TracingController "
                                    + "is API 28+");
                }
                return mController.stop(
                        (OutputStream) args[0], (Executor) args[1]);
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "CompatTracingController";
            default:
                throw new UnsupportedOperationException(
                        "CompatTracingController: " + method.getName());
        }
    }

    @SuppressWarnings("unused")
    private static void checkStartArgs(Object[] args) {
        int mode = (Integer) args[0];
        Collection<String> categories = (Collection<String>) args[1];
        int predefined = args.length > 2 ? (Integer) args[2] : 0;
        if (mode < 0 || predefined < 0 || categories == null) {
            throw new IllegalArgumentException("tracing start args");
        }
    }
}
