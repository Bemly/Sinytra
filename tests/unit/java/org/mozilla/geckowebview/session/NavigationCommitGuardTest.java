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

    @Test
    public void exhaustionStaysInertWithoutLooper() {
        NavigationCommitGuard guard = new NavigationCommitGuard();
        boolean[] exhausted = {false};
        guard.arm(() -> {}, () -> exhausted[0] = true);
        guard.disarm();
        assertFalse(exhausted[0]);
    }

    @Test
    public void pokeFreshGuard_isInert() {
        // Never armed: no window to restart, must not throw.
        assertFalse(new NavigationCommitGuard().poke());
    }

    @Test
    public void pokeArmedGuard_restartsWindow() {
        NavigationCommitGuard guard = new NavigationCommitGuard();
        guard.arm(() -> {});
        assertTrue(guard.poke());
    }

    @Test
    public void pokeAfterDisarm_neverResurrects() {
        NavigationCommitGuard guard = new NavigationCommitGuard();
        guard.arm(() -> {});
        guard.disarm();
        assertFalse(guard.poke());
    }

    @Test
    public void pokeIsIdempotentWhileArmed() {
        // Heartbeats must not consume budget or collapse state: repeated
        // pokes stay armed (device behavior — redirect chains consult per
        // hop for many windows — is device-locked; only the latch is here).
        NavigationCommitGuard guard = new NavigationCommitGuard();
        guard.arm(() -> {});
        assertTrue(guard.poke());
        assertTrue(guard.poke());
        guard.disarm();
        assertFalse(guard.poke());
    }
}
