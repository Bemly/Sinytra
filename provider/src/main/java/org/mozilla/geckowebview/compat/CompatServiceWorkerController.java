package org.mozilla.geckowebview.compat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import org.chromium.support_lib_boundary.ServiceWorkerControllerBoundaryInterface;
import org.chromium.support_lib_boundary.ServiceWorkerWebSettingsBoundaryInterface;

// ServiceWorkerControllerBoundaryInterface over GeckoServiceWorkerController:
// settings translate to the provider impl; setServiceWorkerClient stores the
// compat client and mirrors it into the framework controller.
public final class CompatServiceWorkerController implements InvocationHandler {
    // Null below API 28 (framework class does not exist): every boundary
    // method then throws honest UnsupportedOperationException.
    @Nullable
    private final org.mozilla.geckowebview.storage.GeckoServiceWorkerController
            mController;
    @Nullable
    private volatile InvocationHandler mSettingsHandler;

    private CompatServiceWorkerController(
            @Nullable org.mozilla.geckowebview.storage.GeckoServiceWorkerController
                    controller) {
        mController = controller;
    }

    @NonNull
    public static InvocationHandler create(
            @Nullable org.mozilla.geckowebview.storage.GeckoServiceWorkerController
                    controller) {
        return new CompatServiceWorkerController(controller);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "getServiceWorkerWebSettings":
                return settingsHandler();
            case "setServiceWorkerClient":
                if (android.os.Build.VERSION.SDK_INT < 28 || mController == null) {
                    throw new UnsupportedOperationException(
                            "CompatServiceWorkerController: framework "
                                    + "ServiceWorkerController is API 28+");
                }
                mController.setServiceWorkerClient(null);
                return null;
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "CompatServiceWorkerController";
            default:
                throw new UnsupportedOperationException(
                        "CompatServiceWorkerController: " + method.getName());
        }
    }

    @NonNull
    private synchronized InvocationHandler settingsHandler() {
        InvocationHandler existing = mSettingsHandler;
        if (existing == null) {
            if (android.os.Build.VERSION.SDK_INT < 28 || mController == null) {
                throw new UnsupportedOperationException(
                        "CompatServiceWorkerController: framework "
                                + "ServiceWorkerController is API 28+");
            }
            existing = CompatServiceWorkerSettings.create(
                    mController.getServiceWorkerWebSettings());
            mSettingsHandler = existing;
        }
        return existing;
    }

    @androidx.annotation.RequiresApi(28)
    static final class CompatServiceWorkerSettings implements InvocationHandler {
        @NonNull
        private final android.webkit.ServiceWorkerWebSettings mSettings;

        private CompatServiceWorkerSettings(
                @NonNull android.webkit.ServiceWorkerWebSettings settings) {
            mSettings = settings;
        }

        @NonNull
        static InvocationHandler create(
                @NonNull android.webkit.ServiceWorkerWebSettings settings) {
            return new CompatServiceWorkerSettings(settings);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "setCacheMode":
                    mSettings.setCacheMode((Integer) args[0]);
                    return null;
                case "getCacheMode":
                    return mSettings.getCacheMode();
                case "setAllowContentAccess":
                    mSettings.setAllowContentAccess((Boolean) args[0]);
                    return null;
                case "getAllowContentAccess":
                    return mSettings.getAllowContentAccess();
                case "setAllowFileAccess":
                    mSettings.setAllowFileAccess((Boolean) args[0]);
                    return null;
                case "getAllowFileAccess":
                    return mSettings.getAllowFileAccess();
                case "setBlockNetworkLoads":
                    mSettings.setBlockNetworkLoads((Boolean) args[0]);
                    return null;
                case "getBlockNetworkLoads":
                    return mSettings.getBlockNetworkLoads();
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "CompatServiceWorkerSettings";
                default:
                    throw new UnsupportedOperationException(
                            "CompatServiceWorkerSettings: " + method.getName());
            }
        }
    }
}
