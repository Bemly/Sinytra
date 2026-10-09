package org.mozilla.geckowebview.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

// Unit locks for SinytraLog (central leveled gate): the gating table is
// pure; emitting through android.util.Log is stubbed on the JVM
// (returnDefaultValues) so the emit paths are safe to call here. Static
// process state is restored after every test.
public final class SinytraLogTest {
    private static final String TAG = "Sinytra/test";

    @After
    public void restoreAutomatic() {
        SinytraLog.setLevel(-1);
        SinytraLog.init(false);
    }

    @Test
    public void warnAndErrorAlwaysEnabled() {
        SinytraLog.setLevel(SinytraLog.ERROR);
        assertTrue(SinytraLog.isEnabled(TAG, SinytraLog.WARN));
        assertTrue(SinytraLog.isEnabled(TAG, SinytraLog.ERROR));
        assertFalse(SinytraLog.isEnabled(TAG, SinytraLog.INFO));
    }

    @Test
    public void explicitLevelGatesBelowWarn() {
        SinytraLog.setLevel(SinytraLog.VERBOSE);
        assertTrue(SinytraLog.isEnabled(TAG, SinytraLog.VERBOSE));
        assertTrue(SinytraLog.isEnabled(TAG, SinytraLog.DEBUG));
        SinytraLog.setLevel(SinytraLog.INFO);
        assertFalse(SinytraLog.isEnabled(TAG, SinytraLog.DEBUG));
        assertTrue(SinytraLog.isEnabled(TAG, SinytraLog.INFO));
    }

    @Test
    public void automaticReleaseIsInfoPlus() {
        SinytraLog.init(false);
        assertFalse(SinytraLog.isEnabled(TAG, SinytraLog.VERBOSE));
        assertFalse(SinytraLog.isEnabled(TAG, SinytraLog.DEBUG));
        assertTrue(SinytraLog.isEnabled(TAG, SinytraLog.INFO));
        assertTrue(SinytraLog.isEnabled(TAG, SinytraLog.WARN));
        assertTrue(SinytraLog.isEnabled(TAG, SinytraLog.ERROR));
    }

    @Test
    public void automaticDebuggableIsDebugPlus() {
        SinytraLog.init(true);
        assertFalse(SinytraLog.isEnabled(TAG, SinytraLog.VERBOSE));
        assertTrue(SinytraLog.isEnabled(TAG, SinytraLog.DEBUG));
        assertTrue(SinytraLog.isEnabled(TAG, SinytraLog.INFO));
    }

    @Test
    public void emitNeverThrowsWithoutLooper() {
        SinytraLog.setLevel(SinytraLog.VERBOSE);
        SinytraLog.v(TAG, "v");
        SinytraLog.d(TAG, "d");
        SinytraLog.i(TAG, "i");
        SinytraLog.w(TAG, "w");
        SinytraLog.w(TAG, "w", new RuntimeException("t"));
        SinytraLog.e(TAG, "e");
        SinytraLog.e(TAG, "e", new RuntimeException("t"));
    }
}
