package org.mozilla.geckowebview.provider;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mozilla.geckowebview.runtime.SinytraLog;

// Keeps already-running Gecko tab processes important without ever spawning
// (BISECT thaw-verdict 2026-10-14: a serving tab frozen by the system
// cgroup freezer (cgroup.freeze=1, top-app) eats the whole navigation —
// parent-side necko runs to 200 + RedirectToRealChannel, content never
// consumes; thawing that one tab commits in ~1s with full pixels).
//
// Mechanism: a parallel, non-creating Context.BIND_IMPORTANT binding to
// every tab-family child service (tabN + isolatedTabN, enumerated from our
// own manifest — 40+40 on the current pin). No BIND_AUTO_CREATE, so a
// down service is never spawned (bind returns false, skipped); a live one
// counts toward its oom_adj for as long as we stay bound, so the cached-
// state freezer that fires across the 15–60s dialog-flow gaps never takes
// it. Independent of Gecko's own ServiceAllocator accounting (separate
// ServiceConnection objects; we never touch the binder) and of the 0026
// bind watchdog (that watches Gecko's ChildConnection, not ours).
// A K2 alternative (GeckoSession.setPriorityHint) was falsified on paper:
// the hint lands on remoteTab.priorityHint → compositor layer state, not
// oom_adj — it cannot stop the freezer.
//
// Lifecycle: assure() on every top navigation (idempotent: bound services
// are skipped, so the steady state is ~0 IPCs), release() on destroy().
// Multi-provider: each holder keeps its own connections (AMS refcounts),
// slight duplication, harmless. Never throws: every failure degrades to
// unwarm + loud log (the navigation proceeds regardless).
final class ChildWarmthKeeper {
    private static final String TAG = "Sinytra/warmth";
    private static final String TAB_SERVICE_PREFIX =
            "org.mozilla.gecko.process.GeckoChildProcessServices$tab";
    private static final String ISOLATED_TAB_SERVICE_PREFIX =
            "org.mozilla.gecko.process.GeckoChildProcessServices$isolatedTab";

    // Pure-Java service identity (ComponentName methods are stubbed on the
    // JVM mockable android.jar — equals/hashCode would lie — so map keys
    // and the test seam use this, and ComponentName is built only at the
    // framework call site, device-covered).
    static final class ServiceRef {
        @NonNull
        final String pkg;
        @NonNull
        final String cls;

