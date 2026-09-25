package org.mozilla.geckowebview.storage;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.webkit.ValueCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckowebview.provider.GeckoRuntimeHolder;

// CookieManager state over the real Gecko cookie jar.
//
// Why this is self-written (AGENTS.md §4): GeckoView has no per-cookie API at
// all (GV158 StorageController: jar-wide clearing only) — the jar lives in
// Necko. firefox-patches/0007-cookie-jar adds the four primitives
// (getCookie/setCookie/removeSessionCookies/hasCookies) used below; policy
// flags map onto ContentBlocking.setCookieBehavior, which GV does have.
//
// Threading: WebView's CookieManager API is synchronous by contract and
// Chromium blocks the calling thread on its cookie task as well. All jar
// queries are issued on a dedicated handler thread: GeckoResult needs a
// Looper-backed dispatcher for its listener chains, and this way the caller's
// thread (UI included) only waits on a latch — the chain completes on the
// query thread and never needs the blocked thread. The controller is fetched
// once through the main thread (GeckoRuntime.getStorageController asserts
// it) and cached; StorageController ops themselves are @AnyThread.
public final class GeckoCookieManager {
    private static final String TAG = "Sinytra/storage";
    // Generous against cold GeckoThread startup; cookie ops are ms-fast once
    // running. On timeout we degrade honestly instead of hanging the host.
    private static final long TIMEOUT_MS = 5000;

    private static volatile Handler sQueryHandler;

    private volatile boolean mAcceptCookie = true;
    // Global policy (WebView's is per-WebView; per-WebView granularity would
    // need per-context cookie behavior in Gecko — documented v1 divergence).
    // null = untouched; reported as false, matching WebView's default for
    // apps targeting L+ (our targetSdk=34).
    @Nullable
    private volatile Boolean mThirdParty;
    private volatile int mLastAppliedBehavior = Integer.MIN_VALUE;
    @Nullable
    private volatile StorageController mController;
    // Stored as-is; the app context is only resolved when the UI-thread
    // cold-start path actually creates the runtime (JVM locks pass null).
    @Nullable
    private final Context mContext;

    public GeckoCookieManager(@NonNull Context context) {
        mContext = context;
    }

    public void setAcceptCookie(boolean accept) {
        mAcceptCookie = accept;
        applyPolicy();
    }

    public boolean acceptCookie() {
        return mAcceptCookie;
    }

    public void setAcceptThirdPartyCookies(@Nullable Object webview, boolean accept) {
        mThirdParty = accept;
        applyPolicy();
    }

    public boolean acceptThirdPartyCookies(@Nullable Object webview) {
        Boolean value = mThirdParty;
        return value != null ? value : false;
    }

    /**
     * WebView-parity cookie behavior for the given policy flags. Visible for
     * JVM locks: the facade reports third-party=false unless the app opted in
     * (WebView default for targetSdk ≥ 21), so an untouched policy maps to
     * reject-foreign — engine reality always matches what the facade reports.
     */
    static int cookieBehaviorFor(boolean accept, @Nullable Boolean thirdParty) {
        if (!accept) {
            return ContentBlocking.CookieBehavior.ACCEPT_NONE;
        }
        if (Boolean.TRUE.equals(thirdParty)) {
            return ContentBlocking.CookieBehavior.ACCEPT_ALL;
        }
        return ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY;
    }

