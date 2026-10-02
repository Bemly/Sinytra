package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Unit locks for the time-based filter re-push (Interception): a newer push
// supersedes pending retries via the generation guard, and the retry delays
// stay ordered inside the GeckoViewNavigation onInit window. The live
// dispatch (setResponseDelegate reaching Gecko) is device-locked by the
// harness intercept probes + CTS, which the JVM cannot exercise
// (no Looper; scheduling degrades to synchronous-push-only).
public final class InterceptionRepushTest {

    @Test
    public void sameGeneration_isLive() {
        assertTrue(Interception.isRepushLive(3, 3));
    }

    @Test
    public void supersededGeneration_isStale() {
        assertFalse(Interception.isRepushLive(3, 4));
    }

    @Test
    public void initialGeneration_isLive() {
        assertFalse(Interception.isRepushLive(0, 1));
        assertTrue(Interception.isRepushLive(1, 1));
    }

    @Test
    public void delays_backoffToCap() {
        assertEquals(500L, Interception.retryDelayMs(1));
        assertEquals(1000L, Interception.retryDelayMs(2));
        assertEquals(2000L, Interception.retryDelayMs(3));
        assertEquals(4000L, Interception.retryDelayMs(4));
        assertEquals(8000L, Interception.retryDelayMs(5));
        assertEquals(8000L, Interception.retryDelayMs(6));
        assertEquals(5, Interception.MAX_RETRY_ATTEMPTS);
    }
}
