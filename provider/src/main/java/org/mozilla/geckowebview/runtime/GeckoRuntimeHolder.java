package org.mozilla.geckowebview.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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

// Host-process GeckoRuntime singleton (ARCHITECTURE §2: at most one live
// runtime per host app process). Base layer (AGENTS §2): every package may
// depend on runtime/, runtime/ depends on nothing of ours — so session/
// storage reach the runtime without reverse-depending on provider/.
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
            // Host APK into child-process env (0020): file:///android_asset/
            // resolves from SINYTRA_HOST_APK, and zygote-forked children do
            // NOT inherit the app process env (Os.setenv is parent-only).
            // The extras Bundle carries "envN"="K=V" entries into every
            // child launch (the same rails MOZ_LOG rides on); the C++
            // file hook getenv()s it. Appended after any existing env keys
            // (host app / debug yaml may own env0..). Once per runtime
            // (the host APK cannot change mid-process).
            builder = builder.extras(hostApkEnvExtras(app));
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

    /**
     * Extras Bundle carrying SINYTRA_HOST_APK into child launches (0020).
     * Never null, never throws (a missing path simply yields no entry and
     * Gecko falls back to stock literal-path behavior). Pure except reading
     * the package path; JVM-testable via hostApkEnvValue (FileBridge).
     */
    @NonNull
    static android.os.Bundle hostApkEnvExtras(@NonNull Context app) {
        android.os.Bundle extras = new android.os.Bundle();
        String path;
        try {
            path = app.getApplicationContext().getPackageCodePath();
        } catch (Throwable t) {
            Log.w(TAG, "hostApkEnvExtras path threw", t);
            return extras;
        }
        path = hostApkPathOrNull(path);
        if (path == null) {
            return extras;
        }
        int c = 0;
        while (extras.getString("env" + c) != null) {
            c++;
        }
        extras.putString("env" + c, "SINYTRA_HOST_APK=" + path);
        return extras;
    }

    /**
     * Validates a host APK path for the SINYTRA_HOST_APK environment (0020).
     * Null/empty in, null out. Pure string guard (unit-testable; Context
     * never crosses into tested code).
     */
    static String hostApkPathOrNull(@Nullable String packageCodePath) {
        if (packageCodePath == null || packageCodePath.isEmpty()) {
            return null;
        }
        return packageCodePath;
    }

    @Nullable
    private static File installConfig(@NonNull Context app) {
        try (InputStream in = openConfigAsset(app)) {
            File out = new File(app.getFilesDir(), "geckoview-config.yaml");
            try (OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            return out;
        } catch (IOException | PackageManager.NameNotFoundException e) {
            Log.w(TAG, "geckoview-config.yaml missing/unreadable", e);
            return null;
        }
    }

    // The config asset ships in the PROVIDER apk; the host app's AssetManager
    // cannot see it (third-party hosts, framework-entry finding 2026-09-27 —
    // the hygiene prefs silently didn't apply there). The provider package is
    // the framework's current-webview-package answer; its package context
    // reads our own assets. Host-app assets still win (our own debug app and
    // the reflection harness carry the asset in the app apk).
    @NonNull
    private static InputStream openConfigAsset(@NonNull Context app)
            throws IOException, PackageManager.NameNotFoundException {
        try {
            return app.getAssets().open("geckoview-config.yaml");
        } catch (IOException hostAppMiss) {
            android.content.pm.PackageInfo current =
                    android.webkit.WebView.getCurrentWebViewPackage();
            if (current == null) {
                throw hostAppMiss;
            }
            Context provider = app.createPackageContext(current.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            return provider.getAssets().open("geckoview-config.yaml");
        }
    }
}