        ServiceRef(@NonNull String pkg, @NonNull String cls) {
            this.pkg = pkg;
            this.cls = cls;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof ServiceRef)) {
                return false;
            }
            ServiceRef other = (ServiceRef) o;
            return pkg.equals(other.pkg) && cls.equals(other.cls);
        }

        @Override
        public int hashCode() {
            return pkg.hashCode() * 31 + cls.hashCode();
        }

        @Override
        @NonNull
        public String toString() {
            return pkg + "/" + cls;
        }
    }

    // Binder seam (production = Context.bindService, tests = fake).
    interface Binder {
        boolean bind(@NonNull ServiceRef svc, @NonNull ServiceConnection conn);

        void unbind(@NonNull ServiceRef svc, @NonNull ServiceConnection conn);
    }

    // Pure (JVM-locked): only the tab families keep warmth. gpu/socket/rdd/
    // gmplugin/utility/zygoteTab are singletons with their own lifecycle
    // (M5-A domain for gpu — do not touch), and non-numeric suffixes never
    // match a manifest entry.
    static boolean isTabService(@Nullable String className) {
        if (className == null) {
            return false;
        }
        String suffix = null;
        if (className.startsWith(TAB_SERVICE_PREFIX)) {
            suffix = className.substring(TAB_SERVICE_PREFIX.length());
        } else if (className.startsWith(ISOLATED_TAB_SERVICE_PREFIX)) {
            suffix = className.substring(ISOLATED_TAB_SERVICE_PREFIX.length());
        } else {
            return false;
        }
        if (suffix.isEmpty()) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private final Context mAppContext;
    @Nullable
    private final Binder mBinder;
    // Discovered tab services (cached after first success; empty = unknown).
    @NonNull
    private List<ServiceRef> mServices = new ArrayList<>();
    private boolean mDiscovered;
    // Live connections by service (insertion order = manifest order).
    @NonNull
    private final Map<ServiceRef, ServiceConnection> mBound = new HashMap<>();

    /** Production: resolves the provider package + enumerates services lazily. */
    ChildWarmthKeeper(@NonNull Context appContext) {
        mAppContext = appContext.getApplicationContext();
        mBinder = new Binder() {
            @Override
            public boolean bind(@NonNull ServiceRef svc,
                    @NonNull ServiceConnection conn) {
                Context ctx = mAppContext;
                if (ctx == null) {
                    return false;
                }
                try {
                    Intent intent = new Intent().setComponent(
                            new ComponentName(svc.pkg, svc.cls));
                    // IMPORTANT without AUTO_CREATE: keep-warm-if-alive,
                    // never spawn. The executor overload needs API 29;
                    // below that the 3-arg form posts callbacks on main —
                    // either way the impl only touches the synchronized
                    // map, so no Looper work of its own.
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        return ctx.bindService(intent,
                                Context.BIND_IMPORTANT, Runnable::run, conn);
                    }
                    return ctx.bindService(intent, conn,
                            Context.BIND_IMPORTANT);
                } catch (Throwable t) {
                    SinytraLog.w(TAG, "bind threw svc=" + svc, t);
                    return false;
                }
            }

            @Override
            public void unbind(@NonNull ServiceRef svc,
                    @NonNull ServiceConnection conn) {
                Context ctx = mAppContext;
                if (ctx == null) {
                    return;
                }
                try {
                    ctx.unbindService(conn);
                } catch (Throwable t) {
                    SinytraLog.w(TAG, "unbind threw svc=" + svc, t);
                }
            }
        };
    }

    // Test seam: explicit service list + fake binder (no Context/Looper).
    ChildWarmthKeeper(@NonNull List<ServiceRef> services,
            @NonNull Binder binder) {
        mAppContext = null;
        mBinder = binder;
        mServices = new ArrayList<>(services);
        mDiscovered = true;
    }

    // Idempotent sweep: bind every discovered-but-unbound tab service.
    // Never throws; failures stay unbound (retried next sweep) + loud log.
    synchronized void assure() {
        if (!mDiscovered) {
            discover();
        }
        Binder binder = mBinder;
        if (binder == null || mServices.isEmpty()) {
            return;
        }
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (ServiceRef svc : mServices) {
            if (mBound.containsKey(svc)) {
                continue;
            }
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name,
                        IBinder service) {
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    // Process died: drop so the next sweep re-binds.
                    synchronized (ChildWarmthKeeper.this) {
                        for (Map.Entry<ServiceRef, ServiceConnection> e
                                : new ArrayList<>(mBound.entrySet())) {
                            if (e.getValue() == this) {
                                mBound.remove(e.getKey());
                            }
                        }
                    }
                }
            };
            boolean bound = false;
            try {
                bound = binder.bind(svc, conn);
            } catch (Throwable t) {
                SinytraLog.w(TAG, "assure bind threw svc=" + svc, t);
            }
            if (bound) {
                mBound.put(svc, conn);
                ok++;
            } else {
                failed.add(svc.cls);
            }
        }
        SinytraLog.i(TAG, "assure bound=" + ok + "/" + mServices.size()
                + " live=" + mBound.size()
                + (failed.isEmpty() ? "" : " failed=" + failed));
    }

    // Releases every held connection. Idempotent, never throws.
    synchronized void release() {
        Binder binder = mBinder;
        if (binder != null && !mBound.isEmpty()) {
            for (Map.Entry<ServiceRef, ServiceConnection> e
                    : new ArrayList<>(mBound.entrySet())) {
                try {
                    binder.unbind(e.getKey(), e.getValue());
                } catch (Throwable t) {
                    SinytraLog.w(TAG,
                            "release unbind threw svc=" + e.getKey(), t);
                }
            }
        }
        int dropped = mBound.size();
        mBound.clear();
        SinytraLog.i(TAG, "release dropped=" + dropped);
    }

    // Test-only: live connection count (same-package tests).
    synchronized int liveCount() {
        return mBound.size();
    }

    // One-shot manifest enumeration (production path; device-covered — the
    // PackageManager query needs a live host process).
    private void discover() {
        mDiscovered = true;
        Context ctx = mAppContext;
        if (ctx == null) {
            return;
        }
        try {
            String providerPkg = resolveProviderPackage(ctx);
            if (providerPkg == null) {
                SinytraLog.w(TAG, "discover: no provider package; unwarm");
                return;
            }
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    providerPkg,
                    PackageManager.GET_SERVICES | PackageManager.MATCH_ALL);
            if (pi == null || pi.services == null) {
                SinytraLog.w(TAG, "discover: no services in " + providerPkg);
                return;
            }
            List<ServiceRef> found = new ArrayList<>();
            for (ServiceInfo si : pi.services) {
                if (isTabService(si.name)) {
                    found.add(new ServiceRef(providerPkg, si.name));
                }
            }
            mServices = found;
            SinytraLog.i(TAG, "discover services=" + found.size()
                    + " pkg=" + providerPkg);
        } catch (Throwable t) {
            SinytraLog.w(TAG, "discover threw; unwarm", t);
        }
    }

    // Provider package carrying the child services (initLogGate precedent):
    // our own install identity first, host context as fallback.
    @Nullable
    private static String resolveProviderPackage(@NonNull Context ctx) {
        try {
            android.content.pm.PackageInfo pi =
                    android.webkit.WebView.getCurrentWebViewPackage();
            if (pi != null && pi.packageName != null) {
                return pi.packageName;
            }
        } catch (Throwable t) {
            SinytraLog.w(TAG, "provider package lookup threw", t);
        }
        try {
            return ctx.getPackageName();
        } catch (Throwable t) {
            return null;
        }
    }
}
