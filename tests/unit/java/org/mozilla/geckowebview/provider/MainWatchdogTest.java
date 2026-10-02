package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Unit locks for the debug watchdog predicate (MainWatchdog): only a
// main-loop silence strictly past the threshold counts as stuck. Stack
// dumping itself is device-only (needs a live Looper + threads).
public final class MainWatchdogTest {

    @Test
    public void freshAck_notStuck() {
        assertFalse(MainWatchdog.isStuck(10_000L, 9_999L));
    }

    @Test
    public void exactlyAtThreshold_notStuck() {
        assertFalse(MainWatchdog.isStuck(
                10_000L + MainWatchdog.STUCK_THRESHOLD_MS, 10_000L));
    }

    @Test
    public void pastThreshold_stuck() {
        assertTrue(MainWatchdog.isStuck(
                10_000L + MainWatchdog.STUCK_THRESHOLD_MS + 1, 10_000L));
    }

    @Test
    public void clockSkew_notStuck() {
        assertFalse(MainWatchdog.isStuck(9_000L, 10_000L));
    }
}
