package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Unit locks for guard-exhaustion triage (2026-10-08 coolapk fresh-process
// round: exhaustion fired while the transport itself was still installing —
// first ack landed ~32s in, past both 14s chains — and the reopen burned
// the one rescue on cold start). Reopening into an unready pipeline
// restarts the download against nothing; waiting is strictly better.
// Only the pure triage predicate is locked here (the reopen/wait wiring
// needs a live session + Looper: device-covered).
public final class GeckoSessionBridgeTriageTest {

    @Test
    public void triage_unreadyWithinBudget_waits() {
        assertTrue(GeckoSessionBridge.shouldWaitForTransport(false, 0));
        assertTrue(GeckoSessionBridge.shouldWaitForTransport(false,
                GeckoSessionBridge.MAX_COLD_EXTENSIONS - 1));
    }

    @Test
    public void triage_unreadyOverBudget_reopens() {
        assertFalse(GeckoSessionBridge.shouldWaitForTransport(false,
                GeckoSessionBridge.MAX_COLD_EXTENSIONS));
        assertFalse(GeckoSessionBridge.shouldWaitForTransport(false,
                GeckoSessionBridge.MAX_COLD_EXTENSIONS + 10));
    }

    @Test
    public void triage_readyAlwaysReopens() {
        assertFalse(GeckoSessionBridge.shouldWaitForTransport(true, 0));
        assertFalse(GeckoSessionBridge.shouldWaitForTransport(true,
                GeckoSessionBridge.MAX_COLD_EXTENSIONS));
    }
}
