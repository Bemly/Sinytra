package org.mozilla.geckowebview.provider;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public final class GeckoRuntimeHolder {
    private static final String TAG = "Sinytra/provider";
    private static volatile GeckoRuntime sRuntime;

    private GeckoRuntimeHolder() {}

    @NonNull
    public static GeckoRuntime get(@NonNull Context appContext) {
        GeckoRuntime existing = sRuntime;
        if (existing != null) {
            return existing;
        }
        synchronized (GeckoRuntimeHolder.class) {
            if (sRuntime != null) {
                return sRuntime;
            }
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .build();
            sRuntime = GeckoRuntime.create(appContext.getApplicationContext(), settings);
            Log.i(TAG, "GeckoRuntime created");
            return sRuntime;
        }
    }

    public static void attachView(@NonNull Context context, @NonNull GeckoView view,
            @NonNull GeckoSession session) {
        GeckoRuntime runtime = get(context);
        if (!session.isOpen()) {
            session.open(runtime);
        }
        view.setSession(session);
    }
}
