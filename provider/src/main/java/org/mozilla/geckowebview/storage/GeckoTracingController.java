package org.mozilla.geckowebview.storage;

import android.webkit.TracingConfig;
import android.webkit.TracingController;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

// android.webkit.TracingController implementation. No GeckoView equivalent;
// honest no-op that reports not-tracing (API_MAPPING.md §7).
// The framework superclass is API 28+: instantiate only behind an
// SDK guard (GeckoWebViewFactoryProvider does; see the latent
// NoClassDefFoundError note in its field comment).
@RequiresApi(28)
public final class GeckoTracingController extends TracingController {
    private final AtomicBoolean mTracing = new AtomicBoolean(false);

    @Override
    public void start(@NonNull TracingConfig config) {
        // CTS TracingControllerTest contract (mirrors Chromium validation):
        // null config, double start, comma-joined categories, and
        // exclusion patterns without their base category all throw
        // instead of silently arming a backend we don't have.
        if (config == null) {
            throw new IllegalArgumentException("TracingConfig must not be null");
        }
        validateCategories(config.getCustomIncludedCategories());
        if (!mTracing.compareAndSet(false, true)) {
            throw new IllegalStateException("Tracing already started");
        }
    }

    static void validateCategories(
            @NonNull java.util.List<String> categories) {
        java.util.Set<String> included = new java.util.HashSet<>();
        for (String category : categories) {
            if (category == null || category.isEmpty()
                    || category.indexOf(',') >= 0) {
                throw new IllegalArgumentException(
                        "Invalid tracing category: " + category);
            }
            if (!category.startsWith("-")) {
                included.add(category);
            }
        }
        for (String category : categories) {
            if (category.startsWith("-")
                    && !included.contains(category.substring(1))) {
                throw new IllegalArgumentException(
                        "Exclusion category without its base: " + category);
            }
        }
    }

    @Override
    public boolean stop(@NonNull OutputStream outputStream,
            @NonNull Executor executor) {
        boolean wasTracing = mTracing.getAndSet(false);
        executor.execute(() -> {
            try {
                // No Gecko tracing backend (honest "{}"); the CTS receiver
                // contract needs ≥1 chunk on the executor thread plus
                // close() to signal completion — both delivered here.
                outputStream.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                outputStream.flush();
                outputStream.close();
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
