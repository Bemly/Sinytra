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

    // --- progress-aware checks (2026-10-08 trickle round): attempts count
    // consecutive QUIET checks. Any necko consult resets the ladder
    // instead of re-issuing a progressing load; only sustained quiet
    // exhausts. poke() itself is Looper-free (flag only). ---

    @Test
    public void nextAttempt_quietClimbsToExhaustion() {
        assertEquals(2, NavigationCommitGuard.nextAttempt(false, 1));
        assertEquals(3, NavigationCommitGuard.nextAttempt(false, 2));
        assertEquals(-1, NavigationCommitGuard.nextAttempt(false, 3));
    }

    @Test
    public void nextAttempt_progressResetsLadder() {
        assertEquals(1, NavigationCommitGuard.nextAttempt(true, 1));
        assertEquals(1, NavigationCommitGuard.nextAttempt(true, 2));
        assertEquals(1, NavigationCommitGuard.nextAttempt(true, 3));
    }

    @Test
    public void poke_isLooperFreeAndHarmless() {
        NavigationCommitGuard guard = new NavigationCommitGuard();
        guard.arm(() -> {
            throw new AssertionError("must stay inert without Looper");
        });
        guard.poke("https://example.com/");
        guard.poke("https://example.com/");
        guard.disarm();
    }

    // --- total-refire cap (2026-10-08 S3 fission-suppression round): pokes
    // reset the ladder, so consult chatter alone must not authorize infinite
    // refires. Past the cap without a commit the chain escalates to reopen.

    @Test
    public void forceExhaust_boundsTotalRefires() {
        assertFalse(NavigationCommitGuard.shouldForceExhaust(0));
        assertFalse(NavigationCommitGuard.shouldForceExhaust(1));
        assertFalse(NavigationCommitGuard.shouldForceExhaust(
                NavigationCommitGuard.MAX_TOTAL_FIRES));
        assertTrue(NavigationCommitGuard.shouldForceExhaust(
                NavigationCommitGuard.MAX_TOTAL_FIRES + 1));
    }

    // --- consult latch (2026-10-08 coolapk COMMIT-SUPPRESSED round): once
    // any consult reaches Java the dispatch provably landed, so the arm only
    // extends from then on. Lifecycle is Looper-free (flag only). ---

    @Test
    public void shouldExtend_latchOrProgressExtends() {
        assertFalse(NavigationCommitGuard.shouldExtend(false, false));
        assertTrue(NavigationCommitGuard.shouldExtend(true, false));
        assertTrue(NavigationCommitGuard.shouldExtend(false, true));
        assertTrue(NavigationCommitGuard.shouldExtend(true, true));
    }

    @Test
    public void latch_setByFirstPokeOnly() {
        NavigationCommitGuard guard = new NavigationCommitGuard();
        guard.arm(() -> {
            throw new AssertionError("must stay inert without Looper");
        });
        assertFalse(guard.isConsultLatched());
        guard.poke("https://example.com/");
        assertTrue(guard.isConsultLatched());
        guard.poke("https://example.com/");
        assertTrue(guard.isConsultLatched());
        guard.disarm();
    }

    @Test
    public void latch_clearedByDisarm() {
        NavigationCommitGuard guard = new NavigationCommitGuard();
        guard.arm(() -> {});
        guard.poke("https://example.com/");
        assertTrue(guard.isConsultLatched());
        guard.disarm();
        assertFalse(guard.isConsultLatched());
    }

    @Test
    public void latch_clearedByRearm() {
        NavigationCommitGuard guard = new NavigationCommitGuard();
        guard.arm(() -> {});
        guard.poke("https://example.com/");
        assertTrue(guard.isConsultLatched());
        guard.arm(() -> {});
        assertFalse(guard.isConsultLatched());
        guard.disarm();
    }

    @Test
    public void latch_nullUrlStillLatches() {
        NavigationCommitGuard guard = new NavigationCommitGuard();
        guard.arm(() -> {});
        guard.poke(null);
        assertTrue(guard.isConsultLatched());
        guard.disarm();
    }

    // --- latched-silence escape (2026-10-14 freezer round): a latched arm
    // with zero further pokes is a wedged/frozen content side, not a slow
    // network. Only the pure budget predicate is locked here (the timed
    // check needs a live Looper: device-covered). ---

    @Test
    public void latchedSilence_withinBudgetHolds() {
        assertFalse(NavigationCommitGuard.latchedSilenceSpent(0));
        assertFalse(NavigationCommitGuard.latchedSilenceSpent(1));
        assertFalse(
                NavigationCommitGuard.latchedSilenceSpent(
                        NavigationCommitGuard.MAX_LATCHED_SILENCE_MS - 1));
        assertFalse(
                NavigationCommitGuard.latchedSilenceSpent(
                        NavigationCommitGuard.MAX_LATCHED_SILENCE_MS));
    }

    @Test
    public void latchedSilence_beyondBudgetExhausts() {
        assertTrue(
                NavigationCommitGuard.latchedSilenceSpent(
                        NavigationCommitGuard.MAX_LATCHED_SILENCE_MS + 1));
        assertTrue(NavigationCommitGuard.latchedSilenceSpent(60_000L));
        assertTrue(NavigationCommitGuard.latchedSilenceSpent(Long.MAX_VALUE));
    }

    @Test
    public void latchedSilence_negativeNeverExhausts() {
        // Clock skew backwards (or the zero-clock JVM fallback) must fail
        // safe toward never reopening.
        assertFalse(NavigationCommitGuard.latchedSilenceSpent(-1));
        assertFalse(NavigationCommitGuard.latchedSilenceSpent(Long.MIN_VALUE));
    }
}
