package org.mozilla.geckowebview.session;

import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewRenderProcess;
import android.webkit.WebViewRenderProcessClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.concurrent.Executor;

// RenderProcessBridge: WebViewRenderProcessClient alignment surface.
// P2-7 spike result: Gecko crash signal today is ContentDelegate.onCrash
// (already fanned out as onReceivedError). Full RenderProcess semantics
// (terminate(), responsive/unresponsive, priority-at-exit) have no GV153
// equivalent — needs a firefox-patch for parity. Until then: own the
// client registration, report crash=true gone-details on onCrash, and
// answer getWebViewRenderProcess() with a stable token object.
public final class RenderProcessBridge {
    @Nullable
    private volatile Executor mExecutor;
    @Nullable
    private volatile WebViewRenderProcessClient mClient;
    private final SinytraRenderProcess mProcess = new SinytraRenderProcess();

    public void setClient(@Nullable Executor executor,
            @Nullable WebViewRenderProcessClient client) {
        mExecutor = executor;
        mClient = client;
    }

    @Nullable
    public WebViewRenderProcessClient getClient() {
        return mClient;
    }

    @NonNull
    public WebViewRenderProcess process() {
        return mProcess;
    }

    public void onGeckoCrash(@NonNull WebView view,
            @Nullable android.webkit.WebViewClient webViewClient) {
        WebViewRenderProcessClient client = mClient;
        RenderProcessGoneDetail detail = new RenderProcessGoneDetail() {
            @Override
            public boolean didCrash() {
                return true;
            }

            @Override
            public int rendererPriorityAtExit() {
                return 0;
            }
        };
        if (webViewClient != null) {
            try {
                if (webViewClient.onRenderProcessGone(view, detail)) {
                    return;
                }
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/render",
                        "WebViewClient.onRenderProcessGone threw", t);
            }
        }
        if (client == null) {
            return;
        }
        Executor executor = mExecutor;
        Runnable dispatch = () -> {
            try {
                client.onRenderProcessUnresponsive(view, mProcess);
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/render",
                        "onRenderProcessUnresponsive threw", t);
            }
        };
        if (executor != null) {
            try {
                executor.execute(dispatch);
                return;
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/render", "executor threw", t);
            }
        }
        dispatch.run();
    }

    public void onResponsive(@NonNull WebView view, boolean responsive) {
        WebViewRenderProcessClient client = mClient;
        if (client == null) {
            return;
        }
        Executor executor = mExecutor;
        Runnable dispatch = () -> {
            try {
                if (responsive) {
                    client.onRenderProcessResponsive(view, mProcess);
                } else {
                    client.onRenderProcessUnresponsive(view, mProcess);
                }
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/render", "responsive dispatch threw", t);
            }
        };
        if (executor != null) {
            try {
                executor.execute(dispatch);
                return;
            } catch (Throwable t) {
                android.util.Log.w("Sinytra/render", "executor threw", t);
            }
        }
        dispatch.run();
    }

    static final class SinytraRenderProcess extends WebViewRenderProcess {
        @Override
        public boolean terminate() {
            return false;
        }
    }
}
