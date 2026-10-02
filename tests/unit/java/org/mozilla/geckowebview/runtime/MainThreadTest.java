package org.mozilla.geckowebview.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

// Locks the no-main-looper (JVM) fallback: work runs directly on the
// caller, results pass through, failures stay failures. The cross-thread
// hop itself is device-locked (CTS WebViewStartupTest background-thread
// creation exercises it; a JVM has no second looper to hop to).
public final class MainThreadTest {

    @Test
    public void direct_resultPassesThrough() {
        assertEquals("ok", MainThread.call(() -> "ok"));
    }

    @Test
    public void direct_runtimeExceptionPassesThrough() {
        RuntimeException boom = new IllegalStateException("boom");
        try {
            MainThread.call(() -> {
                throw boom;
            });
            fail("must propagate");
        } catch (RuntimeException e) {
            assertSame(boom, e);
        }
    }

    @Test
    public void direct_checkedExceptionWraps() {
        try {
            MainThread.call(() -> {
                throw new java.io.IOException("checked");
            });
            fail("must propagate");
        } catch (IllegalStateException e) {
            assertTrue(e.getCause() instanceof java.io.IOException);
        }
    }
}
