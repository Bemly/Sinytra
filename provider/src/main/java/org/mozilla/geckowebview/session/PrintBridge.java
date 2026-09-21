package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

// Print over GeckoSession.printPageContent / didPrintPageContent.
// No independent state: every call delegates to the live session.
public final class PrintBridge {
    private final GeckoSession mSession;

    public PrintBridge(@NonNull GeckoSession session) {
        mSession = session;
    }

    @NonNull
    public android.print.PrintDocumentAdapter createAdapter(
            @NonNull android.content.Context context, @NonNull String documentName) {
        GeckoSession session = mSession;
        return new android.print.PrintDocumentAdapter() {
            @Override
            public void onWrite(android.print.PageRange[] pages,
                    android.os.ParcelFileDescriptor destination,
                    android.os.CancellationSignal cancellationSignal,
                    WriteResultCallback callback) {
                try {
                    session.printPageContent();
                    GeckoResult<Boolean> done = session.didPrintPageContent();
                    done.accept(ok -> {
                        if (Boolean.TRUE.equals(ok)) {
                            callback.onWriteFinished(
                                    new android.print.PageRange[] {
                                        android.print.PageRange.ALL_PAGES});
                        } else {
                            callback.onWriteFailed("Gecko print reported failure");
                        }
                    }, e -> callback.onWriteFailed(
                            e != null ? e.toString() : "Gecko print failed"));
                } catch (Throwable t) {
                    callback.onWriteFailed(t.toString());
                }
            }

            @Override
            public void onLayout(android.print.PrintAttributes oldAttributes,
                    android.print.PrintAttributes newAttributes,
                    android.os.CancellationSignal cancellationSignal,
                    LayoutResultCallback callback,
                    android.os.Bundle extras) {
                callback.onLayoutFinished(
                        new android.print.PrintDocumentInfo.Builder(
                                documentName).setContentType(
                                android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                                .setPageCount(
                                        android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                                .build(),
                        true);
            }
        };
    }
}
