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

    // Single settings object (Chromium parity: one ServiceWorker settings
    // per controller, so set-then-get round-trips). Verbatim state only —
    // no Gecko SW backend is wired (SW coexistence is a P2 reserve item).
    private final ServiceWorkerWebSettings mSettings = new ServiceWorkerWebSettings() {
        private int mCacheMode = android.webkit.WebSettings.LOAD_DEFAULT;
        private boolean mAllowContentAccess = true;
        private boolean mAllowFileAccess;
        private boolean mBlockNetworkLoads;

        @Override
        public void setCacheMode(int mode) {
            mCacheMode = mode;
        }

        @Override
        public int getCacheMode() {
            return mCacheMode;
        }

        @Override
        public void setAllowContentAccess(boolean allow) {
            mAllowContentAccess = allow;
        }

        @Override
        public boolean getAllowContentAccess() {
            return mAllowContentAccess;
        }

        @Override
        public void setAllowFileAccess(boolean allow) {
            mAllowFileAccess = allow;
        }

        @Override
        public boolean getAllowFileAccess() {
            return mAllowFileAccess;
        }

        @Override
        public void setBlockNetworkLoads(boolean flag) {
            mBlockNetworkLoads = flag;
        }

        @Override
        public boolean getBlockNetworkLoads() {
            return mBlockNetworkLoads;
        }
    };

    @NonNull
    @Override
    public ServiceWorkerWebSettings getServiceWorkerWebSettings() {
        return mSettings;
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
