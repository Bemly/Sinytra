package org.mozilla.geckowebview.session;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

// Unit locks for the console level mapping (pure strings). Delivery is
// device-locked: the jsConsole probe (warn path) plus CTS
// testOnConsoleMessage (all four levels + exact line numbers).
public final class ConsoleBridgeTest {

    @Test
    public void levels_mapToFrameworkNames() {
        assertEquals("LOG", ConsoleBridge.messageLevelFor("log"));
        assertEquals("LOG", ConsoleBridge.messageLevelFor("info"));
        assertEquals("WARNING", ConsoleBridge.messageLevelFor("warn"));
        assertEquals("WARNING", ConsoleBridge.messageLevelFor("warning"));
        assertEquals("ERROR", ConsoleBridge.messageLevelFor("error"));
        assertEquals("DEBUG", ConsoleBridge.messageLevelFor("debug"));
    }

    @Test
    public void unknown_degradesToLog() {
        assertEquals("LOG", ConsoleBridge.messageLevelFor(null));
        assertEquals("LOG", ConsoleBridge.messageLevelFor("trace"));
    }
}
