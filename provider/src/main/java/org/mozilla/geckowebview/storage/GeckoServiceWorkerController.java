package org.mozilla.geckowebview.storage;

import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerWebSettings;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// android.webkit.ServiceWorkerController implementation. GeckoView owns its
// ServiceWorker stack via GeckoRuntime.ServiceWorkerDelegate (API_MAPPING
// §2); until the provider wires that delegate, this reports honest
// defaults and holds the client reference.
@androidx.annotation.RequiresApi(28)
public final class GeckoServiceWorkerController extends ServiceWorkerController {
    @Nullable
    private volatile ServiceWorkerClient mClient;

    @NonNull
    @Override
    public ServiceWorkerWebSettings getServiceWorkerWebSettings() {
        return new ServiceWorkerWebSettings() {
            @Override
            public void setCacheMode(int mode) {
            }

            @Override
            public int getCacheMode() {
                return android.webkit.WebSettings.LOAD_DEFAULT;
            }

            @Override
            public void setAllowContentAccess(boolean allow) {
            }

            @Override
            public boolean getAllowContentAccess() {
                return true;
            }

            @Override
            public void setAllowFileAccess(boolean allow) {
            }

            @Override
            public boolean getAllowFileAccess() {
                return false;
            }

            @Override
            public void setBlockNetworkLoads(boolean flag) {
            }

            @Override
            public boolean getBlockNetworkLoads() {
                return false;
            }
        };
    }

    @Override
    public void setServiceWorkerClient(@Nullable ServiceWorkerClient client) {
        mClient = client;
    }

    @Nullable
    public ServiceWorkerClient getServiceWorkerClient() {
        return mClient;
    }
}
