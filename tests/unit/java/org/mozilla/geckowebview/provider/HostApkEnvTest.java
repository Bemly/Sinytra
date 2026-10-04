package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

// Unit locks for the host-APK env publication (0020): file:///android_asset/
// resolves from SINYTRA_HOST_APK, published once per host process before any
// tab spawns. Only the pure guard is JVM-locked (Os.setenv is a real
// process side effect; the device CTS file family locks the end to end).
public final class HostApkEnvTest {

    @Test
    public void nullAndEmpty_yieldNull() {
        assertNull(FileBridge.hostApkEnvValue(null));
        assertNull(FileBridge.hostApkEnvValue(""));
    }

    @Test
    public void realPath_passesThrough() {
        assertEquals("/data/app/x/base.apk", FileBridge
                .hostApkEnvValue("/data/app/x/base.apk"));
    }
}
