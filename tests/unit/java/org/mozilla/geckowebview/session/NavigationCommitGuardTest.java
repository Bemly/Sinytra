package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Unit locks for NavigationCommitGuard (lost-LoadUri commit guard): the delay
// table and the generation-liveness gate are pure. Scheduling itself needs a
// live main Looper, so on the JVM arm() must stay inert (never throw, never
// run the redispatch) — the redispatch path is device-locked.
public final class NavigationCommitGuardTest {

    @Test
    public void delayBackoffDoublesToCap() {
        assertEquals(2000L, NavigationCommitGuard.delayForAttempt(1));
        assertEquals(4000L, NavigationCommitGuard.delayForAttempt(2));
        assertEquals(8000L, NavigationCommitGuard.delayForAttempt(3));
        assertEquals(8000L, NavigationCommitGuard.delayForAttempt(4));
    }

    @Test
    public void livenessMatchesGenerationOnly() {
        assertTrue(NavigationCommitGuard.isLive(7, 7));
        assertFalse(NavigationCommitGuard.isLive(7, 8));
        assertFalse(NavigationCommitGuard.isLive(8, 7));
    }

    @Test
    public void armDisarmStayInertWithoutLooper() {
        NavigationCommitGuard guard = new NavigationCommitGuard();
        boolean[] fired = {false};
        // Must not throw and must not run the runnable: no main Looper here.
        guard.arm(() -> fired[0] = true);
        guard.disarm();
        assertFalse(fired[0]);
    }

    @Test
    public void rearmSupersedesWithoutFiring() {
        NavigationCommitGuard guard = new NavigationCommitGuard();
        int[] count = {0};
        guard.arm(() -> count[0]++);
        guard.arm(() -> count[0]++);
        guard.disarm();
        assertEquals(0, count[0]);
    }
}
