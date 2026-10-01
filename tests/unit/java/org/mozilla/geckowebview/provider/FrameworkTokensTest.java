package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

// Fallback locks for the dialog-token helpers. On the JVM the mockable
// android.jar lacks both the ResultReceiver ctor parameter and the
// getResult()/getStringResult() readers (public @SystemApi on the API-34
// device, stripped from the stub), so every reflective path misses and
// the helpers degrade to null/false. The true paths — token constructs,
// the app answers synchronously, the answer routes to Gecko — are locked
// by the device jsDialog probe instead (same split as
// WebMessage.getData(), which the mockable jar also pins to null).
public final class FrameworkTokensTest {

    @Test
    public void tokenConstruction_degradesToNull() {
        assertNull(FrameworkTokens.newJsResult());
        assertNull(FrameworkTokens.newJsPromptResult());
    }

    @Test
    public void readback_degradesToNegative() {
        assertFalse(FrameworkTokens.jsResultValue(null));
        assertNull(FrameworkTokens.jsPromptString(null));
    }
}
