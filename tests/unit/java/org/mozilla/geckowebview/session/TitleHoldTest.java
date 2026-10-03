package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Unit locks for the title-freshness hold (CTS WebSettingsTest image
// family: blockLoadingImages/loadImagesWithoutReload/BlockNetworkImage assert
// getTitle() immediately after loadDataAndWaitForCompletion, but Gecko's
// TitleChange IPC lands ~500ms after PageStop for JS-onload titles).
// The Handler mechanics (arm/fire/timeout) are device-locked (JsTitleProbe
// G/H titleLagMs + those CTS tests on the switched device); only the pure
// hold decision is JVM-locked here.
public final class TitleHoldTest {

    @Test
    public void neverTitledThisNavigation_holds() {
        assertTrue(GeckoSessionBridge.shouldHoldForTitle(null, 0L, 1000L));
    }

    @Test
    public void emptyPlaceholderTitle_holdsEvenWhenFresh() {
        // Gecko reports "" at commit before PageStop: timestamp-fresh but
        // content-empty — must still hold for the post-stop real title.
        assertTrue(GeckoSessionBridge.shouldHoldForTitle("", 1200L, 1000L));
    }

    @Test
    public void previousPageTitleOnly_holds() {
        assertTrue(GeckoSessionBridge.shouldHoldForTitle("48", 500L, 1000L));
    }

    @Test
    public void titleAfterStart_forwardsAtOnce() {
        assertFalse(GeckoSessionBridge.shouldHoldForTitle("0", 1200L, 1000L));
    }

    @Test
    public void sameMsTie_readsFresh() {
        assertFalse(GeckoSessionBridge.shouldHoldForTitle("0", 1000L, 1000L));
    }

    @Test
    public void staticTitleBeforeStop_forwardsAtOnce() {
        // Static <title> lands between start and stop: the common path pays
        // zero added latency.
        assertFalse(GeckoSessionBridge.shouldHoldForTitle(
                "Hello World", 1500L, 1000L));
    }

    @Test
    public void placeholderDoesNotRegressMidNavigation() {
        // Commit placeholder: keep the previous title while navigating.
        assertFalse(GeckoSessionBridge.shouldAdoptTitle("", "0", true));
        assertFalse(GeckoSessionBridge.shouldAdoptTitle(null, "48", true));
    }

    @Test
    public void emptyAdoptedOutsideNavigations() {
        // Settled pages report honestly, including empty.
        assertTrue(GeckoSessionBridge.shouldAdoptTitle("", "0", false));
        assertTrue(GeckoSessionBridge.shouldAdoptTitle(null, null, true));
        assertTrue(GeckoSessionBridge.shouldAdoptTitle("48", "0", true));
    }
}
