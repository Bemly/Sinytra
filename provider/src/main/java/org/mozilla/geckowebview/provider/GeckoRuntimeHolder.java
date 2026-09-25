package org.mozilla.geckowebview.provider;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public final class GeckoRuntimeHolder {
    private static final String TAG = "Sinytra/provider";
    private static volatile GeckoRuntime sRuntime;

    private GeckoRuntimeHolder() {}

    /**
     * Returns the runtime if already created, without creating one.
     *
     * <p>CookieManager and friends can be touched before any WebView exists;
     * those callers must not force a runtime create ({@link
     * GeckoRuntime#create} is main-thread-only), they degrade honestly
     * instead. See GeckoCookieManager.
     */
    @Nullable
    public static GeckoRuntime peek() {
        return sRuntime;
    }

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
            Context app = appContext.getApplicationContext();
            GeckoRuntimeSettings.Builder builder =
                    new GeckoRuntimeSettings.Builder().javaScriptEnabled(true);
            // GeckoView config (public configFilePath mechanism): all builds
            // get the provider hygiene prefs (src/main asset: no network
            // probing, no Remote Settings fetch); debug builds override the
            // asset with sinytra.log.enabled added (0005 日志门). FLAG_
            // DEBUGGABLE is the "debug mode" criterion (AGP 9 generates no
            // BuildConfig); configFilePath needs a real file, so assets are
            // copied to filesDir first.
            boolean debuggable =
                    (app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            File config = installConfig(app);
            if (config != null) {
                builder = builder.configFilePath(config.getAbsolutePath());
            } else if (debuggable) {
                Log.w(TAG, "geckoview-config.yaml missing — 0005 gate off");
            }
            sRuntime = GeckoRuntime.create(app, builder.build());
            Log.i(TAG, "GeckoRuntime created");
            return sRuntime;
        }
    }

    @Nullable
    private static File installConfig(@NonNull Context app) {
        try (InputStream in = app.getAssets().open("geckoview-config.yaml")) {
            File out = new File(app.getFilesDir(), "geckoview-config.yaml");
            try (OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            return out;
        } catch (IOException e) {
            Log.w(TAG, "geckoview-config.yaml missing/unreadable", e);
            return null;
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
