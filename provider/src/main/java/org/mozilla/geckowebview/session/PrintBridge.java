package org.mozilla.geckowebview.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

// Print over GeckoSession.saveAsPdf(): Gecko generates the page PDF and
// the adapter streams it into the destination ParcelFileDescriptor the
// Android print framework handed us.
//
// No independent state: every call delegates to the live session.
//
// Why not upstream GeckoViewPrintDocumentAdapter (AGENTS §4 check): it
// takes the PDF InputStream EAGERLY at construction, but saveAsPdf is
// async — the stream only exists after onWrite already needs to write.
// The eager-ctor shape does not fit; the manual stream copy below is the
// adaptation (15 lines), not a parallel implementation.
//
// Chromium fidelity notes: onLayout reports PAGE_COUNT_UNKNOWN (Gecko does
// not expose a page count before rendering — Chromium computes layout
// metrics); cancellation between saveAsPdf and the copy is best-effort
// (the PDF generation itself cannot be aborted).
//
// writeTo is the framework-independent core (probe/debug entry): the
// PrintDocumentAdapter wrapper below stays a thin shell whose callbacks
// (package-private abstract framework types) are never constructed here.
public final class PrintBridge {
    private static final String TAG = "Sinytra/print";

    public interface WriteCompletion {
        void onFinished();
        void onFailed(@Nullable String error);
    }

    private final GeckoSession mSession;
    @Nullable
    private android.os.Handler mMain;

    public PrintBridge(@NonNull GeckoSession session) {
        mSession = session;
    }

    // Generates the page PDF and streams it into destination. Completion
    // fires on the main thread. Blocking IO runs on a worker (AGENTS §7).
    public void writeTo(@NonNull ParcelFileDescriptorHolder destination,
            @NonNull WriteCompletion completion) {
        main().post(() -> {
            try {
                GeckoResult<InputStream> pdf = mSession.saveAsPdf();
                pdf.accept(
                        stream -> deliver(stream, destination, completion),
                        e -> completion.onFailed(
                                e != null ? e.toString() : "Gecko PDF failed"));
            } catch (Throwable t) {
                android.util.Log.w(TAG, "saveAsPdf threw", t);
                completion.onFailed(t.toString());
            }
        });
    }

    // Hold the fd via a holder so callers keep ownership/close semantics;
    // ParcelFileDescriptor itself works too (identity holder).
    public static final class ParcelFileDescriptorHolder {
        public final android.os.ParcelFileDescriptor fd;

        public ParcelFileDescriptorHolder(
                @NonNull android.os.ParcelFileDescriptor fd) {
            this.fd = fd;
        }
    }

    private void deliver(@Nullable InputStream pdf,
            @NonNull ParcelFileDescriptorHolder destination,
            @NonNull WriteCompletion completion) {
        if (pdf == null) {
            completion.onFailed("Gecko produced no PDF stream");
            return;
        }
        new Thread(() -> {
            try (InputStream in = pdf;
                    OutputStream out = new android.os.ParcelFileDescriptor
                            .AutoCloseOutputStream(destination.fd)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
                main().post(completion::onFinished);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "PDF copy threw", t);
                main().post(() -> completion.onFailed(t.toString()));
            }
        }, "sinytra-print").start();
    }

    @NonNull
    public android.print.PrintDocumentAdapter createAdapter(
            @NonNull android.content.Context context, @NonNull String documentName) {
        return new android.print.PrintDocumentAdapter() {
            private final AtomicBoolean mCancelled = new AtomicBoolean(false);

            @Override
            public void onLayout(android.print.PrintAttributes oldAttributes,
                    android.print.PrintAttributes newAttributes,
                    android.os.CancellationSignal cancellationSignal,
                    LayoutResultCallback callback,
                    android.os.Bundle extras) {
                if (cancellationSignal.isCanceled()) {
                    callback.onLayoutCancelled();
                    return;
                }
                callback.onLayoutFinished(
                        new android.print.PrintDocumentInfo.Builder(
                                documentName).setContentType(
                                android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                                .setPageCount(
                                        android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                                .build(),
                        true);
            }

            @Override
            public void onWrite(android.print.PageRange[] pages,
                    android.os.ParcelFileDescriptor destination,
                    android.os.CancellationSignal cancellationSignal,
                    WriteResultCallback callback) {
                if (cancellationSignal != null) {
                    cancellationSignal.setOnCancelListener(
                            () -> mCancelled.set(true));
                }
                writeTo(new ParcelFileDescriptorHolder(destination),
                        new WriteCompletion() {
                            @Override
                            public void onFinished() {
                                if (mCancelled.get()) {
                                    callback.onWriteFailed("cancelled");
                                } else {
                                    callback.onWriteFinished(
                                            new android.print.PageRange[] {
                                                    android.print.PageRange.ALL_PAGES});
                                }
                            }

                            @Override
                            public void onFailed(@Nullable String error) {
                                callback.onWriteFailed(
                                        mCancelled.get() ? "cancelled"
                                                : error);
                            }
                        });
            }
        };
    }

    // Print callbacks must fire on the thread that started the print job
    // (the UI thread for PrintManager flows) — keep a main-thread hop.
    private android.os.Handler main() {
        if (mMain == null) {
            mMain = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        return mMain;
    }
}
