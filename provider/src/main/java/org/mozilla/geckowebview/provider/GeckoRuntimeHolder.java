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
            // Sinytra 0005 日志门: 调试（debuggable）构建经打包的
            // geckoview-config.yaml 打开 sinytra.log.enabled（C++ 插桩的
            // 运行时开关，0005 宏读）; 非 debuggable 构建不打包该 asset，
            // pref 保持默认 false，零输出。判据用 FLAG_DEBUGGABLE
            // （AGP 9 不再生成 BuildConfig；且这正是「调试模式」的本义，
            // GeckoView 对默认 config 路径也用同一判据）。
            // configFilePath 只收真实文件路径，assets 先拷到 filesDir。
            boolean debuggable =
                    (app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            File debugConfig = debuggable ? installDebugConfig(app) : null;
            if (debugConfig != null) {
                builder = builder.configFilePath(debugConfig.getAbsolutePath());
            }
            sRuntime = GeckoRuntime.create(app, builder.build());
            Log.i(TAG, "GeckoRuntime created");
            return sRuntime;
        }
    }

    @Nullable
    private static File installDebugConfig(@NonNull Context app) {
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
            Log.w(TAG, "debug geckoview-config.yaml missing/unreadable", e);
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
