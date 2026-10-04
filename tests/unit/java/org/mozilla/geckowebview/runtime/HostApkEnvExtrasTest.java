package org.mozilla.geckowebview.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

// Unit locks for the host-APK env extras (0020): the Bundle must never be
// null and construction must never throw (a missing path degrades to stock
// Gecko file behavior). Real-path content is device-locked (needs a live
// app context + child launch; CTS file family covers it).
public final class HostApkEnvExtrasTest {

    @Test
    public void nullContext_doesNotThrow() {
        // The mockable android.jar stubs Bundle (isEmpty() is meaningless
        // here); this locks only the never-throw contract. Real content is
        // device-locked (CTS file family).
        GeckoRuntimeHolder.hostApkEnvExtras(null);
    }

    @Test
    public void pathGuard_matchesContract() {
        assertNull(GeckoRuntimeHolder.hostApkPathOrNull(null));
        assertNull(GeckoRuntimeHolder.hostApkPathOrNull(""));
        assertEquals("/data/app/x/base.apk", GeckoRuntimeHolder
                .hostApkPathOrNull("/data/app/x/base.apk"));
    }
}
