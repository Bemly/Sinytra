package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;
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
        assertTrue(SessionRescue.shouldWaitForTransport(false, 0));
        assertTrue(SessionRescue.shouldWaitForTransport(false,
                SessionRescue.MAX_COLD_EXTENSIONS - 1));
    }

    @Test
    public void triage_unreadyOverBudget_reopens() {
        assertFalse(SessionRescue.shouldWaitForTransport(false,
                SessionRescue.MAX_COLD_EXTENSIONS));
        assertFalse(SessionRescue.shouldWaitForTransport(false,
                SessionRescue.MAX_COLD_EXTENSIONS + 10));
    }

    @Test
    public void triage_readyAlwaysReopens() {
        assertFalse(SessionRescue.shouldWaitForTransport(true, 0));
        assertFalse(SessionRescue.shouldWaitForTransport(true,
                SessionRescue.MAX_COLD_EXTENSIONS));
    }

    @Test
    public void triage_coldBudgetIsMonotonic() {
        // Every extension step below the cap waits; the cap itself reopens.
        for (int used = 0; used < SessionRescue.MAX_COLD_EXTENSIONS; used++) {
            assertTrue(SessionRescue.shouldWaitForTransport(false, used));
        }
    }

    @Test
    public void redirectLeg_armsAfterQuietCommit() {
        assertTrue(SessionRescue.shouldArmRedirectLeg(false,
                "https://www.coolapk.com/link?url=x", true,
                "https://github.com/Bemly/kokoro-no-kitsune/releases/tag/27001b"));
    }

    @Test
    public void redirectLeg_armedChainNeverHijacked() {
        assertFalse(SessionRescue.shouldArmRedirectLeg(true,
                "https://a.example/", true, "https://b.example/"));
    }

    @Test
    public void redirectLeg_uncommittedNeverArms() {
        assertFalse(SessionRescue.shouldArmRedirectLeg(false, null,
                true, "https://b.example/"));
    }

    @Test
    public void redirectLeg_nonRedirectNeverArms() {
        assertFalse(SessionRescue.shouldArmRedirectLeg(false,
                "https://a.example/", false, "https://b.example/"));
    }

    @Test
    public void redirectLeg_sameDocumentNeverArms() {
        assertFalse(SessionRescue.shouldArmRedirectLeg(false,
                "https://a.example/p", true, "https://a.example/p#frag"));
        assertFalse(SessionRescue.shouldArmRedirectLeg(false,
                "HTTPS://A.EXAMPLE/p", true, "https://a.example/p"));
        assertFalse(SessionRescue.shouldArmRedirectLeg(false,
                "https://a.example/", true, null));
        assertFalse(SessionRescue.shouldArmRedirectLeg(false,
                "https://a.example/", true, "about:blank"));
    }

    @Test
    public void rescue_armedTransitions() {
        // SessionRescue(null): @NonNull is advisory; arm/disarm touch only
        // the guard generation on the JVM (scheduling degrades inert
        // without a Looper), never the session.
        SessionRescue rescue = new SessionRescue(null);
        assertFalse(rescue.isArmed());
        rescue.arm(() -> {
        }, "https://a.example/");
        assertTrue(rescue.isArmed());
        rescue.disarm();
        assertFalse(rescue.isArmed());
    }

    @Test
    public void rescue_redirectLegArmsOnlyPostCommitCrossDocument() {
        SessionRescue rescue = new SessionRescue(null);
        Runnable redispatch = () -> {
        };
        // Uncommitted: quiet even for a cross-document redirect.
        rescue.watchRedirectLeg("https://b.example/", true, redispatch);
        assertFalse(rescue.isArmed());
        // Commit, then same-document: quiet.
        rescue.noteCommit("https://a.example/p");
        rescue.watchRedirectLeg("https://a.example/p#frag", true,
                redispatch);
        assertFalse(rescue.isArmed());
        // Commit, then cross-document redirect: armed.
        rescue.watchRedirectLeg("https://b.example/", true, redispatch);
        assertTrue(rescue.isArmed());
        // New direct navigation clears the commit memory.
        rescue.clearCommit();
        rescue.disarm();
        rescue.noteCommit("https://a.example/p");
        rescue.watchRedirectLeg("https://b.example/", false, redispatch);
        assertFalse(rescue.isArmed());
    }

    // Unclaimed-takeover recovery (BISECT_SNC_20261013.md §6 P0): an
    // override-DENY with no reload-or-commit must re-issue once with a
    // one-shot force-allow. Only the slot/consume/fire transitions are
    // locked here (the Handler timer needs a live Looper: device-covered).
    @Test
    public void overrideRecovery_unarmedConsumeAndFireMiss() {
        SessionRescue rescue = new SessionRescue(null);
        assertFalse(rescue.consumeRecoveryAllow("https://a.example/"));
        assertFalse(rescue.consumeRecoveryAllow(null));
        assertFalse(rescue.fireOverride("https://a.example/"));
    }

    @Test
    public void overrideRecovery_fireAllowsOnceExactly() {
        SessionRescue rescue = new SessionRescue(null);
        final int[] runs = {0};
        rescue.onAppOverride("https://a.example/", () -> runs[0]++);
        assertTrue(rescue.fireOverride("https://a.example/"));
        assertEquals(1, runs[0]);
        assertTrue(rescue.consumeRecoveryAllow("https://a.example/"));
        assertFalse(rescue.consumeRecoveryAllow("https://a.example/"));
        // Already fired: a stale refire is inert.
        assertFalse(rescue.fireOverride("https://a.example/"));
    }

    @Test
    public void overrideRecovery_mismatchPreservesFlag() {
        SessionRescue rescue = new SessionRescue(null);
        rescue.onAppOverride("https://a.example/", () -> {
        });
        assertTrue(rescue.fireOverride("https://a.example/"));
        assertFalse(rescue.consumeRecoveryAllow("https://b.example/"));
        assertTrue(rescue.consumeRecoveryAllow("https://a.example/"));
    }

    @Test
    public void overrideRecovery_supersedeKillsStale() {
        SessionRescue rescue = new SessionRescue(null);
        rescue.onAppOverride("https://a.example/", () -> {
        });
        rescue.onAppOverride("https://b.example/", () -> {
        });
        assertFalse(rescue.fireOverride("https://a.example/"));
        assertTrue(rescue.fireOverride("https://b.example/"));
    }

    @Test
    public void overrideRecovery_commitCancels() {
        SessionRescue rescue = new SessionRescue(null);
        rescue.onAppOverride("https://a.example/", () -> {
        });
        rescue.noteCommit("https://a.example/");
        assertFalse(rescue.fireOverride("https://a.example/"));
        assertFalse(rescue.consumeRecoveryAllow("https://a.example/"));
    }

    @Test
    public void overrideRecovery_newNavigationCancels() {
        SessionRescue rescue = new SessionRescue(null);
        rescue.onAppOverride("https://a.example/", () -> {
        });
        rescue.arm(() -> {
        }, "https://b.example/");
        assertFalse(rescue.fireOverride("https://a.example/"));
        assertFalse(rescue.consumeRecoveryAllow("https://a.example/"));
    }

    @Test
    public void blankStart_implicitSwallowed() {
        assertTrue(GeckoSessionBridge.shouldSwallowBlankStart("about:blank",
                false));
        assertTrue(GeckoSessionBridge.shouldSwallowBlankStart("ABOUT:BLANK",
                false));
    }

    @Test
    public void blankStart_explicitAlwaysReports() {
        assertFalse(GeckoSessionBridge.shouldSwallowBlankStart("about:blank",
                true));
        assertFalse(GeckoSessionBridge.shouldSwallowBlankStart("ABOUT:BLANK",
                true));
    }

    @Test
    public void blankStart_realUrlsAlwaysReport() {
        assertFalse(GeckoSessionBridge.shouldSwallowBlankStart(
                "https://example.com/", false));
        assertFalse(GeckoSessionBridge.shouldSwallowBlankStart(
                "https://example.com/", true));
        assertFalse(GeckoSessionBridge.shouldSwallowBlankStart(null, false));
        assertFalse(GeckoSessionBridge.shouldSwallowBlankStart("about:srcdoc",
                false));
    }
}
