package org.mozilla.geckowebview.storage;

import android.content.Context;
import android.webkit.ValueCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckowebview.runtime.GeckoRuntimeHolder;

// WebStorage-shaped facade over GeckoView's StorageController.
// WebStorage itself is NOT subclassed here: its ctor is package-private in
// android.jar, so a provider in another package cannot extend it at compile
// time. The factory returns android.webkit.WebStorage.getInstance() (the
// host framework's own object) for the framework singleton, and uses this
// facade internally for delete paths. Quota/origin enumeration has no
// GeckoView equivalent: honest defaults.
public final class GeckoWebStorageFacade {
    private final Context mAppContext;

    public GeckoWebStorageFacade(@NonNull Context context) {
        mAppContext = context.getApplicationContext();
    }

    @NonNull
    public GeckoResult<Void> deleteAllData() {
        return controller().clearData(StorageController.ClearFlags.ALL);
    }

    @NonNull
    public GeckoResult<Void> deleteOrigin(@NonNull String host) {
        return controller().clearDataFromHost(host, StorageController.ClearFlags.ALL);
    }

    @NonNull
    public GeckoResult<Void> clearCache() {
        return controller().clearData(StorageController.ClearFlags.ALL_CACHES);
    }

    public void getOrigins(@NonNull ValueCallback<java.util.Map> callback) {
        callback.onReceiveValue(java.util.Collections.emptyMap());
    }

    public void getUsageForOrigin(@NonNull String origin,
            @NonNull ValueCallback<Long> callback) {
        callback.onReceiveValue(0L);
    }

    public void getQuotaForOrigin(@NonNull String origin,
            @NonNull ValueCallback<Long> callback) {
        callback.onReceiveValue(0L);
    }

    @NonNull
    private StorageController controller() {
        return GeckoRuntimeHolder.get(mAppContext).getStorageController();
    }

    @Nullable
    public static android.webkit.WebStorage frameworkInstance() {
        try {
            return android.webkit.WebStorage.getInstance();
        } catch (Throwable t) {
            android.util.Log.w("Sinytra/storage",
                    "WebStorage.getInstance threw", t);
            return null;
        }
    }
}
