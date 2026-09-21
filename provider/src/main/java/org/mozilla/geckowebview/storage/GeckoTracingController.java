package org.mozilla.geckowebview.storage;

import android.webkit.TracingConfig;
import android.webkit.TracingController;
import androidx.annotation.NonNull;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

// android.webkit.TracingController implementation. No GeckoView equivalent;
// honest no-op that reports not-tracing (API_MAPPING.md §7).
public final class GeckoTracingController extends TracingController {
    private final AtomicBoolean mTracing = new AtomicBoolean(false);

    @Override
    public void start(@NonNull TracingConfig config) {
        mTracing.set(true);
    }

    @Override
    public boolean stop(@NonNull OutputStream outputStream,
            @NonNull Executor executor) {
        boolean wasTracing = mTracing.getAndSet(false);
        executor.execute(() -> {
            try {
                outputStream.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (Throwable ignored) {
            }
        });
        return wasTracing;
    }

    @Override
    public boolean isTracing() {
        return mTracing.get();
    }
}
