package org.mozilla.geckowebview.provider;

import android.os.Bundle;
import android.print.PrintDocumentAdapter;
import androidx.annotation.NonNull;

// Honest-degradation fallbacks for WebViewProvider entry points whose true
// implementation needs a Gecko primitive that does not exist yet (AGENTS §4).
// Every fallback degrades (no-op / null / failure-callback) plus a warning
// log — never throws: a bare throw would propagate through the framework
// call and kill the host app process (AGENTS §7; CTS
// testPostUrlWithNetworkUrl crash precedent).
public final class ProviderFallbacks {
    private ProviderFallbacks() {}

    // Print adapter that reports layout, then fails the write, so the print
    // UI shows failure instead of the host process dying. Used when
    // PrintBridge.createAdapter itself throws (session broken).
    @NonNull
    public static PrintDocumentAdapter failingPrintAdapter(String documentName) {
        final String name = documentName != null ? documentName : "";
        return new PrintDocumentAdapter() {
            @Override
            public void onLayout(android.print.PrintAttributes oldAttributes,
                    android.print.PrintAttributes newAttributes,
                    android.os.CancellationSignal cancellationSignal,
                    LayoutResultCallback callback,
                    Bundle extras) {
                callback.onLayoutFinished(
                        new android.print.PrintDocumentInfo.Builder(name)
                                .setContentType(android.print.PrintDocumentInfo
                                        .CONTENT_TYPE_DOCUMENT)
                                .setPageCount(android.print.PrintDocumentInfo
                                        .PAGE_COUNT_UNKNOWN)
                                .build(),
                        true);
            }

            @Override
            public void onWrite(android.print.PageRange[] pages,
                    android.os.ParcelFileDescriptor destination,
                    android.os.CancellationSignal cancellationSignal,
                    WriteResultCallback callback) {
                callback.onWriteFailed("Sinytra: print backend unavailable");
            }
        };
    }
}
