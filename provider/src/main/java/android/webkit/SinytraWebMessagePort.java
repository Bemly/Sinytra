package android.webkit;

import android.os.Handler;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Sinytra: framework-typed WebMessagePort with live backing over the
// provider's MessageBridge ports (P2-3 framework face).
//
// The framework base ctor is public @SystemApi ("Only for use by
// WebViewProvider implementations", android14-release source) — the public
// SDK android.jar strips it to package-private, so the subclass lives in
// the android.webkit package: same-package placement satisfies javac while
// the runtime ctor is public. Same pattern as SinytraSslErrorHandler
// (device-verified: harness sslProceed + 39 PASS). Fresh class name —
// Chromium's android.webkit.WebMessagePortImpl occupies only its own name.
// This resolves the ProviderAdapters.WebMessagePortFactory decision point
// (AOSP patch vs factory hook) with neither: no framework change needed.
// The old record had it backwards: the "package-private ctor" was javap of
// the android.jar stub, where @SystemApi is stripped.
//
// Why self-written (AGENTS.md §4): the framework WebMessagePort contract
// needs a concrete subclass and the base class has no implementation;
// Chromium solves it inside its glue (WebMessagePortImpl), which is a
// "Chromium 语义无对应" case for Gecko. The port routes into MessageBridge
// (JsBridge WebExtension transport — same page plumbing as the boundary
// face, CompatSmallBoundaries.LiveMessagePort).
//
// Chromium-parity semantics:
// - postMessage/setWebMessageCallback after close() throw
//   IllegalStateException ("closed port" parity);
// - a second non-null setWebMessageCallback throws
//   IllegalStateException ("Callback already set" parity);
// - onMessage delivers the port ITSELF as first argument (Chromium passes
//   the receiving port; MessageBridge's bare callback surface passes null,
//   so the wrapper below substitutes `this`).
//
// Honest gap: WebMessage port transfer is not routed — the JsBridge
// transport has no transferable-port primitive (MessageBridge header
// records the same gap). Message data rides the transport.
public final class SinytraWebMessagePort extends WebMessagePort {

    // Route surface injected by the provider — keeps this class free of
    // org.mozilla.* imports (same decision-injection style as
    // SinytraSslErrorHandler's Runnables).
    public interface Binding {
        void post(@NonNull String data);

        void close();

        void setCallback(@Nullable WebMessageCallback callback);
    }

    @NonNull
    private final Binding mBinding;
    private volatile boolean mCallbackSet;
    private volatile boolean mClosed;

    public SinytraWebMessagePort(@NonNull Binding binding) {
        mBinding = binding;
    }

    @Override
    public void postMessage(@NonNull WebMessage message) {
        if (mClosed) {
            throw new IllegalStateException(
                    "Cannot postMessage on a closed port");
        }
        String data = message != null ? message.getData() : null;
        mBinding.post(data != null ? data : "");
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        mBinding.close();
    }

    @Override
    public void setWebMessageCallback(@NonNull WebMessageCallback callback) {
        setWebMessageCallback(callback, null);
    }

    @Override
    public void setWebMessageCallback(@NonNull WebMessageCallback callback,
            @Nullable Handler handler) {
        if (mClosed) {
            throw new IllegalStateException("Cannot setWebMessageCallback "
                    + "on a closed port");
        }
        if (callback == null) {
            // Honest clear (boundary parity): MessageBridge supports it.
            mCallbackSet = false;
            mBinding.setCallback(null);
            return;
        }
        if (mCallbackSet) {
            throw new IllegalStateException("Callback already set");
        }
        mCallbackSet = true;
        final WebMessageCallback app = callback;
        final SinytraWebMessagePort self = this;
        WebMessageCallback wrapped = new WebMessageCallback() {
            @Override
            public void onMessage(WebMessagePort port, WebMessage message) {
                Runnable fire = () -> {
                    try {
                        app.onMessage(self, message);
                    } catch (Throwable t) {
                        android.util.Log.w("Sinytra/message",
                                "fw port onMessage threw", t);
                    }
                };
                if (handler != null) {
                    if (!handler.post(fire)) {
                        fire.run();
                    }
                } else {
                    // No handler: MessageBridge delivers on the transport
                    // thread (UI thread per the GeckoView delegate
                    // contract) — Chromium's default delivery surface.
                    fire.run();
                }
            }
        };
        mBinding.setCallback(wrapped);
    }
}
