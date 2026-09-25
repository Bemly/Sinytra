package android.webkit;

import androidx.annotation.NonNull;

// Sinytra: SslErrorHandler with real proceed()/cancel() routing.
//
// The framework base class is an empty shell ("public void proceed() {}")
// that providers are meant to subclass — the ctor is public @SystemApi
// ("Only for use by WebViewProvider implementations") and Chromium's own
// glue does exactly this anonymous-subclass move in
// WebViewContentsClientAdapter#onReceivedSslError. The public SDK android.jar
// strips that ctor to package-private, so the subclass lives in the
// android.webkit package: same-package placement satisfies javac while the
// runtime ctor is public (reflection precedent: the P1 bare token created
// fine). Fresh class name — the no-collision constraint recorded for
// WebMessagePort (Chromium occupies android.webkit.WebMessagePortImpl) does
// not apply; nothing else defines this name.
//
// Why self-written (AGENTS.md §4): the app's proceed()/cancel() calls are
// the ONLY decision channel for onReceivedSslError (framework contract), and
// the base class offers no way to observe them — Chromium solves it by
// subclassing, which is a "Chromium 语义无对应" case for Gecko.
public final class SinytraSslErrorHandler extends SslErrorHandler {
    @NonNull
    private final Runnable mProceed;
    @NonNull
    private final Runnable mCancel;

    public SinytraSslErrorHandler(@NonNull Runnable proceed,
            @NonNull Runnable cancel) {
        mProceed = proceed;
        mCancel = cancel;
    }

    @Override
    public void proceed() {
        mProceed.run();
    }

    @Override
    public void cancel() {
        mCancel.run();
    }
}
