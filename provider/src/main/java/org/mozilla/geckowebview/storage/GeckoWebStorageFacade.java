package org.mozilla.geckowebview.storage;

import android.content.Context;
import android.webkit.ValueCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckowebview.runtime.GeckoRuntimeHolder;

// WebStorage semantics over GeckoView's StorageController, bound into the
// framework-typed android.webkit.SinytraWebStorage the factory hands out.
// Deletes are fire-and-forget like the framework API (void); failures are
// logged, never thrown. Quota/origin enumeration has no GeckoView
// equivalent: honest defaults (empty map / 0).
public final class GeckoWebStorageFacade implements android.webkit.SinytraWebStorage.Binding {
    private static final String TAG = "Sinytra/storage";

    private final Context mAppContext;

    public GeckoWebStorageFacade(@NonNull Context context) {
        mAppContext = context.getApplicationContext();
    }

    @Override
    public void deleteAllData() {
        onMain(() -> logFailure(controller().clearData(StorageController.ClearFlags.ALL),
                "deleteAllData"));
    }

    @Override
    public void deleteOrigin(@NonNull String origin) {
        // WebView passes an origin ("https://host[:port]"); Gecko clears by host.
        String host = android.net.Uri.parse(origin).getHost();
        String target = host != null ? host : origin;
        onMain(() -> logFailure(controller().clearDataFromHost(target,
                StorageController.ClearFlags.ALL), "deleteOrigin"));
    }

    // Apps may call WebStorage from any thread; runtime creation and
    // GeckoResult listener dispatch both need a Looper — issue on main.
    private static void onMain(@NonNull Runnable op) {
        Runnable guarded = () -> {
            try {
                op.run();
            } catch (Throwable t) {
                android.util.Log.w(TAG, "storage op threw", t);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            guarded.run();
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(guarded);
        }
    }

    private static void logFailure(@NonNull GeckoResult<Void> result, @NonNull String op) {
        result.exceptionally(e -> {
            android.util.Log.w(TAG, op + " failed", e);
            return null;
        });
    }

    @Override
    public void getOrigins(@NonNull ValueCallback<java.util.Map> callback) {
        callback.onReceiveValue(java.util.Collections.emptyMap());
    }

    @Override
    public void getUsageForOrigin(@NonNull String origin,
            @NonNull ValueCallback<Long> callback) {
        callback.onReceiveValue(0L);
    }

    @Override
    public void getQuotaForOrigin(@NonNull String origin,
            @NonNull ValueCallback<Long> callback) {
        callback.onReceiveValue(0L);
    }

    @NonNull
    private StorageController controller() {
        return GeckoRuntimeHolder.get(mAppContext).getStorageController();
    }
}
