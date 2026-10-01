package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Unit locks for the Chromium-shaped default UA (user拍板 2026-10-01).
// The Build-field reading stays at the call sites so the builder is
// pure and JVM-testable. The wire-equals-settings contract is
// device-locked (CTS testAccessUserAgentString).
public final class ChromiumUaTest {

    @Test
    public void shape_matchesCtsRegex() {
        String ua = ChromiumUa.build("14", "MD_PH_001", "UP1A.231005.007");
        assertEquals("Mozilla/5.0 (Linux; Android 14; MD_PH_001"
                + " Build/UP1A.231005.007; wv) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Version/4.0 Chrome/156.0.0.0"
                + " Mobile Safari/537.36", ua);
    }

    @Test
    public void blanks_degradeWithoutThrowing() {
        String ua = ChromiumUa.build("", "", "");
        assertTrue(ua.contains("; wv)"));
        assertTrue(ua.contains("Chrome/"));
    }
}
