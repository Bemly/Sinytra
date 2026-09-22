package org.mozilla.geckowebview.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Unit locks for the P2-8 honest feature set: only end-to-end implemented
// features are claimed; everything else stays unclaimed so
// WebViewFeature.isFeatureSupported() degrades honestly. The harness
// locks the count; this locks the invariants.
public final class SupportedFeaturesTest {

    private static final int EXPECTED_COUNT = 18;

    @Test
    public void claimedCount_isExactlyTheHonestSet() {
        assertEquals(EXPECTED_COUNT, SupportedFeatures.all().length);
    }

    @Test
    public void harnessLockedFeatures_areClaimed() {
        assertTrue(SupportedFeatures.contains("GET_WEB_VIEW_CLIENT"));
        assertTrue(SupportedFeatures.contains("GET_WEB_CHROME_CLIENT"));
        assertTrue(SupportedFeatures.contains("GET_WEB_VIEW_RENDERER"));
        assertTrue(SupportedFeatures.contains("TRACING_CONTROLLER_BASIC_USAGE"));
        assertTrue(SupportedFeatures.contains("SAVE_STATE"));
        assertTrue(SupportedFeatures.contains("VISUAL_STATE_CALLBACK"));
        assertTrue(SupportedFeatures.contains("SERVICE_WORKER_BASIC_USAGE"));
    }

    @Test
    public void unimplementedFeatures_neverLeak() {
        // Explicitly NOT implemented (STATUS §1c): JS injection, message
        // listeners, proxy/dropData/builder/profileStore, startup config.
        String[] unclaimed = {
                "JS_INJECTION",
                "JS_INJECTION_IN_FRAME_AND_WORLD",
                "WEB_MESSAGE_LISTENER",
                "PROXY_OVERRIDE",
                "PROXY_CONTROLLER",
                "DROP_DATA_PROVIDER",
                "WEBVIEW_STARTUP_FEATURE_CONFIG",
                "PROCESS_GLOBAL_CONFIG",
                "WEB_MESSAGE_ARRAY_WITH_PORTS",
                "SAFE_BROWSING_RESPONSE_SHOW_INTERSTITIAL",
                "GET_WEB_VIEW_SUPPORT_LIB_VERSION",
        };
        for (String feature : unclaimed) {
            assertFalse("unimplemented feature must not be claimed: " + feature,
                    SupportedFeatures.contains(feature));
        }
    }

    @Test
    public void unknownFeature_isNotClaimed() {
        assertFalse(SupportedFeatures.contains("NOT_A_REAL_FEATURE"));
        assertFalse(SupportedFeatures.contains(""));
    }

    @Test
    public void all_returnsDefensiveCopy() {
        String[] first = SupportedFeatures.all();
        first[0] = "MUTATED";
        assertTrue(SupportedFeatures.contains("GET_WEB_VIEW_CLIENT"));
        assertEquals(EXPECTED_COUNT, SupportedFeatures.all().length);
    }
}
