package org.mozilla.geckowebview.provider;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class BootstrapProbe {
    private static final String TAG = "Sinytra/bootstrap";

    public static final class Result {
        public final List<String> lines = new ArrayList<>();

        void add(String line) {
            lines.add(line);
        }

        @NonNull
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private BootstrapProbe() {}

    @NonNull
    public static Result runStatic(@NonNull Context context) {
        Result result = new Result();
        result.add("package=" + context.getPackageName());
        result.add("process=" + getProcessName());
        result.add("uid=" + android.os.Process.myUid());
        result.add("sdk=" + Build.VERSION.SDK_INT);

        probeClass("org.mozilla.geckoview.GeckoRuntime", result);
        probeClass("org.mozilla.geckoview.GeckoSession", result);
        probeClass("org.mozilla.geckoview.GeckoView", result);
        probeNativeLibraryDir(context, result);
        probeGeckoServices(context, result);
        return result;
    }

    /** Must be called on the UI thread: GeckoRuntime.create is @UiThread. */
    public static void probeRuntimeCreate(@NonNull Context context, @NonNull Result result) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            result.add("GeckoRuntime.create: SKIPPED (must run on UI thread)");
            return;
        }
        try {
            long start = System.currentTimeMillis();
            GeckoRuntimeHolder.get(context);
            long elapsed = System.currentTimeMillis() - start;
            result.add("GeckoRuntime.create: OK in " + elapsed + "ms");
        } catch (Throwable t) {
            result.add("GeckoRuntime.create: FAIL " + Log.getStackTraceString(t));
        }
    }

    private static void probeClass(String name, Result result) {
        try {
            Class<?> clazz = Class.forName(name, false,
                    BootstrapProbe.class.getClassLoader());
            result.add("class " + name + ": OK (" + clazz.getClassLoader() + ")");
        } catch (Throwable t) {
            result.add("class " + name + ": FAIL " + t);
        }
    }

    private static void probeNativeLibraryDir(Context context, Result result) {
        try {
            ApplicationInfo ai = context.getApplicationInfo();
            result.add("nativeLibraryDir=" + ai.nativeLibraryDir);
            File dir = new File(ai.nativeLibraryDir);
            String[] files = dir.list();
            if (files == null) {
                result.add("nativeLibraryDir list: null");
                return;
            }
            boolean foundXul = false;
            for (String file : files) {
                if (file.contains("xul")) {
                    foundXul = true;
                    result.add("native lib: " + file);
                }
            }
            result.add("libxul present=" + foundXul + " (total " + files.length + " libs)");
        } catch (Throwable t) {
            result.add("nativeLibraryDir: FAIL " + t);
        }
    }

    private static void probeGeckoServices(Context context, Result result) {
        String[] services = {
            "org.mozilla.gecko.process.GeckoChildProcessServices$gpu",
            "org.mozilla.gecko.process.GeckoChildProcessServices$tab0",
            "org.mozilla.gecko.process.GeckoChildProcessServices$socket",
            "org.mozilla.gecko.process.GeckoChildProcessServices$rdd",
            "org.mozilla.gecko.media.MediaManager",
            "org.mozilla.gecko.crashhelper.CrashHelper",
        };
        PackageManager pm = context.getPackageManager();
        for (String service : services) {
            try {
                android.content.ComponentName cn =
                        new android.content.ComponentName(context.getPackageName(), service);
                android.content.pm.ServiceInfo info =
                        pm.getServiceInfo(cn, PackageManager.GET_META_DATA);
                result.add("service " + service + ": OK exported=" + info.exported
                        + " process=" + info.processName);
            } catch (PackageManager.NameNotFoundException e) {
                result.add("service " + service + ": NOT FOUND");
            } catch (Throwable t) {
                result.add("service " + service + ": FAIL " + t);
            }
        }
    }

    private static String getProcessName() {
        try {
            // Application.getProcessName is API 28+; minSdk is 26. The
            // reflection fallback covers 26/27 (this is a probe, exactness
            // is not load-bearing).
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                String name = android.app.Application.getProcessName();
                return name != null ? name : "unknown";
            }
            return "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
