package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Unit locks for the pristine-seed-blank rule (Coolapk black screen,
// device 2026-10-14): Gecko seeds every fresh session with an
// about:blank document that Chromium never reports. Both commit-signal
// sites (onPageStarted forward + guard disarm, onUrlChanged adoption +
// guard disarm) share this predicate with the onPageFinished swallow, so
// a swallowed Start always pairs with a swallowed Stop and never with a
// stranded guard. Wiring (forward/disarmed or not) is device-locked;
// only the pure predicate is JVM-locked here.
public final class PristineSeedCommitTest {

    @Test
    public void seedBlankWithoutExplicitLoad_isPristine() {
        assertTrue(GeckoSessionBridge.isPristineSeedCommit(
                "about:blank", false));
    }

    @Test
    public void seedBlankIsCaseInsensitive() {
        assertTrue(GeckoSessionBridge.isPristineSeedCommit(
                "ABOUT:BLANK", false));
    }

    @Test
    public void explicitAboutLoad_alwaysReports() {
        assertFalse(GeckoSessionBridge.isPristineSeedCommit(
                "about:blank", true));
    }

    @Test
    public void realUrl_neverPristine() {
        assertFalse(GeckoSessionBridge.isPristineSeedCommit(
                "https://www.example.com/", false));
    }

    @Test
    public void nullUrl_neverPristine() {
        assertFalse(GeckoSessionBridge.isPristineSeedCommit(null, false));
    }

    @Test
    public void otherAboutUrl_neverPristine() {
        // Only the exact seed document is silenced; about:config-style
        // URLs keep existing behavior.
        assertFalse(GeckoSessionBridge.isPristineSeedCommit(
                "about:config", false));
    }
}