    private void applyPolicy() {
        int behavior = cookieBehaviorFor(mAcceptCookie, mThirdParty);
        if (behavior == mLastAppliedBehavior) {
            return;
        }
        GeckoRuntime runtime = runtimeOrNull();
        if (runtime == null) {
            return;
        }
        Runnable push = () -> runtime.getSettings().getContentBlocking()
                .setCookieBehavior(behavior);
        // RuntimeSettings setters assert the main thread; WebView's
        // CookieManager policy setters are documented thread-tolerant
        // (Chromium applies them from any thread), so hop over — the push
        // is idempotent and the next jar op re-applies if it lands late.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            push.run();
        } else {
            mainHandler().post(push);
        }
        mLastAppliedBehavior = behavior;
    }

    public void setCookie(@NonNull String url, @NonNull String value) {
        setCookie(url, value, null);
    }

    public void setCookie(@NonNull String url, @NonNull String value,
            @Nullable ValueCallback<Boolean> callback) {
        if (url == null || value == null) {
            if (callback != null) {
                callback.onReceiveValue(Boolean.FALSE);
            }
            return;
        }
        StorageController controller = controllerOrNull();
        if (controller == null) {
            if (callback != null) {
                callback.onReceiveValue(Boolean.FALSE);
            }
            return;
        }
        applyPolicy();
        if (callback == null) {
            // Synchronous contract: the op must have landed when we return.
            blockingOp(() -> controller.setCookie(url, value), Boolean.FALSE);
            return;
        }
        asyncOp(() -> controller.setCookie(url, value), callback);
    }

    @NonNull
    public String getCookie(@NonNull String url) {
        if (url == null) {
            return "";
        }
        StorageController controller = controllerOrNull();
        if (controller == null) {
            return "";
        }
        applyPolicy();
        String cookie = blockingOp(() -> controller.getCookie(url), "");
        return cookie != null ? cookie : "";
    }

    public void removeSessionCookies(@Nullable ValueCallback<Boolean> callback) {
        StorageController controller = controllerOrNull();
        if (controller == null) {
            callbackOrDefault(callback, Boolean.FALSE);
            return;
        }
        asyncOp(controller::removeSessionCookies, callback);
    }

    public void removeAllCookies(@Nullable ValueCallback<Boolean> callback) {
        clearByFlags(StorageController.ClearFlags.COOKIES, callback);
    }

    public boolean hasCookies() {
        StorageController controller = controllerOrNull();
        if (controller == null) {
            return false;
        }
        Boolean has = blockingOp(controller::hasCookies, Boolean.FALSE);
        return has != null && has;
    }

    // Gecko persists the jar automatically (cookies.sqlite, batched writes);
    // there is no flush primitive to force it early. Chromium's flush()
    // before process death is best-effort there too.
    public void flush() {
    }

    private void callbackOrDefault(@Nullable ValueCallback<Boolean> callback,
            @Nullable Boolean value) {
        if (callback != null) {
            callback.onReceiveValue(Boolean.TRUE.equals(value));
        }
    }

    /**
     * Runs a jar query on the query thread and blocks the caller (bounded)
     * for its outcome. The accept chain completes on the query thread, so
     * this is deadlock-free even when the caller is the UI thread.
     */
    private <T> T blockingOp(
            @NonNull Supplier<GeckoResult<T>> op, T fallback) {
        final AtomicReference<T> value = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        queryHandler().post(() -> {
            try {
                op.get().accept(
                        v -> {
                            value.set(v);
                            done.countDown();
                        },
                        e -> {
                            Log.w(TAG, "cookie query failed", e);
                            done.countDown();
                        });
            } catch (Throwable t) {
                Log.w(TAG, "cookie query could not be issued", t);
                done.countDown();
            }
        });
        try {
            done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        T result = value.get();
        return result != null ? result : fallback;
    }

    /** Async variant: the app callback is invoked on the UI thread. */
    private void asyncOp(@NonNull Supplier<GeckoResult<Boolean>> op,
            @Nullable ValueCallback<Boolean> callback) {
        Handler main = mainHandler();
        queryHandler().post(() -> {
            try {
                op.get().accept(
                        v -> main.post(() -> callbackOrDefault(callback, v)),
                        e -> main.post(() -> callbackOrDefault(
                                callback, Boolean.FALSE)));
            } catch (Throwable t) {
                Log.w(TAG, "cookie op could not be issued", t);
                main.post(() -> callbackOrDefault(callback, Boolean.FALSE));
            }
        });
    }

    @Nullable
    private StorageController controllerOrNull() {
        StorageController cached = mController;
        if (cached != null) {
            return cached;
        }
        GeckoRuntime runtime = runtimeOrNull();
        if (runtime == null) {
            return null;
        }
        // getStorageController() asserts the main thread; fetch once there
        // (or main-posted) and cache — the controller ops are @AnyThread.
        final AtomicReference<StorageController> ref = new AtomicReference<>();
        final CountDownLatch fetched = new CountDownLatch(1);
        Runnable fetch = () -> {
            ref.set(runtime.getStorageController());
            fetched.countDown();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            fetch.run();
        } else {
            mainHandler().post(fetch);
            try {
                fetched.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        StorageController controller = ref.get();
        if (controller != null) {
            mController = controller;
        }
        return controller;
    }

    @Nullable
    private GeckoRuntime runtimeOrNull() {
        GeckoRuntime existing = GeckoRuntimeHolder.peek();
        if (existing != null) {
            return existing;
        }
        // Cold start: runtime creation is main-thread-only and needs a
        // context. Off the main thread (or without a context) we cannot
        // create it — degrade honestly (the runtime is up long before any
        // app touches cookies through a WebView in practice).
        if (mContext != null && Looper.myLooper() == Looper.getMainLooper()) {
            return GeckoRuntimeHolder.get(mContext.getApplicationContext());
        }
        Log.w(TAG, "cookie op before runtime exists on a background thread");
        return null;
    }

    private void clearByFlags(long flags,
            @Nullable ValueCallback<Boolean> callback) {
        StorageController controller = controllerOrNull();
        if (controller == null) {
            callbackOrDefault(callback, Boolean.FALSE);
            return;
        }
        asyncOp(() -> controller.clearData(flags).map(v -> Boolean.TRUE),
                callback);
    }

    @NonNull
    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    @NonNull
    private static Handler queryHandler() {
        if (sQueryHandler == null) {
            synchronized (GeckoCookieManager.class) {
                if (sQueryHandler == null) {
                    HandlerThread thread = new HandlerThread("Sinytra-cookie");
                    thread.start();
                    sQueryHandler = new Handler(thread.getLooper());
                }
            }
        }
        return sQueryHandler;
    }

    /** Minimal supplier shape so this file needs no extra API level. */
    private interface Supplier<T> {
        T get();
    }
}
