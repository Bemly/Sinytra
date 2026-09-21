package org.mozilla.geckowebview.storage;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckowebview.provider.GeckoRuntimeHolder;

// WebStorage-shaped facade over GeckoView's StorageController.
// No independent state: every call delegates to the host-process runtime.
// clearData requires closed sessions for full effect (API_MAPPING.md §2).
public final class GeckoWebStorage {
    private final Context mAppContext;

    public GeckoWebStorage(@NonNull Context context) {
        mAppContext = context.getApplicationContext();
    }

    @NonNull
    private StorageController controller() {
        GeckoRuntime runtime = GeckoRuntimeHolder.get(mAppContext);
        return runtime.getStorageController();
    }

    @NonNull
    public GeckoResult<Void> deleteAllData() {
        return controller().clearData(StorageController.ClearFlags.ALL);
    }

    @NonNull
    public GeckoResult<Void> deleteOrigin(@NonNull String host) {
        return controller().clearDataFromHost(host, StorageController.ClearFlags.ALL);
    }

    public void getOrigins(@NonNull android.webkit.ValueCallback<java.util.Map> callback) {
        callback.onReceiveValue(java.util.Collections.emptyMap());
    }

    public void getUsageForOrigin(@NonNull String origin,
            @NonNull android.webkit.ValueCallback<Long> callback) {
        callback.onReceiveValue(0L);
    }

    public void getQuotaForOrigin(@NonNull String origin,
            @NonNull android.webkit.ValueCallback<Long> callback) {
        callback.onReceiveValue(0L);
    }
}
