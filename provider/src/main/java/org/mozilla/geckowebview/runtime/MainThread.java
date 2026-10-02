package org.mozilla.geckowebview.runtime;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

// Blocking hop onto the app main thread (base layer: depends on nothing of
// ours, so session/ and provider/ can both use it).
//
// Why this exists: GeckoView construction/open/close are @UiThread
// (ThreadUtils.assertOnUiThread throws off-main), but the framework may
// construct the provider on ANY looper thread — CTS WebViewStartupTest
// creates a WebView on a background HandlerThread and only requires
// create/destroy/looper to work there (getWebViewLooper stays the
// creator thread's looper; the session itself always lives on main).
// Chromium's equivalent glue never touches thread-bound state at
// construction, so it never needed this.
//
// Discipline (AGENTS.md §7, same as GeckoCookieManager): run directly when
// already on main (never self-deadlock); otherwise post + bounded wait —
// the waited-on work always completes on the main thread, never on the
// blocked caller. Timeout/interrupt fail fast with IllegalStateException
// (construction must never hang the host). No main looper (JVM unit
// tests) runs directly; app processes always have one.
public final class MainThread {
    // Cold-process creation (runtime create + session open) is the slow
    // pole; steady-state hops are ms-fast. Past this we fail fast.
    private static final long TIMEOUT_MS = 15000;

    private MainThread() {}

    @NonNull
    public static <T> T call(@NonNull Callable<T> work) {
        Looper main = Looper.getMainLooper();
        if (main == null || Looper.myLooper() == main) {
            try {
                return work.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("main-thread work threw", e);
            }
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        new Handler(main).post(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        boolean posted;
        try {
            posted = done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            posted = false;
        }
        Throwable thrown = error.get();
        if (!posted || thrown != null) {
            throw new IllegalStateException(
                    "main-thread hop failed (completed=" + posted + ")", thrown);
        }
        return result.get();
    }
}
