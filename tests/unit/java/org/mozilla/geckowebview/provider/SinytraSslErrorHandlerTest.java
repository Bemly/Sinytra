package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

// JVM locks for the SslErrorHandler decision routing (android.webkit package
// subclass; the base framework class is an empty shell like on device). The
// Gecko cert-override round-trip itself is locked by the device sslProceed
// probe. The test file lives in tests/unit per AGENTS §1 even though the
// class under test is in android.webkit — the in-package placement of the
// SUBCLASS is a compile-time visibility constraint, not a test-layout rule.
public class SinytraSslErrorHandlerTest {

    @Test
    public void proceedAndCancel_routeToRunnables() {
        final AtomicInteger proceed = new AtomicInteger();
        final AtomicInteger cancel = new AtomicInteger();
        android.webkit.SslErrorHandler handler =
                new android.webkit.SinytraSslErrorHandler(
                        () -> proceed.incrementAndGet(),
                        () -> cancel.incrementAndGet());

        // Chromium contract: exactly one decision per handler; the app may
        // choose either (we record whatever the app calls).
        handler.proceed();
        assertEquals(1, proceed.get());
        assertEquals(0, cancel.get());

        android.webkit.SslErrorHandler cancelling =
                new android.webkit.SinytraSslErrorHandler(
                        () -> proceed.incrementAndGet(),
                        () -> cancel.incrementAndGet());
        cancelling.cancel();
        assertEquals(1, cancel.get());
        assertEquals(1, proceed.get());
    }

    @Test
    public void handler_isAFrameworkSslErrorHandler() {
        // The object handed to the app must satisfy the framework contract
        // (WebViewClient.onReceivedSslError parameter type).
        android.webkit.SslErrorHandler handler =
                new android.webkit.SinytraSslErrorHandler(() -> { },
                        () -> { });
        assertTrue(handler instanceof android.webkit.SslErrorHandler);
        assertTrue(handler instanceof android.os.Handler);
    }
}
