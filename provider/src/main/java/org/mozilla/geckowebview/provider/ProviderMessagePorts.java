package org.mozilla.geckowebview.provider;

import android.net.Uri;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.SinytraWebMessagePort;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mozilla.geckowebview.runtime.SinytraLog;
import org.mozilla.geckowebview.session.MessageBridge;

// Framework WebMessage channel plumbing, split out of
// GeckoWebViewProvider (AGENTS.md §7 file-size rule). Owns the
// framework-port -> bridge-port route table plus channel creation and
// app-to-page posting; the page transport itself stays in MessageBridge
// (session/). Tag stays Sinytra/session (log continuity with the code's
// previous home).
final class ProviderMessagePorts {
    interface Host {
        @NonNull
        MessageBridge messages();
    }

    private static final String TAG = "Sinytra/session";

    @NonNull
    private final Host mHost;
    // Framework port -> bridge port (app-to-page MessagePort transfer;
    // weak: closed ports must not pin the bridge).
    private final java.util.Map<android.webkit.WebMessagePort, MessageBridge.Port>
            mPortRoutes = new java.util.WeakHashMap<>();

    ProviderMessagePorts(@NonNull Host host) {
        mHost = host;
    }

    @NonNull
    WebMessagePort[] createChannel() {
        // Framework-typed ports: android.webkit.SinytraWebMessagePort
        // (same-package subclass over the @SystemApi framework ctor — see
        // its header; resolves the old AOSP-patch-vs-hook decision point
        // with neither). Routes into MessageBridge; the page transport is
        // shared with the boundary face (LiveMessagePort). Port transfer
        // (getPorts) stays an honest gap.
        MessageBridge messages = mHost.messages();
        MessageBridge.Port[] ports = messages.createChannel();
        android.webkit.WebMessagePort[] fw = new WebMessagePort[] {
                new SinytraWebMessagePort(portBinding(ports[0])),
                new SinytraWebMessagePort(portBinding(ports[1])),
        };
        synchronized (mPortRoutes) {
            mPortRoutes.put(fw[0], ports[0]);
            mPortRoutes.put(fw[1], ports[1]);
        }
        return fw;
    }

    @NonNull
    private SinytraWebMessagePort.Binding portBinding(
            @NonNull MessageBridge.Port port) {
        return new SinytraWebMessagePort.Binding() {
            @Override
            public void post(@NonNull String data) {
                mHost.messages().postMessage(port, data, null);
            }

            @Override
            public void close() {
                mHost.messages().close(port);
            }

            @Override
            public void setCallback(
                    @Nullable WebMessagePort.WebMessageCallback callback) {
                mHost.messages().setCallback(port, callback);
            }
        };
    }

    void postToMainFrame(@Nullable WebMessage message,
            @Nullable Uri targetOrigin) {
        if (message == null) {
            return;
        }
        try {
            // App-to-page MessagePort transfer (CTS PostMessageTest
            // testMessageChannel family): framework ports map back to
            // bridge ports; unknown/closed ports are skipped (honest).
            // Page-created transfer stays unsupported (see header).
            java.util.List<MessageBridge.Port> transferred =
                    new java.util.ArrayList<>();
            WebMessagePort[] fwPorts = message.getPorts();
            if (fwPorts != null) {
                synchronized (mPortRoutes) {
                    for (WebMessagePort fw : fwPorts) {
                        MessageBridge.Port routed = mPortRoutes.get(fw);
                        if (routed != null) {
                            transferred.add(routed);
                        }
                    }
                }
            }
            mHost.messages().postToMainFrame(
                    message.getData() != null ? message.getData() : "",
                    targetOrigin != null ? targetOrigin.toString() : null,
                    transferred);
        } catch (Throwable t) {
            SinytraLog.w(TAG, "postMessageToMainFrame threw", t);
        }
    }
}
