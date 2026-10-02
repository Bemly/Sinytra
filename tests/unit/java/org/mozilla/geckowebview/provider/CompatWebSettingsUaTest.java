package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.mozilla.geckowebview.settings.GeckoWebSettings;

// Locks the CTS testAccessUserAgentString facade contract: null and ""
// are no-ops (default stays, never stored), a custom string round-trips.
// The wire-equals-settings half stays device-locked (that CTS test).
public final class CompatWebSettingsUaTest {

    @Test
    public void nullAndEmpty_areNoOps() {
        CompatWebSettings settings =
                new CompatWebSettings(new GeckoWebSettings());
        String def = settings.getUserAgentString();
        assertFalse(def == null || def.isEmpty());
        settings.setUserAgentString(null);
        assertEquals(def, settings.getUserAgentString());
        settings.setUserAgentString("");
        assertEquals(def, settings.getUserAgentString());
    }

    @Test
    public void custom_roundTrips() {
        CompatWebSettings settings =
                new CompatWebSettings(new GeckoWebSettings());
        settings.setUserAgentString("Cts/test");
        assertEquals("Cts/test", settings.getUserAgentString());
    }
}
