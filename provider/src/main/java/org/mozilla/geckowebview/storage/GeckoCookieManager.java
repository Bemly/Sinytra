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
import org.mozilla.geckowebview.runtime.GeckoRuntimeHolder;

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
        // Synchronous push: the jar query below reads the pref on the
        // Gecko thread, so a posted-but-not-yet-run push loses the race
        // (and a persisted REJECT from an earlier setAcceptCookie(false)
        // poisons later runs). Wait bounded for the main-thread set, then
        // a bounded settle for the Gecko round-trip: setDefaultPrefs is
        // an async dispatch with no completion signal (the Java
        // getCookieBehavior getter reflects the local commit only, so
        // polling it cannot observe Gecko-side application). Chromium
        // applies policy synchronously before the jar op; we emulate it.
        // Deadlock-free: the setter runs on the main thread (never the
        // query thread), and the caller here never holds the query latch.
        //
        // Confirmed-only bookkeeping: mLastAppliedBehavior records the
        // behavior ONLY when the push provably ran. A timed-out or
        // throwing push must NOT be recorded — otherwise the pref stays
        // stale forever (every later call early-returns on the recorded
        // value and never retries). This bit CTS CookieManagerTest:
        // setUp hammers policy while the main thread is busy creating
        // the first runtime, the FIRST_PARTY re-push silently died, and
        // the REJECT stuck.
        final CountDownLatch pushed = new CountDownLatch(1);
        final AtomicReference<Throwable> pushError = new AtomicReference<>();
        Runnable push = () -> {
            try {
                runtime.getSettings().getContentBlocking()
                        .setCookieBehavior(behavior);
            } catch (Throwable t) {
                pushError.set(t);
            } finally {
                pushed.countDown();
            }
        };
        boolean posted = true;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            push.run();
        } else {
            mainHandler().post(push);
            try {
                posted = pushed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                posted = false;
            }
        }
        Throwable error = pushError.get();
        if (!posted || error != null) {
            Log.w(TAG, "cookie policy push failed (behavior=" + behavior
                    + " posted=" + posted + ")", error);
            return;
        }
        waitForBehavior();
        mLastAppliedBehavior = behavior;
    }

    // The SetDefaultPrefs dispatch is async to the Gecko thread with no
    // completion signal: even after the main-thread set lands, the jar
    // query can overtake it. Fixed bounded settle on the caller thread
    // (test/UI, never the query thread) — the pref lands in ms; the
    // alternative (stale REJECT) fails closed. Matches the class's
    // bounded-wait discipline (AGENTS.md §7 sync-contract exception).
    private void waitForBehavior() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
            Boolean landed =
                    blockingOp(() -> controller.setCookie(url, value), Boolean.FALSE);
            if (!Boolean.TRUE.equals(landed)) {
                // The jar refused (behavior gate / add() validation) — loud,
                // or CTS-style set-then-get goes hunting a ghost.
                Log.w(TAG, "setCookie refused url=" + url + " value=" + value);
            }
            return;
        }
        asyncOp(
                () -> controller.setCookie(url, value),
                accepted -> {
                    if (!accepted) {
                        Log.w(TAG, "setCookie refused url=" + url + " value="
                                + value);
                    }
                    callback.onReceiveValue(accepted);
                });
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
        // Ordered like Chromium's cookie queue: the clear must have landed
        // before this returns (a bare clearData dispatch is async inside
        // Gecko and could otherwise overtake a later set). The app
        // callback still fires on the UI thread.
        Boolean removed =
                blockingOp(controller::removeSessionCookies, Boolean.FALSE);
        Handler main = mainHandler();
        main.post(() -> callbackOrDefault(callback, removed));
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
        if (done.getCount() > 0) {
            Log.w(TAG, "cookie query timed out after " + TIMEOUT_MS + "ms");
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
        // Same ordering contract as removeSessionCookies above.
        Boolean cleared = blockingOp(
                () -> controller.clearData(flags).map(v -> Boolean.TRUE),
                Boolean.FALSE);
        Handler main = mainHandler();
        main.post(() -> callbackOrDefault(callback, cleared));
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
