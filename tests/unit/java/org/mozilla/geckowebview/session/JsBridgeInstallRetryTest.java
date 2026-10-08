package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Unit locks for the ensureBuiltIn install watchdog (2026-10-08 coolapk
// fresh process: dispatched pre-RUNNING, neither callback ever fired).
// The schedule itself needs a main Looper (device-covered); only the pure
// delay ladder is locked here: 4s first retry covering cold start, 16s cap
// afterwards, ~60s total window across MAX_INSTALL_ATTEMPTS (device round
// needed ~32s to first ack).
public final class JsBridgeInstallRetryTest {

    @Test
    public void installRetryDelay_firstAttemptCoversColdStart() {
        assertEquals(4000L, JsBridge.installRetryDelayMs(1));
    }

    @Test
    public void installRetryDelay_doublesToCap() {
        assertEquals(8000L, JsBridge.installRetryDelayMs(2));
        assertEquals(16000L, JsBridge.installRetryDelayMs(3));
        assertEquals(16000L, JsBridge.installRetryDelayMs(9));
    }

    @Test
    public void installRetryDelay_windowIsBounded() {
        long total = 0;
        for (int attempt = 1;
                attempt <= JsBridge.MAX_INSTALL_ATTEMPTS; attempt++) {
            total += JsBridge.installRetryDelayMs(attempt);
        }
        assertTrue(total <= 70000L);
    }
}
