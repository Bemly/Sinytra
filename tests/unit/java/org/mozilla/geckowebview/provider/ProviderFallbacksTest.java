package org.mozilla.geckowebview.provider;

import static org.junit.Assert.assertNotNull;

import android.print.PrintDocumentAdapter;
import org.junit.Test;

// Unit locks for the honest-degradation contract (AGENTS §7: fallbacks must
// never throw and never hand the framework a null that would NPE the host
// process — CTS testPostUrlWithNetworkUrl crash precedent). Callback
// behavior (layout-finished + write-failed) stays device-locked: the
// result-callback types are abstract framework classes with no JVM
// stand-ins (no mockito on this classpath).
public final class ProviderFallbacksTest {

    @Test
    public void failingAdapter_returnsNonNull() {
        PrintDocumentAdapter adapter =
                ProviderFallbacks.failingPrintAdapter("doc");
        assertNotNull(adapter);
    }

    @Test
    public void failingAdapter_nullName_returnsNonNull() {
        PrintDocumentAdapter adapter =
                ProviderFallbacks.failingPrintAdapter(null);
        assertNotNull(adapter);
    }
}
